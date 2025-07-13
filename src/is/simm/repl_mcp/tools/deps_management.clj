(ns is.simm.repl-mcp.tools.deps-management
  "Dynamic dependency management tools for REPL development"
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [is.simm.repl-mcp.logging :as logging]
            [taoensso.telemere :as log]
            [clojure.string :as str]
            [clojure.edn :as edn]))

;; Pure implementation functions for testing

(defn parse-lib-coords
  "Parse library coordinates from various input formats"
  [coords-input]
  (cond
    (map? coords-input) coords-input
    (string? coords-input) (try
                             (edn/read-string coords-input)
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
    
    ;; Check if we're in a REPL context
    (when-not *repl*
      (throw (ex-info "add-libs only works in REPL context" {})))
    
    ;; Get the add-libs function
    (let [add-libs-fn (requiring-resolve 'clojure.repl.deps/add-libs)
          parsed-coords (parse-lib-coords lib-coords)]
      
      (when-not add-libs-fn
        (throw (ex-info "add-libs not available - requires Clojure 1.12+" {})))
      
      ;; Call add-libs
      (add-libs-fn parsed-coords)
      
      (log/log! {:level :info :msg "Libraries added successfully" 
                 :data {:coords parsed-coords}})
      
      {:libraries (vec (keys parsed-coords))
       :coordinates parsed-coords
       :message "Libraries added successfully to REPL classpath"
       :status :success})
    
    (catch Exception e
      (log/log! {:level :error :msg "Error adding libraries" 
                 :data {:error (.getMessage e) :coords lib-coords}})
      (logging/log-error "Library addition failed" e {:coords lib-coords})
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
      (logging/log-error "Dependency sync failed" e {})
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

;; MCP Tool Registrations

(register-tool! :add-libs
  "Add libraries to the running REPL without restart (Clojure 1.12+)"
  {:coordinates {:type "object" :description "Library coordinates as EDN map (e.g. {'hiccup/hiccup {:mvn/version \"1.0.5\"}})"}}
  (fn [tool-call _context]
    (log/log! {:level :info :msg "add-libs tool called" 
               :data {:args (:args tool-call)}})
    (let [{:strs [coordinates]} (:args tool-call)
          result (add-libraries coordinates)]
      (log/log! {:level :info :msg "add-libs tool result" 
                 :data {:result result}})
      {:content [{:type "text" 
                  :text (if (= (:status result) :success)
                          (str (:message result) "\\n\\nAdded libraries: " 
                               (str/join ", " (map str (:libraries result))))
                          (str "Error: " (:error result)))}]
       :isError (= (:status result) :error)}))
  :tags #{:dependencies :development :repl}
  :dependencies #{})

(register-tool! :sync-deps
  "Sync dependencies from deps.edn that aren't yet on the classpath"
  {}
  (fn [_tool-call _context]
    (log/log! {:level :info :msg "sync-deps tool called"})
    (let [result (sync-project-deps)]
      (log/log! {:level :info :msg "sync-deps tool result" 
                 :data {:result result}})
      {:content [{:type "text" 
                  :text (if (= (:status result) :success)
                          (:message result)
                          (str "Error: " (:error result)))}]
       :isError (= (:status result) :error)}))
  :tags #{:dependencies :development :repl :project}
  :dependencies #{})

(register-tool! :check-namespace
  "Check if a library/namespace is available on the classpath"
  {:namespace {:type "string" :description "Namespace to check (e.g. 'hiccup.core')"}}
  (fn [tool-call _context]
    (log/log! {:level :info :msg "check-namespace tool called" 
               :data {:args (:args tool-call)}})
    (let [{:strs [namespace]} (:args tool-call)
          result (check-library-available namespace)]
      (log/log! {:level :info :msg "check-namespace tool result" 
                 :data {:result result}})
      {:content [{:type "text" 
                  :text (:message result)}]
       :isError (= (:status result) :error)}))
  :tags #{:dependencies :development :introspection}
  :dependencies #{})

