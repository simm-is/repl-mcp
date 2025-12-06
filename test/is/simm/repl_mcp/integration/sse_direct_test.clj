(ns is.simm.repl-mcp.integration.sse-direct-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.server :as server]
            [is.simm.repl-mcp.tools :as tools]
            [is.simm.repl-mcp.transport.sse :as sse]
            [babashka.http-client :as http]
            [jsonista.core :as json]))

(deftest direct-sse-test
  "Test SSE directly without complex fixtures"
  (testing "Direct server creation and HTTP test"
    
    ;; Create server instance without nREPL dependency
    (let [context-factory (fn []
                           (let [instance (server/create-mcp-server-instance!
                                           {:tools (tools/get-tool-definitions)
                                            :server-info {:name "direct-sse-server" :version "1.0.0"}})]
                             {:session (:session instance)
                              :nrepl-client nil})) ; No nREPL for this test
          test-port 18491]
      
      (let [http-server (sse/start-http-server! context-factory test-port)]
        
        (try
          (Thread/sleep 2000)
          
          (let [response (http/get (str "http://127.0.0.1:" test-port "/sse")
                                  {:timeout 5000
                                   :throw false
                                   :as :stream})]
            (is (= 200 (:status response)))
            (is (= "text/event-stream" (get-in response [:headers "content-type"])))
            
            ;; Read from stream incrementally
            (with-open [reader (java.io.BufferedReader. 
                               (java.io.InputStreamReader. (:body response)))]
              (let [session-id (loop [lines-read 0]
                                (when (< lines-read 10) ; Limit to prevent infinite loop
                                  (when-let [line (.readLine reader)]
                                    (cond
                                      (.startsWith line "event: endpoint")
                                      (let [data-line (.readLine reader)]
                                        (when (.startsWith data-line "data: ")
                                          (let [endpoint (subs data-line 6)]
                                            (last (clojure.string/split endpoint #"/")))))
                                      
                                      :else
                                      (recur (inc lines-read))))))]
                (is (string? session-id))
                (is (not (empty? session-id)))
                
                ;; Now test that we can send a message
                (when session-id
                  (let [message-url (str "http://127.0.0.1:" test-port "/messages/" session-id)
                        init-msg {:jsonrpc "2.0"
                                 :method "initialize" 
                                 :params {:clientInfo {:name "test-client" :version "1.0.0"}
                                         :protocolVersion "2025-03-26"
                                         :capabilities {}}
                                 :id 1}]
                    (let [response (http/post message-url 
                                             {:headers {"Content-Type" "application/json"}
                                              :body (json/write-value-as-string init-msg)
                                              :timeout 3000
                                              :throw false})]
                      ;; Should be 202 Accepted
                      (is (= 202 (:status response)))
                      (is (= "Accepted" (:body response)))))))))
          
          (finally
            (sse/stop-http-server! http-server)))))))