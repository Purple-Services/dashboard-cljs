(ns dashboard-cljs.dev
  (:require [dashboard-cljs.core :as core]
            [dashboard-cljs.state :as state]
            [dashboard-cljs.utils :as utils]
            [reagent.core :as r]))

(defn ^:export on-jsload
  []
  (core/init-new-dash)
  (utils/select-toggle-key! (r/cursor state/landing-state [:tab-content-toggle])
                            :zones-view))
