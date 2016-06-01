(ns dashboard-cljs.zones
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [clojure.set :refer [subset?]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          parse-to-number? accessible-routes
                                          diff-message]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton TablePager
                                               FormGroup TextInput KeyVal
                                               EditFormSubmit DismissButton
                                               ConfirmationAlert AlertSuccess
                                               SubmitDismissConfirmGroup]]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [clojure.string :as s]))

(def default-zone
  {:errors nil
   :retrieving? false
   :editing? false})

(def state (r/atom {:current-zone nil
                    :confirming-edit? false
                    :alert-success ""
                    ;; :editing? false
                    :edit-zone default-zone
                    :selected "active"}))

(defn displayed-zone
  [zone]
  (assoc zone
         :price-87 (cents->dollars
                    (-> zone
                        :fuel_prices
                        :87))
         :price-91 (cents->dollars
                    (-> zone
                        :fuel_prices
                        :91))
         :service-fee-60 (cents->dollars
                          (-> zone
                              :service_fees
                              :60))
         :service-fee-180 (cents->dollars
                           (-> zone
                               :service_fees
                               :180))
         :service-time-bracket-begin
         (-> zone
             :service_time_bracket
             first
             str)
         :service-time-bracket-end
         (-> zone
             :service_time_bracket
             second
             str)))

(defn reset-edit-zone!
  [edit-zone current-zone]
  (reset! edit-zone
          (displayed-zone @current-zone)))

(defn zone->server-req
  [zone]
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
             service-time-bracket-end))))

(defn zone-form-submit
  "A submit button for the zone using on-click and label for
  the submit button"
  [zone on-click label]
  (fn []
    (let [retrieving? (r/cursor zone [:retrieving?])
          errors      (r/cursor zone [:errors])
          code        (r/cursor zone [:code])]

      [:div {:class "form-group"}
       [:div {:class "col-sm-2 control-label"}]
       [:div {:class "col-sm-10"}
        [:button {:type "submit"
                  :class "btn btn-default"
                  :on-click on-click}
         (if @retrieving?
           [:i {:class "fa fa-lg fa-refresh fa-pulse "}]
           label)]]])))

(defn zone-form
  "Form for a zone using submit-button"
  [zone]
  (let [
        edit-zone (r/cursor state [:edit-zone])
        
        price-87 (r/cursor edit-zone [:price-87])
        price-91 (r/cursor edit-zone [:price-91])
        service-fee-60 (r/cursor edit-zone [:service-fee-60])
        service-fee-180 (r/cursor edit-zone [:service-fee-180])
        service-time-bracket-begin (r/cursor edit-zone
                                             [:service-time-bracket-begin])
        service-time-bracket-end   (r/cursor edit-zone
                                             [:service-time-bracket-end])
        editing? (r/cursor edit-zone [:editing?])
        retrieving? (r/cursor edit-zone [:retrieving?])
        errors (r/cursor edit-zone [:errors])

        current-zone (r/cursor state [:current-zone])
        alert-success (r/cursor state [:alert-success])
        confirming?   (r/cursor state [:confirming-edit?])

        diff-key-str {:price-87 "87 Octane"
                      :price-91 "91 Octane"
                      :service-fee-60 "1 Hour Fee"
                      :service-fee-180 "3 Hour Fee"
                      :service-time-bracket-begin "Service Starts"
                      :service-time-bracket-end "Service Ends"}
        diff-msg-gen (fn [edit current] (diff-message edit
                                                      (displayed-zone current)
                                                      diff-key-str))]
    (fn [zone]
      (let [submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (if (every? nil? (diff-msg-gen @edit-zone
                                                               @current-zone))
                                  ;; there isn't a diff message, no changes
                                  ;; do nothing
                                  (reset! editing? (not @editing?))
                                  ;; there is a diff message, confirm changes
                                  (reset! confirming? true))
                                (do
                                  ;; get rid of alert-success
                                  (reset! alert-success "")
                                  (reset! editing? (not @editing?)))))
            dismiss-fn (fn [e]
                         ;; reset any errors
                         (reset! errors nil)
                         ;; no longer editing
                         (reset! editing? false)
                         ;; reset current zone
                         (reset-edit-zone! edit-zone current-zone)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:form {:class "form-horizontal"}
         (if @editing?
           [:div {:class "row"}
            [:div {:class "col-xs-2"}
             ;; 87 Price
             [FormGroup {:label-for "87 price"
                         :label "87 Octane"
                         :errors (:price-87 @errors)
                         :input-group-addon [:div {:class "input-group-addon"}
                                             "$"]}
              [TextInput {:value @price-87
                          :on-change #(reset! price-87 (-> %
                                                           (aget "target")
                                                           (aget "value")))}]]
             ;; 91 price
             [FormGroup {:label-for "91 price"
                         :label "91 Octane"
                         :errors (:price-91 @errors)
                         :input-group-addon [:div {:class "input-group-addon"}
                                             "$"]}
              [TextInput {:value @price-91
                          :on-change #(reset! price-91 (-> %
                                                           (aget "target")
                                                           (aget "value")))}]]
             ;; 1 hour fee
             [FormGroup {:label-for "1 Hour Fee"
                         :label "1 Hour Fee"
                         :errors  (:service-fee-60 @errors)
                         :input-group-addon [:div {:class "input-group-addon"}
                                             "$"]}
              [TextInput {:value @service-fee-60
                          :on-change #(reset! service-fee-60
                                              (-> %
                                                  (aget "target")
                                                  (aget "value")))}]]
             ;; 3 hour fee
             [FormGroup {:label-for "3 Hour Fee"
                         :label "3 Hour Fee"
                         :errors  (:service-fee-180 @errors)
                         :input-group-addon [:div {:class "input-group-addon"}
                                             "$"]}
              [TextInput {:value @service-fee-180
                          :on-change #(reset! service-fee-180
                                              (-> %
                                                  (aget "target")
                                                  (aget "value")))}]]
             ;; service starts
             [FormGroup {:label-for "Service Starts"
                         :label "service starts"
                         :errors (:service-time-bracket-begin @errors)}
              [TextInput {:value @service-time-bracket-begin
                          :on-change #(reset! service-time-bracket-begin
                                              (-> %
                                                  (aget "target")
                                                  (aget "value")))}]]
             ;; service ends
             [FormGroup {:label-for "Service Ends"
                         :label "service ends"
                         :errors (:service-time-bracket-end @errors)}
              [TextInput {:value @service-time-bracket-end
                          :on-change #(reset! service-time-bracket-end
                                              (-> %
                                                  (aget "target")
                                                  (aget "value")))}]]]]
           ;; not editing
           [:div
            ;;87 price
            [KeyVal "87 Octane" (-> @zone
                                    :fuel_prices
                                    :87
                                    (cents->$dollars))]
            ;; 91 price
            [KeyVal "91 Octane" (-> @zone
                                    :fuel_prices
                                    :91
                                    (cents->$dollars))]
            ;; 1 hour fee
            [KeyVal "1 Hour Fee" (-> @zone
                                     :service_fees
                                     :60
                                     (cents->$dollars))]
            ;; 3 hour fee
            [KeyVal "3 Hour Fee" (-> @zone
                                     :service_fees
                                     :180
                                     (cents->$dollars))]
            ;; service starts
            [KeyVal "Service Starts" (-> @zone
                                         :service_time_bracket
                                         first)]
            ;; service ends
            [KeyVal "Service Ends" (-> @zone
                                       :service_time_bracket
                                       second)]])
         ;; submit button
         [SubmitDismissConfirmGroup {:confirming? confirming?
                                     :editing? editing?
                                     :retrieving? retrieving?
                                     :submit-fn submit-on-click
                                     :dismiss-fn dismiss-fn}]
         (if (and @confirming?
                  (not-every? nil? (diff-msg-gen
                                    @edit-zone @current-zone)))
           [ConfirmationAlert
            {:confirmation-message
             (fn []
               [:div (str "The following changes will be made to "
                          (:name @current-zone))
                (map (fn [el]
                       ^{:key el}
                       [:h4 el])
                     (diff-msg-gen
                      @edit-zone @current-zone))])
             :cancel-on-click dismiss-fn
             :confirm-on-click (fn [_]
                                 (entity-save
                                  (zone->server-req @edit-zone)
                                  "zone"
                                  "PUT"
                                  retrieving?
                                  (edit-on-success "zone" edit-zone current-zone
                                                   alert-success
                                                   :aux-fn
                                                   #(reset! confirming? false))
                                  (edit-on-error edit-zone
                                                 :aux-fn
                                                 #(reset! confirming? false))))
             :retrieving? retrieving?}]
           (reset! confirming? false))
         (when-not (empty? @alert-success)
           [AlertSuccess {:message @alert-success
                          :dismiss #(reset! alert-success "")}])]))))

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
       (conj props {:keyword :id})
       "ID"]
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
    [:tr {:class (when (= (:id zone)
                          (:id @current-zone))
                   "active")
          :on-click (fn [_]
                      (reset! current-zone zone)
                      (reset! (r/cursor state [:alert-success]) ""))}
     ;; market
     [:td (-> zone
              :id
              (quot 50)
              markets)]
     ;; id
     [:td (-> zone
              :id)]
     ;; name
     [:td {:style {:min-width "13em"}}
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
        selected (r/cursor state [:selected])
        current-page (r/atom 1)
        page-size 15]
    (fn [zones]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-zones zones
            sorted-zones (fn []
                           (->> displayed-zones
                                sort-fn
                                (partition-all page-size)))
            paginated-zones (fn []
                              (-> (sorted-zones)
                                  (nth (- @current-page 1)
                                       '())))
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
            table-pager-on-click (fn []
                                   (reset! current-zone
                                           (first (paginated-zones))))]
        (if (nil? @current-zone)
          ;;(reset! current-zone (first paginated-zones))
          (table-pager-on-click))
        (reset-edit-zone! edit-zone current-zone)
        [:div {:class "panel panel-default"}
         (when (subset? #{{:uri "/zone"
                           :method "PUT"}}
                        @accessible-routes)
           [:div {:class "panel-body"}
            [:h2 [:i {:class "fa fa-circle"
                      :style {:color
                              (:color @current-zone)}}]
             (str " " (:name @current-zone))]
            [zone-form current-zone]])
         [:div {:class "panel-body"}
          [:div [:h3 {:class "pull-left"
                      :style {:margin-top "4px"
                              :margin-bottom 0}}
                 "Zones"]]
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group"
                  :role "group"}
            [RefreshButton {:refresh-fn
                            refresh-fn}]]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [zone-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (zone-row current-zone)}
           (paginated-zones)]]
         [TablePager
          {:total-pages (count (sorted-zones))
           :current-page current-page
           :on-click table-pager-on-click}]]))))
