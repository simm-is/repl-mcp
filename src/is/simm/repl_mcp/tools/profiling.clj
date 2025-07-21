(ns is.simm.repl-mcp.tools.profiling
  (:require [nrepl.core :as nrepl]
            [taoensso.telemere :as log]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; Helper functions for profiling data analysis

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

(defn calculate-stack-frequencies
  "Calculate frequency of complete call stacks"
  [stacks id->frame]
  (->> stacks
       (map (fn [[stack-vec sample-count]]
              (let [stack-str (->> stack-vec
                                   (map #(get id->frame %))
                                   (filter some?)
                                   (str/join ";"))]
                {:stack stack-str :samples sample-count})))
       (sort-by :samples >)))

(defn format-percentage [samples total-samples]
  (if (> total-samples 0)
    (double (/ (* samples 100.0) total-samples))
    0.0))

(defn add-percentages
  "Add percentage field to items based on total samples"
  [items total-samples]
  (map #(assoc % :percentage (format-percentage (:samples %) total-samples)) items))

(defn build-call-tree
  "Build a hierarchical call tree from stacks"
  [stacks id->frame total-samples]
  (let [tree (atom {:name "ROOT" :self-samples 0 :total-samples 0 :children {}})]
    
    ;; Process each stack  
    (doseq [[stack-vec sample-count] stacks]
      (let [stack-names (mapv #(get id->frame %) stack-vec)]
        ;; Traverse down the tree, creating nodes as needed
        (loop [current-node tree
               remaining-stack stack-names]
          (when (seq remaining-stack)
            (let [frame-name (first remaining-stack)
                  rest-stack (rest remaining-stack)]
              
              ;; Update total samples for current node
              (swap! current-node update :total-samples + sample-count)
              
              ;; If this is the last frame, it gets self-samples
              (when (empty? rest-stack)
                (swap! current-node update :self-samples + sample-count))
              
              ;; Create or update child node
              (when (seq remaining-stack)
                (let [child-node (get-in @current-node [:children frame-name])]
                  (when-not child-node
                    (swap! current-node assoc-in [:children frame-name] 
                           (atom {:name frame-name :self-samples 0 :total-samples 0 :children {}})))
                  
                  ;; Recurse into child
                  (recur (get-in @current-node [:children frame-name]) rest-stack))))))))
    
    ;; Convert atom tree to plain data with percentages
    (letfn [(atom-tree->data [node-atom]
              (let [node @node-atom]
                {:name (:name node)
                 :self-samples (:self-samples node)
                 :total-samples (:total-samples node)
                 :self-percentage (format-percentage (:self-samples node) total-samples)
                 :total-percentage (format-percentage (:total-samples node) total-samples)
                 :children (mapv atom-tree->data (vals (:children node)))}))]
      (atom-tree->data tree))))

(defn analyze-profile-data
  "Analyze raw profile data and return comprehensive navigatable structure"
  [dense-data event-type duration-ms & {:keys [top-k] :or {top-k 10}}]
  (let [{:keys [stacks id->frame]} dense-data
        total-samples (reduce + (map second stacks))
        
        ;; Build complete call tree
        call-tree (build-call-tree stacks id->frame total-samples)
        
        ;; Calculate frame and stack frequencies
        frame-frequencies (calculate-frame-frequencies stacks id->frame)
        stack-frequencies (calculate-stack-frequencies stacks id->frame)
        
        ;; All functions with percentages (not just top-k)
        all-functions (->> frame-frequencies
                          (#(add-percentages % total-samples)))
        
        ;; All stacks with percentages
        all-stacks (->> stack-frequencies
                       (#(add-percentages % total-samples)))
        
        ;; Hot paths (complete execution paths)
        hot-paths (->> stacks
                      (map (fn [[stack-vec sample-count]]
                            {:stack-ids (vec stack-vec)
                             :stack-names (mapv #(get id->frame %) stack-vec)
                             :samples sample-count
                             :percentage (format-percentage sample-count total-samples)}))
                      (sort-by :samples >))
        
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
     :call-tree call-tree
     :all-functions all-functions
     :all-stacks all-stacks
     :hot-paths hot-paths
     :frame-index (into {} (map-indexed (fn [i frame] [i frame]) id->frame))
     :raw-data dense-data}))

(defn profile-expression-comprehensive
  "Profile a Clojure expression and return comprehensive analysis"
  [nrepl-client code-str & {:keys [event duration generate-flamegraph namespace top-k]
                            :or {event :cpu duration 5000 generate-flamegraph false top-k 10}}]
  (try
    (log/log! {:level :info :msg "Starting comprehensive profiling" 
               :data {:code code-str :event event :duration duration}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client not available")))
    
    (let [start-time (System/currentTimeMillis)
          
          ;; Prepare the profiling code to execute in nREPL
          profiling-code 
          (format 
           "(do
              (require '[clj-async-profiler.core :as prof]
                       '[clj-async-profiler.post-processing :as post-proc])
              %s
              (prof/start {:event %s})
              (let [start-time (System/currentTimeMillis)
                    result %s
                    end-time (System/currentTimeMillis)
                    raw-file (prof/stop {:generate-flamegraph? %s})
                    dense-data (post-proc/read-raw-profile-file-to-dense-profile raw-file)]
                {:result result
                 :duration-ms (- end-time start-time)
                 :raw-file (str raw-file)
                 :dense-data dense-data}))"
           (if namespace (str "(in-ns '" namespace ")") "")
           (str event)
           code-str
           generate-flamegraph)
          
          ;; Execute profiling in nREPL
          _ (log/log! {:level :debug :msg "Sending profiling code to nREPL"
                       :data {:profiling-code-length (count profiling-code)
                              :profiling-code (subs profiling-code 0 (min 200 (count profiling-code)))}})
          responses (nrepl/message nrepl-client {:op "eval" :code profiling-code :timeout 10000})
          combined (nrepl/combine-responses responses)
          
          end-time (System/currentTimeMillis)]
      
      (log/log! {:level :debug :msg "Profiling nREPL responses" 
                 :data {:response-count (count responses)}})
      
      (log/log! {:level :debug :msg "Combined response" 
                 :data {:combined combined :values (nrepl/response-values responses)}})
      
      (if (:err combined)
        {:error (:err combined) :status :error}
        
        (let [response-values (:value combined)
              first-value (first response-values)]
          
          (log/log! {:level :debug :msg "Response analysis"
                     :data {:response-values-count (count response-values)
                            :first-value-exists (boolean first-value)
                            :first-value-length (when first-value (count first-value))}})
          
          (if (empty? response-values)
            {:error "No response values from profiling" :status :error}
            
            (let [result-data (read-string first-value)
              {:keys [result duration-ms raw-file dense-data]} result-data
              
              ;; Analyze the profiling data
              analysis (analyze-profile-data dense-data event duration-ms :top-k top-k)
              
              ;; Build comprehensive response
              comprehensive-result
              (merge analysis
                     {:result result
                      :files {:raw-data raw-file
                              :flamegraph (when generate-flamegraph
                                           (str/replace raw-file "-collapsed.txt" "-flamegraph.html"))}
                      :metadata {:start-time (Instant/ofEpochMilli start-time)
                                 :end-time (Instant/ofEpochMilli end-time)
                                 :profiler-version "3.0"
                                 :jvm-info {:version (System/getProperty "java.version")
                                            :vendor (System/getProperty "java.vendor")}}})]
          
          (log/log! {:level :info :msg "Profiling completed successfully"
                     :data {:total-samples (get-in comprehensive-result [:summary :total-samples])
                            :top-frame (first (get comprehensive-result :top-frames))}})
          
          comprehensive-result)))))
    
    (catch Exception e
      (log/log! {:level :error :msg "Profiling failed" :data {:error (.getMessage e)}})
      {:error (.getMessage e) :status :error})))

(defn format-tree-node
  "Format a single tree node for display"
  [node depth max-depth]
  (when (<= depth max-depth)
    (let [indent (str/join "" (repeat (* depth 2) " "))
          self-info (if (> (:self-samples node) 0)
                     (format " [self: %.1f%%, %d samples]" 
                            (:self-percentage node) (:self-samples node))
                     "")]
      (str indent "• " (:name node) 
           (format " (%.1f%%, %d total%s)" 
                   (:total-percentage node) (:total-samples node) self-info)
           (when (and (seq (:children node)) (< depth max-depth))
             (str "\n" (str/join "\n" 
                               (map #(format-tree-node % (inc depth) max-depth) 
                                    (take 5 (sort-by :total-samples > (:children node)))))))))))

(defn format-profile-result
  "Format profile result for readable MCP output with multiple navigation views"
  [result]
  (let [{:keys [summary all-functions hot-paths call-tree]} result
        
        ;; Header with summary
        header (format "🔍 %s Profile Results\n%s\n• Duration: %dms (%.1f samples/sec)\n• Total samples: %d across %d unique stacks\n• Unique functions: %d\n"
                       (str/upper-case (:event-type summary))
                       (str/join "" (repeat 50 "="))
                       (:duration-ms summary)
                       (:samples-per-second summary)
                       (:total-samples summary)
                       (:unique-stacks summary)
                       (:unique-functions summary))
        
        ;; Top functions by samples
        top-functions (str "\n🔥 TOP FUNCTIONS BY SAMPLES:\n"
                          (->> all-functions
                               (take 15)
                               (map-indexed (fn [i {:keys [frame samples percentage]}]
                                             (format "%2d. %5.1f%% (%3d samples) %s" 
                                                     (inc i) percentage samples frame)))
                               (str/join "\n")))
        
        ;; Call tree view (hierarchical)
        tree-view (str "\n\n🌳 CALL TREE (top 3 levels):\n"
                      (format-tree-node call-tree 0 3))
        
        ;; Hot execution paths
        hot-paths-view (str "\n\n🔥 HOTTEST EXECUTION PATHS:\n"
                           (->> hot-paths
                                (take 10)
                                (map-indexed (fn [i {:keys [stack-names samples percentage]}]
                                              (format "%2d. %5.1f%% (%3d samples)\n    %s"
                                                      (inc i) percentage samples 
                                                      (str/join " → " (take 5 stack-names)))))
                                (str/join "\n\n")))
        
        ;; Data access info
        data-info (str "\n\n📊 AVAILABLE DATA:\n"
                      "• :call-tree - Hierarchical call tree with self/total samples\n"
                      "• :all-functions - All functions sorted by frequency\n"
                      "• :hot-paths - Complete execution paths with samples\n"
                      "• :all-stacks - All unique call stacks\n"
                      "• :frame-index - ID to frame name mapping\n"
                      "• :raw-data - Original clj-async-profiler data")]
    
    (str header top-functions tree-view hot-paths-view data-info)))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn profile-cpu-tool [mcp-context arguments]
  (try
    (log/log! {:level :info :msg "CPU profiling tool raw inputs" 
               :data {:arguments arguments :arguments-type (type arguments) 
                      :mcp-context-keys (keys mcp-context)}})
    (let [{:keys [code duration generate-flamegraph namespace top-k]} arguments
          nrepl-client (:nrepl-client mcp-context)]
      (log/log! {:level :info :msg "CPU profiling tool called"
                 :data {:code code :duration duration :generate-flamegraph generate-flamegraph}})
      
      (if (nil? nrepl-client)
        {:content [{:type "text" 
                   :text "Error: nREPL client not available"}]}
        
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
                              (format-profile-result result))}]})))
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
      
      (if (nil? nrepl-client)
        {:content [{:type "text" 
                   :text "Error: nREPL client not available"}]}
        
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
                              (format-profile-result result))}]})))
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