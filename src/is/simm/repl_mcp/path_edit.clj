(ns is.simm.repl-mcp.path-edit
  "Path-based structural editing using rewrite-clj and Specter"
  (:require [rewrite-clj.zip :as z]
            [com.rpl.specter :as sp]
            [clojure.string :as str]
            [is.simm.repl-mcp.structural-edit :as edit]))

;; =============================================================================
;; PATH UTILITIES
;; =============================================================================

(defn right-n
  "Move n positions to the right"
  [zloc n]
  (nth (iterate z/right zloc) n))

(defn get-semantic-path
  "Get semantic path to current zipper location"
  [zloc]
  (loop [loc zloc, path []]
    (if-let [parent (z/up loc)]
      (let [parent-type (z/tag parent)
            position (loop [l loc, i 0]
                      (if-let [left (z/left l)]
                        (recur left (inc i))
                        i))
            semantic-info (cond
                           (z/map? parent) (let [key-loc (z/left loc)]
                                            (if (and key-loc (keyword? (z/sexpr key-loc)))
                                              {:type :map-value :key (z/sexpr key-loc)}
                                              {:type :map-entry :index position}))
                           (z/vector? parent) {:type :vector :index position}
                           (z/list? parent) {:type :list :index position}
                           (z/set? parent) {:type :set :index position}
                           :else {:type parent-type :index position})]
        (recur parent (conj path semantic-info)))
      path)))

(defn navigate-to-semantic-path
  "Navigate zipper to semantic path"
  [zloc path]
  (reduce (fn [loc step]
            (when loc
              (case (:type step)
                :map-value (-> loc
                              (z/find-value z/next (:key step))
                              z/right)
                :vector (right-n (z/down loc) (:index step))
                :list (right-n (z/down loc) (:index step))
                (z/down loc))))
          zloc
          (reverse path)))

(defn matches-query?
  "Check if zipper location matches query"
  [zloc query]
  (cond
    (keyword? query) (and (not (z/whitespace? zloc)) (= (z/sexpr zloc) query))
    (map? query) (every? (fn [[k v]]
                          (case k
                            :type (= (z/tag zloc) v)
                            :value (= (z/sexpr zloc) v)
                            :parent (and (z/up zloc) (matches-query? (z/up zloc) v))
                            false))
                        query)
    :else false))

(defn find-by-semantic-query
  "Find nodes matching semantic query"
  [zloc query]
  (let [results (atom [])]
    (z/prewalk zloc
      (fn [loc]
        (when (matches-query? loc query)
          (swap! results conj loc))
        loc))
    @results))

;; =============================================================================
;; SPECTER INTEGRATION
;; =============================================================================

(defn zipper->data
  "Convert zipper to plain data for Specter"
  [zloc]
  (z/sexpr (z/root zloc)))

(defn data->zipper
  "Convert data back to zipper with position tracking"
  [data]
  (z/of-string (pr-str data) {:track-position? true}))

(defn specter-transform-session
  "Apply Specter transformation to session"
  [session-id specter-path transform-fn]
  (let [session (edit/get-session session-id)]
    (when session
      (let [data (zipper->data (:zipper session))
            result (sp/transform specter-path transform-fn data)
            new-zloc (data->zipper result)]
        (edit/update-session! 
          session-id 
          #(assoc % :zipper new-zloc))
        {:status :success
         :transform :specter
         :path specter-path
         :result result}))))

;; =============================================================================
;; TEMPLATE-BASED OPERATIONS
;; =============================================================================

(def templates
  {:function "(defn ~name ~params\n  ~body)"
   :def "(def ~name\n  ~value)"
   :require "[~ns ~as-alias]"
   :let "(let [~bindings]\n  ~body)"
   :if "(if ~condition\n  ~then\n  ~else)"})

(defn render-template
  "Render template with data"
  [template-name data]
  (let [template (get templates template-name)]
    (reduce (fn [tmpl [k v]]
              (clojure.string/replace tmpl 
                (str "~" (name k)) 
                (str v)))
            template
            data)))

(defn insert-template-at-path
  "Insert rendered template at semantic path"
  [session-id template-name data path]
  (let [session (edit/get-session session-id)]
    (when session
      (let [code (render-template template-name data)
            zloc (navigate-to-semantic-path (:zipper session) path)
            new-zloc (z/insert-right zloc (z/of-string code))]
        (edit/update-session! 
          session-id 
          #(assoc % :zipper new-zloc))
        {:status :success
         :template template-name
         :code code
         :path path}))))

;; =============================================================================
;; BATCH OPERATIONS
;; =============================================================================

(defn execute-single-operation
  "Execute a single operation"
  [session-id operation]
  (case (:type operation)
    :navigate (edit/navigate 
                session-id (:direction operation) :steps (:steps operation 1))
    :replace (edit/replace-node 
               session-id (:value operation))
    :insert-after (edit/insert-after 
                    session-id (:value operation))
    :find-value (edit/find-value 
                  session-id (:value operation))
    :specter-transform (specter-transform-session 
                         session-id 
                         (:path operation) 
                         (:transform operation))
    {:status :error :error (str "Unknown operation: " (:type operation))}))

(defn execute-batch-operations
  "Execute multiple operations atomically"
  [session-id operations]
  (let [session (edit/get-session session-id)
        original-zipper (:zipper session)]
    (try
      (let [results (mapv #(execute-single-operation session-id %) operations)]
        {:status :success
         :operations (count operations)
         :results results})
      (catch Exception e
        ;; Rollback on error
        (edit/update-session! 
          session-id 
          #(assoc % :zipper original-zipper))
        {:status :error
         :error (.getMessage e)
         :rollback true}))))

;; =============================================================================
;; VALIDATION AND PREVIEW
;; =============================================================================

(defn validate-session-syntax
  "Validate current session syntax"
  [session-id]
  (let [session (edit/get-session session-id)]
    (if session
      (try
        (let [code (z/root-string (:zipper session))]
          (read-string code)
          {:valid? true :syntax :ok})
        (catch Exception e
          {:valid? false 
           :syntax :error 
           :error (.getMessage e)}))
      {:valid? false :error "Session not found"})))

(defn preview-session-changes
  "Preview changes without saving"
  [session-id]
  (let [session (edit/get-session session-id)]
    (if session
      (let [original (:original-source session)
            current (z/root-string (:zipper session))
            changed? (not= original current)]
        {:preview current
         :original original
         :changed? changed?
         :length-delta (- (count current) (count original))})
      {:error "Session not found"})))

(defn get-session-summary
  "Get comprehensive session summary"
  [session-id]
  (let [session (edit/get-session session-id)]
    (if session
      (let [zloc (:zipper session)
            current-node (when zloc (z/sexpr zloc))
            path (when zloc (get-semantic-path zloc))
            validation (validate-session-syntax session-id)
            preview (preview-session-changes session-id)]
        {:session-id session-id
         :current-node current-node
         :semantic-path path
         :validation validation
         :preview preview
         :position (when zloc
                    (try (z/position zloc) 
                         (catch Exception _ {:line "unknown" :column "unknown"})))})
      {:error "Session not found"})))