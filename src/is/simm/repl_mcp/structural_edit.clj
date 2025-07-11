(ns is.simm.repl-mcp.structural-edit
  "Comprehensive structural editing functions using rewrite-clj"
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.paredit :as paredit]
            [rewrite-clj.node :as node]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [taoensso.telemere :as log]))

;; =============================================================================
;; SESSION MANAGEMENT
;; =============================================================================

(def ^:dynamic *sessions* (atom {}))

(defn create-session
  "Create a new zipper session from file or string"
  [session-id source & {:keys [from-file?] :or {from-file? true}}]
  (try
    (let [zipper (if from-file?
                   (z/of-file source)
                   (z/of-string source))
          session {:zipper zipper
                   :original-source source
                   :history []
                   :current-position nil
                   :from-file? from-file?}]
      (swap! *sessions* assoc session-id session)
      {:session-id session-id
       :status :success})
    (catch Exception e
      (log/log! {:level :error :msg "Failed to create session" :data {:session-id session-id :error (.getMessage e)}})
      {:error (.getMessage e) :status :error})))

(defn get-session [session-id]
  (get @*sessions* session-id))

(defn update-session! [session-id update-fn]
  (swap! *sessions* update session-id update-fn))

(defn save-session
  "Save zipper session back to file or return as string"
  [session-id & {:keys [file-path]}]
  (if-let [session (get-session session-id)]
    (try
      (let [content (z/root-string (:zipper session))
            target-path (or file-path (:original-source session))]
        (if (:from-file? session)
          (do
            (spit target-path content)
            {:file-path target-path :status :success})
          {:code content :status :success}))
      (catch Exception e
        (log/log! {:level :error :msg "Failed to save session" :data {:session-id session-id :error (.getMessage e)}})
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn close-session [session-id]
  (swap! *sessions* dissoc session-id)
  {:session-id session-id :status :closed})

;; =============================================================================
;; POSITION AND CONTEXT INFORMATION
;; =============================================================================

(defn get-node-info [zloc]
  "Get detailed information about current node"
  (when zloc
    (let [node (z/node zloc)
          sexpr (try (z/sexpr zloc) (catch Exception _ nil))]
      {:node-type (node/tag node)
       :sexpr sexpr
       :string-repr (z/string zloc)
       :position (try (z/position zloc) (catch Exception _ nil))
       :whitespace? (z/whitespace? zloc)
       :comment? (when-let [n (z/node zloc)] (= :comment (node/tag n)))
       :end? (z/end? zloc)
       :leftmost? (nil? (z/left zloc))
       :rightmost? (nil? (z/right zloc))
       :has-children? (not (nil? (z/down zloc)))
       :depth (loop [loc zloc, depth 0]
                (if-let [parent (z/up loc)]
                  (recur parent (inc depth))
                  depth))})))

(defn get-available-operations [zloc]
  "List operations available at current position"
  (when zloc
    (cond-> []
      (z/up zloc) (conj :up)
      (z/down zloc) (conj :down)
      (z/left zloc) (conj :left)
      (z/right zloc) (conj :right)
      (not (z/end? zloc)) (conj :next)
      (not (z/whitespace? zloc)) (conj :replace :wrap :unwrap)
      (z/seq? zloc) (conj :slurp-forward :slurp-backward :barf-forward :barf-backward)
      (z/vector? zloc) (conj :vector-ops)
      (z/map? zloc) (conj :map-ops)
      (z/set? zloc) (conj :set-ops)
      true (conj :insert-before :insert-after))))

(defn get-zipper-info [session-id]
  "Get comprehensive information about current zipper state"
  (if-let [session (get-session session-id)]
    (let [zloc (:zipper session)]
      {:current-node (get-node-info zloc)
       :parent (when-let [parent (z/up zloc)]
                 (get-node-info parent))
       :children (when-let [child (z/down zloc)]
                   (loop [c child, children []]
                     (if c
                       (recur (z/right c) (conj children (get-node-info c)))
                       children)))
       :siblings {:left (when-let [left (z/left zloc)]
                          (get-node-info left))
                  :right (when-let [right (z/right zloc)]
                           (get-node-info right))}
       :available-operations (get-available-operations zloc)
       :status :success})
    {:error "Session not found" :status :error}))

;; =============================================================================
;; NAVIGATION FUNCTIONS
;; =============================================================================

(defn navigate
  "Navigate zipper in session"
  [session-id direction & {:keys [steps] :or {steps 1}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            nav-fn (case direction
                     :up z/up
                     :down z/down
                     :left z/left
                     :right z/right
                     :next z/next
                     :prev z/prev
                     :leftmost z/leftmost
                     :rightmost z/rightmost)
            new-zloc (loop [loc zloc, remaining steps]
                       (if (and (pos? remaining) loc)
                         (recur (nav-fn loc) (dec remaining))
                         loc))]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :info (get-zipper-info session-id)})
          {:error "Cannot navigate in that direction" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn find-value
  "Find specific value in zipper"
  [session-id value & {:keys [direction] :or {direction :next}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            nav-fn (case direction
                     :next z/next
                     :prev z/prev)
            found-zloc (z/find-value zloc nav-fn value)]
        (if found-zloc
          (do
            (update-session! session-id #(assoc % :zipper found-zloc))
            {:status :success :found value :info (get-zipper-info session-id)})
          {:error (str "Value not found: " value) :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn find-by-predicate
  "Find node matching predicate"
  [session-id pred-fn & {:keys [direction] :or {direction :next}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            nav-fn (case direction
                     :next z/next
                     :prev z/prev)
            found-zloc (z/find zloc nav-fn pred-fn)]
        (if found-zloc
          (do
            (update-session! session-id #(assoc % :zipper found-zloc))
            {:status :success :info (get-zipper-info session-id)})
          {:error "No matching node found" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; STRUCTURAL EDITING OPERATIONS
;; =============================================================================

(defn replace-node
  "Replace current node with new expression"
  [session-id new-expr]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (z/replace zloc new-expr)]
        (update-session! session-id #(assoc % :zipper new-zloc))
        {:status :success :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn edit-node
  "Edit current node with function"
  [session-id edit-fn & args]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (apply z/edit zloc edit-fn args)]
        (update-session! session-id #(assoc % :zipper new-zloc))
        {:status :success :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn insert-before
  "Insert expression before current node"
  [session-id expr]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (z/insert-left zloc expr)]
        (update-session! session-id #(assoc % :zipper new-zloc))
        {:status :success :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn insert-after
  "Insert expression after current node"
  [session-id expr]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (z/insert-right zloc expr)]
        (update-session! session-id #(assoc % :zipper new-zloc))
        {:status :success :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn remove-node
  "Remove current node"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            removed-node (z/sexpr zloc)
            new-zloc (z/remove zloc)]
        (update-session! session-id #(assoc % :zipper new-zloc))
        {:status :success :removed removed-node :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; PAREDIT OPERATIONS
;; =============================================================================

(defn slurp-forward
  "Slurp next sibling into current expression"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (paredit/slurp-forward zloc)]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :slurp-forward :info (get-zipper-info session-id)})
          {:error "Cannot slurp forward" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn slurp-backward
  "Slurp previous sibling into current expression"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (paredit/slurp-backward zloc)]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :slurp-backward :info (get-zipper-info session-id)})
          {:error "Cannot slurp backward" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn barf-forward
  "Barf last element out of current expression"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (paredit/barf-forward zloc)]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :barf-forward :info (get-zipper-info session-id)})
          {:error "Cannot barf forward" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn barf-backward
  "Barf first element out of current expression"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (paredit/barf-backward zloc)]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :barf-backward :info (get-zipper-info session-id)})
          {:error "Cannot barf backward" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn wrap-around
  "Wrap current expression with given type"
  [session-id wrapper-type]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            wrap-type (case wrapper-type
                        "list" :list
                        "vector" :vector
                        "map" :map
                        "set" :set
                        "fn" :fn
                        (keyword wrapper-type))
            new-zloc (paredit/wrap-around zloc wrap-type)]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :wrap-around :wrapper-type wrapper-type :info (get-zipper-info session-id)})
          {:error "Cannot wrap with that type" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn unwrap
  "Remove wrapper, keep contents"
  [session-id & {:keys [direction] :or {direction :forward}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (when (z/seq? zloc)
                       (let [children (-> zloc z/down z/node)]
                         (if children
                           (z/replace zloc children)
                           zloc)))]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :unwrap :direction direction :info (get-zipper-info session-id)})
          {:error "Cannot unwrap in that direction" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn splice
  "Splice current expression (remove parentheses, keep contents)"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            new-zloc (paredit/splice zloc)]
        (if new-zloc
          (do
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :splice :info (get-zipper-info session-id)})
          {:error "Cannot splice at this position" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; COLLECTION-SPECIFIC OPERATIONS
;; =============================================================================

(defn assoc-in-map
  "Associate key-value pair in map"
  [session-id key value]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (if (z/map? zloc)
          (let [new-zloc (z/assoc zloc key value)]
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :assoc :key key :value value :info (get-zipper-info session-id)})
          {:error "Current node is not a map" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn dissoc-in-map
  "Dissociate key from map"
  [session-id key]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (if (z/map? zloc)
          (let [new-zloc (z/edit zloc dissoc key)]
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :dissoc :key key :info (get-zipper-info session-id)})
          {:error "Current node is not a map" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn get-from-map
  "Get value from map by key"
  [session-id key]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (if (z/map? zloc)
          (let [value (z/get zloc key)]
            {:status :success :key key :value value})
          {:error "Current node is not a map" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn append-to-vector
  "Append element to vector"
  [session-id element]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (if (z/vector? zloc)
          (let [new-zloc (-> zloc
                             (z/append-child element))]
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :append :element element :info (get-zipper-info session-id)})
          {:error "Current node is not a vector" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; COMPLEX TRANSFORMATIONS
;; =============================================================================

(defn transform-collection-type
  "Transform between collection types (vector/list/set)"
  [session-id target-type]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (if (z/seq? zloc)
          (let [elements (z/sexpr zloc)
                new-collection (case target-type
                                 "vector" (vec elements)
                                 "list" (list* elements)
                                 "set" (set elements)
                                 elements)
                new-zloc (z/replace zloc new-collection)]
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :transform-collection :target-type target-type :info (get-zipper-info session-id)})
          {:error "Current node is not a collection" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn extract-to-let
  "Extract expression to let binding"
  [session-id binding-name]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            current-expr (z/sexpr zloc)
            binding-symbol (symbol binding-name)
            let-form (list 'let [binding-symbol current-expr] binding-symbol)
            new-zloc (z/replace zloc let-form)]
        (update-session! session-id #(assoc % :zipper new-zloc))
        {:status :success :operation :extract-to-let :binding-name binding-name :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn thread-first
  "Convert nested calls to thread-first (->) macro"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            current-expr (z/sexpr zloc)]
        (if (and (list? current-expr) (> (count current-expr) 1))
          (let [threaded-form (list '-> (second current-expr) (first current-expr) (rest (rest current-expr)))
                new-zloc (z/replace zloc threaded-form)]
            (update-session! session-id #(assoc % :zipper new-zloc))
            {:status :success :operation :thread-first :info (get-zipper-info session-id)})
          {:error "Cannot thread this expression" :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; SMART NAVIGATION SHORTCUTS
;; =============================================================================

(defn navigate-to-end
  "Navigate to the end of the current form or file"
  [session-id & {:keys [target] :or {target :file}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            target-zloc (case target
                         :file (-> zloc z/root z/down z/rightmost)
                         :form (loop [z zloc]
                                 (if-let [right (z/right z)]
                                   (recur right)
                                   z))
                         :list (if (z/list? zloc) 
                                 (-> zloc z/down z/rightmost)
                                 zloc)
                         zloc)]
        (update-session! session-id #(assoc % :zipper target-zloc))
        {:status :success :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn navigate-to-beginning
  "Navigate to the beginning of the current form or file"
  [session-id & {:keys [target] :or {target :file}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            target-zloc (case target
                         :file (-> zloc z/root z/down z/leftmost)
                         :form (loop [z zloc]
                                 (if-let [left (z/left z)]
                                   (recur left)
                                   z))
                         :list (if (z/list? zloc) 
                                 (-> zloc z/down z/leftmost)
                                 zloc)
                         zloc)]
        (update-session! session-id #(assoc % :zipper target-zloc))
        {:status :success :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn find-next-function
  "Navigate to the next function definition"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (loop [current (z/next zloc)]
          (cond
            (nil? current) {:error "No more functions found" :status :error}
            (z/end? current) {:error "No more functions found" :status :error}
            
            (try
              (and (z/list? current)
                   (#{:defn :defmacro :defmulti :defprotocol :defrecord :deftype} 
                    (z/sexpr (z/down current))))
              (catch Exception _ false))
            (do (update-session! session-id #(assoc % :zipper current))
                {:status :success :found "function" :info (get-zipper-info session-id)})
            
            :else (recur (z/next current)))))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn find-previous-function
  "Navigate to the previous function definition"
  [session-id]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (loop [current (z/prev zloc)]
          (cond
            (nil? current) {:error "No previous functions found" :status :error}
            
            (try
              (and (z/list? current)
                   (#{:defn :defmacro :defmulti :defprotocol :defrecord :deftype} 
                    (z/sexpr (z/down current))))
              (catch Exception _ false))
            (do (update-session! session-id #(assoc % :zipper current))
                {:status :success :found "function" :info (get-zipper-info session-id)})
            
            :else (recur (z/prev current)))))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; PATH-BASED NAVIGATION
;; =============================================================================

(defn find-by-symbol
  "Navigate to first occurrence of a symbol by name with enhanced matching"
  [session-id symbol-name & {:keys [exact-match? case-sensitive?] :or {exact-match? false case-sensitive? true}}]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            normalize-str (if case-sensitive? identity str/lower-case)
            target-str (normalize-str symbol-name)
            
            ;; Enhanced matching function
            matches? (fn [current]
                      (try
                        (when-let [sexpr (z/sexpr current)]
                          (let [current-str (normalize-str (str sexpr))]
                            (cond
                              ;; Exact match
                              exact-match? (= current-str target-str)
                              
                              ;; Keyword match - handles both :keyword and keyword
                              (str/starts-with? target-str ":")
                              (or (= current-str target-str)
                                  (= current-str (subs target-str 1)))
                              
                              ;; Symbol match - handles both symbol and :symbol
                              (str/starts-with? current-str ":")
                              (or (= current-str target-str)
                                  (= current-str (str ":" target-str)))
                              
                              ;; Flexible match - contains pattern
                              :else (str/includes? current-str target-str))))
                        (catch Exception _ false)))]
        
        (loop [current zloc]
          (cond
            (nil? current) {:error (format "Symbol '%s' not found" symbol-name) :status :error}
            (z/end? current) {:error (format "Symbol '%s' not found" symbol-name) :status :error}
            
            (matches? current)
            (do (update-session! session-id #(assoc % :zipper current))
                {:status :success :found symbol-name :info (get-zipper-info session-id)})
            
            :else (recur (z/next current)))))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn find-function-definition
  "Navigate to function definition by name"
  [session-id fn-name]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)]
        (loop [current zloc]
          (cond
            (nil? current) {:error (format "Function '%s' not found" fn-name) :status :error}
            (z/end? current) {:error (format "Function '%s' not found" fn-name) :status :error}
            
            (try
              (and (z/list? current)
                   (= (z/sexpr (z/down current)) 'defn)
                   (= (str (z/sexpr (z/right (z/down current)))) fn-name))
              (catch Exception _ false))
            (do (update-session! session-id #(assoc % :zipper current))
                {:status :success :found fn-name :info (get-zipper-info session-id)})
            
            :else (recur (z/next current)))))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn navigate-to-path
  "Navigate to a specific path in the structure (e.g., [1 2 0] for second form, third child, first element)"
  [session-id path]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            ;; Start from root for absolute navigation
            start-zloc (-> zloc z/root z/of-node)
            target (reduce (fn [z step]
                            (cond
                              (nil? z) nil
                              (= step :down) (z/down z)
                              (= step :up) (z/up z)
                              (= step :left) (z/left z)
                              (= step :right) (z/right z)
                              (number? step) (let [child (z/down z)]
                                              (loop [i 0 current child]
                                                (cond
                                                  (nil? current) nil
                                                  (= i step) current
                                                  :else (recur (inc i) (z/right current)))))
                              :else z))
                          start-zloc
                          path)]
        (if target
          (do (update-session! session-id #(assoc % :zipper target))
              {:status :success :path path :info (get-zipper-info session-id)})
          {:error (format "Path %s not found" path) :status :error}))
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; BULK OPERATIONS
;; =============================================================================

;; Helper function for bulk operations
(defn navigate-to-path-internal
  "Internal helper for bulk operations - navigate without updating session"
  [zloc path]
  (try
    (let [start-zloc (-> zloc z/root z/of-node)]
      (reduce (fn [z step]
                (cond
                  (nil? z) nil
                  (= step :down) (z/down z)
                  (= step :up) (z/up z)
                  (= step :left) (z/left z)
                  (= step :right) (z/right z)
                  (number? step) (let [child (z/down z)]
                                  (loop [i 0 current child]
                                    (cond
                                      (nil? current) nil
                                      (= i step) current
                                      :else (recur (inc i) (z/right current)))))
                  :else z))
              start-zloc path))
    (catch Exception _ nil)))

(defn bulk-insert
  "Insert multiple forms at specified locations"
  [session-id operations]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            ;; Sort operations by position (deepest first) to avoid position shifts
            sorted-ops (sort-by (fn [op] (- (count (:path op)))) operations)
            
            ;; Apply operations in sequence
            final-zloc (reduce (fn [current-zloc op]
                                (let [target-zloc (navigate-to-path-internal current-zloc (:path op))]
                                  (if target-zloc
                                    (case (:type op)
                                      :insert-after (z/insert-right target-zloc (z/node (z/of-string (:content op))))
                                      :insert-before (z/insert-left target-zloc (z/node (z/of-string (:content op))))
                                      :insert-child (z/insert-child target-zloc (z/node (z/of-string (:content op))))
                                      :replace (z/replace target-zloc (z/node (z/of-string (:content op))))
                                      current-zloc)
                                    current-zloc)))
                              zloc sorted-ops)]
        
        (update-session! session-id #(assoc % :zipper final-zloc))
        {:status :success :operations-applied (count operations) :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn find-symbol-in-node
  "Find a specific symbol within a zipper node"
  [zloc symbol-name]
  (when zloc
    (cond
      ;; If current node is the symbol we want
      (and (not (z/seq? zloc))
           (= (str (z/sexpr zloc)) symbol-name))
      zloc
      
      ;; If it's a sequence, search children
      (z/seq? zloc)
      (when-let [child (z/down zloc)]
        (loop [current child]
          (when current
            (if-let [found (find-symbol-in-node current symbol-name)]
              found
              (recur (z/right current)))))))))

(defn bulk-find-and-replace
  "Find all occurrences of a pattern and replace them"
  [session-id find-pattern replace-with & {:keys [exact-match?] :or {exact-match? false}}]
  (if-let [session (get-session session-id)]
    (try
      (let [replacements (atom 0)]
        ;; Keep finding and replacing until no more matches
        (loop [iteration 0]
          (when (< iteration 100) ; Safety limit
            (let [find-result (find-by-symbol session-id find-pattern)]
              (if (= (:status find-result) :success)
                (do
                  ;; Navigate to the actual symbol node and replace it
                  (let [current-session (get-session session-id)
                        current-zloc (:zipper current-session)]
                    ;; Find the exact symbol node to replace
                    (when-let [symbol-zloc (find-symbol-in-node current-zloc find-pattern)]
                      (update-session! session-id #(assoc % :zipper symbol-zloc))
                      (swap! replacements inc)
                      (replace-node session-id (symbol replace-with))))
                  (recur (inc iteration)))))))
        
        {:status :success :replacements @replacements :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

(defn bulk-wrap
  "Wrap multiple locations with the same wrapper"
  [session-id paths wrapper-type]
  (if-let [session (get-session session-id)]
    (try
      (let [zloc (:zipper session)
            ;; Sort paths by depth (deepest first) to avoid position shifts
            sorted-paths (sort-by count > paths)
            
            final-zloc (reduce (fn [current-zloc path]
                                (let [target-zloc (navigate-to-path-internal current-zloc path)]
                                  (if target-zloc
                                    (case wrapper-type
                                      :list (paredit/wrap-around target-zloc :list)
                                      :vector (paredit/wrap-around target-zloc :vector)
                                      :map (paredit/wrap-around target-zloc :map)
                                      :set (paredit/wrap-around target-zloc :set)
                                      current-zloc)
                                    current-zloc)))
                              zloc sorted-paths)]
        
        (update-session! session-id #(assoc % :zipper final-zloc))
        {:status :success :wrapped-count (count paths) :info (get-zipper-info session-id)})
      (catch Exception e
        {:error (.getMessage e) :status :error}))
    {:error "Session not found" :status :error}))

;; =============================================================================
;; UTILITY FUNCTIONS
;; =============================================================================

(defn get-all-sessions
  "Get information about all active sessions"
  []
  (into {} (for [[id session] @*sessions*]
             [id {:original-source (:original-source session)
                  :from-file? (:from-file? session)
                  :history-count (count (:history session))}])))

(defn reset-sessions!
  "Clear all sessions (useful for testing)"
  []
  (reset! *sessions* {})
  {:status :success :message "All sessions cleared"})