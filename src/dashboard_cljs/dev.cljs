(ns dashboard-cljs.dev
  (:require [weasel.repl :as repl]
            [dashboard-cljs.core :as core]))

(when-not (repl/alive?)
  (repl/connect "ws://127.0.0.1:9001"))
