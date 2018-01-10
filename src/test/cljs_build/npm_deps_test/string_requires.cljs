(ns npm-deps-test.string-requires
  (:require [react :refer [createElement]]
            ["react-dom/server" :as ReactDOMServer]
            ["lodash-es/toArray" :refer [default] :rename {default toArray}]
            ["lodash-es/toFinite" :as toFinite]
            ["lodash-es/array" :as array]
            ["lodash-es/takeRight" :default take-right]
            [npm-deps-test.string-requires-in-classpath]))

(enable-console-print!)

;; CJS namespace access
(println ReactDOMServer)

;; CJS method call
(ReactDOMServer/renderToString nil)

;; es6 default with refer rename
(toArray nil)

;; es6 :as and default
(toFinite/default nil)

;; es6
(array/findIndex #js [1 2] 2)

;; es6 default with :default require
(take-right #js [1 2])
