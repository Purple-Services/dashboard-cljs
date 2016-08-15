(ns dashboard-cljs.analytics
  (:require [reagent.core :as r]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt
                                          continuous-update-until
                                          get-event-time timezone
                                          orders-per-hour
                                          now]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.components :refer [RefreshButton
                                               Plotly
                                               DownloadCSVLink
                                               KeyVal
                                               DatePicker
                                               Select]]
            [cljsjs.plotly]))


(def selector-options
  (clj->js {:buttons [{:step "month"
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
                      {:step "all"}]}))

(def data-default
  {:data [{}]})

(def hourly-defaults
  (merge data-default
         {:from-date (-> (now))
          :to-date (now)}))

(def daily-defaults
  (merge data-default
         {:from-date (-> (js/moment)
                         (.startOf "month")
                         (.unix))
          :to-date (now)}))

(def weekly-defaults
  (merge data-default
         {:from-date (-> (js/moment)
                         (.startOf "year")
                         (.unix))
          :to-date (now)}))

(defn csv-data-default
  [timeframe]
  (merge {:data [{}]
          :timeframe timeframe}
         (condp = timeframe
           "hourly" {:from-date (-> (now))
                     :to-date (now)}
           "daily"  {:from-date (-> (js/moment)
                                    (.startOf "month")
                                    (.unix))
                     :to-date (now)}
           "weekly" {:from-date (-> (js/moment)
                                    (.startOf "year")
                                    (.unix))
                     :to-date (now)}
           "monthly" {:from-date (-> (js/moment)
                                     (.startOf "year")
                                     (.unix))
                      :to-date (now)})))


(def state (r/atom {:stats-status {:status ""
                                   :timestamp ""}
                    :alert-success ""
                    :alert-danger  ""
                    :retrieving? false

                    :total-orders-per-day
                    {:data {:x ["2016-01-01"]
                            :y [0]}
                     :from-date nil
                     :to-date nil
                     :layout {:yaxis {:title "Completed Orders"
                                      :fixedrange true
                                      }
                              :xaxis {:rangeselector selector-options
                                      :rangeslider {}
                                      :tickmode "auto"
                                      :tickformat "%a, %b %d"
                                      }}
                     :config {:modeBarButtonsToRemove
                              ["toImage","sendDataToCloud"]
                              :autosizable true
                              :displaylogo false}}}))

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
        retrieving? (r/cursor state [:retrieving?])]
    (get-stats-status stats-status)
    (fn []
      [:div {:class "panel panel-default hidden-xs hidden-sm"}
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

(defn unix-epoch->YYYY-MM-DD
  [epoch]
  (unix-epoch->fmt epoch "YYYY-MM-DD"))

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

;;!! not currently used
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
        config (clj->js {:modeBarButtonsToRemove
                         ["toImage","sendDataToCloud"]})]
    ;; [Plotly {:data data
    ;;          :layout layout
    ;;          :config config}]
    ))

(defn retrieve-json
  "Retrieve analytics json from server using url"
  [{:keys [url data-atom timeframe from-date to-date refresh-fn]}]
  (retrieve-url
   (str base-url url)
   "POST"
   (js/JSON.stringify (clj->js {:timezone @timezone
                                :timeframe timeframe
                                :from-date from-date
                                :to-date to-date
                                :response-type "json"}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys
                                      true)]
                (reset! data-atom response)
                (when refresh-fn
                  (refresh-fn)))))))

(defn total-orders-per-day-chart
  "Display the total orders per day"
  []
  (let [data  (r/cursor state [:total-orders-per-day :data])
        _ (retrieve-json {:url "total-orders"
                          :data-atom data
                          :timeframe "daily"
                          :from-date "2015-04-01"
                          :to-date (unix-epoch->YYYY-MM-DD (now))})
        refresh-fn (fn [refreshing?]
                     (reset! refreshing? true)
                     (retrieve-json {:url "total-orders"
                                     :data-atom data
                                     :timeframe "daily"
                                     :from-date "2015-04-01"
                                     :to-date (unix-epoch->YYYY-MM-DD (now))
                                     :refresh-fn #(reset! refreshing? false)}))]
    [:div {:class "table-responsive"
           :style {:border "none !important"}}
     [:h1 "Completed orders per day "
      [RefreshButton {:refresh-fn
                      refresh-fn}]]
     [Plotly {:data  [(merge @data
                             {:type "scatter"})]
              :layout  @(r/cursor
                         state
                         [:total-orders-per-day :layout])
              :config  @(r/cursor
                         state [:total-orders-per-day :config])}]]))
(defn retrieve-csv
  "Retrieve analytics csv from server using url"
  [{:keys [url data-atom timeframe filename from-date to-date refresh-fn]}]
  (retrieve-url
   (str base-url url)
   "POST"
   (js/JSON.stringify (clj->js {:timezone @timezone
                                :timeframe timeframe
                                :from-date from-date
                                :to-date to-date
                                :response-type "csv"}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys
                                      true)]
                (reset! data-atom (:data response))
                (reset!
                 filename
                 (str timeframe "-" url "-"
                      from-date
                      "-through-"
                      to-date
                      ".csv"))
                (.log js/console "filename should be: " @filename)
                (when refresh-fn (refresh-fn)))))))

(defn DownloadCSV
  "Download links for obtaining the orders per courier
  props is:
  {:url        ; str
   :timeframe? ; boolean - whether or not to include a timeframe,
                           default it true
  }"
  [props]
  (let [{:keys [url timeframe]
         :or {timeframe true}} props
         data (r/atom (csv-data-default "daily"))
         data-atom (r/atom [{}])
         from-date (r/cursor data [:from-date])
         to-date   (r/cursor data [:to-date])
         filename (r/atom "no-data")
         timeframe-id->timeframe-str {"t0" "hourly"
                                      "t1" "daily"
                                      "t2" "weekly"
                                      "t3" "monthly"}
         timeframe-id (r/atom "t1")
         refresh-fn (fn [refreshing?]
                      (reset! refreshing? true)
                      (reset! filename "no-data")
                      (retrieve-csv {:url url
                                     :data-atom data-atom
                                     :timeframe (timeframe-id->timeframe-str
                                                 @timeframe-id)
                                     :filename filename
                                     :from-date (unix-epoch->YYYY-MM-DD
                                                 @from-date)
                                     :to-date (unix-epoch->YYYY-MM-DD @to-date)
                                     :refresh-fn #(reset! refreshing?
                                                          false)}))
         refreshing? (r/atom false)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (retrieve-csv {:url url
                       :data-atom data-atom
                       :timeframe (timeframe-id->timeframe-str
                                   @timeframe-id)
                       :filename filename
                       :from-date (unix-epoch->YYYY-MM-DD @from-date)
                       :to-date   (unix-epoch->YYYY-MM-DD @to-date)}))
      :reagent-render
      (fn [args this]
        [:div
         [:h3
          (if (= @filename "no-data")
            [:span "CSV file is processing "
             [:i {:class "fa fa-lg fa-spinner fa-pulse "}]]
            [:span [DownloadCSVLink {:content  @data-atom
                                     :filename @filename}
                    @filename]
             " "
             [RefreshButton
              {:refresh-fn
               refresh-fn
               :refreshing? refreshing?
               }]])]
         (when timeframe
           [Select {:value timeframe-id
                    :options #{{:id "t0" :display-key "hourly"}
                               {:id "t1"  :display-key "daily"}
                               {:id "t2" :display-key "weekly"}
                               {:id "t3" :display-key "monthly"}}
                    :display-key :display-key}])
         [:div {:class "form-group"
                :style {:margin-left "1px"}}
          [:label {:for "expires?"
                   :class "control-label"}
           [:div {:style {:display "inline-block"}}
            [:div
             [:div {:class "input-group"}
              [DatePicker from-date]]]]]
          [:span {:style {:font-size "3em"
                          :color "grey"}} " - "]
          [:label {:for "expires?"
                   :class "control-label"}
           [:div {:style {:display "inline-block"}}
            [:div
             [:div {:class "input-group"}
              [DatePicker to-date]]]]]]])})))

(defn analytics-panel
  "Panel for the analytics table"
  []
  (fn []
    [:div
     [stats-panel]
     [total-orders-per-day-chart]
     [:div {:class "hidden-xs hidden-sm"}
      [:h2 "Total Completed Orders"]
      [DownloadCSV {:url "total-orders"}]
      [:h2 "Total Cancelled Orders"]
      [DownloadCSV {:url "total-cancelled-orders"}]
      [:h2 "Cancelled Unassigned Orders"]
      [DownloadCSV {:url "cancelled-unassigned-orders"}]
      [:h2 "Completed Orders Per Courier"]
      [DownloadCSV {:url "orders-per-courier"}]
      [:h2 "Cancelled Orders Per Courier"]
      [DownloadCSV {:url "cancelled-orders-per-courier"}]
      [:h2 "Scheduled Orders Per Courier"]
      [DownloadCSV {:url "scheduled-orders-per-courier"}]
      [:h2 "Flex Orders Per Courier"]
      [DownloadCSV {:url "flex-orders-per-courier"}]
      [:h2 "Total Gallons Sold"]
      [DownloadCSV {:url "total-gallons"}]
      [:h2 "Total 87 Octance Gallons Sold"]
      [DownloadCSV {:url "total-87-gallons"}]
      [:h2 "Total 91 Octance Gallons Sold"]
      [DownloadCSV {:url "total-91-gallons"}]
      [:h2 "Gallons Sold Per Courier"]
      [DownloadCSV {:url "gallons-per-courier"}]
      [:h2 "Gallons 87 Octane Sold Per Courier"]
      [DownloadCSV {:url "gallons-87-per-courier"}]
      [:h2 "Gallons 91 Octane Sold Per Courier"]
      [DownloadCSV {:url "gallons-91-per-courier"}]
      [:h2 "Total Revenue"]
      [DownloadCSV {:url "total-revenue"}]
      [:h2 "Revenue Per Courier"]
      [DownloadCSV {:url "revenue-per-courier"}]
      [:h2 "Service Fees"]
      [DownloadCSV {:url "service-fees"}]
      [:h2 "Service Fee Per Courier"]
      [DownloadCSV {:url "service-fees-per-courier"}]
      [:h2 "Referral Gallons Cost"]
      [DownloadCSV {:url "referral-gallons-cost"}]
      [:h2 "Coupon Cost"]
      [DownloadCSV {:url "coupon-cost"}]
      [:h2 "Total Fuel Price"]
      [DownloadCSV {:url "fuel-price"}]
      [:h2 "Fuel Price Per Courier"]
      [DownloadCSV {:url "fuel-price-per-courier"}]
      [:h2 "Fleet Invoices"]
      [DownloadCSV {:url "fleets-invoice"
                    :timeframe false}]]]))
