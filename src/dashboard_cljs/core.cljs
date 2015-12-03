(ns dashboard-cljs.core
  (:require [dashboard-cljs.gmaps :as gmaps]
            [dashboard-cljs.login :as login]
            ))

(defn ^:export get-map-info
  []
  (gmaps/get-map-info))

(defn ^:export init-map-orders
  []
  (gmaps/init-map-orders))

(defn ^:export init-map-couriers
  []
  (gmaps/init-map-couriers))

(defn ^:export init-map-coverage-map
  []
  (gmaps/init-map-coverage-map))

(defn ^:export login
  []
  (login/login))
