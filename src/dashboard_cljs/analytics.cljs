(ns dashboard-cljs.analytics
  (:require [reagent.core :as r]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt
                                          continuous-update-until]]))

(def state (r/atom {:stats-status {:processing? false
                                   :timestamp ""}
                    :alert-success ""
                    :alert-danger  ""
                    :retrieving? false
                    }))

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
                              :processing? (:processing? response)
                              :timestamp  (:timestamp response)))
               ))))

(defn stats-panel
  "A panel for downloading stats.csv"
  []
  (let [stats-status (r/cursor state [:stats-status])
        processing? (r/cursor stats-status [:processing?])
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
        (when (not @processing?)
          [:h3
           (when (not @retrieving?)
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
                                    (when (:processing? response)
                                      (reset! processing? true)
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
                                    (when (not (:processing? response))
                                      (-> e
                                          (aget "target")
                                          .click))))))}
               (str "Download stats.csv generated at "
                    (unix-epoch->fmt (:timestamp @stats-status) "h:mm A")
                    " on "
                    (unix-epoch->fmt (:timestamp @stats-status) "M/D")
                    )]
              [:br]
              [:br]
              ;; button for recalculating
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
                                    (reset! processing? true)
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
               "Generate New stats.csv"]])
           (when @retrieving?
             [:i {:class "fa fa-lg fa-spinner fa-pulse"}])]
          )
        ;; stats.csv file is processing
        (when @processing?
          (continuous-update-until
           #(get-stats-status stats-status)
           #(not @processing?)
           5000)
          [:h3 "stat.csv file processing "
           [:i {:class "fa fa-lg fa-spinner fa-pulse "}]])
        ;; get rid of all messages when processing is complete
        (when (not @processing?)
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
           [:strong @alert-danger]])
        ]])))
