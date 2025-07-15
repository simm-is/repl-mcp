(ns is.simm.repl-mcp.test-fixtures
  "Test fixtures providing embedded nREPL server for testing"
  (:require [clojure.test :as test]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]
            [taoensso.telemere :as log]))

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