(ns is.simm.repl-mcp.tools.function-refactor
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [is.simm.repl-mcp.structural-edit :as edit]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.telemere :as log]
            [cljfmt.core :as fmt]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; FUNCTION RENAMING AND REFACTORING TOOLS
;; =============================================================================


(defn find-function-definition
  "Find the definition of a function in a file using structural editing"
  [file-path function-name]
  (let [session-id (str "find-def-" (System/currentTimeMillis))
        result (atom nil)]
    (try
      (let [create-result (edit/create-session session-id file-path)]
        (if (= (:status create-result) :success)
          (let [find-result (edit/find-by-symbol session-id function-name)]
            (if (= (:status find-result) :success)
              (let [info (edit/get-zipper-info session-id)
                    current-node (get-in info [:current-node])]
                (reset! result {:status :success
                               :file-path file-path
                               :function-name function-name
                               :line (or (:line current-node) 1)
                               :column (or (:column current-node) 1)
                               :location info}))
              (reset! result {:status :error
                             :error (or (:error find-result) "Function not found")})))
          (reset! result {:status :error
                         :error (or (:error create-result) "Failed to create session")})))
      (catch Exception e
        (log/log! {:level :error :msg "Error finding function definition" :data {:error (.getMessage e)}})
        (reset! result {:status :error
                       :error (.getMessage e)}))
      (finally
        (edit/close-session session-id)))
    @result))

(defn rename-function-in-file
  "Rename a function definition and all its invocations within a single file"
  [file-path old-name new-name]
  (let [session-id (str "rename-" (System/currentTimeMillis))
        results (atom {:definition-renamed false
                      :invocations-renamed 0
                      :errors []})]
    (try
      (edit/create-session session-id file-path)
      
      ;; Use bulk find-and-replace to rename all occurrences at once
      ;; This is safer and more consistent than separate operations
      (let [bulk-result (edit/bulk-find-and-replace session-id old-name new-name)]
        (if (= (:status bulk-result) :success)
          (do
            (swap! results assoc :invocations-renamed (:replacements bulk-result))
            (swap! results assoc :definition-renamed true))
          (swap! results update :errors conj {:type :bulk-rename
                                             :error (:error bulk-result)})))
      
      ;; Save the modified file
      (let [save-result (edit/save-session session-id :file-path file-path)]
        (if (= (:status save-result) :success)
          (swap! results assoc :file-saved true)
          (swap! results update :errors conj {:type :file-save
                                             :error (:error save-result)})))
      
      (finally
        (edit/close-session session-id)))
    
    (assoc @results
           :old-name old-name
           :new-name new-name
           :file-path file-path
           :replacements (:invocations-renamed @results)
           :status (if (empty? (:errors @results)) :success :partial))))

(defn find-function-usages-in-project
  "Find all usages of a function across the entire project"
  [nrepl-client project-root function-name]
  (try
    (log/log! {:level :info :msg "Finding function usages" 
               :data {:project-root project-root :function-name function-name}})
    
    ;; Validate that the project root exists
    (let [project-dir (io/file project-root)]
      (when-not (.exists project-dir)
        (throw (Exception. (str "Project directory does not exist: " project-root))))
      (when-not (.isDirectory project-dir)
        (throw (Exception. (str "Project root is not a directory: " project-root))))
      
      ;; Find all .clj files in the project
      (let [clj-files (->> (file-seq project-dir)
                          (filter #(.isFile %))
                          (filter #(str/ends-with? (.getName %) ".clj"))
                          (map #(.getPath %)))
          
          all-usages (atom [])]
      
      ;; Search each file for the function
      (doseq [file-path clj-files]
        (let [session-id (str "search-" (System/currentTimeMillis))
              content (try (slurp file-path) (catch Exception _ nil))]
          (when content
            (try
              (edit/create-session session-id file-path)
              (let [find-result (edit/find-by-symbol session-id function-name)]
                (when (= (:status find-result) :success)
                  (swap! all-usages conj {:file-path file-path
                                         :function-name function-name
                                         :found true})))
              (finally
                (edit/close-session session-id))))))
      
        {:usages @all-usages
         :project-root project-root
         :function-name function-name
         :total-files (count clj-files)
         :files-with-usages (count @all-usages)
         :status :success}))
    
    (catch Exception e
      (log/log! {:level :error :msg "Error finding function usages" 
                 :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn rename-function-across-project
  "Rename a function and all its usages across an entire project"
  [nrepl-client project-root old-name new-name]
  (try
    (log/log! {:level :info :msg "Renaming function across project" 
               :data {:project-root project-root :old-name old-name :new-name new-name}})
    
    ;; 1. Find all files that use the function
    (let [usage-result (find-function-usages-in-project nrepl-client project-root old-name)]
      (if (= (:status usage-result) :success)
        (let [files-to-modify (map :file-path (:usages usage-result))
              results (atom {:files-modified 0
                            :total-replacements 0
                            :errors []
                            :modified-files []})]
          
          ;; 2. Rename function in each file
          (doseq [file-path files-to-modify]
            (let [rename-result (rename-function-in-file file-path old-name new-name)]
              (if (= (:status rename-result) :success)
                (do
                  (swap! results update :files-modified inc)
                  (swap! results update :total-replacements + (:invocations-renamed rename-result))
                  (swap! results update :modified-files conj file-path))
                (swap! results update :errors conj {:file-path file-path
                                                   :errors (:errors rename-result)}))))
          
          (assoc @results
                 :old-name old-name
                 :new-name new-name
                 :project-root project-root
                 :total-files (:total-files usage-result)
                 :files-with-usages (:files-with-usages usage-result)
                 :status (if (empty? (:errors @results)) :success :partial)))
        
        ;; Failed to find usages
        {:error (:error usage-result)
         :status :error}))
    
    (catch Exception e
      (log/log! {:level :error :msg "Error renaming function across project" 
                 :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn replace-function-definition
  "Replace an entire function definition with a new implementation"
  [file-path function-name new-implementation]
  (let [session-id (str "replace-def-" (System/currentTimeMillis))
        result (atom {:replaced false :error nil})]
    (try
      (edit/create-session session-id file-path)
      
      ;; Find the function definition
      (let [find-result (edit/find-by-symbol session-id function-name)]
        (if (= (:status find-result) :success)
          ;; Get the old definition before replacing
          (let [old-definition (-> (edit/get-zipper-info session-id)
                                  :current-node
                                  :string-repr)
                ;; Parse the new implementation using rewrite-clj to preserve formatting
                formatted-node (try
                                 (let [zloc (z/of-string new-implementation)]
                                   (z/node zloc))
                                 (catch Exception e
                                   {:error (str "Failed to parse new implementation: " (.getMessage e))}))
                replace-result (if (:error formatted-node)
                                 formatted-node
                                 (edit/replace-node session-id formatted-node))]
            (if (= (:status replace-result) :success)
              ;; Format the code before saving using cljfmt
              (let [formatted-result (try
                                       (let [current-code (-> (edit/get-zipper-info session-id)
                                                            :parent :string-repr)
                                             formatted-code (fmt/reformat-string current-code)]
                                         (when (not= current-code formatted-code)
                                           ;; Only recreate session if formatting changed the code
                                           (edit/close-session session-id)
                                           (edit/create-session session-id formatted-code))
                                         {:status :success :formatted (not= current-code formatted-code)})
                                       (catch Exception e
                                         (log/log! {:level :warn :msg "Code formatting failed, proceeding without formatting" 
                                                   :data {:error (.getMessage e)}})
                                         {:status :success :formatted false}))
                    save-result (edit/save-session session-id :file-path file-path)]
                (if (= (:status save-result) :success)
                  (reset! result {:replaced true
                                 :function-name function-name
                                 :file-path file-path
                                 :old-definition old-definition
                                 :new-definition new-implementation
                                 :formatted (:formatted formatted-result)})
                  (reset! result {:replaced false
                                 :error (:error save-result)})))
              (reset! result {:replaced false
                             :error (:error replace-result)})))
          (reset! result {:replaced false
                         :error (:error find-result)})))
      
      (finally
        (edit/close-session session-id)))
    
    (assoc @result :status (if (:replaced @result) :success :error))))

;; =============================================================================
;; MCP TOOL REGISTRATIONS
;; =============================================================================

(register-tool! :find-function-definition
  "Find the definition of a function in a file"
  {:file-path {:type "string" :description "Path to the Clojure file"}
   :function-name {:type "string" :description "Name of the function to find"}}
  (fn [tool-call context]
    (let [{:strs [file-path function-name]} (:args tool-call)]
      (find-function-definition file-path function-name))))

(register-tool! :rename-function-in-file
  "Rename a function and all its invocations within a single file"
  {:file-path {:type "string" :description "Path to the Clojure file"}
   :old-name {:type "string" :description "Current name of the function"}
   :new-name {:type "string" :description "New name for the function"}}
  (fn [tool-call context]
    (let [{:strs [file-path old-name new-name]} (:args tool-call)]
      (rename-function-in-file file-path old-name new-name))))

(register-tool! :find-function-usages-in-project
  "Find all usages of a function across the entire project"
  {:project-root {:type "string" :description "Root directory of the project"}
   :function-name {:type "string" :description "Name of the function to find"}}
  (fn [tool-call context]
    (let [{:strs [project-root function-name]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (find-function-usages-in-project nrepl-client project-root function-name))))

(register-tool! :rename-function-across-project
  "Rename a function and all its usages across an entire project"
  {:project-root {:type "string" :description "Root directory of the project"}
   :old-name {:type "string" :description "Current name of the function"}
   :new-name {:type "string" :description "New name for the function"}}
  (fn [tool-call context]
    (let [{:strs [project-root old-name new-name]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (rename-function-across-project nrepl-client project-root old-name new-name))))

(register-tool! :replace-function-definition
  "Replace an entire function definition with a new implementation"
  {:file-path {:type "string" :description "Path to the Clojure file"}
   :function-name {:type "string" :description "Name of the function to replace"}
   :new-implementation {:type "string" :description "New function implementation (as EDN string)"}}
  (fn [tool-call context]
    (let [{:strs [file-path function-name new-implementation]} (:args tool-call)]
      (replace-function-definition file-path function-name new-implementation))))

;; Tools are automatically registered by register-tool! function