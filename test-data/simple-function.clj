(ns test-data.simple-function
  (:require [clojure.string :as str]))

(defn sum-values
  "Adds two numbers together"
  [a b]
  (+ a b))

(defn process-name
  "Processes a name string"
  [name]
  (str/upper-case name))

(defn main-function
  "Main processing function"
  [input]
  (let [sum (sum-values 1 2)
        name (process-name (:name input))]
    {:sum sum
     :name name
     :processed true}))

(defn extract-names-and-jobs
  "Extract names and jobs from a sequence of maps into separate sets"
  [person-maps]
  {:names (set (map :name person-maps))
   :jobs (set (map :job person-maps))})