(ns dashboard-cljs.zones
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          parse-to-number?]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton]]
            [clojure.string :as s]))

(def default-new-zone
  {:price-87 nil
   :price-91 nil
   :service-fee-60 nil
   :service-fee-180 nil
   :service-time-bracket-begin nil
   :service-time-bracket-end nil
   :errors nil
   :retrieving? false
   :alert-success ""
   })

(def state (r/atom {:current-zone nil
                    :edit-zone
                    default-new-zone
                    :selected "active"
                    }))

(defn edit-on-click
  [zone current-zone]
  (let [retrieving? (r/cursor zone [:retrieving?])
        errors      (r/cursor zone [:errors])
        zone->server-req
        (fn [zone]
          (let [{:keys [price-87 price-91 service-fee-60 service-fee-180
                        service-time-bracket-begin service-time-bracket-end]}
                zone]
            (assoc zone
                   :price-87
                   (if (parse-to-number? price-87)
                     (dollars->cents
                      price-87)
                     price-87)
                   :price-91
                   (if (parse-to-number? price-91)
                     (dollars->cents
                      price-91)
                     price-91)
                   :service-fee-60
                   (if (parse-to-number? service-fee-60)
                     (dollars->cents
                      service-fee-60)
                     service-fee-60)
                   :service-fee-180
                   (if (parse-to-number? service-fee-180)
                     (dollars->cents
                      service-fee-180)
                     service-fee-180)
                   :service-time-bracket-begin
                   (if (parse-to-number? service-time-bracket-begin)
                     (js/Number service-time-bracket-begin)
                     service-time-bracket-begin)
                   :service-time-bracket-end
                   (if (parse-to-number? service-time-bracket-end)
                     (js/Number service-time-bracket-end)
                     service-time-bracket-end))))]
    (fn [e]
      (.preventDefault e)
      ;; we are retrieving
      (reset! retrieving? true)
      ;; send response to server
      (retrieve-url
       (str base-url "zone")
       "PUT"
       (js/JSON.stringify
        (clj->js (zone->server-req @zone)))
       (partial
        xhrio-wrapper
        (fn [r]
          (let [response (js->clj r
                                  :keywordize-keys
                                  true)]
            (when (not (:success response))
              ;; we obviously have an error, there shouldn't
              ;; be a success message!
              (reset! (r/cursor zone [:alert-success])
                      ""
                      )
              (reset! retrieving? false)
              ;; handle errors
              (reset! errors (first
                              (:validation response))))
            (when (:success response)
              ;; retrieve the new zone
              (retrieve-url
               (str base-url "zone/" (:id @zone))
               "GET"
               {}
               (partial
                xhrio-wrapper
                (fn [r]
                  (let [response
                        (js->clj
                         r :keywordize-keys true)]
                    (put! datastore/modify-data-chan
                          {:topic "zones"
                           :data response})
                    ;; reset  edit-zone
                    (reset!
                     zone
                     (assoc
                      @zone
                      :retrieving? false
                      :errors nil
                      ))
                    ;; reset the current-zone
                    (reset! current-zone (assoc
                                          (first response)
                                          :alert-success
                                          (str "Zone"
                                               " successfully updated!")))
                    ))))))))))))

(defn zone-form-submit
  [zone on-click label]
  (fn []
    (let [retrieving? (r/cursor zone [:retrieving?])
          errors      (r/cursor zone [:errors])
          code        (r/cursor zone [:code])]
      ;; submit
      [:button {:type "submit"
                :class "btn btn-default"
                :on-click on-click
                }
       (if @retrieving?
         [:i {:class "fa fa-lg fa-refresh fa-pulse "}]
         label)
       ])))


(defn zone-form
  "Form for a new zone"
  [zone submit-button]
  (let [
        price-87 (r/cursor zone [:price-87])
        price-91 (r/cursor zone [:price-91])
        service-fee-60 (r/cursor zone [:service-fee-60])
        service-fee-180 (r/cursor zone [:service-fee-180])
        service-time-bracket-begin (r/cursor zone [:service-time-bracket-begin])
        service-time-bracket-end   (r/cursor zone [:service-time-bracket-end])
        errors (r/cursor zone [:errors])
        retrieving? (r/cursor zone [:retrieving?])
        alert-success (r/cursor zone [:alert-success])
        ]
    (fn []
      [:form {:class "form-horizontal"}
       ;; 87 Price
       [:div {:class "form-group"}
        [:label {:for "87 price"
                 :class "col-sm-2 control-label"}
         "87 Octane"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:div {:class "input-group-addon"}
           "$"]
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "87 Price"
                   :value @price-87
                   :on-change #(reset! price-87 (-> %
                                                    (aget "target")
                                                    (aget "value")))
                   }]]
         (when (:price-87 @errors)
           [:div {:class "alert alert-danger"}
            (first (:price-87 @errors))])]
        ]
       ;; 91 Price
       [:div {:class "form-group"}
        [:label {:for "91 price"
                 :class "col-sm-2 control-label"}
         "91 Octane"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:div {:class "input-group-addon"}
           "$"]
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "91 Price"
                   :value @price-91
                   :on-change #(reset! price-91 (-> %
                                                    (aget "target")
                                                    (aget "value")))
                   }]]
         (when (:price-91 @errors)
           [:div {:class "alert alert-danger"}
            (first (:price-91 @errors))])]
        ]
       ;; 1 Hour Fee
       [:div {:class "form-group"}
        [:label {:for "1 Hour Fee"
                 :class "col-sm-2 control-label"}
         "1 Hour Fee"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:div {:class "input-group-addon"}
           "$"]
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "91 Price"
                   :value @service-fee-60
                   :on-change #(reset! service-fee-60 (-> %
                                                          (aget "target")
                                                          (aget "value")))
                   }]]
         (when (:service-fee-60 @errors)
           [:div {:class "alert alert-danger"}
            (first (:service-fee-60 @errors))])]
        ]
       ;; 3 Hour Fee
       [:div {:class "form-group"}
        [:label {:for "3 Hour Fee"
                 :class "col-sm-2 control-label"}
         "3 Hour Fee"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:div {:class "input-group-addon"}
           "$"]
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "91 Price"
                   :value @service-fee-180
                   :on-change #(reset! service-fee-180 (-> %
                                                           (aget "target")
                                                           (aget "value")))
                   }]]
         (when (:service-fee-180 @errors)
           [:div {:class "alert alert-danger"}
            (first (:service-fee-180 @errors))])]]
       ;; Service Starts
       [:div {:class "form-group"}
        [:label {:for "Service Starts"
                 :class "col-sm-2 control-label"}
         "Service Starts"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "Service Starts"
                   :value @service-time-bracket-begin
                   :on-change #(reset! service-time-bracket-begin
                                       (-> %
                                           (aget "target")
                                           (aget "value")))
                   }]]
         (when (:service-time-bracket-begin @errors)
           [:div {:class "alert alert-danger"}
            (first (:service-time-bracket-begin @errors))])]]
       ;; Service Ends
       [:div {:class "form-group"}
        [:label {:for "Service Ends"
                 :class "col-sm-2 control-label"}
         "Service Ends"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "Service Ends"
                   :value @service-time-bracket-end
                   :on-change #(reset! service-time-bracket-end
                                       (-> %
                                           (aget "target")
                                           (aget "value")))
                   }]]
         (when (:service-time-bracket-end @errors)
           [:div {:class "alert alert-danger"}
            (first (:service-time-bracket-end @errors))])]]
       ;; submit button
       submit-button
       (when (not (empty? @alert-success))
         [:div {:class "alert alert-success alert-dismissible"}
          [:button {:type "button"
                    :class "close"
                    :aria-label "Close"}
           [:i {:class "fa fa-times"
                :on-click #(reset! alert-success "")}]
           ]
          [:strong @alert-success]])
       ])))

(defn zone-table-header
  "props is:
  {
  :sort-keyword   ; reagent atom keyword used to sort table
  :sort-reversed? ; reagent atom boolean for determing if the sort is reversed
  }"
  [props]
  (fn [props]
    [:thead
     [:tr
      [TableHeadSortable
       (conj props {:keyword :id})
       "Market"]
      [TableHeadSortable
       (conj props {:keyword :name})
       "Name"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "87 Price"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "91 Price"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "1 Hour Fee"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "3 Hour fee"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Service Starts"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Service Ends"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Zip Codes"]]]))

(defn zone-row
  "A table row for a zone."
  [current-zone]
  (fn [zone]
    (let [])
    [:tr {:class (when (= (:id zone)
                          (:id @current-zone))
                   "active")
          :on-click #(reset! current-zone zone)}
     ;; market
     [:td (-> zone
              :id
              (quot 50)
              markets)]
     ;; name
     [:td
      [:i {:class "fa fa-circle"
           :style {:color (:color zone)}}]
      (str " " (:name zone))]
     ;; 87 Price
     [:td (cents->$dollars (-> zone
                               :fuel_prices
                               :87))]
     ;; 91 Price
     [:td (cents->$dollars (-> zone
                               :fuel_prices
                               :91))]
     ;; 1 Hour Fee
     [:td (cents->$dollars (-> zone
                               :service_fees
                               :60))]
     ;; 3 Hour Fee
     [:td (cents->$dollars (-> zone
                               :service_fees
                               :180))]
     ;; Service Starts
     [:td (-> zone
              :service_time_bracket
              first)]
     ;; Service Ends
     [:td (-> zone
              :service_time_bracket
              second)]
     ;; Zip Codes
     [:td (:zip_codes zone)]]))

(defn zones-panel
  "Display a table of zones"
  [zones]
  (let [current-zone (r/cursor state [:current-zone])
        edit-zone (r/cursor state [:edit-zone])
        sort-keyword (r/atom :id)
        sort-reversed? (r/atom true)
        selected (r/cursor state [:selected])]
    (fn [zones]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-zones zones
            sorted-zones (->> displayed-zones
                              sort-fn)
            refresh-fn (fn [refreshing?]
                         (reset! refreshing? true)
                         (retrieve-url
                          (str base-url "zones")
                          "GET"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             ;; update the users atom
                             (put! datastore/modify-data-chan
                                   {:topic "zones"
                                    :data (js->clj response :keywordize-keys
                                                   true)})
                             (reset! refreshing? false)))))
            ]
        (if (nil? @current-zone)
          (reset! current-zone (first sorted-zones)))
        ;; set the edit-zone values to match those of current-zone
        (reset! edit-zone (assoc default-new-zone
                                 :price-87 (cents->dollars
                                            (-> @current-zone
                                                :fuel_prices
                                                :87))
                                 :price-91 (cents->dollars
                                            (-> @current-zone
                                                :fuel_prices
                                                :91))
                                 :service-fee-60 (cents->dollars
                                                  (-> @current-zone
                                                      :service_fees
                                                      :60))
                                 :service-fee-180 (cents->dollars
                                                   (-> @current-zone
                                                       :service_fees
                                                       :180))
                                 :service-time-bracket-begin
                                 (-> @current-zone
                                     :service_time_bracket
                                     first)
                                 :service-time-bracket-end
                                 (-> @current-zone
                                     :service_time_bracket
                                     second)
                                 :id
                                 (:id @current-zone)
                                 :alert-success (:alert-success
                                                 @current-zone)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:h2 [:i {:class "fa fa-circle"
                    :style {:color
                            (:color @current-zone)}}]
           (str " " (:name @current-zone))]
          [zone-form edit-zone
           [zone-form-submit edit-zone (edit-on-click edit-zone
                                                      current-zone)
            "Update"]]]
         [:div {:class "panel-body"}
          [:div [:h4 {:class "pull-left"} "Zones"]]
          [:div {:class "btn-toolbar"
                 :role "toolbar"
                 :aria-label "Toolbar with button groups"}
           [:div {:class "btn-group"
                  :role "group"
                  :aria-label "refresh group"}
            [RefreshButton {:refresh-fn
                            refresh-fn}]
            ]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [zone-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (zone-row current-zone)}
           sorted-zones]]
         ]))))
