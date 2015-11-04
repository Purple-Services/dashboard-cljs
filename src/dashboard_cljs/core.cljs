(ns dashboard-cljs.core
  (:require [crate.core :as crate]
            [goog.net.XhrIo :as xhr]
            [pikaday]
            [moment]
            ))

(def state (atom {:orders nil
                  :couriers nil
                  :couriers-control
                  {:selected? true
                   :color "#8E44AD"}
                  :google-map nil
                  :from-date "2015-05-01"
                  :to-date   (.format (js/moment) "YYYY-MM-DD")
                  :status
                  {:unassigned {:color "#ff0000"
                                :selected? true}
                   :enroute    {:color "#ffdd00"
                                :selected? true}
                   :servicing  {:color "#0000ff"
                                :selected? true}
                   :complete   {:color "#00ff00"
                                :selected? true}
                   :cancelled   {:color "#000000"
                                 :selected? true}}}))

(defn send-xhr
  "Send a xhr to url using callback and HTTP method."
  [url callback method & [data headers]]
  (.send goog.net.XhrIo url callback method data headers))

(defn get-obj-with-prop-val
  "Get the object in array by prop with val"
  [array prop val]
  (first (filter #(= val (aget % prop)) array)))

;; the base url to use for server calls
(def base-server-url (-> (.getElementById js/document "base-url")
                         (.getAttribute "value")))

(defn create-point
  "Create a circle point on google-map with lat lng and color"
  [google-map lat lng color click-fn]
  (let [point
        (js/google.maps.Circle.
         (js-obj "strokeColor" color
                 "strokeOpacity" 1.0
                 "strokeWeight" 2
                 "fillColor" color
                 "fillOpacity" 1
                 "map" google-map
                 "center" (js-obj "lat" lat "lng" lng)
                 "radius" 20))]
    (.addListener point "click" click-fn)
    point))

(defn set-obj-point-map!
  "Given an obj with a point property, set its point's map to map-value"
  [obj map-value]
  (if (not (nil? (aget obj "point")))
    (.setMap (aget obj "point") map-value)))

(defn set-objs-points-map!
  "Given a set of objs, set their points map to map-value"
  [objs map-value]
  (mapv #(set-obj-point-map! % map-value) objs))

(defn point-displayed?
  "Given the state, determine if an order should be displayed or not"
  [state order]
  (let [from-date (.parse js/Date (:from-date @state))
        order-date (aget order "timestamp_created")
        to-date (+ (.parse js/Date (:to-date @state))
                   (* 24 60 60 1000)) ; need to add 24 hours so day is included
        status  (aget order "status")
        status-selected? (get-in @state [:status (keyword status) :selected?])
        ]
    (and
     ;; order is within the range of dates
     (<= from-date order-date to-date)
     status-selected?)))

(defn display-selected-points!
  "Given the state, only display points that are selected in state"
  [state]
  (mapv #(if (point-displayed? state %)
           (set-obj-point-map! % (:google-map @state))
           (set-obj-point-map! % nil)
           )
        (:orders @state)))

(defn add-point-to-spatial-object!
  "Given an object with lat,lng properties, create a point on gmap
  with color and optional click-fn. Add point as obj property."
  ([obj gmap color]
   (add-point-to-spatial-object! obj gmap color #()))
  ([obj gmap color click-fn]
   (aset obj "point"
         (create-point gmap
                       (aget obj "lat")
                       (aget obj "lng")
                       color
                       click-fn))))

(defn click-order-fn
  "Given order, display information about it when it is clicked on the map"
  [order]
  (.log js/console (str "timestamp_created: "
                        (js/Date.
                         (aget order "timestamp_created")))))


(defn retrieve-route
  "Access route with data and process xhr callback with f"
  [route data f]
  (let [url (str base-server-url route)
        data data
        header (clj->js {"Content-Type" "application/json"})]
    (send-xhr url
              f
              "POST"
              data
              header)))

(defn set-couriers!
  "Get the couriers from the server and store them in the state atom"
  [state]
  (retrieve-route "couriers" {}
                  #(let [xhrio (.-target %)
                         response (.getResponseJson xhrio)
                         couriers (aget response "couriers")]
                     (do
                       ;; replace the couriers
                       (swap! state assoc :couriers couriers)
                       ;; add a point for each courier
                       (mapv
                        (fn [courier]
                          (add-point-to-spatial-object!
                           courier
                           (:google-map @state)
                           (get-in @state [:couriers-control
                                           :color])))
                        (:couriers @state))))))


(defn update-couriers!
  "Update the couriers positions with data from the server"
  [state]
  (retrieve-route "couriers" {}
                  #(let [xhrio (.-target %)
                         response (.getResponseJson xhrio)
                         couriers (aget response "couriers")]
                     (do
                       (mapv (fn [courier]
                               (let [state-courier
                                     (get-obj-with-prop-val (:couriers @state)
                                                            "id" (aget courier
                                                                       "id"))
                                     state-courier-point
                                     (aget state-courier "point")
                                     new-lat (aget courier "lat")
                                     new-lng (aget courier "lng")
                                     new-center (clj->js {:lat new-lat
                                                          :lng new-lng})]
                                 ;; update the couriers lat lng
                                 (aset state-courier "lat" new-lat)
                                 (aset state-courier "lng" new-lng)
                                 ;; set the corresponding point's center
                                 (.setCenter state-courier-point
                                             new-center)
                                 (.setRadius state-courier-point 50)))
                             couriers)))))

(defn retrieve-orders
  "Retrieve the orders since date and apply f to them"
  [date f]
  (retrieve-route "orders-since-date" (js/JSON.stringify
                                       (clj->js {:date date})) f))

(defn init-orders!
  "Get the all orders from the server and store them in the state atom. This
  should only be called once initially."
  [state]
  (do
    (retrieve-orders
     "" ; because an empty date will return ALL orders
     #(let [xhrio (.-target %)
            response (.getResponseJson xhrio)
            orders (aget response "orders")]
        (do
          (swap! state assoc :orders orders)
          ;; add a point for each order
          (mapv
           (fn [order]
             (add-point-to-spatial-object!
              order
              (:google-map @state)
              (get-in @state [:status
                              (keyword
                               (.-status order))
                              :color])
              (fn [] (click-order-fn order))))
           (:orders @state))
          ;; convert the server dates to js/Date
          ;; (i.e. unix timestamp)
          (mapv (fn [order]
                  (aset order "timestamp_created"
                        (.parse js/Date
                                (aget order "timestamp_created"))))
                (:orders @state))
          ;; redraw the points according to which ones are selected
          (display-selected-points! state))))))

(defn update-orders!
  "Retrieve orders that are more recent than those in state"
  [state]
  (let [most-recent-order (first (sort #(>
                                         (aget %1 "timestamp_created")
                                         (aget %2 "timestamp_created"))
                                       (:orders @state)))
        most-recent-date  (js/Date.
                           (aget most-recent-order "timestamp_created"))
        server-date       (str (.getUTCFullYear most-recent-date) "-"
                               (+ 1 (.getUTCMonth    most-recent-date)) "-"
                               (+ 1 (.getUTCDay      most-recent-date)) " "
                               (.getUTCHours    most-recent-date) ":"
                               (.getUTCMinutes  most-recent-date) ":"
                               (.getUTCSeconds  most-recent-date))]
    (retrieve-orders
     server-date
     #(let [xhrio (.-target %)
            response (.getResponseJson xhrio)
            orders (aget response "orders")]
        (mapv (fn [order]
                (do
                  ;; add the point to the order
                  (add-point-to-spatial-object!
                   order
                   (:google-map @state)
                   (get-in @state [:status
                                   (keyword
                                    (aget order "status"))
                                   :color])
                   (fn [] (click-order-fn order)))
                  ;; convert the server dates to js/Date
                  (aset order "timestamp_created"
                        (.parse js/Date
                                (aget order "timestamp_created")))
                  (.push (:orders @state) order)))
              orders)
        ;; redraw the points
        (display-selected-points! state)
        ;; (.log js/console (count orders
        ;;                         ;;(mapv (fn [order]) orders)
        ;;                         ))
        ))))

;; (defn sync-orders!
;;   "Retrieve orders from the server since date and update the orders in state"
;;   [state date]
;;   (retrieve-route date
;;                   #(let [xhrio (.-target %)
;;                          response (.getResponseJson xhrio)
;;                          orders (aget response "orders")]
;;                      (do
;;                        ;; check to see if the order already exists
;;                        ;; if it does, change all values inside of it
;;                        ;; change its point as well
;;                        ;; if it doesn't exists, add the order to orders
;;                        ;; add a point for it as well
;;                        ))))

(defn legend-symbol
  "A round div for use as a legend symbol"
  [color]
  (crate/html [:div {:style (str "height: 10px;"
                                 " width: 10px;"
                                 " display: inline-block;"
                                 " float: right;"
                                 " border-radius: 10px;"
                                 " margin-top: 7px;"
                                 " margin-left: 5px;"
                                 " background-color: "
                                 color)}]))

(defn order-status-checkbox
  "A checkbox for controlling an order of status"
  [state status]
  (let [checkbox (crate/html [:input {:type "checkbox"
                                      :name "orders"
                                      :value "orders"
                                      :class "orders-checkbox"
                                      :checked true}])
        control-text (crate/html
                      [:div {:class "setCenterText"}
                       checkbox status
                       (legend-symbol (get-in @state [:status
                                                      (keyword status)
                                                      :color]))])]
    (.addEventListener checkbox "click" #(do (if (aget checkbox "checked")
                                               (swap! state assoc-in
                                                      [:status (keyword status)
                                                       :selected?] true)
                                               (swap! state assoc-in
                                                      [:status (keyword status)
                                                       :selected?] false))
                                             (display-selected-points! state)))
    control-text))

(defn orders-status-control
  "A control for viewing orders on the map for courier managers"
  [state]
  (crate/html [:div
               [:div
                {:class "setCenterUI"
                 :title "Select order status"}
                (map #(order-status-checkbox state %)
                     '("unassigned"
                       "enroute"
                       "servicing"
                       "complete"
                       "cancelled"))]]))

(defn orders-date-control
  "A control for picking the orders range of dates"
  [state]
  (let [date-picker-input #(crate/html [:input {:type "text"
                                                :name "orders-date"
                                                :class "date-picker"
                                                :value %}])
        date-picker #(js/Pikaday.
                      (js-obj "field" %1
                              "format" "YYYY-MM-DD"
                              "onSelect"
                              %2))
        from-input (date-picker-input (:from-date @state))
        from-date-picker (date-picker from-input
                                      #(do
                                         (swap! state assoc :from-date
                                                from-input.value)
                                         (display-selected-points! state)))
        to-input (date-picker-input (:to-date @state))
        to-date-picker (date-picker
                        to-input #(do
                                    (swap! state assoc :to-date to-input.value)
                                    (display-selected-points! state)))]
    (crate/html [:div
                 [:div {:class "setCenterUI"
                        :title "Click to change dates"}
                  [:div {:class "setCenterText"}
                   "Orders"
                   [:br]
                   "From: "
                   from-input
                   [:br]
                   "To:   " to-input]]])))

(defn couriers-control
  "A checkbox for controlling whether or not couriers are shown
  on the map"
  [state]
  (let [checkbox (crate/html [:input {:type "checkbox"
                                      :name "couriers"
                                      :value "couriers"
                                      :class "couriers-checkbox"
                                      :checked true}])
        control-text (crate/html
                      [:div {:class "setCenterText"}
                       checkbox "Couriers" (legend-symbol
                                            (get-in
                                             @state [:couriers-control
                                                     :color]))])]
    (.addEventListener
     checkbox "click" #(do (if (aget checkbox "checked")
                             (set-objs-points-map! (:couriers @state)
                                                   (:google-map @state))
                             (set-objs-points-map! (:couriers @state)
                                                   nil))))
    (crate/html [:div [:div {:class "setCenterUI" :title "Select couriers"}
                       control-text]])))

(defn add-control!
  "Add control to g-map using position"
  [g-map control position]
  (.push  (aget g-map "controls" position)
          control))


(defn continous-update
  "Call f continously "
  [f]
  (js/setTimeout #(do (f)
                      (continous-update f))
                 1000))

(defn ^:export init-map
  "Initialize the map for managing orders"
  []
  (do
    (swap! state
           assoc
           :google-map
           (js/google.maps.Map.
            (.getElementById js/document "map")
            (js-obj "center"
                    (js-obj "lat" 34.0714522 "lng" -118.40362)
                    "zoom" 16) ))
    ;; add a control for selecting dates of orders
    (add-control! (:google-map @state) (crate/html
                                        [:div
                                         (orders-date-control state)
                                         (orders-status-control state)])
                  js/google.maps.ControlPosition.LEFT_TOP)
    ;; add a control for the couriers
    (add-control! (:google-map @state)
                  (crate/html [:div (couriers-control state)])
                  js/google.maps.ControlPosition.LEFT_TOP)
    ;; initialize the orders
    (init-orders! state)
    ;; initialize the couriers
    (set-couriers! state)
    ;; poll the server every second and update the courier's position
    (continous-update #(do (update-couriers! state)
                           ;;(update-orders! state)
                           ))
    ))
