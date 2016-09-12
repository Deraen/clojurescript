(ns cljs.module-processing-tests
  (:require [cljs.build.api :as b]
            [clojure.java.io :as io]
            [cljs.closure :as closure]
            [clojure.test :refer :all]
            [cljs.env :as env]))

;; Hard coded JSX transform for the test case
(defmethod closure/js-transforms :jsx [ijs _]
  (assoc ijs :source (clojure.string/replace
                       (:source ijs)
                       (re-pattern (str "\\(\n"
                                        "\\s*<svg width=\"200px\" height=\"200px\" className=\"center\">\n"
                                        "\\s*<circle cx=\"100px\" cy=\"100px\" r=\"100px\" fill=\\{this.props.color\\}>\n"
                                        "\\s*</circle>\n"
                                        "\\s*</svg>\n"
                                        "\\s*\\)"))
                       (str " React.createElement(\"svg\", {width:\"200px\", height:\"200px\", className:\"center\"}, "
                            "React.createElement(\"circle\", {cx:\"100px\", cy:\"100px\", r:\"100px\", fill:this.props.color})"
                            ")"))))

(def cenv (env/default-compiler-env))

(deftest commonjs-module-processing
  (is (= {:foreign-libs []
          :libs ["out/react.js"
                 "out/Circle.js"]
          :closure-warnings {:non-standard-jsdoc :off}}
         (env/with-compiler-env cenv
           (closure/process-js-modules
             {:foreign-libs [{:file        "src/test/cljs/react.js"
                              :provides    ["React"]
                              :module-type :commonjs}
                             {:file        "src/test/cljs/Circle.js"
                              :provides    ["Circle"]
                              :module-type :commonjs
                              :preprocess  :jsx}]
              :closure-warnings {:non-standard-jsdoc :off}})))
      "processed modules are added to :libs")

  (testing "processed modules have goog.provide calls"
    (is (.contains "goog.provide" (with-open [r (io/reader "out/react.js")]
                                    (first (line-seq r)))))
    (is (.contains "goog.provide" (with-open [r (io/reader "out/Circle.js")]
                                    (first (line-seq r))))))

  (is (= {"React" "modules$out$react"
          "Circle" "modules$out$Circle"}
         (:js-module-index @cenv))
      ""))