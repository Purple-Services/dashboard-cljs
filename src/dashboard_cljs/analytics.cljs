(ns dashboard-cljs.analytics
  (:require [reagent.core :as r]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt
                                          continuous-update-until
                                          get-event-time]]
            [dashboard-cljs.datastore :as datastore]
            [cljsjs.plotly]))

(def state (r/atom {:stats-status {:status ""
                                   :timestamp ""}
                    :alert-success ""
                    :alert-danger  ""
                    :retrieving? false
                    :total-orders-per-day {:data {:x ["2016-01-01"]
                                                  :y [0]}
                                           :from-date nil
                                           :to-date nil}}))

(defn get-stats-status
  [stats-status]
  (retrieve-url
   (str base-url "status-stats-csv")
   "GET"
   {}
   (partial xhrio-wrapper
            #(let [response (js->clj % :keywordize-keys true)]
               ;; reset the state
               (reset! stats-status
                       (assoc @stats-status
                              :status (:status response)
                              :timestamp  (:timestamp response)))))))


(defn stats-panel
  "A panel for downloading stats.csv"
  []
  (let [stats-status (r/cursor state [:stats-status])
        status (r/cursor stats-status [:status])
        timestamp   (r/cursor stats-status [:timestamp])
        alert-success (r/cursor state [:alert-success])
        alert-danger   (r/cursor state [:alert-danger])
        retrieving? (r/cursor state [:retrieving?])
        ]
    (get-stats-status stats-status)
    (fn []
      [:div {:class "panel panel-default"}
       [:div {:class "panel-body"}
        [:h2 "stats.csv"]
        [:h3
         (when (= @status "ready")
           [:div
            ;; link for downloading
            [:a {:href (str base-url "download-stats-csv")
                 :on-click (fn [e]
                             (.preventDefault e)
                             (reset! retrieving? true)
                             ;; check to make sure that the file
                             ;; is still available for download
                             (retrieve-url
                              (str base-url "status-stats-csv")
                              "GET"
                              {}
                              (partial
                               xhrio-wrapper
                               #(let [response
                                      (js->clj % :keywordize-keys true)]
                                  (reset! retrieving? false)
                                  ;; the file is processing,
                                  ;; someone else must have initiated
                                  (when (= "processing"
                                           (:status response))
                                    (reset! status "processing")
                                    ;; tell the user the file is
                                    ;; processing
                                    (reset!
                                     alert-danger
                                     (str "stats.csv file is currently"
                                          " processing. Someone else"
                                          " initiated a stats.csv "
                                          "generation")))
                                  ;; the file is not processing
                                  ;; proceed as normal
                                  (when (= "ready"
                                           (:status response)))
                                  (set! (.-location js/window)
                                        (str base-url "download-stats-csv"))
                                  ))))}
             (str "Download stats.csv generated at "
                  (unix-epoch->fmt (:timestamp @stats-status) "h:mm A")
                  " on "
                  (unix-epoch->fmt (:timestamp @stats-status) "M/D"))]
            [:br]
            [:br]])
         ;; button for recalculating
         (when (or (= @status "ready")
                   (= @status "non-existent")))
         [:button {:type "submit"
                   :class "btn btn-default"
                   :on-click (fn [e]
                               ;; initiate generation of stats file
                               (retrieve-url
                                (str base-url "generate-stats-csv")
                                "GET"
                                {}
                                (fn []))
                               ;; processing? is now true
                               (reset! status "processing")
                               ;; create a message to let the user know
                               (reset!
                                alert-success
                                (str "stats.csv generation initiated."
                                     " Generation of file may take"
                                     " some time, but will be"
                                     " immediately"
                                     " available when done."
                                     " No need to refresh the browser."
                                     )))}
          "Generate New stats.csv"]]
        ;;stats.csv file is processing
        (when  (= @status "processing")
          (reset! retrieving? true)
          (continuous-update-until
           #(get-stats-status stats-status)
           #(= @status "ready")
           5000)
          [:h3 "stat.csv file processing "
           [:i {:class "fa fa-lg fa-spinner fa-pulse "}]])
        ;; get rid of all messages when processing is complete
        (when (= @status "ready")
          (reset! alert-danger "")
          (reset! alert-success ""))
        ;; alert success message
        (when (not (empty? @alert-success))
          [:div {:class "alert alert-success alert-dismissible"}
           [:button {:type "button"
                     :class "close"
                     :aria-label "Close"}
            [:i {:class "fa fa-times"
                 :on-click #(reset! alert-success "")}]]
           [:strong @alert-success]])
        ;; alert error message
        (when (not (empty? @alert-danger))
          [:div {:class "alert alert-danger alert-dismissible"}
           [:button {:type "button"
                     :class "close"
                     :aria-label "Close"}
            [:i {:class "fa fa-times"
                 :on-click #(reset! alert-danger "")}]]
           [:strong @alert-danger]])]])))

(defn orders-within-dates
  "Filter orders to those whose :target_time_start falls within from-date and
  to-date"
  [orders from-date to-date]
  (filter #(<= from-date (:target_time_start %) to-date)
          orders))

(defn orders-with-statuses
  "Filter orders to those who status is contained within the set statuses"
  [orders statuses]
  (filter #(contains? statuses (:status %)) orders))

(defn orders-per-hour
  "Given orders, count the amount of orders placed at each hour. return the
  result in a coll of hashmaps."
  [orders]
  (let [order-count-per-hour-non-zero
        (map #(hash-map (key %) (count (val %)))
             (group-by #(unix-epoch->fmt
                         (:target_time_start %) "h:00 A") orders))
        hours-of-day-in-seconds (map #(unix-epoch->fmt % "h:00 A")
                                     (range 0 (* 24 60 60) (* 60 60)))]
    (->>
     (merge-with +
                 (apply merge
                        (map #(hash-map (key %) (count (val %)))
                             (group-by #(unix-epoch->fmt (:target_time_start %)
                                                         "h:00 A") orders)))
                 (apply merge (map #(hash-map % 0) hours-of-day-in-seconds)))
     (sort-by #(.format (js/moment. (first %) "h:mm A") "HH:mm")))))
;; (clj->js (into [] (map first (orders-per-hour (orders-within-dates @dashboard-cljs.datastore/orders 1459468800 1462165199)))))

(defn orders-per-day
  "Given orders, count the amount of orders completed each day. return the
  result in a coll of hashmaps."
  [orders]
  (let [completed-order-count-per-day-non-zero
        (map #(hash-map (key %) (count (val %)))
             (group-by
              #(unix-epoch->fmt
                ;;(get-event-time (:event_log %) "complete")
                (:target_time_start %)
                "M-D-YYYY") orders))]
    ;; assuming there are orders everyday, for now
    (sort-by first completed-order-count-per-day-non-zero)))

(defn PlotlyComponent
  [props]
  (let [{:keys [data layout config]} props]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (let [node (r/dom-node this)]
          (js/Plotly.newPlot node data layout config)))

      :display-name "PlotlyComponent"

      :component-did-update
      (fn [this old-argv]
        (let [{:keys [data layout config]} (r/props this)]
          (js/Plotly.newPlot (r/dom-node this) data layout config)))

      :reagent-render
      (fn [args this]
        [:div])})))

(defn orders-by-hour
  "A panel for displaying orders by hour"
  []
  (let [orders (orders-per-hour (orders-within-dates
                                 @dashboard-cljs.datastore/orders
                                 1459468800 1462165199))
        trace1 {:x (into [] (map first orders))
                :y (into [] (map second orders))
                :type "bar"}
        data (clj->js [trace1])
        layout (clj->js {;;:barmode "stack"
                         :title "April 2016"
                         :yaxis {:title "Orders"}
                         })
        ;; a list of buttons to remove:
        ;; http://community.plot.ly/t/remove-options-from-the-hover-toolbar/130
        config (clj->js {:modeBarButtonsToRemove ["toImage","sendDataToCloud"]})]
    [PlotlyComponent {:data data
                      :layout layout
                      :config config}]))

(defn total-orders-per-day-chart
  "Display the total orders per day"
  []
  (let [data  (r/cursor state [:total-orders-per-day :data])
        _     (retrieve-url
               (str base-url "orders-per-day")
               "GET"
               {}
               (partial xhrio-wrapper
                        #(let [orders %]
                           (if-not (nil? orders)
                             (reset! data (js->clj orders :keywordize-keys true))))))
        selector-options (clj->js {:buttons [{:step "month"
                                              :stepmode "backward"
                                              :count 1
                                              :label "1m"},
                                             {:step "month"
                                              :stepmode "backward"
                                              :count 6
                                              :label "6m"},
                                             {:step "year"
                                              :stepmode "todate"
                                              :count 1
                                              :label "YTD"},
                                             {:step "year"
                                              :stepmode "backward"
                                              :count 1
                                              :label "1y"},
                                             {:step "all"}]})
        layout (clj->js {;;:barmode "stack"
                         :title "Completed Orders / Day"
                         :yaxis {:title "Completed Orders"
                                 :fixedrange true}
                         :xaxis {:rangeselector selector-options
                                 :rangeslider {}}
                         })
        config (clj->js {:modeBarButtonsToRemove ["toImage","sendDataToCloud"]})
        ]
    [PlotlyComponent {:data (clj->js [(merge @data
                                             {:mode "lines"})])
                      :layout layout
                      :config config}]))
