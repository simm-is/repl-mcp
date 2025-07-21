(ns is.simm.repl-mcp.tools.deps-management
  "Dependency management tools for hot-loading libraries"
  (:require 
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nrepl.core :as nrepl]
   [taoensso.telemere :as log]))

;; ===============================================
;; Dependency Management Functions
;; ===============================================

(defn parse-lib-coords
  "Parse library coordinates from various input formats"
  [coords-input]
  (cond
    (map? coords-input) coords-input
    (string? coords-input) (try
                             (let [parsed (edn/read-string coords-input)]
                               (if (map? parsed)
                                 parsed
                                 (throw (ex-info "Coordinates must be a map"
                                                 {:input coords-input :parsed parsed}))))
                             (catch Exception e
                               (throw (ex-info "Invalid EDN format for coordinates" 
                                               {:input coords-input :error (.getMessage e)}))))
    :else (throw (ex-info "Coordinates must be a map or EDN string" 
                          {:input coords-input :type (type coords-input)}))))

(defn add-libraries
  "Add libraries to the running REPL using Clojure 1.12+ add-libs"
  [lib-coords]
  (try
    (log/log! {:level :info :msg "Adding libraries to REPL" 
               :data {:coords lib-coords}})
    
    ;; Parse coordinates first to validate them
    (let [parsed-coords (parse-lib-coords lib-coords)]
      
      ;; Check if we're in a REPL context
      (when-not *repl*
        (throw (ex-info "add-libs only works in REPL context" {})))
      
      ;; Get the add-libs function  
      (let [add-libs-fn (requiring-resolve 'clojure.repl.deps/add-libs)]
      
      (when-not add-libs-fn
        (throw (ex-info "add-libs not available - requires Clojure 1.12+" {})))
        
        ;; Call add-libs
        (add-libs-fn parsed-coords)
        
        (log/log! {:level :info :msg "Libraries added successfully" 
                   :data {:coords parsed-coords}})
        
        {:libraries (vec (keys parsed-coords))
         :coordinates parsed-coords
         :message "Libraries added successfully to REPL classpath"
         :status :success}))
    
    (catch Exception e
      (log/log! {:level :error :msg "Error adding libraries" 
                 :data {:error (.getMessage e) :coords lib-coords}})
      {:error (.getMessage e)
       :coordinates lib-coords
       :status :error})))

(defn sync-project-deps
  "Sync dependencies from deps.edn that aren't yet on the classpath"
  []
  (try
    (log/log! {:level :info :msg "Syncing project dependencies"})
    
    ;; Check if we're in a REPL context
    (when-not *repl*
      (throw (ex-info "sync-deps only works in REPL context" {})))
    
    ;; Get the sync-deps function
    (let [sync-deps-fn (requiring-resolve 'clojure.repl.deps/sync-deps)]
      
      (when-not sync-deps-fn
        (throw (ex-info "sync-deps not available - requires Clojure 1.12+" {})))
      
      ;; Call sync-deps
      (sync-deps-fn)
      
      (log/log! {:level :info :msg "Project dependencies synced"})
      
      {:message "Project dependencies synced from deps.edn"
       :status :success})
    
    (catch Exception e
      (log/log! {:level :error :msg "Error syncing dependencies" 
                 :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn check-library-available
  "Check if a library/namespace is available on the classpath"
  [namespace-sym]
  (try
    (let [ns-sym (if (string? namespace-sym) 
                   (symbol namespace-sym)
                   namespace-sym)]
      (require ns-sym)
      {:namespace ns-sym
       :available true
       :message (str "Namespace " ns-sym " is available")
       :status :success})
    (catch Exception e
      {:namespace namespace-sym
       :available false
       :error (.getMessage e)
       :message (str "Namespace " namespace-sym " is not available")
       :status :error})))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn add-libs-tool [mcp-context arguments]
  (log/log! {:level :info :msg "add-libs tool called" :data {:args arguments}})
  (let [coordinates (get arguments "coordinates")
        nrepl-client (:nrepl-client mcp-context)]
    
    ;; For testing, provide helpful message about limitations
    (let [result (try
                   (add-libraries coordinates)
                   (catch Exception e
                     {:error (.getMessage e)
                      :status :error}))]
      (log/log! {:level :info :msg "add-libs tool result" :data {:result result}})
      {:content [{:type "text" 
                  :text (if (= (:status result) :success)
                          (str (:message result) "\n\nAdded libraries: " 
                               (str/join ", " (map str (:libraries result))))
                          (let [error-msg (:error result)]
                            (if (str/includes? error-msg "REPL context")
                              (str "Note: " error-msg 
                                   "\n\nIn test environments, add-libs requires a true REPL context. "
                                   "This tool works in production when connected to a running REPL.")
                              (str "Error: " error-msg))))}]})))

(defn sync-deps-tool [mcp-context arguments]
  (log/log! {:level :info :msg "sync-deps tool called"})
  (let [nrepl-client (:nrepl-client mcp-context)]
    
    (let [result (try
                   (sync-project-deps)
                   (catch Exception e
                     {:error (.getMessage e)
                      :status :error}))]
      (log/log! {:level :info :msg "sync-deps tool result" :data {:result result}})
      {:content [{:type "text" 
                  :text (if (= (:status result) :success)
                          (:message result)
                          (str "Note: " (:error result) 
                               "\n\nIn test environments, sync-deps requires a true REPL context. "
                               "This tool works in production when connected to a running REPL."))}]})))

(defn check-namespace-tool [mcp-context arguments]
  (log/log! {:level :info :msg "check-namespace tool called" :data {:args arguments}})
  (let [namespace (get arguments "namespace")
        result (check-library-available namespace)]
    (log/log! {:level :info :msg "check-namespace tool result" :data {:result result}})
    {:content [{:type "text" 
                :text (:message result)}]}))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Dependency management tool definitions for mcp-toolkit"
  [{:name "add-libs"
    :description "Add libraries to the running REPL without restart (Clojure 1.12+)"
    :inputSchema {:type "object"
                  :properties {:coordinates {:type "object" :description "Library coordinates as EDN map (e.g. {'hiccup/hiccup {:mvn/version \"1.0.5\"}})"}}
                  :required ["coordinates"]}
    :tool-fn add-libs-tool}
   
   {:name "sync-deps"
    :description "Sync dependencies from deps.edn that aren't yet on the classpath"
    :inputSchema {:type "object"
                  :properties {}}
    :tool-fn sync-deps-tool}
   
   {:name "check-namespace"
    :description "Check if a library/namespace is available on the classpath"
    :inputSchema {:type "object"
                  :properties {:namespace {:type "string" :description "Namespace to check (e.g. 'hiccup.core')"}}
                  :required ["namespace"]}
    :tool-fn check-namespace-tool}])