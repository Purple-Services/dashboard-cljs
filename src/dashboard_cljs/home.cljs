(ns dashboard-cljs.home
  (:require [reagent.core :as r]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt
                                          unix-epoch->hrf cents->$dollars
                                          markets get-event-time]]
            [dashboard-cljs.components :refer [DynamicTable RefreshButton
                                               CountPanel TablePager]]
            [dashboard-cljs.orders :as orders]
            goog.string.format
            [clojure.string :as s]))

(def state (r/atom {:current-order nil}))

(defn orders-count-panel
  "Component for displaying today's complete orders count."
  []
  (fn []
    (let [today-begin-unix (-> (js/moment) (.startOf "day") (.unix))
          todays-orders
          (fn [orders]
            (filter #(>= (or (get-event-time (:event_log %) "complete") 0)
                         today-begin-unix)
                    orders))]
      [CountPanel {:value (count (todays-orders @datastore/orders))
                   :caption "Completed Orders Today"
                   :panel-class "panel-primary"}])))

(defn cancelled-orders-panel
  "Component for displaying today's cancelled orders"
  []
  (fn []
    (let [today-begin-unix (-> (js/moment) (.startOf "day") (.unix))
          cancelled-orders
          (fn [orders]
            (filter #(>= (or (get-event-time (:event_log %) "cancelled") 0)
                         today-begin-unix)
                    orders))]
      [CountPanel {:value (count (cancelled-orders @datastore/orders))
                   :caption "Cancelled Orders Today"
                   :panel-class "panel-primary"}])))

(defn percent-cancelled-panel
  "Component for displaying the amount of cancelled orders"
  []
  (fn []
    (let [today-begin-unix (-> (js/moment) (.startOf "day") (.unix))
          completed-orders
          (fn [orders]
            (filter #(>= (or (get-event-time (:event_log %) "complete") 0)
                         today-begin-unix)
                    orders))
          cancelled-orders
          (fn [orders]
            (filter #(>= (or (get-event-time (:event_log %) "cancelled") 0)
                         today-begin-unix)
                    orders))
          percent-cancelled
          (fn [orders] (/ (count (cancelled-orders orders))
                          (count (completed-orders orders))))]
      [CountPanel {:value (str "%" (* 100 (.toFixed (percent-cancelled
                                                     @datastore/orders)
                                                    2)))
                   :caption "Percent Cancelled Today"
                   :panel-class "panel-primary"}])))

(defn current-orders-panel
  "Display the orders that have yet to be completed or cancelled"
  [orders]
  (let [current-order (r/cursor state [:current-order])
        sort-keyword (r/atom :target_time_end)
        sort-reversed? (r/atom true)
        current-page (r/atom 1)
        page-size 15]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (fn [order]
                        (contains? #{"unassigned"
                                     "assigned"
                                     "accepted"
                                     "enroute"
                                     "servicing"} (:status order)))
            displayed-orders orders
            sorted-orders (->> displayed-orders
                               sort-fn
                               (filter filter-fn)
                               (partition-all page-size))
            paginated-orders (-> sorted-orders
                                 (nth (- @current-page 1)
                                      '()))]
        (if (nil? @current-order)
          (reset! current-order (first paginated-orders)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          (if (= (count sorted-orders) 0)
            [:h3 "No Current Orders"]
            [:h3 "Current Orders"])]
         [:div {:class (when (= (count sorted-orders) 0)
                         "hide")}
          [:div {:class "table-responsive"}
           [DynamicTable {:current-item current-order
                          :tr-props-fn
                          (fn [order current-order]
                            {:class (str (when (= (:id order)
                                                  (:id @current-order))
                                           "active"))
                             :on-click
                             (fn [_]
                               (reset! current-order order))})
                          :sort-keyword sort-keyword
                          :sort-reversed? sort-reversed?
                          :table-vecs
                          orders/orders-table-vecs}
            paginated-orders]]
          [TablePager
           {:total-pages (count sorted-orders)
            :current-page current-page}]]]))))

