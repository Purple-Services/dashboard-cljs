(ns dashboard-cljs.core
  (:require [crate.core :as crate]
            [goog.net.XhrIo :as xhr]
            [pikaday]
            [moment]
            [maplabel]
            ))

(def state (atom {:orders (array)
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
                   :accepted   {:color "#808080"
                                :selected? true}
                   :enroute    {:color "#ffdd00"
                                :selected? true}
                   :servicing  {:color "#0000ff"
                                :selected? true}
                   :complete   {:color "#00ff00"
                                :selected? true}
                   :cancelled  {:color "#000000"
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

(defn create-circle
  "Create a circle shape on google-map with lat lng and color"
  [google-map lat lng color click-fn]
  (let [circle
        (js/google.maps.Circle.
         (js-obj "strokeColor" color
                 "strokeOpacity" 1.0
                 "strokeWeight" 2
                 "fillColor" color
                 "fillOpacity" 1
                 "map" google-map
                 "center" (js-obj "lat" lat "lng" lng)
                 "radius" 200))]
    (.addListener circle "click" click-fn)
    circle))

(defn add-circle-to-spatial-object!
  "Given an object with lat,lng properties, create a circle on gmap
  with color and optional click-fn. Add circle as obj property."
  ([obj gmap color]
   (add-circle-to-spatial-object! obj gmap color #()))
  ([obj gmap color click-fn]
   (aset obj "circle"
         (create-circle gmap
                       (aget obj "lat")
                       (aget obj "lng")
                       color
                       click-fn))))

(defn scale-by-zoom
  "Given a zoom, min-val and max-val return a value scaled by zoom"
  [zoom min-val max-val]
  (let [min-zoom 10
        max-zoom 21
        zoom-normalized (- zoom min-zoom)
        scale-factor (.pow js/Math 2 (- 21 zoom))
        new-radius   (cond (>= zoom max-zoom)
                           min-val
                           (<= zoom min-zoom )
                           max-val
                           (< min-zoom zoom max-zoom)
                           (* scale-factor 1128.497220 0.0027 (/ 1 10)))]
    new-radius))

(defn scale-spatial-object-circle-radius!
  "Given obj with a circle prop, scale its radius by zoom"
  [zoom obj]
  (.setRadius (aget obj "circle") (scale-by-zoom zoom 1 200)))

(defn dlat-label
  "Calculate the dLat by which the label should be moved based on the zoom so
  that it can be seen"
  [zoom]
  (* -1 (.pow js/Math 10 (/ (* -1 zoom) 4.2))))

(defn set-obj-label-lat-lng!
  "Given an obj, set the lat, lng positions of the label"
  [lat lng obj]
  (let [label (aget obj "label")]
    (.set label "position"
            (js/google.maps.LatLng. lat lng))
    (.draw label)))

(defn set-obj-label-position!
  "Given state, set the obj's label position based on the map's zoom"
  [state obj]
  (let [circle (aget obj "circle")
        circle-center (aget circle "center")
        label (aget obj "label")
        circle-lat (.lat circle-center)
        circle-lng (.lng circle-center)
        zoom       (.getZoom (:google-map @state))
        dlat       (dlat-label zoom)]
    (set-obj-label-lat-lng! (+ circle-lat dlat) circle-lng obj)))

(defn ^:export get-map-info
  "Utility function for viewing map information in the browser"
  []
  (.log js/console (str "Map-Zoom:" (.getZoom (:google-map @state))
                        " "
                        "Font-size:" (-> (:couriers @state)
                                         first
                                         (aget "label")
                                         (aget "fontSize"))
                        " "
                        "label-lat:" (-> (:couriers @state)
                                         first
                                         (aget "label")
                                         (aget "position")
                                         .lat))))

(defn create-label
  "Create a MapLabel object on google-map with lat, lng and text"
  [google-map lat lng text]
  (js/MapLabel.
   (js-obj "map" google-map
           "position" (js/google.maps.LatLng. (+ lat
                                                 (dlat-label
                                                  (.getZoom google-map)))
                                              lng)
           "text" text
           "fontSize" 12
           "align" "center")))

(defn add-label-to-spatial-object!
  "Given an object with lat,lng properties, create a label on gmap
  with text. Add label as obj property"
  [obj gmap text]
  (aset obj "label"
        (create-label gmap
                      (aget obj "lat")
                      (aget obj "lng")
                      text)))

(defn order-displayed?
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

(defn set-obj-prop-map!
  "Given an obj with prop, set its circle's map to map-value"
  [obj prop map-value]
  (if (not (nil? (aget obj prop)))
    (.setMap (aget obj prop) map-value)))

(defn display-selected-props!
  "Given the state, only display props of objs that satisfy pred on
  object in objs"
  [state objs prop pred]
  (mapv #(if (pred %)
           (set-obj-prop-map! % prop (:google-map @state))
           (set-obj-prop-map! % prop nil))
        objs))

(defn courier-displayed?
  "Given the state, determine if a courier should be displayed or not"
  [state courier]
  (let [active?    (aget courier "active")
        on-duty?   (aget courier "on_duty")
        connected? (aget courier "connected")
        selected?  (get-in @state [:couriers-control :selected?])
        ]
    (and connected? active? on-duty? selected?)))

(defn display-selected-couriers!
  "Given the state, display selected couriers"
  [state]
  (let [display-courier-props!
        #(display-selected-props!
          state (:couriers @state) % (partial courier-displayed? state))]
    (display-courier-props! "circle")
    (display-courier-props! "label")))

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
                       ;; add a circle for each courier
                       (mapv
                        (fn [courier]
                          (add-circle-to-spatial-object!
                           courier
                           (:google-map @state)
                           (get-in @state [:couriers-control
                                           :color])))
                        (:couriers @state))
                       ;; add a label for each courier
                       (mapv
                        (fn [courier]
                          (add-label-to-spatial-object!
                           courier
                           (:google-map @state)
                           (aget courier "name")))
                        (:couriers @state))
                       ;; display the couriers
                       (display-selected-couriers! state)))))


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
                                     state-courier-circle
                                     (aget state-courier "circle")
                                     state-courier-label
                                     (aget state-courier "label")
                                     new-lat    (aget courier "lat")
                                     new-lng    (aget courier "lng")
                                     active?    (aget courier "active")
                                     on_duty?   (aget courier "on_duty")
                                     connected? (aget courier "connected")
                                     new-center (clj->js {:lat new-lat
                                                          :lng new-lng})]
                                 ;; update the couriers lat lng
                                 (aset state-courier "lat" new-lat)
                                 (aset state-courier "lng" new-lng)
                                 ;; update the statuses of courier
                                 (aset state-courier "active" active?)
                                 (aset state-courier "on_duty" on_duty?)
                                 (aset state-courier "connected" connected?)
                                 ;; set the corresponding circle's center
                                 (.setCenter state-courier-circle
                                             new-center)
                                 ;;set the corresponding label's position
                                 (set-obj-label-position! state state-courier)))
                             couriers))
                     ;; show the couriers
                     (display-selected-couriers! state))))

(defn retrieve-orders
  "Retrieve the orders since date and apply f to them"
  [date f]
  (retrieve-route "orders-since-date" (js/JSON.stringify
                                       (clj->js {:date date})) f))

(defn click-order-fn
  "Given order, display information about it when it is clicked on the map"
  [order]
  (.log js/console (str "timestamp_created: "
                        (js/Date.
                         (aget order "timestamp_created")))))

(defn add-circle-to-order!
  "Given state, add a circle to order"
  [state order]
  (add-circle-to-spatial-object! order (:google-map @state)
                                (get-in @state [:status
                                                (keyword
                                                 (.-status order))
                                                :color])
                                (fn [] (click-order-fn order))))

(defn convert-orders-timestamp!
  "Convert an orders timestamp_created to a js/Date"
  [order]
  (aset order "timestamp_created"
        (.parse js/Date
                (aget order "timestamp_created"))))

(defn init-orders!
  "Get the all orders from the server and store them in the state atom. This
  should only be called once initially."
  [state date]
  (do
    (retrieve-orders
     date ; because an empty date will return ALL orders
     #(let [xhrio (.-target %)
            response (.getResponseJson xhrio)
            orders (aget response "orders")]
        (if (not (nil? orders))
          (do
            (swap! state assoc :orders orders)
            ;; add a circle for each order
            (mapv
             (partial add-circle-to-order! state)
             (:orders @state))
            ;; convert the server dates to js/Date
            ;; (i.e. unix timestamp)
            (mapv
             convert-orders-timestamp!
             (:orders @state))
            ;; redraw the circles according to which ones are selected
            (display-selected-props! state (:orders @state)
                                     "circle"
                                     (partial order-displayed? state))))))))

(defn sync-order!
  "Given an order, sync it with the one in state. If it does not already exist,
  add it to the orders in state"
  [state order]
  (let [order-status (aget order "status")
        status-color (get-in @state [:status (keyword order-status)
                                     :color])
        state-order (get-obj-with-prop-val (:orders @state) "id"
                                           (aget order "id"))]
    (if (nil? state-order)
      ;; process the order and add it to orders of state
      (do
        (add-circle-to-order! state order)
        (convert-orders-timestamp! order)
        (.push (:orders @state) order))
      ;; update the existing orders properties
      (do
        ;; currently, only the status could change
        (aset state-order "status" order-status)
        ;; change the color of circle to correspond to order status
        (let [color (get-in @state [:status (keyword order-status)])]
          (.setOptions (aget state-order "circle")
                       (clj->js {:options {:fillColor status-color
                                           :strokeColor status-color}})))))))

(defn sync-orders!
  "Retrieve today's orders and sync them with the state"
  [state]
  (retrieve-orders (.format (js/moment) "YYYY-MM-DD")
                   #(let [xhrio (.-target %)
                          response (.getResponseJson xhrio)
                          orders (aget response "orders")]
                      (if (not (nil? orders))
                        (mapv (partial sync-order! state) orders)))))

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
                                             (display-selected-props!
                                              state
                                              (:orders @state)
                                              "circle"
                                              (partial order-displayed? state)
                                              )))
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
                       "accepted"
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
                                         (display-selected-props!
                                          state (:orders @state)
                                          "circle"
                                          (partial order-displayed? state))))
        to-input (date-picker-input (:to-date @state))
        to-date-picker (date-picker
                        to-input #(do
                                    (swap! state assoc :to-date to-input.value)
                                    (display-selected-props!
                                     state (:orders @state)
                                     "circle"
                                     (partial order-displayed? state))))]
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
                             (swap! state assoc-in
                                    [:couriers-control :selected?] true)
                             (swap! state assoc-in
                                    [:couriers-control :selected?] false))
                           (display-selected-props!
                            state
                            (:couriers @state)
                            "circle"
                            (partial courier-displayed? state)
                            )))
    (crate/html [:div [:div {:class "setCenterUI" :title "Select couriers"}
                       control-text]])))

(defn add-control!
  "Add control to g-map using position"
  [g-map control position]
  (.push  (aget g-map "controls" position)
          control))


(defn continous-update
  "Call f continously every n seconds"
  [f n]
  (js/setTimeout #(do (f)
                      (continous-update f n))
                 n))

(defn ^:export init-map-orders
  "Initialize the map for viewing all orders"
  []
  (do
    (swap! state
           assoc
           :google-map
           (js/google.maps.Map.
            (.getElementById js/document "map")
            (js-obj "center"
                    (js-obj "lat" 34.0714522 "lng" -118.40362)
                    "zoom" 12) ))
    ;; add listener for when the map is zoomed
    (.addListener (:google-map @state) "zoom_changed"
                  #(mapv (partial scale-spatial-object-circle-radius!
                                  (.getZoom (:google-map @state)))
                         (:orders @state)))
    ;; add controls
    (add-control! (:google-map @state) (crate/html
                                        [:div
                                         (orders-date-control state)
                                         (orders-status-control state)
                                         ])
                  js/google.maps.ControlPosition.LEFT_TOP)
    ;; initialize the orders
    (init-orders! state "")
    ;; poll the server and update orders
    (continous-update #(do (sync-orders! state))
                      (* 10 60 1000))))

(defn ^:export init-map-couriers
  "Initialize the map for manager couriers"
  []
  (do
    (swap! state
           assoc
           :google-map
           (js/google.maps.Map.
            (.getElementById js/document "map")
            (js-obj "center"
                    (js-obj "lat" 34.0714522 "lng" -118.40362)
                    "zoom" 12)))
    ;; listener for map zone
    (.addListener (:google-map @state) "zoom_changed"
                  #(do
                     (mapv (partial scale-spatial-object-circle-radius!
                                    (.getZoom (:google-map @state)))
                           (:orders @state))
                     (mapv (partial scale-spatial-object-circle-radius!
                                    (.getZoom (:google-map @state)))
                           (:couriers @state))
                     (mapv (partial set-obj-label-position! state)
                           (:couriers @state))))
    ;; add controls
    (add-control! (:google-map @state) (crate/html
                                        [:div
                                         (orders-status-control state)
                                         (crate/html
                                          [:div (couriers-control state)])])
                  js/google.maps.ControlPosition.LEFT_TOP)
    ;; initialize the orders
    (init-orders! state (.format (js/moment) "YYYY-MM-DD"))
    ;; initialize the couriers
    (set-couriers! state)
    ;; poll the server and update the orders and couriers
    (continous-update #(do (update-couriers! state)
                           (sync-orders! state))
                      5000)))
