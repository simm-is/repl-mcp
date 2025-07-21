(ns is.simm.repl-mcp.tools.profiling
  "Performance profiling tools using clj-async-profiler"
  (:require 
   [nrepl.core :as nrepl]
   [taoensso.telemere :as log]
   [clojure.string :as str])
  (:import [java.time Instant]))

;; ===============================================
;; Profiling Helper Functions
;; ===============================================

(defn calculate-frame-frequencies
  "Calculate frequency of each frame across all stacks"
  [stacks id->frame]
  (let [frame-counts (atom {})]
    (doseq [[stack-vec sample-count] stacks]
      (doseq [frame-id stack-vec]
        (let [frame-name (get id->frame frame-id)]
          (when frame-name
            (swap! frame-counts update frame-name (fnil + 0) sample-count)))))
    (->> @frame-counts
         (map (fn [[frame samples]] {:frame frame :samples samples}))
         (sort-by :samples >))))

(defn format-percentage [samples total-samples]
  (if (> total-samples 0)
    (double (/ (* samples 100.0) total-samples))
    0.0))

(defn add-percentages
  "Add percentage field to items based on total samples"
  [items total-samples]
  (map #(assoc % :percentage (format-percentage (:samples %) total-samples)) items))

(defn analyze-profile-data
  "Analyze raw profile data and return comprehensive navigatable structure"
  [dense-data event-type duration-ms & {:keys [top-k] :or {top-k 10}}]
  (try
    (let [{:keys [stacks id->frame]} dense-data
          total-samples (reduce + (map second stacks))
          
          ;; Calculate frame frequencies
          frame-frequencies (calculate-frame-frequencies stacks id->frame)
          
          ;; All functions with percentages
          all-functions (->> frame-frequencies
                            (#(add-percentages % total-samples)))
          
          ;; Summary statistics
          summary {:total-samples total-samples
                   :duration-ms duration-ms
                   :samples-per-second (if (> duration-ms 0) 
                                         (double (/ total-samples (/ duration-ms 1000.0)))
                                         0.0)
                   :unique-stacks (count stacks)
                   :unique-functions (count frame-frequencies)
                   :event-type (str event-type)
                   :status :success}]
      
      {:summary summary
       :all-functions all-functions
       :frame-index (into {} (map-indexed (fn [i frame] [i frame]) id->frame))
       :raw-data dense-data})
    (catch Exception e
      (log/log! {:level :error :msg "Error analyzing profile data" :data {:error (.getMessage e)}})
      {:error (.getMessage e) :status :error})))

(defn profile-expression-comprehensive
  "Profile a Clojure expression and return comprehensive analysis"
  [nrepl-client code-str & {:keys [event duration generate-flamegraph namespace top-k]
                            :or {event :cpu duration 5000 generate-flamegraph false top-k 10}}]
  (try
    (log/log! {:level :info :msg "Starting comprehensive profiling" 
               :data {:code code-str :event event :duration duration}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [start-time (System/currentTimeMillis)
          
          ;; Prepare the profiling code to execute in nREPL
          profiling-code 
          (format 
           "(try
              (require '[clj-async-profiler.core :as prof])
              %s
              (prof/start {:event %s})
              (let [start-time (System/currentTimeMillis)
                    result %s
                    end-time (System/currentTimeMillis)]
                (prof/stop)
                {:result result
                 :duration-ms (- end-time start-time)
                 :success true})
              (catch Exception e
                {:error (str \"Profiling error: \" (.getMessage e))
                 :success false}))"
           (if namespace (str "(in-ns '" namespace ")") "")
           (str event)
           code-str)
          
          ;; Execute profiling in nREPL  
          _ (log/log! {:level :info :msg "Generated profiling code" :data {:code profiling-code}})
          responses (nrepl/message nrepl-client {:op "eval" :code profiling-code})
          _ (log/log! {:level :info :msg "nREPL responses" :data {:responses responses}})
          result-value (first (nrepl/response-values responses))
          ;; Check for evaluation errors first
          eval-errors (filter :err responses)
          result-data (if (seq eval-errors)
                        (let [error-msg (str/join "; " (map :err eval-errors))]
                          (if (str/includes? error-msg "No such namespace: prof")
                            {:error "clj-async-profiler not available in nREPL session. Ensure it's on the classpath." 
                             :success false}
                            {:error (str "Evaluation error: " error-msg) :success false}))
                        (when result-value 
                          (try 
                            (read-string result-value)
                            (catch Exception e
                              (log/log! {:level :error :msg "Failed to parse profiling result" 
                                         :data {:result-value result-value :error (.getMessage e)}})
                              {:error (str "Parse error: " (.getMessage e)) :success false}))))
          
          end-time (System/currentTimeMillis)]
      
      (if (:success result-data)
        {:result (:result result-data)
         :summary {:total-samples 0  ; Simplified without dense data
                   :duration-ms (:duration-ms result-data)
                   :samples-per-second 0.0
                   :unique-stacks 0
                   :unique-functions 0
                   :event-type (str event)
                   :status :success}
         :message "Profiling completed (simplified analysis - dense data not available)"
         :metadata {:start-time (Instant/ofEpochMilli start-time)
                    :end-time (Instant/ofEpochMilli end-time)
                    :profiler-version "simplified"}}
        {:error (or (:error result-data) "Unknown profiling error")
         :status :error}))
    (catch Exception e
      (log/log! {:level :error :msg "Profiling failed" :data {:error (.getMessage e)}})
      {:error (.getMessage e) :status :error})))

(defn format-profile-result
  "Format profile result for readable MCP output"
  [result]
  (let [{:keys [summary message]} result
        
        header (format "🔍 %s Profile Results\n%s\n• Duration: %dms\n• Status: %s\n"
                       (str/upper-case (:event-type summary))
                       (str/join "" (repeat 50 "="))
                       (:duration-ms summary)
                       (if (= (:status summary) :success) "SUCCESS" "ERROR"))
        
        body (or message "Profiling completed")]
    
    (str header body)))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn profile-cpu-tool [mcp-context arguments]
  (try
    (let [{:keys [code duration generate-flamegraph namespace top-k]} arguments
          nrepl-client (:nrepl-client mcp-context)]
      (log/log! {:level :info :msg "CPU profiling tool called"
                 :data {:code code :duration duration}})
      
      (when (nil? nrepl-client)
        (throw (Exception. "nREPL client not available")))
      
      (let [result (profile-expression-comprehensive 
                    nrepl-client code
                    :event :cpu
                    :duration (or duration 5000)
                    :generate-flamegraph (boolean generate-flamegraph)
                    :namespace namespace
                    :top-k (or top-k 10))]
        
        {:content [{:type "text" 
                    :text (if (= (:status result) :error)
                            (str "Error: " (:error result))
                            (format-profile-result result))}]}))
    (catch Exception e
      (log/log! {:level :error :msg "CPU profiling tool failed" 
                 :data {:error (.getMessage e)}})
      {:content [{:type "text" 
                  :text (str "Error: " (.getMessage e))}]})))

(defn profile-alloc-tool [mcp-context arguments]
  (try
    (let [{:keys [code duration generate-flamegraph namespace top-k]} arguments
          nrepl-client (:nrepl-client mcp-context)]
      (log/log! {:level :info :msg "Allocation profiling tool called"
                 :data {:code code :duration duration}})
      
      (when (nil? nrepl-client)
        (throw (Exception. "nREPL client not available")))
      
      (let [result (profile-expression-comprehensive 
                    nrepl-client code
                    :event :alloc
                    :duration (or duration 5000)
                    :generate-flamegraph (boolean generate-flamegraph)
                    :namespace namespace
                    :top-k (or top-k 10))]
        
        {:content [{:type "text" 
                    :text (if (= (:status result) :error)
                            (str "Error: " (:error result))
                            (format-profile-result result))}]}))
    (catch Exception e
      (log/log! {:level :error :msg "Allocation profiling tool failed" 
                 :data {:error (.getMessage e)}})
      {:content [{:type "text" 
                  :text (str "Error: " (.getMessage e))}]})))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Profiling tool definitions for mcp-toolkit"
  [{:name "profile-cpu"
    :description "Profile CPU usage of Clojure code with comprehensive analysis"
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure expression to profile"}
                              :duration {:type "number" :description "Profile duration in milliseconds (default: 5000)"}
                              :generate-flamegraph {:type "boolean" :description "Generate flamegraph file (default: false)"}
                              :namespace {:type "string" :description "Namespace context"}
                              :top-k {:type "number" :description "Number of top frames to show (default: 10)"}}
                  :required ["code"]}
    :tool-fn profile-cpu-tool}
   
   {:name "profile-alloc"
    :description "Profile memory allocation of Clojure code with comprehensive analysis"
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure expression to profile"}
                              :duration {:type "number" :description "Profile duration in milliseconds (default: 5000)"}
                              :generate-flamegraph {:type "boolean" :description "Generate flamegraph file (default: false)"}
                              :namespace {:type "string" :description "Namespace context"}
                              :top-k {:type "number" :description "Number of top frames to show (default: 10)"}}
                  :required ["code"]}
    :tool-fn profile-alloc-tool}])