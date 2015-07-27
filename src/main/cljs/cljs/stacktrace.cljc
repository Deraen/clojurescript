;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns cljs.stacktrace
  (:require #?@(:clj  [[cljs.util :as util]
                       [clojure.java.io :as io]]
                :cljs [[goog.string :as gstring]])
            [clojure.string :as string])
  #?(:clj (:import [java.util.regex Pattern]
                   [java.io File])))

(defmulti parse-stacktrace (fn [repl-env st err opts] (:ua-product err)))

(defn parse-int [s]
  #?(:clj  (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn starts-with?
  #?(:cljs {:tag boolean})
  [^String s0 s1]
  #?(:clj  (.startsWith s0 s1)
     :cljs (gstring/startsWith s0 s1)))

(defn ends-with?
  #?(:cljs {:tag boolean})
  [^String s0 s1]
  #?(:clj  (.endsWith s0 s1)
     :cljs (gstring/endsWith s0 s1)))

(defn string->regex [s]
  #?(:clj  (Pattern/compile s)
     :cljs (js/RegExp. s)))

(defn output-directory [opts]
  #?(:clj  (util/output-directory opts)
     :cljs (or (:output-dir opts) "out")))

(defmethod parse-stacktrace :default
  [repl-env st err opts] st)

(defn parse-file-line-column [flc]
  (let [xs (string/split flc #":")
        [pre [line column]]
        (reduce
          (fn [[pre post] [x i]]
            (if (<= i 2)
              [pre (conj post x)]
              [(conj pre x) post]))
          [[] []] (map vector xs (range (count xs) 0 -1)))
        file (string/join ":" pre)]
    [(cond-> file
       (starts-with? file "(") (string/replace "(" ""))
     (parse-int
       (cond-> line
         (ends-with? line ")") (string/replace ")" "")))
     (parse-int
       (cond-> column
         (ends-with? column ")") (string/replace ")" "")))]))

(defn parse-file
  "Given a browser file url convert it into a relative path that can be used
   to locate the original source."
  [{:keys [host host-port port] :as repl-env} file {:keys [asset-path] :as opts}]
  (let [base-url-pattern (string->regex (str "http://" host ":" (or host-port port) "/"))]
    (if (re-find base-url-pattern file)
      (-> file
        (string/replace base-url-pattern "")
        (string/replace
          (string->regex
            ;; if :asset-path specified drop leading slash
            (str "^" (or (and asset-path (string/replace asset-path #"^/" ""))
                         (output-directory opts)) "/"))
          ""))
      (if-let [asset-root (:asset-root opts)]
        (string/replace file asset-root "")
        (throw
          (ex-info (str "Could not relativize URL " file)
            {:type :parse-stacktrace
             :reason :relativize-url}))))))

;; -----------------------------------------------------------------------------
;; Chrome Stacktrace

(defn chrome-st-el->frame
  [repl-env st-el opts]
  (let [xs (-> st-el
             (string/replace #"\s+at\s+" "")
             (string/split #"\s+"))
        [function flc] (if (== 1 (count xs))
                         [nil (first xs)]
                         [(first xs) (last xs)])
        [file line column] (parse-file-line-column flc)]
    (if (and file function line column)
      {:file (parse-file repl-env file opts)
       :function (string/replace function #"Object\." "")
       :line line
       :column column}
      (when-not (string/blank? function)
        {:file nil
         :function (string/replace function #"Object\." "")
         :line nil
         :column nil}))))

(comment
  (chrome-st-el->frame {:host "localhost" :port 9000}
    "\tat cljs$core$ffirst (http://localhost:9000/out/cljs/core.js:5356:34)" {})
  )

(defmethod parse-stacktrace :chrome
  [repl-env st err opts]
  (->> st
    string/split-lines
    (drop-while #(starts-with? % "Error"))
    (take-while #(not (starts-with? % "    at eval")))
    (map #(chrome-st-el->frame repl-env % opts))
    (remove nil?)
    vec))

(comment
  (parse-stacktrace {:host "localhost" :port 9000}
    "Error: 1 is not ISeqable
    at Object.cljs$core$seq [as seq] (http://localhost:9000/out/cljs/core.js:4258:8)
    at Object.cljs$core$first [as first] (http://localhost:9000/out/cljs/core.js:4288:19)
    at cljs$core$ffirst (http://localhost:9000/out/cljs/core.js:5356:34)
    at http://localhost:9000/out/cljs/core.js:16971:89
    at cljs.core.map.cljs$core$map__2 (http://localhost:9000/out/cljs/core.js:16972:3)
    at http://localhost:9000/out/cljs/core.js:10981:129
    at cljs.core.LazySeq.sval (http://localhost:9000/out/cljs/core.js:10982:3)
    at cljs.core.LazySeq.cljs$core$ISeqable$_seq$arity$1 (http://localhost:9000/out/cljs/core.js:11073:10)
    at Object.cljs$core$seq [as seq] (http://localhost:9000/out/cljs/core.js:4239:13)
    at Object.cljs$core$pr_sequential_writer [as pr_sequential_writer] (http://localhost:9000/out/cljs/core.js:28706:14)"
    {:ua-product :chrome}
    nil)

  (parse-stacktrace {:host "localhost" :port 9000}
    "Error: 1 is not ISeqable
    at Object.cljs$core$seq [as seq] (http://localhost:9000/js/cljs/core.js:4258:8)
    at Object.cljs$core$first [as first] (http://localhost:9000/js/cljs/core.js:4288:19)
    at cljs$core$ffirst (http://localhost:9000/js/cljs/core.js:5356:34)
    at http://localhost:9000/js/cljs/core.js:16971:89
    at cljs.core.map.cljs$core$map__2 (http://localhost:9000/js/cljs/core.js:16972:3)
    at http://localhost:9000/js/cljs/core.js:10981:129
    at cljs.core.LazySeq.sval (http://localhost:9000/js/cljs/core.js:10982:3)
    at cljs.core.LazySeq.cljs$core$ISeqable$_seq$arity$1 (http://localhost:9000/js/cljs/core.js:11073:10)
    at Object.cljs$core$seq [as seq] (http://localhost:9000/js/cljs/core.js:4239:13)
    at Object.cljs$core$pr_sequential_writer [as pr_sequential_writer] (http://localhost:9000/js/cljs/core.js:28706:14)"
    {:ua-product :chrome}
    {:asset-path "/js"})

  (parse-stacktrace {:host "localhost" :port 9000}
    "Error: 1 is not ISeqable
    at Object.cljs$core$seq [as seq] (http://localhost:9000/out/cljs/core.js:4259:8)
    at Object.cljs$core$first [as first] (http://localhost:9000/out/cljs/core.js:4289:19)
    at cljs$core$ffirst (http://localhost:9000/out/cljs/core.js:5357:18)
    at eval (eval at <anonymous> (http://localhost:9000/out/clojure/browser/repl.js:23:272), <anonymous>:1:106)
    at eval (eval at <anonymous> (http://localhost:9000/out/clojure/browser/repl.js:23:272), <anonymous>:9:3)
    at eval (eval at <anonymous> (http://localhost:9000/out/clojure/browser/repl.js:23:272), <anonymous>:14:4)
    at http://localhost:9000/out/clojure/browser/repl.js:23:267
    at clojure$browser$repl$evaluate_javascript (http://localhost:9000/out/clojure/browser/repl.js:26:4)
    at Object.callback (http://localhost:9000/out/clojure/browser/repl.js:121:169)
    at goog.messaging.AbstractChannel.deliver (http://localhost:9000/out/goog/messaging/abstractchannel.js:142:13)"
    {:ua-product :chrome}
    nil)
  )

;; -----------------------------------------------------------------------------
;; Safari Stacktrace

(defn safari-st-el->frame
  [repl-env st-el opts]
  (let [[function flc] (if (re-find #"@" st-el)
                         (string/split st-el #"@")
                         [nil st-el])
        [file line column] (parse-file-line-column flc)]
    (if (and file function line column)
      {:file (parse-file repl-env file opts)
       :function function
       :line line
       :column column}
      (when-not (string/blank? function)
        {:file nil
         :function (string/trim function)
         :line nil
         :column nil}))))

(comment
  (safari-st-el->frame {:host "localhost" :port 9000}
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4259:17" {})

  (safari-st-el->frame {:host "localhost" :port 9000}
    "cljs$core$seq@http://localhost:9000/js/cljs/core.js:4259:17" {:asset-path "js"})
  )

(defmethod parse-stacktrace :safari
  [repl-env st err opts]
  (->> st
    string/split-lines
    (drop-while #(starts-with? % "Error"))
    (take-while #(not (starts-with? % "eval code")))
    (remove string/blank?)
    (map #(safari-st-el->frame repl-env % opts))
    (remove nil?)
    vec))

(comment
  (parse-stacktrace {:host "localhost" :port 9000}
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4259:17
cljs$core$first@http://localhost:9000/out/cljs/core.js:4289:22
cljs$core$ffirst@http://localhost:9000/out/cljs/core.js:5357:39
http://localhost:9000/out/cljs/core.js:16972:92
http://localhost:9000/out/cljs/core.js:16973:3
http://localhost:9000/out/cljs/core.js:10982:133
sval@http://localhost:9000/out/cljs/core.js:10983:3
cljs$core$ISeqable$_seq$arity$1@http://localhost:9000/out/cljs/core.js:11074:14
cljs$core$seq@http://localhost:9000/out/cljs/core.js:4240:44
cljs$core$pr_sequential_writer@http://localhost:9000/out/cljs/core.js:28707:17
cljs$core$IPrintWithWriter$_pr_writer$arity$3@http://localhost:9000/out/cljs/core.js:29386:38
cljs$core$pr_writer_impl@http://localhost:9000/out/cljs/core.js:28912:57
cljs$core$pr_writer@http://localhost:9000/out/cljs/core.js:29011:32
cljs$core$pr_seq_writer@http://localhost:9000/out/cljs/core.js:29015:20
cljs$core$pr_sb_with_opts@http://localhost:9000/out/cljs/core.js:29078:24
cljs$core$pr_str_with_opts@http://localhost:9000/out/cljs/core.js:29092:48
cljs$core$pr_str__delegate@http://localhost:9000/out/cljs/core.js:29130:34
cljs$core$pr_str@http://localhost:9000/out/cljs/core.js:29139:39
eval code
eval@[native code]
http://localhost:9000/out/clojure/browser/repl.js:23:271
clojure$browser$repl$evaluate_javascript@http://localhost:9000/out/clojure/browser/repl.js:26:4
http://localhost:9000/out/clojure/browser/repl.js:121:173
deliver@http://localhost:9000/out/goog/messaging/abstractchannel.js:142:21
xpcDeliver@http://localhost:9000/out/goog/net/xpc/crosspagechannel.js:733:19
messageReceived_@http://localhost:9000/out/goog/net/xpc/nativemessagingtransport.js:321:23
fireListener@http://localhost:9000/out/goog/events/events.js:741:25
handleBrowserEvent_@http://localhost:9000/out/goog/events/events.js:862:34
http://localhost:9000/out/goog/events/events.js:276:42"
    {:ua-product :safari}
    nil)
  )

;; -----------------------------------------------------------------------------
;; Firefox Stacktrace

(defn firefox-clean-function [f]
  (as-> f f
    (cond
      (string/blank? f) nil
      (not= (.indexOf f "</") -1)
      (let [idx (.indexOf f "</")]
        (.substring f (+ idx 2)))
      :else f)
    (-> f
      (string/replace #"<" "")
      (string/replace #?(:clj #"\/" :cljs (js/RegExp. "\\/")) ""))))

(defn firefox-st-el->frame
  [repl-env st-el opts]
  (let [[function flc] (if (re-find #"@" st-el)
                         (string/split st-el #"@")
                         [nil st-el])
        [file line column] (parse-file-line-column flc)]
    (if (and file function line column)
      {:file (parse-file repl-env file opts)
       :function (firefox-clean-function function)
       :line line
       :column column}
      (when-not (string/blank? function)
        {:file nil
         :function (firefox-clean-function function)
         :line nil
         :column nil}))))

(comment
  (firefox-st-el->frame {:host "localhost" :port 9000}
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4258:8" {})

  (firefox-st-el->frame {:host "localhost" :port 9000}
    "cljs.core.map</cljs$core$map__2/</<@http://localhost:9000/out/cljs/core.js:16971:87" {})

  (firefox-st-el->frame {:host "localhost" :port 9000}
    "cljs.core.map</cljs$core$map__2/</<@http://localhost:9000/out/cljs/core.js:16971:87" {})

  (firefox-st-el->frame {:host "localhost" :port 9000}
    "cljs.core.pr_str</cljs$core$pr_str@http://localhost:9000/out/cljs/core.js:29138:8" {})

  (firefox-st-el->frame {:host "localhost" :port 9000}
    "cljs.core.pr_str</cljs$core$pr_str__delegate@http://localhost:9000/out/cljs/core.js:29129:8" {})
  )

(defmethod parse-stacktrace :firefox
  [repl-env st err opts]
  (->> st
    string/split-lines
    (drop-while #(starts-with? % "Error"))
    (take-while #(= (.indexOf % "> eval") -1))
    (remove string/blank?)
    (map #(firefox-st-el->frame repl-env % opts))
    (remove nil?)
    vec))

(comment
  (parse-stacktrace {:host "localhost" :port 9000}
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4258:8
cljs$core$first@http://localhost:9000/out/cljs/core.js:4288:9
cljs$core$ffirst@http://localhost:9000/out/cljs/core.js:5356:24
cljs.core.map</cljs$core$map__2/</<@http://localhost:9000/out/cljs/core.js:16971:87
cljs.core.map</cljs$core$map__2/<@http://localhost:9000/out/cljs/core.js:16970:1
cljs.core.LazySeq.prototype.sval/self__.s<@http://localhost:9000/out/cljs/core.js:10981:119
cljs.core.LazySeq.prototype.sval@http://localhost:9000/out/cljs/core.js:10981:13
cljs.core.LazySeq.prototype.cljs$core$ISeqable$_seq$arity$1@http://localhost:9000/out/cljs/core.js:11073:1
cljs$core$seq@http://localhost:9000/out/cljs/core.js:4239:8
cljs$core$pr_sequential_writer@http://localhost:9000/out/cljs/core.js:28706:4
cljs.core.LazySeq.prototype.cljs$core$IPrintWithWriter$_pr_writer$arity$3@http://localhost:9000/out/cljs/core.js:29385:8
cljs$core$pr_writer_impl@http://localhost:9000/out/cljs/core.js:28911:8
cljs$core$pr_writer@http://localhost:9000/out/cljs/core.js:29010:8
cljs$core$pr_seq_writer@http://localhost:9000/out/cljs/core.js:29014:1
cljs$core$pr_sb_with_opts@http://localhost:9000/out/cljs/core.js:29077:1
cljs$core$pr_str_with_opts@http://localhost:9000/out/cljs/core.js:29091:23
cljs.core.pr_str</cljs$core$pr_str__delegate@http://localhost:9000/out/cljs/core.js:29129:8
cljs.core.pr_str</cljs$core$pr_str@http://localhost:9000/out/cljs/core.js:29138:8
@http://localhost:9000/out/clojure/browser/repl.js line 23 > eval:1:25
@http://localhost:9000/out/clojure/browser/repl.js line 23 > eval:1:2
clojure$browser$repl$evaluate_javascript/result<@http://localhost:9000/out/clojure/browser/repl.js:23:267
clojure$browser$repl$evaluate_javascript@http://localhost:9000/out/clojure/browser/repl.js:23:15
clojure$browser$repl$connect/</<@http://localhost:9000/out/clojure/browser/repl.js:121:128
goog.messaging.AbstractChannel.prototype.deliver@http://localhost:9000/out/goog/messaging/abstractchannel.js:142:5
goog.net.xpc.CrossPageChannel.prototype.xpcDeliver@http://localhost:9000/out/goog/net/xpc/crosspagechannel.js:733:7
goog.net.xpc.NativeMessagingTransport.messageReceived_@http://localhost:9000/out/goog/net/xpc/nativemessagingtransport.js:321:1
goog.events.fireListener@http://localhost:9000/out/goog/events/events.js:741:10
goog.events.handleBrowserEvent_@http://localhost:9000/out/goog/events/events.js:862:1
goog.events.getProxy/f<@http://localhost:9000/out/goog/events/events.js:276:16"
    {:ua-product :firefox}
    nil)
  )

;; -----------------------------------------------------------------------------
;; Rhino Stacktrace

(defmethod parse-stacktrace :rhino
  [repl-env st err {:keys [output-dir] :as opts}]
  (letfn [(process-frame [frame-str]
            (when-not (or (string/blank? frame-str)
                          (== -1 (.indexOf frame-str "\tat")))
              (let [[file-side line-fn-side] (string/split frame-str #":")
                   file                      (string/replace file-side #"\s+at\s+" "")
                   [line function]           (string/split line-fn-side #"\s+")]
               {:file     (string/replace file
                            (str output-dir
                              #?(:clj File/separator :cljs "/"))
                            "")
                :function (when function
                            (-> function
                              (string/replace "(" "")
                              (string/replace ")" "")))
                :line     (when (and line (not (string/blank? line)))
                            (parse-int line))
                :column   0})))]
    (->> (string/split st #"\n")
      (map process-frame)
      (remove nil?)
      vec)))

(comment
  (parse-stacktrace {}
    "\tat .cljs_rhino_repl/goog/../cljs/core.js:4215 (seq)
     \tat .cljs_rhino_repl/goog/../cljs/core.js:4245 (first)
     \tat .cljs_rhino_repl/goog/../cljs/core.js:5295 (ffirst)
     \tat <cljs repl>:1
     \tat <cljs repl>:1"
    {:ua-product :rhino}
    {:output-dir ".cljs_rhino_repl"})

  (parse-stacktrace {}
    "org.mozilla.javascript.JavaScriptException: Error: 1 is not ISeqable (.cljs_rhino_repl/goog/../cljs/core.js#3998)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:3998 (cljs$core$seq)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:4017 (cljs$core$first)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:5160 (cljs$core$ffirst)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:16005
   \tat .cljs_rhino_repl/goog/../cljs/core.js:16004
   \tat .cljs_rhino_repl/goog/../cljs/core.js:10243
   \tat .cljs_rhino_repl/goog/../cljs/core.js:10334
   \tat .cljs_rhino_repl/goog/../cljs/core.js:3979 (cljs$core$seq)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28083 (cljs$core$pr_sequential_writer)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28811
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28267 (cljs$core$pr_writer_impl)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28349 (cljs$core$pr_writer)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28353 (cljs$core$pr_seq_writer)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28416 (cljs$core$pr_sb_with_opts)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28430 (cljs$core$pr_str_with_opts)
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28524
   \tat .cljs_rhino_repl/goog/../cljs/core.js:28520 (cljs$core$pr_str)
   at <cljs repl>:1
   "
    {:ua-product :rhino}
    {:output-dir ".cljs_rhino_repl"})
  )

;; -----------------------------------------------------------------------------
;; Nashorn Stacktrace

(defmethod parse-stacktrace :nashorn
  [repl-env st err {:keys [output-dir] :as opts}]
  (letfn [(process-frame [frame-str]
            (when-not (or (string/blank? frame-str)
                        (== -1 (.indexOf frame-str "\tat")))
              (let [frame-str               (string/replace frame-str #"\s+at\s+" "")
                    [function file-and-line] (string/split frame-str #"\s+")
                    [file-part line-part]    (string/split file-and-line #":")]
                {:file     (string/replace (.substring file-part 1)
                             (str output-dir File/separator) "")
                 :function function
                 :line     (when (and line-part (not (string/blank? line-part)))
                             (parse-int
                               (.substring line-part 0
                                 (dec (count line-part)))))
                 :column   0})))]
    (->> (string/split st #"\n")
      (map process-frame)
      (remove nil?)
      vec)))

(comment
  (parse-stacktrace {}
    "Error: 1 is not ISeqable
    \tat cljs$core$seq (.cljs_nashorn_repl/goog/../cljs/core.js:3998)
    \tat cljs$core$first (.cljs_nashorn_repl/goog/../cljs/core.js:4017)
    \tat cljs$core$ffirst (.cljs_nashorn_repl/goog/../cljs/core.js:5160)
    \tat <anonymous> (.cljs_nashorn_repl/goog/../cljs/core.js:16005)
    \tat <anonymous> (.cljs_nashorn_repl/goog/../cljs/core.js:16004)
    \tat sval (.cljs_nashorn_repl/goog/../cljs/core.js:10243)
    \tat cljs$core$ISeqable$_seq$arity$1-6 (.cljs_nashorn_repl/goog/../cljs/core.js:10334)
    \tat cljs$core$seq (.cljs_nashorn_repl/goog/../cljs/core.js:3979)
    \tat cljs$core$pr_sequential_writer (.cljs_nashorn_repl/goog/../cljs/core.js:28083)
    \tat cljs$core$IPrintWithWriter$_pr_writer$arity$3-5 (.cljs_nashorn_repl/goog/../cljs/core.js:28811)
    \tat cljs$core$pr_writer_impl (.cljs_nashorn_repl/goog/../cljs/core.js:28267)
    \tat cljs$core$pr_writer (.cljs_nashorn_repl/goog/../cljs/core.js:28349)
    \tat cljs$core$pr_seq_writer (.cljs_nashorn_repl/goog/../cljs/core.js:28353)
    \tat cljs$core$pr_sb_with_opts (.cljs_nashorn_repl/goog/../cljs/core.js:28416)
    \tat cljs$core$pr_str_with_opts (.cljs_nashorn_repl/goog/../cljs/core.js:28430)
    \tat cljs$core$IFn$_invoke$arity$variadic-71 (.cljs_nashorn_repl/goog/../cljs/core.js:28524)
    \tat cljs$core$pr_str (.cljs_nashorn_repl/goog/../cljs/core.js:28520)
    \tat <anonymous> (<eval>:1)
    \tat <program> (<eval>:1)\n"
    {:ua-product :nashorn}
    {:output-dir ".cljs_nashorn_repl"})
  )

;; -----------------------------------------------------------------------------
;; Stacktrace Mapping

(defn mapped-line-column-call
  "Given a cljs.source-map source map data structure map a generated line
   and column back to the original line, column, and function called."
  [source-map line column]
  ;; source maps are 0 indexed for columns
  ;; multiple segments may exist at column
  ;; the last segment seems most accurate
  (letfn [(get-best-column [columns column]
            (last (or (get columns
                        (last (filter #(<= % (dec column))
                                (sort (keys columns)))))
                      (second (first columns)))))
          (adjust [mapped]
            (vec (map #(%1 %2) [inc inc identity] mapped)))]
    (let [default [line column nil]]
      ;; source maps are 0 indexed for lines
      (if-let [columns (get source-map (dec line))]
        (adjust (map (get-best-column columns column) [:line :col :name]))
        default))))

(defn mapped-frame
  "Given opts and a canonicalized JavaScript stacktrace frame, return the
  ClojureScript frame."
  [{:keys [function file line column]} sm opts]
  (let [no-source-file?      (if-not file true (starts-with? file "<"))
        [line' column' call] (mapped-line-column-call sm line column)
        file'                (if (ends-with? file ".js")
                               (str (subs file 0 (- (count file) 3)) ".cljs")
                               file)]
    {:function function
     :call     call
     :file     (if no-source-file?
                 (str "NO_SOURCE_FILE" (when file (str " " file)))
                 file')
     :line     line'
     :column   column'}))

(defn mapped-stacktrace
  "Given a vector representing the canonicalized JavaScript stacktrace
   return the ClojureScript stacktrace. The canonical stacktrace must be
   in the form:

    [{:file <string>
      :function <string>
      :line <integer>
      :column <integer>}*]

   :file must be a URL path (without protocol) relative to :output-dir or a
   identifier delimited by angle brackets. The returned mapped stacktrace will
   also contain :url entries to the original sources if it can be determined
   from the classpath."
  ([stacktrace sm] (mapped-stacktrace stacktrace sm nil))
  ([stacktrace sm opts]
   (letfn [(call->function [x]
             (if (:call x)
               (hash-map :function (:call x))
               {}))
           (call-merge [function call]
             (merge-with
               (fn [munged-fn-name unmunged-call-name]
                 (if (= munged-fn-name
                        (string/replace (munge unmunged-call-name) "." "$"))
                   unmunged-call-name
                   munged-fn-name))
               function call))]
     (let [mapped-frames (map (memoize #(mapped-frame % sm opts)) stacktrace)]
       ;; take each non-nil :call and optionally merge it into :function one-level
       ;; up to avoid replacing with local symbols, we only replace munged name if
       ;; we can munge call symbol back to it
       (vec (map call-merge
              (map #(dissoc % :call) mapped-frames)
              (concat (rest (map call->function mapped-frames)) [{}])))))))

(defn mapped-stacktrace-str
  "Given a vector representing the canonicalized JavaScript stacktrace
   print the ClojureScript stacktrace. See mapped-stacktrace."
  ([stacktrace sm]
   (mapped-stacktrace-str stacktrace sm nil))
  ([stacktrace sm opts]
   (with-out-str
     (doseq [{:keys [function file line column]}
             (mapped-stacktrace stacktrace sm opts)]
       (println "\t"
         (str (when function (str function " "))
              "(" file (when line (str ":" line))
                       (when column (str ":" column)) ")"))))))

(comment
  (require '[cljs.closure :as cljsc]
           '[clojure.data.json :as json]
           '[cljs.source-map :as sm]
           '[clojure.pprint :as pp])

  (cljsc/build "samples/hello/src"
    {:optimizations :none
     :output-dir "samples/hello/out"
     :output-to "samples/hello/out/hello.js"
     :source-map true})

  (def sm
    (sm/decode
      (json/read-str
        (slurp "samples/hello/out/hello/core.js.map")
        :key-fn keyword)))

  (pp/pprint sm)

  ;; maps to :line 5 :column 24
  (mapped-stacktrace
    [{:file "hello/core.js"
      :function "first"
      :line 6
      :column 0}]
    sm {:output-dir "samples/hello/out"})

  (mapped-stacktrace-str
    [{:file "hello/core.js"
      :function "first"
      :line 6
      :column 0}]
    sm {:output-dir "samples/hello/out"})
  )