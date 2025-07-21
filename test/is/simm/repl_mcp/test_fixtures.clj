(ns is.simm.repl-mcp.test-fixtures
  "Test fixtures providing embedded nREPL server for testing"
  (:require [clojure.test :as test]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]
            [taoensso.telemere :as log]))

;; Set telemere log level to error to reduce verbose test output
(log/set-min-level! :error)

(def test-nrepl-port 57999)  ; Use high port to avoid conflicts
(def ^:dynamic *test-nrepl-server* nil)
(def ^:dynamic *test-nrepl-client* nil)

(defn start-test-nrepl-server!
  "Start nREPL server for testing"
  []
  (try
    (log/log! {:level :info :msg "Starting test nREPL server" :data {:port test-nrepl-port}})
    (let [server (nrepl-server/start-server 
                   :port test-nrepl-port 
                   :handler (wrap-refactor cider-nrepl-handler))
          client (nrepl/client (nrepl/connect :host "localhost" :port test-nrepl-port) 1000)]
      (log/log! {:level :info :msg "Test nREPL server started successfully"})
      {:server server :client client})
    (catch Exception e
      (log/log! {:level :error :msg "Failed to start test nREPL server" :data {:error (.getMessage e)}})
      (throw e))))

(defn stop-test-nrepl-server!
  "Stop nREPL server"
  [nrepl-state]
  (try
    (when (:client nrepl-state)
      ;; nREPL client doesn't have .close method, just let it be garbage collected
      (log/log! {:level :debug :msg "nREPL client connection closed"}))
    (when (:server nrepl-state)
      (nrepl-server/stop-server (:server nrepl-state))
      ;; Give server threads time to finish cleanup
      (Thread/sleep 100))
    (log/log! {:level :info :msg "Test nREPL server stopped"})
    (catch Exception e
      (log/log! {:level :warn :msg "Error stopping test nREPL server" :data {:error (.getMessage e)}}))))

(defn nrepl-fixture
  "Test fixture that provides nREPL server for all tests in namespace"
  [test-fn]
  (let [nrepl-state (start-test-nrepl-server!)]
    (try
      (binding [*test-nrepl-server* (:server nrepl-state)
                *test-nrepl-client* (:client nrepl-state)]
        (test-fn))
      (finally
        (stop-test-nrepl-server! nrepl-state)))))

(defn create-test-nrepl-config
  "Create nREPL config pointing to test server"
  []
  {:port test-nrepl-port
   :host "localhost"
   :middleware ["cider.nrepl/cider-middleware" "refactor-nrepl.middleware/wrap-refactor"]
   :bind-address "127.0.0.1"})

(defn wait-for-nrepl-ready
  "Wait for nREPL server to be ready"
  [port & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [start-time (System/currentTimeMillis)
        end-time (+ start-time timeout-ms)]
    (loop []
      (let [current-time (System/currentTimeMillis)]
        (cond
          (> current-time end-time)
          (throw (ex-info "nREPL server not ready within timeout" {:port port :timeout-ms timeout-ms}))
          
          (try
            (with-open [client (nrepl/client (nrepl/connect :host "localhost" :port port) 1000)]
              (let [response (nrepl/message client {:op :eval :code "(+ 1 2)"})]
                (= 3 (-> response first :value read-string))))
            (catch Exception _e false))
          true
          
          :else
          (do
            (Thread/sleep 100)
            (recur)))))))

(defn with-test-nrepl-context
  "Create server context using test nREPL configuration"
  [mode & {:keys [tools prompts]}]
  {:tools (or tools {})
   :prompts (or prompts {})
   :mode mode
   :nrepl (create-test-nrepl-config)
   :created-at (java.time.Instant/now)})

;; Utility functions for tests
(defn test-nrepl-eval
  "Evaluate code in test nREPL server"
  [code]
  (when *test-nrepl-client*
    (let [response (nrepl/message *test-nrepl-client* {:op :eval :code code})]
      (-> response first :value))))

(defn test-server-running?
  "Check if test nREPL server is running"
  []
  (try
    (test-nrepl-eval "(+ 1 1)")
    true
    (catch Exception _e false)))

;; ===============================================
;; Integration Test Helpers
;; ===============================================

(defn test-context
  "Create a test context with the nREPL client"
  []
  {:nrepl-client *test-nrepl-client*})

(defn test-context-with-opts
  "Create a test context with additional options"
  [opts]
  (merge {:nrepl-client *test-nrepl-client*} opts))

(defn test-tool-with-nrepl
  "Helper to test a tool with real nREPL client.
   Returns the tool result and performs basic validation."
  [tool-def args & {:keys [expect-success expect-text expect-error context]
                    :or {expect-success true context {}}}]
  (let [tool-fn (:tool-fn tool-def)
        test-context (merge (test-context) context)
        result (tool-fn test-context args)]
    
    ;; Verify basic MCP structure
    (assert (map? result) "Tool result should be a map")
    (assert (contains? result :content) "Tool result should have :content")
    (assert (vector? (:content result)) "Tool :content should be a vector")
    (assert (seq (:content result)) "Tool :content should not be empty")
    
    (let [content-text (:text (first (:content result)))]
      (assert (string? content-text) "Tool content text should be a string")
      
      ;; Check expectations
      (when expect-text
        (let [expected-texts (if (string? expect-text) [expect-text] expect-text)]
          (assert (some #(clojure.string/includes? content-text %) expected-texts)
                  (str "Expected text not found in: " content-text))))
      
      (when expect-error
        (assert (clojure.string/includes? content-text "Error")
                (str "Expected error message. Got: " content-text)))
      
      (when expect-success
        (assert (not (clojure.string/includes? content-text "Error"))
                (str "Unexpected error in successful test. Got: " content-text)))
      
      result)))

(defn find-tool-by-name
  "Find a tool definition by name"
  [tools tool-name]
  (first (filter #(= (:name %) tool-name) tools)))

(defn wait-for-nrepl-warmup
  "Wait for nREPL to be warmed up and ready"
  []
  (when *test-nrepl-client*
    (try
      ;; Simple warmup evaluation
      (nrepl/message *test-nrepl-client* {:op "eval" :code "(+ 1 1)"})
      (Thread/sleep 100)
      (catch Exception e
        (log/log! {:level :warn :msg "nREPL warmup failed" 
                   :data {:error (.getMessage e)}})))))