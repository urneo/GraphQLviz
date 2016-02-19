(ns graphqlviz.core
  (:require [clojure.data.json :as json]
            [tangle.core :refer :all]
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:gen-class))

;;; GraphQL

(defn internal-type? [t]
  (let [name (:name t)]
    (or (and name (.startsWith name "__"))
        #_(= "QueryType" name))))

(defn scalar? [t]
  (= "SCALAR" (:kind t)))

(defn enum? [t]
  (= "ENUM" (:kind t)))

(defn enum-values [t]
  (:enumValues t))

(defn terminal-type [t]
  (if (:ofType t)
    (recur (:ofType t))
    t))

(defn introspection-query []
  {:query (slurp (io/resource "introspection.query"))})



;;; Configuration

(def config (atom {:expand-args false
                   :expand-arg-types false}))


;;; Visualization

(defn relation-field? [f]
  (not (scalar? (terminal-type (:type f)))))

(defn uninteresting-type? [t]
  (internal-type? t))

(defn type->id [t]
  (:name t))

(defn describe-field-type [t]
  (case (:kind t)
    "NON_NULL" (str (describe-field-type (:ofType t)) "!")
    "LIST" (str "[" (describe-field-type (:ofType t)) "]")
    (:name t)))

(defn arg->str [a]
  (str (:name a)
       (if (:expand-arg-types @config)
         (str ": " (describe-field-type (:type a)))
         "")))

(defn format-args [args]
  (if (:expand-args @config)
    (apply str (interpose ", " (map arg->str args)))
    "..."))

(defn get-field-label [field]
  (let [{:keys [type name description args]} field]
    (str name
         (if-not (empty? args)
           (str "(" (format-args args) ")")
           "")
         ": "
         (describe-field-type type))))

(defn type->edges [t]
  (remove nil? (map (fn [{:keys [type name description] :as field}]
                      [(:name t) (:name (terminal-type type)) {:label (get-field-label field)
                                                               :labeltooltip (str description)}])
                    (filter relation-field? (:fields t)))))

(defn field->str [t f]
  (let [target (str "<" (:name t) "_" (:name f) ">")]
    (str (:name f) ": " (describe-field-type (:type f)))))

(defn stereotype [t]
  (cond (enum? t) "&laquo;enum&raquo;"
        :else ""))

(defn type-description [t]
  [[:TR [:TD {:BGCOLOR "#E535AB" :COLSPAN 2} [:FONT {:COLOR "white"} [:B (:name t)] [:BR] (stereotype t)]]]])

(defn scalar-field-description [t f]
  [:TR [:TD {:ALIGN "left" :BORDER 0} (:name f) ": " (describe-field-type (:type f))]])

(defn enum-value-description [v]
  [:TR [:TD {:ALIGN "left" :BORDER 0 :COLSPAN 2} (:name v)]])

(defn type->descriptor [t]
  (let [scalar-fields (remove relation-field? (:fields t))]
    {:label (into [:TABLE {:CELLSPACING 0 :BORDER 1}]
                  (concat (type-description t)
                          (map (partial scalar-field-description t) scalar-fields)
                          (map enum-value-description (enum-values t))))}))

(defn render [nodes edges filename]
  (println "Generating graph from" (count nodes) "nodes and" (count edges) "edges")
  (let [dot (graph->dot nodes edges {:node {:shape :none :margin 0}
                                     :graph {:label filename :rankdir :LR}
                                     :directed? true
                                     :node->id type->id
                                     :node->descriptor type->descriptor})]
    (println "Writing DOT" (str filename ".dot"))
    (spit (str filename ".dot") dot)
    (println "Writing SVG" (str filename ".svg"))
    (spit (str filename ".svg") (dot->svg dot))
    ))

(defn slurp-json [filename]
  (-> filename
      (slurp)
      (json/read-str :key-fn keyword)))

(defn load-schema [filename]
  (let [schema (slurp-json filename)
        types (->> (:types (:__schema (:data schema)))
                   (remove uninteresting-type?))
        nodes (remove scalar? types)
        edges (mapcat type->edges types)]
    [nodes edges]))

(defn fetch-schema [input output]
  (if (.startsWith input "http")
    (do (println "Fetching schema from" input)
        (let [response (http/post input {:content-type "application/json"
                                         :body (json/write-str (introspection-query))})]
          (spit (str output ".json") (-> response
                                         (:body)
                                         (json/read-str :key-fn keyword)
                                         (json/write-str)))))
    (do (println "Loading schema from" input)
        (spit (str output ".json") (slurp input)))))

(defn process-schema [output]
  (let [[nodes edges] (load-schema (str output ".json"))]
    (render nodes edges output)))

(defn -main [& args]
  (if (= (count args) 2)
    (let [input (first args)
          output (second args)]
      (fetch-schema input output)
      (process-schema output)
      (println "Done!")
      (shutdown-agents))
    (println "Usage: graphqlviz <url-or-file> <output-name>")))

