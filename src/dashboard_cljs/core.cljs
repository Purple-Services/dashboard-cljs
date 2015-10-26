(ns dashboard-cljs.core
  (:require [clojure.browser.repl :as repl]))

;; (defonce conn
;;   (repl/connect "http://localhost:9000/repl"))

(enable-console-print!)

(defn send-xhr
  "Send a xhr to url using callback and HTTP method."
  [url callback method & [data headers]]
  (.send goog.net.XhrIo url callback method data headers))

(defn create-point
  [google-map lat lng]
  (js/google.maps.Circle.
   (js-obj "strokeColor" "#ff0000"
           "strokeOpacity" 1.0
           "strokeWeight" 2
           "fillColor" "#ff0000"
           "fillOpacity" 1
           "map" google-map
           "center" (js-obj "lat" lat "lng" lng)
           "radius" 10)))

(defn show-orders-callback
  [reply]
  (let [xhrio (.-target reply)
        response (.getResponseJson xhrio)
        orders (aget response "orders")
        ]
    (mapv #(create-point google-map (.-lat %) (.-lng %)) orders)))

(defn show-orders
  []
  (let [url "http://localhost:3000/dashboard/orders-since-date"
        date "2015-01-01"
        data (js/JSON.stringify (clj->js {:date date}))
        header (clj->js {"Content-Type" "application/json"})
        orders (send-xhr url show-orders-callback "POST" data header)]))

(defn ^:export init-map
  []
  (do (def google-map
        (js/google.maps.Map. (.getElementById js/document "map")
                             (js-obj "center" (js-obj "lat" 34.0714522 "lng" -118.40362)
                                     "zoom" 16) ))
      ;;(create-point map 34.0714522 -118.40362)
      (show-orders)
      ))

;;(create-point map 34.0714522 -118.40362)

(println "Hello foo!")
