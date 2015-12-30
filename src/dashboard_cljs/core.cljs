(ns dashboard-cljs.core
  (:require [dashboard-cljs.gmaps :as gmaps]
            [dashboard-cljs.login :as login]
            [dashboard-cljs.tables :as tables]
            [dashboard-cljs.landing :as landing]
            [weasel.repl :as repl]))

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

(defn ^:export init-app
  []
  (landing/init-landing))

(when-not (repl/alive?)
  (repl/connect "ws://127.0.0.1:9001"))
