(ns dashboard-cljs.core
  (:require [clojure.browser.repl :as repl]
            [crate.core :as crate]
            [pikaday]
            [moment]
            ))

(def state (atom {:orders nil
                  :orders-points nil
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
                 "radius" 10))]
    (.addListener point "click" click-fn)
    point))

(defn set-order-point-map!
  "Given an order, set its point's map to map-value"
  [order map-value]
  (if (not (nil? (aget order "point")))
    (.setMap (aget order "point") map-value)))

(defn set-orders-points-map!
  "Given a set of orders, set their map to map-value"
  [orders map-value]
  (mapv #(set-order-point-map! % map-value) orders))

(defn get-orders-points-with-status
  "Given a state, return all orders-points with status"
  [state status]
  (let [status-color (get-in @state [:status (keyword status) :color])
        orders-points (filterv #(= (.-strokeColor %) status-color)
                               (:orders-points @state))]
    orders-points))

(defn display-status-selected-points!
  "Given state, set the map to nil for any points that are not selected in
  (:status @state)"
  [state]
  (mapv
   (fn [status]
     (let [orders (filterv (fn [order] (= (aget order "status")
                                          (name (key status))))
                           (:orders @state))
           selected? (:selected? (val status))]
       (do
         (if selected?
           (set-orders-points-map! orders (:google-map @state))
           (set-orders-points-map! orders nil)))))
   (:status @state)))

(defn display-orders-before-date!
  "Given the state, set the maps of all order's points who occur after date to
  nil"
  [state]
  (let [date (+ (.parse js/Date (:to-date @state))
                (* 24 60 60 1000))
        orders-beyond-date
        (filterv (fn [order] (>=
                              (aget order "timestamp_created")
                              date))
                 (:orders @state))]
    (set-orders-points-map! orders-beyond-date nil)))

(defn display-selected-points!
  "Given state, set the map to nil for any points that should not be shown"
  [state]
  (do
    ;; check the selected? status first
    (display-status-selected-points! state)
    ;; remove any points that occur after (:to-date @state)
    (display-orders-before-date! state)))

(defn click-order-fn
  "Given order, display information about it when it is clicked on the map"
  [order]
  (.log js/console (str "timestamp_created: "
                        (js/Date.
                         (aget order "timestamp_created")))))

(defn swap-orders!
  "Get the orders since date (YYYY-MM-DD) store them in the state atom"
  [state date]
  (let [url (str base-server-url "orders-since-date")
        data (js/JSON.stringify (clj->js {:date date}))
        header (clj->js {"Content-Type" "application/json"})]
    (send-xhr url
              #(let [xhrio (.-target %)
                     response (.getResponseJson xhrio)
                     orders (aget response "orders")]
                 (do
                   ;; remove all orders points from the map
                   (set-orders-points-map! (:orders @state) nil)
                   (swap! state assoc :orders orders)
                   ;; add a point for each order
                   (mapv
                    (fn [order]
                      (aset order "point"
                            (create-point
                             (:google-map @state)
                             (.-lat order)
                             (.-lng order)
                             (get-in @state [:status
                                             (keyword
                                              (.-status order))
                                             :color])
                             (fn [] (click-order-fn order)))))
                    (:orders @state))
                   ;; convert the server dates to js/Date (i.e. unix timestamp)
                   (mapv (fn [order]
                           (aset order "timestamp_created"
                                 (.parse js/Date
                                         (aget order "timestamp_created"))))
                         (:orders @state))
                   ;; redraw the points according to which ones are selected
                   (display-selected-points! state)))
              "POST" data header)))

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
                       [:div {:style (str "height: 10px;"
                                          " width: 10px;"
                                          " display: inline-block;"
                                          " float: right;"
                                          " border-radius: 10px;"
                                          " margin-top: 7px;"
                                          " margin-left: 5px;"
                                          " background-color: "
                                          (get-in @state [:status
                                                          (keyword status)
                                                          :color]))}]])]
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
                                         (swap-orders! state from-input.value)))
        to-input (date-picker-input (:to-date @state))
        to-date-picker
        (date-picker
         to-input
         #(do
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

(defn add-control!
  "Add control to g-map using position"
  [g-map control position]
  (.push  (aget g-map "controls" position)
          control))

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
    ;; initialize the orders
    (swap-orders! state (:from-date @state))))
