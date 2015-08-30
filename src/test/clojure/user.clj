(ns user)

(require '[cljs.build.api :refer [build inputs]]
         '[cljs.analyzer.api :refer [empty-state]])

(def o {:main "hello.core"
        :output-to "out/main.js"
        :output-dir "out"
        :optimizations :none
        :verbose true})

(def s (empty-state))

(defn broken []
  (build "src/main/src-a" o s))

(defn working []
  (build (inputs "src/main/src-b" "src/main/src-a") o s))
