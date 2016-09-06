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

;; not really using this
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
                         :yaxis {:title "Orders"}})
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
        _ (retrieve-json {:url "total-orders-customer"
                          :data-atom data
                          :timeframe "daily"
                          :from-date "2015-04-01"
                          :to-date (unix-epoch->YYYY-MM-DD (now))})
        refresh-fn (fn [refreshing?]
                     (reset! refreshing? true)
                     (retrieve-json {:url "total-orders-customer"
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
                (when refresh-fn (refresh-fn)))))))


(defn DownloadXLSX
  [props]
  (let [{:keys [use-timeframe? use-datepicker? filename
                from-date to-date]
         :or {use-timeframe? true
              use-datepicker? true
              from-date (-> (js/moment)
                            (.startOf "month")
                            (.unix))
              to-date (now)}} props
              data (r/atom (csv-data-default "daily"))
              from-date (r/cursor data [:from-date])
              to-date   (r/cursor data [:to-date])
              timeframe-id->timeframe-str {"t0" "hourly"
                                           "t1" "daily"
                                           "t2" "weekly"
                                           "t3" "monthly"}
              timeframe-id (r/atom "t1")
              file-status  (r/atom {:status ""
                                    :timestamp ""})
              status (r/cursor file-status [:status])
              timestamp (r/cursor file-status [:timestamp])
              alert-danger (r/atom "")
              get-file-status
              (fn []
                (retrieve-url
                 (str base-url (str "status-file/" filename))
                 "GET"
                 {}
                 (partial xhrio-wrapper
                          #(let [response (js->clj % :keywordize-keys true)]
                             ;; reset the state
                             (reset! file-status
                                     {:status (:status response)
                                      :timestamp  (:timestamp response)})))))]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (get-file-status))
      :reagent-render
      (fn [argrs this]
        [:div
         (when (or (= @status "non-existent")
                   (= @status "ready"))
           [:div
            [:h3
             (when (= @status "ready")
               ;; clear any error message
               (reset! alert-danger "")
               [:div
                ;; link for downloading
                [:a {:href (str base-url "download-file/" filename)
                     :on-click (fn [e]
                                 (.preventDefault e)
                                 ;; check to make sure that the file
                                 ;; is still available for download
                                 (retrieve-url
                                  (str base-url (str "status-file/" filename))
                                  "GET"
                                  {}
                                  (partial
                                   xhrio-wrapper
                                   #(let [response
                                          (js->clj % :keywordize-keys true)]
                                      ;; the file is processing,
                                      ;; someone else must have initiated
                                      (when (= "processing"
                                               (:status response))
                                        (reset! status "processing")
                                        ;; tell the user the file is
                                        ;; processing
                                        (reset!
                                         alert-danger
                                         (str filename " is currently"
                                              " processing. Someone else"
                                              " initiated a " filename
                                              " generation")))
                                      (when (= "non-existent"
                                               (:status response))
                                        (reset! status "non-existent")
                                        ;; tell the user the file is
                                        ;; processing
                                        (reset!
                                         alert-danger
                                         (str filename " is currently"
                                              " non-existent. Someone else"
                                              " deleted " filename)))
                                      ;; the file is not processing
                                      ;; proceed as normal
                                      (when (= "ready"
                                               (:status response))
                                        (set! (.-location js/window)
                                              (str base-url "download-file/"
                                                   filename)))))))}
                 (str "Download " filename " generated at "
                      (unix-epoch->fmt (:timestamp @file-status) "h:mm A")
                      " on "
                      (unix-epoch->fmt (:timestamp @file-status) "M/D"))]])
             (when (= @status "non-existent")
               (str filename " does not exist. Click below to generate "
                    "a new one."))
             ]
            ;;generation button
            [:div
             [:button
              {:type "submit"
               :class "btn btn-default"
               :on-click
               (fn [e]
                 ;; initiate generation of stats file
                 (retrieve-url
                  (str base-url "generate-file/" filename)
                  "POST"
                  (js/JSON.stringify
                   (clj->js
                    {:parameters {:from-date
                                  (unix-epoch->YYYY-MM-DD @from-date)
                                  :to-date (unix-epoch->YYYY-MM-DD @to-date)
                                  :timeframe (timeframe-id->timeframe-str
                                              @timeframe-id)
                                  :timezone @timezone}}))
                  (partial
                   xhrio-wrapper
                   #(let [response
                          (js->clj % :keywordize-keys true)]
                      ;; the file is processing,
                      ;; someone else must have initiated
                      (when (= "processing"
                               (:message response))
                        (reset! status "processing")
                        ;; tell the user the file is
                        ;; processing
                        (reset!
                         alert-danger
                         (str filename " is currently"
                              " processing. Someone else"
                              " initiated a " filename
                              " generation")))
                      ;; the file is not processing
                      ;; proceed as normal
                      (when (:success response)
                        ;; processing? is now true
                        (reset! status "processing"))))))}
              (str "Generate " filename)]
             [:br]
             [:br]]
            ;;timeframe
            (when use-timeframe?
              [Select {:value timeframe-id
                       :options #{{:id "t0" :display-key "hourly"}
                                  {:id "t1"  :display-key "daily"}
                                  {:id "t2" :display-key "weekly"}
                                  {:id "t3" :display-key "monthly"}}
                       :display-key :display-key}])
            ;;datepicker
            (when use-datepicker?
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
                   [DatePicker to-date]]]]]])])
         ;;if the file is processing, check to see if it is done
         (when (= @status "processing")
           (continuous-update-until
            get-file-status
            #(= @status "ready")
            5000)
           [:h3 [:span (str filename " is processing ")
                 [:i {:class "fa fa-lg fa-spinner fa-pulse"}]]])
         ;;alert error message
         (when (not (empty? @alert-danger))
           [:div {:class "alert alert-danger alert-dismissible"}
            [:button {:type "button"
                      :class "close"
                      :aria-label "Close"}
             [:i {:class "fa fa-times"
                  :on-click #(reset! alert-danger "")}]]
            [:strong @alert-danger]])])})))

(defn analytics-panel
  "Entire content of analytics tab"
  []
  (fn []
    [:div
     [DownloadXLSX {:filename "stats.xlsx"
                    :use-timeframe? false
                    :use-datepicker? false}]
     [total-orders-per-day-chart]
     [DownloadXLSX {:filename "totals.xlsx"}]
     [DownloadXLSX {:filename "couriers-totals.xlsx"}]
     [DownloadXLSX {:filename "managed-accounts.xlsx"
                    :use-timeframe? false
                    :from-date (-> (js/moment)
                                   (.subtract 1 "day"))}]
     [DownloadXLSX {:filename "fleet-accounts.xlsx"
                    :use-timeframe? false
                    :from-date (-> (js/moment)
                                   (.subtract 1 "day"))}]]))
