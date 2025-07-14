(ns is.simm.repl-mcp.tools.navigation
  "Advanced navigation tools for Clojure codebases"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [nrepl.core :as nrepl]
            [taoensso.telemere :as log]))

;; Call Hierarchy Implementation

(defn bencode-friendly-data
  "Transform data to be bencode-friendly by handling Boolean values and other problematic types"
  [data]
  (walk/postwalk
   (fn [x]
     (cond
       (boolean? x) (if x "true" "false")  ; Convert booleans to strings
       (set? x) (vec x)                   ; Convert sets to vectors
       :else x))
   data))

(defn send-nrepl-message
  "Send a synchronous nREPL message and return the combined response"
  [nrepl-client message]
  (log/log! {:level :debug :msg "Sending nREPL message" :data {:message message}})
  (let [responses (nrepl/message nrepl-client message)]
    (log/log! {:level :debug :msg "Raw nREPL responses" :data {:count (count responses) :responses responses}})
    ;; For find-symbol operations, collect all occurrences
    (if (= (:op message) "find-symbol")
      (let [occurrences (->> responses
                             (filter :occurrence)
                             (map :occurrence))
            _ (log/log! {:level :debug :msg "Filtered occurrences" :data {:count (count occurrences) :occurrences occurrences}})
            final-response (->> responses
                                (filter :status)
                                first)
            _ (log/log! {:level :debug :msg "Final response" :data {:final-response final-response}})
            ;; Parse occurrences from string format if needed
            parsed-occurrences (if (and (seq occurrences) (string? (first occurrences)))
                                 (map #(read-string %) occurrences)
                                 occurrences)
            result (bencode-friendly-data (assoc final-response :occurrences parsed-occurrences))]
        (log/log! {:level :debug :msg "Processed result" :data {:result result}})
        result)
      ;; For other operations, use normal combine-responses
      (let [result (-> (nrepl/combine-responses responses)
                       bencode-friendly-data)]
        (log/log! {:level :debug :msg "Combined result" :data {:result result}})
        result))))

(defn find-function-callers 
  "Find all functions that call the specified function"
  [nrepl-client namespace-name function-name]
  (let [project-root (System/getProperty "user.dir")
        ;; Note: namespace loading will be added separately
        _ nil
        ;; Convert namespace to file path with multiple attempts
        namespace-path (str/replace namespace-name #"\." "/")
        namespace-path (str/replace namespace-path #"-" "_")
        potential-paths [(str project-root "/src/" namespace-path ".clj")
                        (str project-root "/src/main/clojure/" namespace-path ".clj")
                        (str project-root "/" namespace-path ".clj")]
        file-path (or (first (filter #(.exists (java.io.File. %)) potential-paths))
                     (first potential-paths))
        
        response (send-nrepl-message nrepl-client
                                     {:op "find-symbol"
                                      :ns namespace-name
                                      :name function-name
                                      :file file-path
                                      :line 1
                                      :column 1
                                      :dir project-root
                                      :ignore-errors "true"
                                      :serialization-format "edn"})]
    (when (:occurrences response)
      (->> (:occurrences response)
           (map (fn [usage]
                  {:file (:file usage)
                   :line (:line-beg usage)
                   :column (:col-beg usage)
                   :match (:match usage)
                   :name (:name usage)}))
           (remove nil?)))))

(defn parse-function-calls 
  "Parse function calls from Clojure code using simple regex approach"
  [code]
  (let [function-call-pattern #"\(([a-zA-Z][a-zA-Z0-9\-\*\+\!\?]*(?:/[a-zA-Z][a-zA-Z0-9\-\*\+\!\?]*)?)"
        matches (re-seq function-call-pattern code)]
    (->> matches
         (map second)
         (remove #(contains? #{"if" "when" "let" "defn" "def" "do" "try" "catch" "finally"} %))
         distinct)))


(defn build-call-hierarchy 
  "Build call hierarchy for a function in the specified direction"
  [nrepl-client namespace-name function-name direction max-depth]
  (letfn [(build-hierarchy [current-ns current-fn depth visited]
            (if (or (>= depth max-depth)
                    (contains? visited [current-ns current-fn]))
              {:namespace current-ns
               :function current-fn
               :depth depth
               :relations []}
              (let [relations (case direction
                                "callers" (find-function-callers nrepl-client current-ns current-fn)
                                [])]
                {:namespace current-ns
                 :function current-fn
                 :depth depth
                 :relations (case direction
                              "callers" relations
                              [])})))]
    (build-hierarchy namespace-name function-name 0 #{})))

(defn format-call-hierarchy 
  "Format call hierarchy result for display"
  [hierarchy direction]
  (let [direction-desc (case direction
                         "callers" "Functions that call"
                         "unknown")
        relations (:relations hierarchy)
        relation-summary (if (seq relations)
                          (str " - Found " (count relations) " " 
                               "callers:" 
                               " " (str/join ", " (map #(str (:file %) ":" (:line %)) relations)))
                          " - No relations found")]
    {:summary (str direction-desc " " (:namespace hierarchy) "/" (:function hierarchy) relation-summary)
     :direction direction
     :root-function {:namespace (:namespace hierarchy)
                     :function (:function hierarchy)}
     :relations relations
     :hierarchy hierarchy
     :analysis {:total-relations (count relations)
                :max-depth (:depth hierarchy)}}))

;; Enhanced Usage Finder Implementation

(defn ensure-namespace-loaded
  "Ensure a namespace is loaded in the nREPL session"
  [nrepl-client namespace-name]
  (try
    (log/log! {:level :debug :msg "Ensuring namespace is loaded" :data {:namespace namespace-name}})
    (send-nrepl-message nrepl-client {:op "eval" :code (str "(require '" namespace-name ")")})
    (log/log! {:level :debug :msg "Namespace loaded successfully" :data {:namespace namespace-name}})
    true
    (catch Exception e
      (log/log! {:level :warn :msg "Failed to load namespace" :data {:namespace namespace-name :error (.getMessage e)}})
      false)))

(defn find-symbol-usages 
  "Find all usages of a symbol across the project with enhanced context"
  [nrepl-client namespace-name symbol-name _options]
  (try
    (log/log! {:level :info :msg "Starting find-symbol-usages" :data {:namespace namespace-name :symbol symbol-name}})
    (let [project-root (System/getProperty "user.dir")
          _ (log/log! {:level :debug :msg "Project root determined" :data {:project-root project-root}})
          
          ;; Ensure namespace is loaded for better info operations
          _ (ensure-namespace-loaded nrepl-client namespace-name)
          
          ;; Convert namespace to file path with better error handling
          namespace-path (str/replace namespace-name #"\." "/")
          namespace-path (str/replace namespace-path #"-" "_")
          ;; Try multiple common source paths for project namespaces
          potential-paths [(str project-root "/src/" namespace-path ".clj")
                          (str project-root "/src/main/clojure/" namespace-path ".clj")
                          (str project-root "/" namespace-path ".clj")]
          project-file-path (first (filter #(.exists (java.io.File. %)) potential-paths))
          
          is-external-namespace (nil? project-file-path)
          _ (log/log! {:level :debug :msg "File path resolved" :data {:namespace namespace-name :file-path project-file-path :is-external is-external-namespace :attempted-paths potential-paths}})]
          
      (if is-external-namespace
        ;; For external namespaces, return empty results instead of querying refactor-nrepl
        ;; This avoids the NullPointerException in refactor-nrepl
        (do
          (log/log! {:level :debug :msg "Skipping external namespace" :data {:namespace namespace-name :reason "External namespaces not supported for usage search"}})
          {:total-usages 0
           :namespaces-with-usages []
           :usages []})
        
        ;; For project namespaces, proceed with refactor-nrepl
        (let [;; Use refactor-nrepl's find-symbol for project namespaces only
              find-symbol-params {:op "find-symbol"
                                  :ns namespace-name
                                  :name symbol-name
                                  :file project-file-path
                                  :line 1
                                  :column 1
                                  :dir project-root
                                  :ignore-errors "true"
                                  :serialization-format "edn"}
              _ (log/log! {:level :debug :msg "find-symbol params" :data {:params find-symbol-params}})
              response (send-nrepl-message nrepl-client find-symbol-params)
              _ (log/log! {:level :debug :msg "find-symbol response" :data {:response response}})]
          (if (:error response)
            {:error (:error response) :usages []}
            (let [usages (:occurrences response)]
              {:total-usages (count usages)
               :namespaces-with-usages (->> usages
                                            (map :file)
                                            (map #(when % (second (re-find #"([^/]+)\.clj$" %))))
                                            (remove nil?)
                                            distinct
                                            sort)
               :usages (->> usages
                            (map (fn [usage]
                                   {:file (:file usage)
                                    :line (:line usage)
                                    :column (:col usage)
                                    :match (:match usage)
                                    :context (:context usage)
                                    :type (cond
                                            (re-find #"^\s*\(" (:context usage "")) :function-call
                                            (re-find #"^\s*\[" (:context usage "")) :binding
                                            :else :reference)}))
                            (sort-by (juxt :file :line)))})))))
    (catch Exception e
      {:error (str "Failed to find usages: " (.getMessage e))
       :usages []})))

(defn categorize-usages 
  "Categorize usages by type and location"
  [usages]
  (let [by-type (group-by :type usages)
        by-file (group-by :file usages)]
    {:by-type {:function-calls (count (:function-call by-type))
               :bindings (count (:binding by-type))
               :references (count (:reference by-type))}
     :by-file (->> by-file
                   (map (fn [[file file-usages]]
                          {:file file
                           :usage-count (count file-usages)
                           :lines (map :line file-usages)}))
                   (sort-by :usage-count >))}))

(defn format-usage-analysis 
  "Format the usage analysis results"
  [symbol-name namespace-name usage-result]
  (let [categorization (categorize-usages (:usages usage-result))]
    {:summary (format "Found %d usages of %s/%s across %d namespaces"
                      (:total-usages usage-result)
                      namespace-name
                      symbol-name
                      (count (:namespaces-with-usages usage-result)))
     :symbol {:namespace namespace-name :name symbol-name}
     :statistics {:total-usages (:total-usages usage-result)
                  :affected-namespaces (count (:namespaces-with-usages usage-result))
                  :usage-types (:by-type categorization)}
     :namespaces (:namespaces-with-usages usage-result)
     :files-analysis (:by-file categorization)
     :detailed-usages (:usages usage-result)}))

;; Register the call-hierarchy tool
(register-tool! :call-hierarchy
  "Analyze function call hierarchy (callers) in a Clojure project"
  {:namespace {:type "string" :description "Namespace containing the function"}
   :function {:type "string" :description "Function name to analyze"}
   :direction {:type "string" :description "Direction: 'callers' (who calls this)" :enum ["callers"]}
   :max-depth {:type "number" :description "Maximum depth to traverse (default: 3)" :default 3}}
  (fn [tool-call context]
    (let [{:strs [namespace function direction max-depth]} (:args tool-call)
          nrepl-client (:nrepl-client context)
          depth (or max-depth 3)]
      (if nrepl-client
        (try
          (let [result (-> (build-call-hierarchy nrepl-client namespace function direction depth)
                           (format-call-hierarchy direction))]
            {:value (:summary result)
             :status :success})
          (catch Exception e
            {:error (str "Failed to analyze call hierarchy: " (.getMessage e))
             :status :error}))
        {:error "nREPL client not available"
         :status :error})))
  :tags #{:navigation :analysis :code-understanding}
  :dependencies #{:nrepl})

;; Register the enhanced usage-finder tool
(register-tool! :usage-finder
  "Find all usages of a symbol across the project with detailed analysis"
  {:namespace {:type "string" :description "Namespace containing the symbol"}
   :symbol {:type "string" :description "Symbol name to find usages for"}
   :include-context {:type "boolean" :description "Include surrounding code context (default: true)" :default true}}
  (fn [tool-call context]
    (let [{:strs [namespace symbol include-context]} (:args tool-call)
          nrepl-client (:nrepl-client context)
          options {:include-context (not= include-context false)}]
      (if nrepl-client
        (try
          (let [usage-result (find-symbol-usages nrepl-client namespace symbol options)]
            (if (:error usage-result)
              {:error (:error usage-result) :status :error}
              (let [formatted-result (format-usage-analysis symbol namespace usage-result)]
                {:value (:summary formatted-result)
                 :status :success})))
          (catch Exception e
            {:error (str "Failed to find symbol usages: " (.getMessage e))
             :status :error}))
        {:error "nREPL client not available"
         :status :error})))
  :tags #{:navigation :search :code-understanding}
  :dependencies #{:nrepl})