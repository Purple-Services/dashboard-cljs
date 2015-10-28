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

(defn center-control
  [control-div google-map]
  (let [control-ui (.createElement js/document "div")
        control-text (.createElement js/document "div")]
    (aset control-ui "style" "backgroundColor" "#fff")
    (aset control-ui "style" "border" "2px solid #fff")
    (aset control-ui "style" "borderRadius" "3px")
    (aset control-ui "style" "boxShadow" "0 2px 6px rgba(0,0,0,.3")
    (aset control-ui "style" "cursor" "pointer")
    (aset control-ui "style" "marginBottom" "22px")
    (aset control-ui "style" "textAlign" "center")
    (aset control-ui "title" "Click to recenter the map")
    (.appendChild control-div control-ui)
    (aset control-text "style" "color" "rgb(25,25,25)")
    (aset control-text "style" "fontFamily" "Roboto,Arial,sans-serif")
    (aset control-text "style" "fontSize" "16px")
    (aset control-text "style" "lineHeight" "38px")
    (aset control-text "style" "paddingLeft" "5px")
    (aset control-text "style" "paddingRight" "5px")
    (aset control-text "innerHTML" "Center Map")
    (.appendChild control-ui control-text)
    (.addEventListener control-ui "click"
                       #(.setCenter google-map #js {:lat 41.85 :lng -87.65}))
    control-div))

(defn ^:export init-map
  []
  (do
    (def google-map
      (js/google.maps.Map. (.getElementById js/document "map")
                           (js-obj "center" (js-obj "lat" 34.0714522 "lng" -118.40362)
                                   "zoom" 16) ))
    (let [center-control-div (.createElement js/document "div")
          centerControl      (center-control (do (aset center-control-div "index" 1)
                                                 center-control-div) google-map)]
      (aset center-control-div "index" 1)
      (.push  (aget google-map "controls"
                    js/google.maps.ControlPosition.TOP_CENTER) center-control-div)
      (show-orders))))

(println "Hello foo!")
