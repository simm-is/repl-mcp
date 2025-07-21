(ns is.simm.repl-mcp.tools.function-refactor
  "Function-level refactoring tools"
  (:require 
   [clojure.string :as str]
   [clojure.java.io :as io]
   [taoensso.telemere :as log]))

;; ===============================================
;; Function Refactoring Functions
;; ===============================================

(defn find-function-definition
  "Find the definition of a function in a file"
  [file-path function-name]
  (try
    (log/log! {:level :info :msg "Finding function definition" 
               :data {:file-path file-path :function-name function-name}})
    
    (if (.exists (io/file file-path))
      (let [content (slurp file-path)
            lines (str/split-lines content)
            pattern (re-pattern (str "\\(defn\\s+" function-name "\\b"))
            found (atom nil)]
        
        (doseq [[idx line] (map-indexed vector lines)]
          (when (and (not @found) (re-find pattern line))
            (reset! found {:line (inc idx) :column (.indexOf line function-name)})))
        
        (if @found
          {:status :success
           :file-path file-path
           :function-name function-name
           :line (:line @found)
           :column (:column @found)
           :location @found}
          {:status :error
           :error "Function not found"}))
      {:status :error
       :error "File does not exist"})
    (catch Exception e
      (log/log! {:level :error :msg "Error finding function definition" :data {:error (.getMessage e)}})
      {:status :error
       :error (.getMessage e)})))

(defn rename-function-in-file
  "Rename a function definition and all its invocations within a single file"
  [file-path old-name new-name]
  (try
    (log/log! {:level :info :msg "Renaming function in file" 
               :data {:file-path file-path :old-name old-name :new-name new-name}})
    
    (if (.exists (io/file file-path))
      (let [content (slurp file-path)
            ;; Replace function definition
            def-pattern (re-pattern (str "\\(defn\\s+" old-name "\\b"))
            content-with-def (str/replace content def-pattern (str "(defn " new-name))
            
            ;; Replace function calls (simple approach)
            call-pattern (re-pattern (str "\\b" old-name "\\b"))
            final-content (str/replace content-with-def call-pattern new-name)
            
            replacements (- (count (str/split final-content (re-pattern new-name)))
                           (count (str/split content (re-pattern old-name))))]
        
        ;; Write back to file
        (spit file-path final-content)
        
        {:old-name old-name
         :new-name new-name
         :file-path file-path
         :replacements replacements
         :definition-renamed true
         :invocations-renamed replacements
         :file-saved true
         :status :success})
      {:status :error
       :error "File does not exist"})
    (catch Exception e
      (log/log! {:level :error :msg "Error renaming function in file" :data {:error (.getMessage e)}})
      {:status :error
       :error (.getMessage e)})))

(defn find-function-usages-in-project
  "Find all usages of a function across the entire project"
  [nrepl-client project-root function-name]
  (try
    (log/log! {:level :info :msg "Finding function usages" 
               :data {:project-root project-root :function-name function-name}})
    
    (let [project-dir (io/file project-root)]
      (if (.exists project-dir)
        (let [clj-files (->> (file-seq project-dir)
                            (filter #(.isFile %))
                            (filter #(str/ends-with? (.getName %) ".clj"))
                            (map #(.getPath %)))
              
              all-usages (atom [])]
          
          (doseq [file-path clj-files]
            (let [content (try (slurp file-path) (catch Exception _ ""))]
              (when (str/includes? content function-name)
                (swap! all-usages conj {:file-path file-path
                                       :function-name function-name
                                       :found true}))))
          
          {:usages @all-usages
           :project-root project-root
           :function-name function-name
           :total-files (count clj-files)
           :files-with-usages (count @all-usages)
           :status :success})
        {:error "Project directory does not exist"
         :status :error}))
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
    
    (let [usage-result (find-function-usages-in-project nrepl-client project-root old-name)]
      (if (= (:status usage-result) :success)
        (let [files-to-modify (map :file-path (:usages usage-result))
              results (atom {:files-modified 0
                            :total-replacements 0
                            :errors []
                            :modified-files []})]
          
          (doseq [file-path files-to-modify]
            (let [rename-result (rename-function-in-file file-path old-name new-name)]
              (if (= (:status rename-result) :success)
                (do
                  (swap! results update :files-modified inc)
                  (swap! results update :total-replacements + (:invocations-renamed rename-result))
                  (swap! results update :modified-files conj file-path))
                (swap! results update :errors conj {:file-path file-path
                                                   :error (:error rename-result)}))))
          
          (assoc @results
                 :old-name old-name
                 :new-name new-name
                 :project-root project-root
                 :total-files (:total-files usage-result)
                 :files-with-usages (:files-with-usages usage-result)
                 :status (if (empty? (:errors @results)) :success :partial)))
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
  (try
    (log/log! {:level :info :msg "Replacing function definition" 
               :data {:file-path file-path :function-name function-name}})
    
    (if (.exists (io/file file-path))
      (let [content (slurp file-path)
            lines (str/split-lines content)
            pattern (re-pattern (str "\\(defn\\s+" function-name "\\b"))
            
            ;; Find start of function
            start-line (atom nil)
            _ (doseq [[idx line] (map-indexed vector lines)]
                (when (and (not @start-line) (re-find pattern line))
                  (reset! start-line idx)))
            
            ;; Simple replacement: replace entire function (this is a simplified version)
            new-content (if @start-line
                         (str/replace content 
                                    (re-pattern (str "\\(defn\\s+" function-name "[^\\)]*\\)[^\\(]*\\([^\\)]*\\)"))
                                    new-implementation)
                         content)]
        
        (if @start-line
          (do
            (spit file-path new-content)
            {:replaced true
             :function-name function-name
             :file-path file-path
             :new-definition new-implementation
             :status :success})
          {:replaced false
           :error "Function not found"
           :status :error}))
      {:replaced false
       :error "File does not exist"
       :status :error})
    (catch Exception e
      (log/log! {:level :error :msg "Error replacing function definition" :data {:error (.getMessage e)}})
      {:replaced false
       :error (.getMessage e)
       :status :error})))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn find-function-definition-tool [mcp-context arguments]
  (let [{:strs [file-path function-name]} arguments]
    (cond
      (or (nil? file-path) (empty? file-path))
      {:content [{:type "text" :text "Error: file-path parameter is required"}]}
      
      (or (nil? function-name) (empty? function-name))
      {:content [{:type "text" :text "Error: function-name parameter is required"}]}
      
      :else
      (let [result (find-function-definition file-path function-name)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str "Found function '" function-name "' in " file-path 
                                 " at line " (:line result) ", column " (:column result))
                            (str "Error: " (:error result)))}]}))))

(defn rename-function-in-file-tool [mcp-context arguments]
  (let [{:strs [file-path old-name new-name]} arguments]
    (cond
      (or (nil? file-path) (empty? file-path))
      {:content [{:type "text" :text "Error: file-path parameter is required"}]}
      
      (or (nil? old-name) (empty? old-name))
      {:content [{:type "text" :text "Error: old-name parameter is required"}]}
      
      (or (nil? new-name) (empty? new-name))
      {:content [{:type "text" :text "Error: new-name parameter is required"}]}
      
      :else
      (let [result (rename-function-in-file file-path old-name new-name)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str "Renamed function '" old-name "' to '" new-name "' in " file-path "\n"
                                 "Made " (:replacements result) " replacements")
                            (str "Error: " (:error result)))}]}))))

(defn find-function-usages-in-project-tool [mcp-context arguments]
  (let [{:strs [project-root function-name]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (cond
      (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Function usage search requires an active nREPL connection."}]}
      
      (or (nil? project-root) (empty? project-root))
      {:content [{:type "text" :text "Error: project-root parameter is required"}]}
      
      (or (nil? function-name) (empty? function-name))
      {:content [{:type "text" :text "Error: function-name parameter is required"}]}
      
      :else
      (let [result (find-function-usages-in-project nrepl-client project-root function-name)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str "Found function '" function-name "' in " (:files-with-usages result) 
                                 " out of " (:total-files result) " files\n"
                                 "Files with usages:\n"
                                 (str/join "\n" (map :file-path (:usages result))))
                            (str "Error: " (:error result)))}]}))))

(defn rename-function-across-project-tool [mcp-context arguments]
  (let [{:strs [project-root old-name new-name]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (cond
      (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Function renaming requires an active nREPL connection."}]}
      
      (or (nil? project-root) (empty? project-root))
      {:content [{:type "text" :text "Error: project-root parameter is required"}]}
      
      (or (nil? old-name) (empty? old-name))
      {:content [{:type "text" :text "Error: old-name parameter is required"}]}
      
      (or (nil? new-name) (empty? new-name))
      {:content [{:type "text" :text "Error: new-name parameter is required"}]}
      
      :else
      (let [result (rename-function-across-project nrepl-client project-root old-name new-name)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str "Successfully renamed function '" old-name "' to '" new-name "'\n"
                                 "Modified " (:files-modified result) " files with " 
                                 (:total-replacements result) " total replacements\n"
                                 "Modified files:\n" (str/join "\n" (:modified-files result)))
                            (str "Error: " (:error result)))}]}))))

(defn replace-function-definition-tool [mcp-context arguments]
  (let [{:strs [file-path function-name new-implementation]} arguments]
    (cond
      (or (nil? file-path) (empty? file-path))
      {:content [{:type "text" :text "Error: file-path parameter is required"}]}
      
      (or (nil? function-name) (empty? function-name))
      {:content [{:type "text" :text "Error: function-name parameter is required"}]}
      
      (or (nil? new-implementation) (empty? new-implementation))
      {:content [{:type "text" :text "Error: new-implementation parameter is required"}]}
      
      :else
      (let [result (replace-function-definition file-path function-name new-implementation)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str "Successfully replaced function '" function-name "' in " file-path)
                            (str "Error: " (:error result)))}]}))))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Function refactoring tool definitions for mcp-toolkit"
  [{:name "find-function-definition"
    :description "Find the definition of a function in a file"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :function-name {:type "string" :description "Name of the function to find"}}
                  :required ["file-path" "function-name"]}
    :tool-fn find-function-definition-tool}
   
   {:name "rename-function-in-file"
    :description "Rename a function and all its invocations within a single file"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :old-name {:type "string" :description "Current name of the function"}
                              :new-name {:type "string" :description "New name for the function"}}
                  :required ["file-path" "old-name" "new-name"]}
    :tool-fn rename-function-in-file-tool}
   
   {:name "find-function-usages-in-project"
    :description "Find all usages of a function across the entire project"
    :inputSchema {:type "object"
                  :properties {:project-root {:type "string" :description "Root directory of the project"}
                              :function-name {:type "string" :description "Name of the function to find"}}
                  :required ["project-root" "function-name"]}
    :tool-fn find-function-usages-in-project-tool}
   
   {:name "rename-function-across-project"
    :description "Rename a function and all its usages across an entire project"
    :inputSchema {:type "object"
                  :properties {:project-root {:type "string" :description "Root directory of the project"}
                              :old-name {:type "string" :description "Current name of the function"}
                              :new-name {:type "string" :description "New name for the function"}}
                  :required ["project-root" "old-name" "new-name"]}
    :tool-fn rename-function-across-project-tool}
   
   {:name "replace-function-definition"
    :description "Replace an entire function definition with a new implementation"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :function-name {:type "string" :description "Name of the function to replace"}
                              :new-implementation {:type "string" :description "New function implementation"}}
                  :required ["file-path" "function-name" "new-implementation"]}
    :tool-fn replace-function-definition-tool}])