(ns is.simm.repl-mcp.tools.clj-kondo
  "clj-kondo integration tools for static analysis and linting"
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [is.simm.repl-mcp.logging :as logging]
            [clj-kondo.core :as clj-kondo]
            [taoensso.telemere :as log]
            [clojure.string :as str]))

;; Pure implementation functions for testing

(defn format-findings
  "Format clj-kondo findings for human-readable output"
  [findings]
  (if (empty? findings)
    "No issues found."
    (str/join "\n"
              (map (fn [{:keys [filename row col level type message]}]
                     (format "%s:%d:%d: %s [%s] %s"
                             (or filename "<stdin>")
                             (or row 1)
                             (or col 1)
                             (name level)
                             (name type)
                             message))
                   findings))))

(defn lint-code-string
  "Lint a code string using clj-kondo"
  [code & {:keys [filename lang config]
           :or {filename "stdin.clj" 
                lang :clj
                config {}}}]
  (try
    (log/log! {:level :info :msg "Linting code string" 
               :data {:filename filename :lang lang :config config}})
    (let [result (with-in-str code
                   (clj-kondo/run! (cond-> {:lint ["-"]
                                           :lang lang
                                           :filename filename}
                                     (seq config) (assoc :config config))))
          {:keys [findings summary]} result
          formatted-output (format-findings findings)]
      (log/log! {:level :info :msg "Linting completed" 
                 :data {:findings-count (count findings) :summary summary}})
      {:findings findings
       :summary summary
       :formatted-output formatted-output
       :status (if (pos? (:error summary 0)) :error :success)})
    (catch Exception e
      (log/log! {:level :error :msg "Error linting code" 
                 :data {:error (.getMessage e) :filename filename}})
      (logging/log-error "clj-kondo lint failed" e {:filename filename})
      {:error (.getMessage e)
       :status :error})))

(defn lint-files
  "Lint files or directories using clj-kondo"
  [paths & {:keys [config cache parallel]
            :or {cache true parallel true}}]
  (try
    (log/log! {:level :info :msg "Linting files" 
               :data {:paths paths :config config :cache cache :parallel parallel}})
    (let [result (clj-kondo/run! (cond-> {:lint paths
                                         :cache cache
                                         :parallel parallel}
                                   (seq config) (assoc :config config)))
          {:keys [findings summary]} result
          formatted-output (format-findings findings)]
      (log/log! {:level :info :msg "File linting completed" 
                 :data {:findings-count (count findings) :summary summary}})
      {:findings findings
       :summary summary
       :formatted-output formatted-output
       :status (if (pos? (:error summary 0)) :error :success)})
    (catch Exception e
      (log/log! {:level :error :msg "Error linting files" 
                 :data {:error (.getMessage e) :paths paths}})
      (logging/log-error "clj-kondo file lint failed" e {:paths paths})
      {:error (.getMessage e)
       :status :error})))

(defn setup-project-config
  "Initialize or update clj-kondo configuration for the project"
  [project-root & {:keys [copy-configs dependencies]
                   :or {copy-configs true dependencies false}}]
  (try
    (log/log! {:level :info :msg "Setting up clj-kondo config" 
               :data {:project-root project-root :copy-configs copy-configs :dependencies dependencies}})
    (let [result (clj-kondo/run! (cond-> {:lint [project-root]
                                         :skip-lint true
                                         :copy-configs copy-configs}
                                   dependencies (assoc :dependencies true)))
          {:keys [summary]} result]
      (log/log! {:level :info :msg "Config setup completed" 
                 :data {:summary summary}})
      {:summary summary
       :message "clj-kondo configuration updated successfully"
       :status :success})
    (catch Exception e
      (log/log! {:level :error :msg "Error setting up config" 
                 :data {:error (.getMessage e) :project-root project-root}})
      (logging/log-error "clj-kondo config setup failed" e {:project-root project-root})
      {:error (.getMessage e)
       :status :error})))

;; MCP Tool Registrations

(register-tool! :lint-code
  "Lint Clojure code string for errors and style issues"
  {:code {:type "string" :description "Clojure code to lint"}
   :filename {:type "string" :optional true :description "Filename for context (default: stdin.clj)"}
   :lang {:type "string" :optional true :description "Language: clj, cljs, or cljc (default: clj)"}
   :config {:type "object" :optional true :description "Custom clj-kondo configuration as EDN map"}}
  (fn [tool-call _context]
    (log/log! {:level :info :msg "lint-code tool called" 
               :data {:args (:args tool-call)}})
    (let [{:strs [code filename lang config]} (:args tool-call)
          lang-kw (when lang (keyword lang))
          result (lint-code-string code 
                                   :filename filename 
                                   :lang lang-kw 
                                   :config config)]
      (log/log! {:level :info :msg "lint-code tool result" 
                 :data {:result result}})
      {:content [{:type "text" 
                  :text (:formatted-output result)}]
       :isError (= (:status result) :error)}))
  :tags #{:linting :analysis :code-quality}
  :dependencies #{})

(register-tool! :lint-project
  "Lint entire project or specific paths for errors and style issues"
  {:paths {:type "array" :description "Array of file paths or directories to lint"}
   :config {:type "object" :optional true :description "Custom clj-kondo configuration as EDN map"}
   :cache {:type "boolean" :optional true :description "Enable caching (default: true)"}
   :parallel {:type "boolean" :optional true :description "Enable parallel processing (default: true)"}}
  (fn [tool-call _context]
    (log/log! {:level :info :msg "lint-project tool called" 
               :data {:args (:args tool-call)}})
    (let [{:strs [paths config cache parallel]} (:args tool-call)
          result (lint-files paths 
                            :config config 
                            :cache cache 
                            :parallel parallel)]
      (log/log! {:level :info :msg "lint-project tool result" 
                 :data {:result result}})
      {:content [{:type "text" 
                  :text (:formatted-output result)}]
       :isError (= (:status result) :error)}))
  :tags #{:linting :analysis :code-quality :project}
  :dependencies #{})

(register-tool! :setup-clj-kondo
  "Initialize or update clj-kondo configuration for the project"
  {:project-root {:type "string" :description "Root directory of the project"}
   :copy-configs {:type "boolean" :optional true :description "Copy configurations from dependencies (default: true)"}
   :dependencies {:type "boolean" :optional true :description "Analyze dependencies for config (default: false)"}}
  (fn [tool-call _context]
    (log/log! {:level :info :msg "setup-clj-kondo tool called" 
               :data {:args (:args tool-call)}})
    (let [{:strs [project-root copy-configs dependencies]} (:args tool-call)
          result (setup-project-config project-root 
                                       :copy-configs copy-configs 
                                       :dependencies dependencies)]
      (log/log! {:level :info :msg "setup-clj-kondo tool result" 
                 :data {:result result}})
      {:content [{:type "text" 
                  :text (:message result)}]
       :isError (= (:status result) :error)}))
  :tags #{:linting :configuration :setup}
  :dependencies #{})