(ns dashboard-cljs.dev
  (:require [dashboard-cljs.core :as core]))

(defn ^:export on-jsload
  []
  (core/init-new-dash))
