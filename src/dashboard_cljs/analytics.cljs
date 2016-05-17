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
                                               DatePicker]]
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

(def state (r/atom {:stats-status {:status ""
                                   :timestamp ""}
                    :alert-success ""
                    :alert-danger  ""
                    :retrieving? false

                    ;; order counts
                    :weekly-total-orders weekly-defaults
                    :daily-total-orders daily-defaults
                    :hourly-total-orders hourly-defaults

                    :weekly-orders-per-courier weekly-defaults
                    :daily-orders-per-courier daily-defaults
                    :hourly-orders-per-courier hourly-defaults

                    ;; gallons count
                    :weekly-total-gallons weekly-defaults
                    :daily-total-gallons daily-defaults
                    :hourly-total-gallons hourly-defaults

                    :weekly-gallons-per-courier weekly-defaults
                    :daily-gallons-per-courier daily-defaults
                    :hourly-gallons-per-courier hourly-defaults

                    ;; revenue count
                    :weekly-revenue weekly-defaults
                    :daily-revenue daily-defaults
                    :hourly-revenue hourly-defaults

                    :weekly-revenue-per-courier weekly-defaults
                    :daily-revenue-per-courier daily-defaults
                    :hourly-revenue-per-courier hourly-defaults

                    :total-orders-per-day
                    {:data {:x ["2016-01-01"]
                            :y [0]}
                     :from-date nil
                     :to-date nil
                     :layout {;;:barmode "stack"
                              :yaxis {:title "Completed Orders"
                                      :fixedrange true
                                      }
                              :xaxis {:rangeselector selector-options
                                      :rangeslider {}
                                      :tickmode "auto"
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

(defn total-orders-per-day-chart
  "Display the total orders per day"
  []
  (let [data  (r/cursor state [:total-orders-per-day :data])
        get-data (fn []
                   (retrieve-url
                    (str base-url "total-orders")
                    "POST"
                    (js/JSON.stringify (clj->js {:timezone @timezone
                                                 :response-type "json"
                                                 :timeframe "daily"
                                                 :from-date "2015-04-1"
                                                 :to-date
                                                 (unix-epoch->YYYY-MM-DD (now))
                                                 }))
                    (partial xhrio-wrapper
                             #(let [orders %]
                                (if-not (nil? orders)
                                  (reset! data (js->clj orders :keywordize-keys
                                                        true)))))))
        _ (get-data)
        refresh-fn (fn [refreshing?]
                     (reset! refreshing? true)
                     (retrieve-url
                      (str base-url "total-orders-per-timeframe")
                      "POST"
                      (js/JSON.stringify (clj->js {:timezone @timezone
                                                   :response-type "json"
                                                   :timeframe "daily"
                                                   :from-date "2015-04-01"
                                                   :to-date
                                                   (unix-epoch->YYYY-MM-DD
                                                    (now))}))
                      (partial xhrio-wrapper
                               #(let [orders %]
                                  (if-not (nil? orders)
                                    (reset! data (js->clj
                                                  orders
                                                  :keywordize-keys true)))
                                  (reset! refreshing? false)))))]
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
  "Retrieve totals from server using url"
  [url data-atom timeframe filename from-date to-date & [refresh-fn]]
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
                (when refresh-fn (refresh-fn)))))))

(defn DownloadCSV
  "Download links for obtaining the orders per courier
  props is:
  {:data        ; r/atom
   :timeframe   ; str
   :retrieve-fn ; fn
  }
  "
  [props]
  (let [{:keys [data timeframe retrieve-fn]} props
        data-atom (r/cursor data [:data])
        from-date (r/cursor data [:from-date])
        to-date   (r/cursor data [:to-date])
        filename (r/atom "no-data")]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (retrieve-fn data-atom timeframe filename
                     (unix-epoch->YYYY-MM-DD @from-date)
                     (unix-epoch->YYYY-MM-DD @to-date)))
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
              {:refresh-fn (fn [refreshing?]
                             (reset! refreshing? true)
                             (reset! filename "no-data")
                             (retrieve-fn data-atom
                                          timeframe
                                          filename
                                          (unix-epoch->YYYY-MM-DD @from-date)
                                          (unix-epoch->YYYY-MM-DD @to-date)
                                          (reset! refreshing?
                                                  false)))}]])]
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
