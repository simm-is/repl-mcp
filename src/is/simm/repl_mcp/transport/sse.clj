(ns is.simm.repl-mcp.transport.sse
  "SSE transport implementation for repl-mcp based on mcp-toolkit patterns"
  (:require
   [clojure.string :as str]
   [jsonista.core :as j]
   [mcp-toolkit.json-rpc :as json-rpc]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as reitit]
   [taoensso.telemere :as log]))

(def object-mapper
  (j/object-mapper {:decode-key-fn keyword :encode-key-fn name}))

(defn parse-message [body]
  (try
    (j/read-value body object-mapper)
    (catch Exception e
      (log/log! {:level :error :msg "JSON parse error" 
                 :data {:error (.getMessage e)}})
      nil)))

(defn ->json [v]
  (j/write-value-as-string v object-mapper))

(defn error-response [msg status]
  {:status  status
   :headers {"content-type" "text/plain"}
   :body    msg})

(defn valid-content-type? [req]
  (let [ct (get-in req [:headers "content-type"])]
    (and (some? ct) (str/starts-with? ct "application/json"))))

(defn validate-request [req]
  (cond
    (and (= :post (:request-method req))
         (not (valid-content-type? req))) (error-response "Invalid Content-Type header" 400)
    :else nil))

(def base-sse-headers 
  {"Content-Type"  "text/event-stream"
   "Cache-Control" "no-cache, no-transform"
   "Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
   "Access-Control-Allow-Headers" "content-type"})

(defn sse-headers [req]
  (let [protocol (:protocol req)
        keep-alive? (or (nil? protocol) (neg? (compare protocol "HTTP/1.1")))]
    (cond-> base-sse-headers
      keep-alive? (assoc "Connection" "keep-alive"))))

(defn send-base-sse-response! [req channel]
  (log/log! {:level :debug :msg "Sending SSE headers"})
  (http-kit/send! channel
                  {:status 200
                   :headers (sse-headers req)}
                  false))

(defn channel-send! [channel event data]
  (let [message (str "event: " event "\ndata: " data "\n\n")]
    (log/log! {:level :info :msg "Sending SSE message" 
               :data {:event event :data data :message message}})
    (http-kit/send! channel message false)))

(defonce connections (atom {}))

(defn new-session-id [] 
  (str (java.util.UUID/randomUUID)))

(defn assoc-session! [session-id channel mcp-context]
  (let [data {:session/session-id session-id
              :session/channel channel
              :session/context mcp-context
              :session/send! (fn [event data]
                              (channel-send! channel event data))}]
    (swap! connections assoc session-id data)
    data))

(defn dissoc-session! [session-id]
  (swap! connections dissoc session-id))

(defn fetch-session [session-id]
  (get @connections session-id))

(defn make-send-message [{:session/keys [send!]}]
  (fn [message]
    (log/log! {:level :debug :msg "SSE outgoing message" :data {:message message}})
    (send! "message" (->json message))))

(defn handle-message-response [session message]
  (let [context (assoc (:session/context session)
                       :send-message (make-send-message session)
                       :connection-id (:session/session-id session))]
    (log/log! {:level :debug :msg "SSE accepted message" :data {:message message}})
    (json-rpc/handle-message context message))
  {:status 202
   :headers {"content-type" "text/plain"}
   :body "Accepted"})

(defn handle-sse-stream [context-factory req]
  (if-let [error-response (validate-request req)]
    error-response
    (let [session-id (new-session-id)]
      (http-kit/as-channel req
                           {:on-open
                            (fn [channel]
                              (log/log! {:level :info :msg "SSE connection opened" 
                                         :data {:session-id session-id}})
                              (let [mcp-context (context-factory)
                                    {:session/keys [send!]} (assoc-session!
                                                              session-id 
                                                              channel
                                                              mcp-context)]
                                (send-base-sse-response! req channel)
                                (send! "endpoint" (str "/messages/" session-id))))
                            :on-close
                            (fn [_channel status]
                              (log/log! {:level :info :msg "SSE connection closed" 
                                         :data {:status status :session-id session-id}})
                              (dissoc-session! session-id))}))))

(defn handle-messages [req]
  (if-let [error-response (validate-request req)]
    error-response
    (if-let [session (fetch-session (get-in req [:path-params :id]))]
      (if-let [message (parse-message (:body req))]
        (handle-message-response session message)
        (error-response "Could not parse message" 400))
      (error-response "Session not found" 404))))

(defn routes [context-factory]
  [""
   ["/sse" {:get (partial handle-sse-stream context-factory)}]
   ["/messages/:id" {:post handle-messages}]])

(defn create-ring-handler [context-factory]
  (reitit/ring-handler
   (reitit/router (routes context-factory))))

(defn start-http-server! [context-factory port]
  (log/log! {:level :info :msg "Starting HTTP+SSE server" :data {:port port}})
  (let [handler (create-ring-handler context-factory)]
    (http-kit/run-server handler {:port port :legacy-return-value? false})))

(defn stop-http-server! [server]
  (when server
    (log/log! {:level :info :msg "Stopping HTTP+SSE server"})
    (http-kit/server-stop! server {:timeout 100})
    (reset! connections {})))