(ns dashboard-cljs.core
  (:require [dashboard-cljs.gmaps :as gmaps]
            [dashboard-cljs.login :as login]
            [dashboard-cljs.landing :as landing]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url accessible-routes]]))

(defn ^:export get-map-info
  []
  (gmaps/get-map-info))

(defn ^:export init-map-orders
  []
  (gmaps/init-map-orders))

(defn ^:export init-map-couriers
  []
  (gmaps/init-map-couriers))

;; (defn ^:export init-map-coverage-map
;;   []
;;   (gmaps/init-map-coverage-map))

(defn ^:export login
  []
  (login/login))

(defn ^:export init-new-dash
  []
  (landing/init-landing)
  ;; accessible-routes has to be setup BEFORE datastore is initialized
  (retrieve-url
     (str base-url "permissions")
     "GET"
     {}
     (partial xhrio-wrapper
              (fn [response]
                (reset! accessible-routes
                        (set (js->clj response :keywordize-keys true)))
                (datastore/init-datastore)))))
