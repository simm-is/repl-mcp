(ns is.simm.repl-mcp.tools.refactor
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [is.simm.repl-mcp.structural-edit :as edit]
            [nrepl.core :as nrepl]
            [clojure.edn :as edn]
            [rewrite-clj.zip :as z]
            [taoensso.telemere :as log]))

;; Pure implementation functions for testing

(defn clean-namespace
  "Clean and organize namespace declarations using refactor-nrepl. Returns result map."
  [nrepl-client file-path & {:keys [prune-unused prefer-prefix] 
                             :or {prune-unused true prefer-prefix true}}]
  (try
    (log/log! {:level :info :msg "Cleaning namespace in file" :data {:file-path file-path}})
    (let [responses (nrepl/message nrepl-client 
                                  {:op "clean-ns" 
                                   :path file-path
                                   :prune-ns-form (boolean prune-unused)
                                   :prefix-rewriting (boolean prefer-prefix)})
          result (reduce (fn [acc response]
                          (cond
                            (:ns response) (assoc acc :cleaned-ns (:ns response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        {:error (:error result)
         :output (:output result)
         :status :error}
        {:cleaned-ns (:cleaned-ns result)
         :output (:output result)
         :file-path file-path
         :status :success}))
    (catch Exception e
      (log/log! {:level :error :msg "Error cleaning namespace" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

;; MCP Tool Registrations

(register-tool! :clean-ns
  "Clean and organize namespace declarations"
  {:file-path {:type "string" :description "Path to the Clojure file"}
   :prune-unused {:type "boolean" :default true :optional true :description "Remove unused requires"}
   :prefer-prefix {:type "boolean" :default true :optional true :description "Prefer prefix form for requires"}}
  (fn [tool-call context]
    (let [{:strs [file-path prune-unused prefer-prefix]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (clean-namespace nrepl-client file-path 
                      :prune-unused prune-unused 
                      :prefer-prefix prefer-prefix))))

(defn find-symbol-occurrences
  "Find all occurrences of a symbol in the codebase using refactor-nrepl. Returns result map."
  [nrepl-client file-path line column]
  (try
    (log/log! {:level :info :msg "Finding symbol" :data {:file-path file-path :line line :column column}})
    (let [responses (nrepl/message nrepl-client 
                                  {:op "find-symbol" 
                                   :file file-path
                                   :line line
                                   :column column})
          occurrences (atom [])]
      (doseq [response responses]
        (when (:occurrence response)
          (swap! occurrences conj (:occurrence response))))
      
      {:occurrences @occurrences
       :file-path file-path
       :line line
       :column column
       :status :success})
    (catch Exception e
      (log/log! {:level :error :msg "Error finding symbol" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(register-tool! :find-symbol
  "Find all occurrences of a symbol in the codebase"
  {:file-path {:type "string" :description "Path to the Clojure file"}
   :line {:type "number" :description "Line number of the symbol"}
   :column {:type "number" :description "Column number of the symbol"}}
  (fn [tool-call context]
    (let [{:strs [file-path line column]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (find-symbol-occurrences nrepl-client file-path line column))))

(defn rename-file-or-directory
  "Rename a file or directory and update all references using refactor-nrepl. Returns result map."
  [nrepl-client old-path new-path]
  (try
    (log/log! {:level :info :msg "Renaming file/directory" :data {:old-path old-path :new-path new-path}})
    (let [responses (nrepl/message nrepl-client 
                                  {:op "rename-file-or-dir" 
                                   :old-path old-path
                                   :new-path new-path})
          result (reduce (fn [acc response]
                          (cond
                            (:touched response) (assoc acc :touched (:touched response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        {:error (:error result)
         :output (:output result)
         :status :error}
        {:touched (:touched result)
         :output (:output result)
         :old-path old-path
         :new-path new-path
         :status :success}))
    (catch Exception e
      (log/log! {:level :error :msg "Error renaming file or directory" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(register-tool! :rename-file-or-dir
  "Rename a file or directory and update all references"
  {:old-path {:type "string" :description "Current path of the file or directory"}
   :new-path {:type "string" :description "New path for the file or directory"}}
  (fn [tool-call context]
    (let [{:strs [old-path new-path]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (rename-file-or-directory nrepl-client old-path new-path))))

(defn resolve-missing-symbol
  "Resolve missing or unresolved symbols using refactor-nrepl. Returns result map."
  [nrepl-client symbol namespace]
  (try
    (log/log! {:level :info :msg "Resolving missing symbol" :data {:symbol symbol :namespace namespace}})
    (let [responses (nrepl/message nrepl-client 
                                  {:op "resolve-missing" 
                                   :symbol symbol
                                   :ns namespace})
          result (reduce (fn [acc response]
                          (cond
                            (:candidates response) (assoc acc :candidates (:candidates response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        {:error (:error result)
         :output (:output result)
         :status :error}
        {:candidates (:candidates result)
         :output (:output result)
         :symbol symbol
         :namespace namespace
         :status :success}))
    (catch Exception e
      (log/log! {:level :error :msg "Error resolving missing symbol" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(register-tool! :resolve-missing
  "Resolve missing or unresolved symbols"
  {:symbol {:type "string" :description "The unresolved symbol"}
   :namespace {:type "string" :description "The namespace containing the symbol"}}
  (fn [tool-call context]
    (let [{:strs [symbol namespace]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (resolve-missing-symbol nrepl-client symbol namespace))))

(defn find-used-locals
  "Find locally used variables at a specific location using refactor-nrepl. Returns result map."
  [nrepl-client file-path line column]
  (try
    (log/log! {:level :info :msg "Finding used locals" :data {:file-path file-path :line line :column column}})
    (let [responses (nrepl/message nrepl-client 
                                  {:op "find-used-locals" 
                                   :file file-path
                                   :line line
                                   :column column})
          result (reduce (fn [acc response]
                          (cond
                            (:used-locals response) (assoc acc :used-locals (:used-locals response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        {:error (:error result)
         :output (:output result)
         :status :error}
        {:used-locals (:used-locals result)
         :output (:output result)
         :file-path file-path
         :line line
         :column column
         :status :success}))
    (catch Exception e
      (log/log! {:level :error :msg "Error finding used locals" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(register-tool! :find-used-locals
  "Find locally used variables at a specific location"
  {:file-path {:type "string" :description "Path to the Clojure file"}
   :line {:type "number" :description "Line number"}
   :column {:type "number" :description "Column number"}}
  (fn [tool-call context]
    (let [{:strs [file-path line column]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (find-used-locals nrepl-client file-path line column))))

;; =============================================================================
;; ADVANCED REFACTORING TOOLS
;; =============================================================================

(defn extract-function-from-code
  "Extract selected code into a new function"
  [session-id function-name parameters]
  (if-let [session (edit/get-session session-id)]
    (try
      (let [current-zloc (:zipper session)
            current-expr (z/sexpr current-zloc)
            
            ;; Create new function definition
            new-fn-def `(~'defn ~(symbol function-name) ~parameters ~current-expr)
            
            ;; Replace current expression with function call
            fn-call `(~(symbol function-name) ~@parameters)
            
            ;; Replace the current node with the function call
            updated-zloc (z/replace current-zloc fn-call)
            
            ;; Navigate to the top level to insert the function
            top-level-zloc (loop [zloc updated-zloc]
                            (if-let [parent (z/up zloc)]
                              (recur parent)
                              zloc))
            
            ;; Insert the function definition at the top level
            with-fn-zloc (z/insert-child top-level-zloc new-fn-def)]
        
        (edit/update-session! session-id #(assoc % :zipper with-fn-zloc))
        {:status :success
         :function-name function-name
         :parameters parameters
         :extracted-code current-expr
         :function-definition new-fn-def
         :info (edit/get-zipper-info session-id)})
      (catch Exception e
        (log/log! {:level :error :msg "Error extracting function" :data {:error (.getMessage e)}})
        {:status :error :error (.getMessage e)}))
    {:status :error :error "Session not found"}))

(defn extract-variable-from-code
  "Extract current expression into a let binding"
  [session-id variable-name]
  (if-let [session (edit/get-session session-id)]
    (try
      (let [current-zloc (:zipper session)
            current-expr (z/sexpr current-zloc)
            
            ;; Find the enclosing function or let block
            enclosing-fn (loop [zloc current-zloc]
                          (if-let [parent (z/up zloc)]
                            (if (and (z/list? parent)
                                    (#{`defn `defn- `fn `let} (first (z/sexpr parent))))
                              parent
                              (recur parent))
                            nil))
            
            ;; Replace current expression with variable reference
            _updated-zloc (z/replace current-zloc (symbol variable-name))]
        
        (if enclosing-fn
          (let [;; Add let binding or extend existing let
                with-let-zloc (if (= (first (z/sexpr enclosing-fn)) 'let)
                               ;; Extend existing let
                               (let [bindings-zloc (z/down (z/right enclosing-fn))
                                     new-bindings (conj (z/sexpr bindings-zloc) 
                                                       (symbol variable-name) current-expr)]
                                 (z/replace bindings-zloc new-bindings))
                               ;; Wrap in new let
                               (z/replace enclosing-fn 
                                        `(~'let [~(symbol variable-name) ~current-expr]
                                          ~@(drop 3 (z/sexpr enclosing-fn)))))]
            
            (edit/update-session! session-id #(assoc % :zipper with-let-zloc))
            {:status :success
             :variable-name variable-name
             :extracted-expression current-expr
             :info (edit/get-zipper-info session-id)})
          {:status :error :error "Could not find enclosing function or let block"}))
      (catch Exception e
        (log/log! {:level :error :msg "Error extracting variable" :data {:error (.getMessage e)}})
        {:status :error :error (.getMessage e)}))
    {:status :error :error "Session not found"}))

(defn add-function-parameter
  "Add a parameter to a function definition"
  [session-id parameter-name _default-value]
  (if-let [session (edit/get-session session-id)]
    (try
      (let [current-zloc (:zipper session)]
        ;; Find function definition
        (if (and (z/list? current-zloc)
                (#{`defn `defn-} (first (z/sexpr current-zloc))))
          (let [;; Navigate to parameter vector
                params-zloc (z/down (z/right (z/right current-zloc)))
                current-params (z/sexpr params-zloc)
                new-params (conj current-params (symbol parameter-name))
                updated-zloc (z/replace params-zloc new-params)]
            
            (edit/update-session! session-id #(assoc % :zipper updated-zloc))
            {:status :success
             :parameter-name parameter-name
             :old-parameters current-params
             :new-parameters new-params
             :info (edit/get-zipper-info session-id)})
          {:status :error :error "Current node is not a function definition"}))
      (catch Exception e
        (log/log! {:level :error :msg "Error adding parameter" :data {:error (.getMessage e)}})
        {:status :error :error (.getMessage e)}))
    {:status :error :error "Session not found"}))

(defn organize-namespace-imports
  "Organize and clean up namespace imports"
  [session-id]
  (if-let [session (edit/get-session session-id)]
    (try
      (let [root-zloc (:zipper session)
            ;; Find namespace declaration
            ns-zloc (z/find-value root-zloc z/next 'ns)]
        
        (if ns-zloc
          (let [ns-form (z/sexpr ns-zloc)
                ;; Extract and organize require forms
                require-forms (filter #(and (seq? %) (= :require (first %))) ns-form)
                sorted-requires (sort-by second require-forms)
                
                ;; Reconstruct namespace with organized imports
                new-ns-form (vec (concat (take 2 ns-form) 
                                               [`(:require ~@sorted-requires)]))
                
                updated-zloc (z/replace ns-zloc new-ns-form)]
            
            (edit/update-session! session-id #(assoc % :zipper updated-zloc))
            {:status :success
             :organized-imports sorted-requires
             :info (edit/get-zipper-info session-id)})
          {:status :error :error "No namespace declaration found"}))
      (catch Exception e
        (log/log! {:level :error :msg "Error organizing imports" :data {:error (.getMessage e)}})
        {:status :error :error (.getMessage e)}))
    {:status :error :error "Session not found"}))

(defn inline-function
  "Inline a function call by replacing it with the function body"
  [session-id function-name]
  (if-let [session (edit/get-session session-id)]
    (try
      (let [current-zloc (:zipper session)
            root-zloc (z/root current-zloc)
            
            ;; Find the function definition
            fn-def-zloc (z/find-value root-zloc z/next 'defn)
            fn-def (when fn-def-zloc (z/sexpr fn-def-zloc))
            
            ;; Extract function parts
            [_ _fn-name params & body] fn-def
            
            ;; If current location is a function call, replace with body
            current-expr (z/sexpr current-zloc)]
        
        (if (and (seq? current-expr) 
                 (= (first current-expr) (symbol function-name)))
          (let [;; Get function arguments from the call
                call-args (rest current-expr)
                
                ;; Create parameter bindings
                param-bindings (vec (interleave params call-args))
                
                ;; Create let form with function body
                inlined-expr (if (= (count body) 1)
                              `(let ~param-bindings ~(first body))
                              `(let ~param-bindings (do ~@body)))
                
                ;; Replace the function call with inlined expression
                updated-zloc (z/replace current-zloc inlined-expr)]
            
            (edit/update-session! session-id #(assoc % :zipper updated-zloc))
            {:status :success
             :inlined-function function-name
             :original-call current-expr
             :inlined-expression inlined-expr
             :info (edit/get-zipper-info session-id)})
          {:status :error :error "Current position is not a function call"}))
      (catch Exception e
        {:status :error :error (.getMessage e)}))
    {:status :error :error "Session not found"}))

(defn rename-local-variable
  "Rename a local variable within its scope"
  [session-id old-name new-name]
  (if-let [session (edit/get-session session-id)]
    (try
      (let [current-zloc (:zipper session)
            
            ;; Find the enclosing let or function
            enclosing-form (loop [zloc current-zloc]
                            (if-let [parent (z/up zloc)]
                              (let [parent-expr (z/sexpr parent)]
                                (if (and (seq? parent-expr)
                                        (#{`let `defn `defn- `fn `loop} (first parent-expr)))
                                  parent
                                  (recur parent)))
                              nil))]
        
        (if enclosing-form
          (let [;; Replace all occurrences of old-name with new-name within scope
                replaced-form (z/edit enclosing-form
                                     (fn [node]
                                       (if (= node (symbol old-name))
                                         (symbol new-name)
                                         node)))]
            
            (edit/update-session! session-id #(assoc % :zipper replaced-form))
            {:status :success
             :old-name old-name
             :new-name new-name
             :scope (first (z/sexpr enclosing-form))
             :info (edit/get-zipper-info session-id)})
          {:status :error :error "No enclosing scope found for variable"}))
      (catch Exception e
        {:status :error :error (.getMessage e)}))
    {:status :error :error "Session not found"}))

;; =============================================================================
;; MCP TOOL REGISTRATIONS FOR ADVANCED REFACTORING
;; =============================================================================

(register-tool! :extract-function
  "Extract selected code into a new function"
  {:session-id {:type "string" :description "Session identifier"}
   :function-name {:type "string" :description "Name for the new function"}
   :parameters {:type "string" :description "Function parameters as EDN vector"}}
  (fn [tool-call _context]
    (let [{:strs [session-id function-name parameters]} (:args tool-call)
          params-vec (edn/read-string parameters)]
      (extract-function-from-code session-id function-name params-vec))))

(register-tool! :extract-variable
  "Extract current expression into a let binding"
  {:session-id {:type "string" :description "Session identifier"}
   :variable-name {:type "string" :description "Name for the new variable"}}
  (fn [tool-call _context]
    (let [{:strs [session-id variable-name]} (:args tool-call)]
      (extract-variable-from-code session-id variable-name))))

(register-tool! :add-function-parameter
  "Add a parameter to a function definition"
  {:session-id {:type "string" :description "Session identifier"}
   :parameter-name {:type "string" :description "Name of the parameter to add"}
   :default-value {:type "string" :optional true :description "Default value for the parameter"}}
  (fn [tool-call _context]
    (let [{:strs [session-id parameter-name default-value]} (:args tool-call)]
      (add-function-parameter session-id parameter-name default-value))))

(register-tool! :organize-imports
  "Organize and clean up namespace imports"
  {:session-id {:type "string" :description "Session identifier"}}
  (fn [tool-call _context]
    (let [{:strs [session-id]} (:args tool-call)]
      (organize-namespace-imports session-id))))

(register-tool! :inline-function
  "Inline a function call by replacing it with the function body"
  {:session-id {:type "string" :description "Session identifier"}
   :function-name {:type "string" :description "Name of the function to inline"}}
  (fn [tool-call _context]
    (let [{:strs [session-id function-name]} (:args tool-call)]
      (inline-function session-id function-name))))

(register-tool! :rename-local-variable
  "Rename a local variable within its scope"
  {:session-id {:type "string" :description "Session identifier"}
   :old-name {:type "string" :description "Current variable name"}
   :new-name {:type "string" :description "New variable name"}}
  (fn [tool-call _context]
    (let [{:strs [session-id old-name new-name]} (:args tool-call)]
      (rename-local-variable session-id old-name new-name))))

;; Tools are automatically registered by register-tool! function
