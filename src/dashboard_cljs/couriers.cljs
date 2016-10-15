(ns dashboard-cljs.couriers
  (:require [cljs.core.async :refer [put!]]
            [cljsjs.moment]
            [clojure.set :refer [subset?]]
            [clojure.string :as s]
            [cljs.reader :refer [read-string]]
            [reagent.core :as r]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               TableFilterButtonGroup
                                               RefreshButton KeyVal StarRating
                                               ErrorComp TablePager
                                               TelephoneNumber
                                               Mailto
                                               FormGroup
                                               TextInput
                                               SubmitDismissConfirmGroup
                                               ConfirmationAlert
                                               AlertSuccess
                                               GoogleMapLink
                                               Tab
                                               TabContent
                                               Plotly
                                               Select
                                               ]]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [unix-epoch->fmt unix-epoch->hrf
                                          base-url accessible-routes pager-helper!
                                          diff-message now get-event-time
                                          select-toggle-key!]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [get-cached-gmaps gmap
                                               on-click-tab]]
            [dashboard-cljs.users :as users]))

(def default-courier {:editing? false
                      :retrieving? false
                      :zones ""
                      :errors nil
                      :active nil})

(def state (r/atom {:edit-courier default-courier
                    :current-courier nil
                    :confirimng-edit? false
                    :alert-success ""
                    :tab-content-toggle {}
                    :orders-data {:data {:hourly {:x ["2016-05-01 22:23:00"
                                                      "2016-05-01 12:20:00"
                                                      "2016-05-02 22:23:00"
                                                      "2016-05-02 10:00:00"
                                                      "2016-05-02 2:30:00"
                                                      "2016-05-03 22:23:00"]
                                                  :y [1 1 1 1 1 1]}
                                         :daily {:x ["2016-05-01"
                                                     "2016-05-02"
                                                     "2016-05-03"]
                                                 :y [2 3 1]}
                                         :weekly {:x ["2016-05-02"
                                                      "2016-04-02"]
                                                  :y [7 6]}}
                                  :layout {:yaxis {:title "Completed Orders"}
                                           :xaxis {:tickmode "auto"}}
                                  :config {:autosizable true
                                           :dismisslog false}
                                  :selected-timeframe "t0"}
                    :courier-orders-current-page 1}))
(defn zones->str
  "Convert a vector of zones into a comma-seperated string"
  [zones]
  (-> zones
      sort
      clj->js
      .join))

(defn displayed-courier
  [courier]
  (assoc courier
         :zones (zones->str (:zones courier))))

(defn reset-edit-courier!
  [edit-courier current-courier]
  (reset! edit-courier
          (displayed-courier @current-courier)))

(defn courier-row
  "A table row for a courier in a table. current-courier is an r/atom."
  [current-courier]
  (fn [courier]
    (let [courier-orders (fn [courier]
                           (->> @datastore/orders
                                (filter (fn [order]
                                          (= (:id courier)
                                             (:courier_id order))))))]
      [:tr {:class (when (= (:id courier)
                            (:id @current-courier))
                     "active")
            :on-click #(do
                         (reset! current-courier courier)
                         (reset! (r/cursor state [:alert-success]) "")
                         (when (<= (count (courier-orders courier))
                                   0)
                           (select-toggle-key!
                            (r/cursor state [:tab-content-toggle])
                            :info-view))
                         (reset! (r/cursor state [:courier-orders-current-page])
                                 1))}
       ;; name
       [:td (:name courier)]
       ;; market
       [:td (:name
             (first
              (filter #(and (= 100 (:rank %))
                            (contains? (set (:zones courier)) (:id %)))
                      @datastore/zones)))]
       ;; orders count
       [:td (->> @datastore/orders
                 (filter (fn [order] (= (:id courier)
                                        (:courier_id order))))
                 (filter (fn [order] (not (contains? #{"cancelled" "complete"}
                                                     (:status order)))))
                 count)]
       ;; phone
       [:td [TelephoneNumber (:phone_number courier)]]
       ;; last active
       ;; [:td (if-not (= (:last_ping courier) 0)
       ;;        (unix-epoch->fmt
       ;;         (:last_ping courier)
       ;;         "M/D/YYYY h:mm A")
       ;;        "Never")]
       ;; joined
       [:td (unix-epoch->fmt (:timestamp_created courier) "M/D/YYYY")]
       ;; os
       [:td (:os courier)]
       ;; app version
       [:td (:app_version courier)]
       ;; status
       [:td
        (let [connected? (and (:connected courier)
                              (:on_duty courier)
                              (:active courier))]
          [:div
           [:i {:class
                (str "fa fa-circle "
                     (if connected?
                       "courier-active"
                       "courier-inactive"
                       ))}]
           (if connected?
             " Online"
             " Offline")])]])))

(defn courier-table-header
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
       (conj props {:keyword :name
                    :style {:border-bottom "none"}})
       "Name"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"
                    :border-bottom "none"}} "Market"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"
                    :border-bottom "none"}} "Current Orders"]
      [TableHeadSortable
       (conj props {:keyword :phone_number
                    :style {:border-bottom "none"}})
       "Phone"]
      ;; [TableHeadSortable
      ;;  (conj props {:keyword :last_ping})
      ;;  "Last Active"]
      [TableHeadSortable
       (conj props {:keyword :timestamp_created
                    :style {:border-bottom "none"}})
       "Joined"]
      [TableHeadSortable
       (conj props {:keyword :os
                    :style {:border-bottom "none"}})
       "OS"]
      [TableHeadSortable
       (conj props {:keyword :app_version
                    :style {:border-bottom "none"}})
       "App Version"]
      [TableHeadSortable
       (conj props {:keyword :connected
                    :style {:border-bottom "none"}})
       "Status"]]]))

(defn courier-orders-header
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
       (conj props {:keyword :status})
       "Status"]
      [TableHeadSortable
       (conj props {:keyword :target_time_start})
       "Placed"]
      [TableHeadSortable
       (conj props {:keyword :target_time_end})
       "Deadline"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Completed"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Customer Name"]
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Customer Phone"]
      [TableHeadSortable
       (conj props {:keyword :address_street})
       "Order Address"]
      [TableHeadSortable
       (conj props {:keyword :number_rating})
       "Courier Rating"]]]))

(defn courier-orders-row
  "Table row to display a couriers orders"
  []
  (fn [order]
    [:tr
     ;; order status
     [:td (:status order)]
     ;; order placed
     [:td (unix-epoch->hrf (:target_time_start order))]
     ;; order dealine
     [:td {:style (when-not (contains? #{"complete" "cancelled"} (:status order))
                    (when (< (- (:target_time_end order)
                                (now))
                             (* 60 60))
                      {:color "#d9534f"}))}
      (unix-epoch->hrf (:target_time_end order)) " "
      (when (:tire_pressure_check order)
        ;; http://www.flaticon.com/free-icon/car-wheel_75660#term=wheel&page=1&position=34
        [:img {:src (str base-url "/images/car-wheel.png")
               :alt "tire-check"}])]
     ;; order completed
     [:td
      (when (contains? #{"complete"} (:status order))
        (let [completed-time
              (get-event-time (:event_log order) "complete")]
          [:span {:style
                  (when (> completed-time
                           (:target_time_end order)) {:color "#d9534f"})}
           (unix-epoch->hrf completed-time)]))
      (when (contains? #{"cancelled"} (:status order))
        "Cancelled")
      (when-not (contains? #{"complete" "cancelled"} (:status order))
        "In-Progress")]
     ;; username
     [:td {:style (when-not (= 0 (:subscription_id order))
                    {:color "#5cb85c"})}
      (:customer_name order)]
     ;; phone #
     [:td [TelephoneNumber (:customer_phone_number order)]]
     ;; street address
     [:td [GoogleMapLink (str (:address_street order)
                              ", " (:address_zip order))
           (:lat order) (:lng order)]]
     ;; star rating
     [:td
      (let [number-rating (:number_rating order)]
        (when number-rating
          [StarRating number-rating]))]]))

(defn courier-form
  "Form for editing a courier"
  [courier]
  (let [edit-courier (r/cursor state [:edit-courier])
        current-courier (r/cursor state [:current-courier])
        confirming?     (r/cursor state [:confirming-edit?])
        retrieving?     (r/cursor edit-courier [:retrieving?])
        editing?      (r/cursor edit-courier [:editing?])
        alert-success (r/cursor state [:alert-success])
        errors (r/cursor edit-courier [:errors])
        zones (r/cursor edit-courier [:zones])
        active? (r/cursor edit-courier [:active])
        zones->str (fn [zones] (-> zones
                                   sort
                                   clj->js
                                   .join))
        diff-key-str {:zones "Assigned Zones"
                      :active "Active?"}
        diff-msg-gen (fn [edit current] (diff-message
                                         edit
                                         (displayed-courier current)
                                         diff-key-str))
        submit-on-click (fn [e]
                          (.preventDefault e)
                          (if @editing?
                            (if (every? nil?
                                        (diff-msg-gen @edit-courier
                                                      @current-courier))
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
                     ;; reset current user
                     (reset-edit-courier! edit-courier current-courier)
                     ;; reset confirming
                     (reset! confirming? false))]
    (fn [courier]
      [:form {:class "form-horizontal"}
       ;; email
       [KeyVal "ID" (:id @current-courier)]
       [KeyVal "Email" [Mailto (:email @current-courier)]]
       ;; phone number
       [KeyVal "Phone Number" [TelephoneNumber
                               (:phone_number @current-courier)]]
       ;; date started
       [KeyVal "Date Started" (unix-epoch->fmt
                               (:timestamp_created @current-courier)
                               "M/D/YYYY")]
       ;; last seen (last ping)
       [KeyVal "Last Seen" (unix-epoch->fmt
                            (:last_ping @current-courier)
                            "M/D/YYYY h:mm A")]
       (if @editing?
         [:div
          ;; active?
          [FormGroup {:label (str "Active ")
                      :label-for "courier is active?"}
           [:input {:type "checkbox"
                    :checked @active?
                    :style {:margin-left "4px"}
                    :on-change #(reset!
                                 active?
                                 (-> %
                                     (aget "target")
                                     (aget "checked")))}]]
          ;; courier zones
          [FormGroup {:label "Assigned Zones"
                      :label-for "courier zones"
                      :errors (:zones @errors)}
           [TextInput {:value @zones
                       :default-value @zones
                       :on-change (fn [e]
                                    (reset!
                                     zones
                                     (-> e
                                         (aget "target")
                                         (aget "value"))))}]]]
         [:div
          [KeyVal "Active" (if (:active @current-courier)
                             "Yes"
                             "No")]
          [KeyVal "Assigned Zones" (if (not (empty? (:zones @current-courier)))
                                     (zones->str (:zones @current-courier))
                                     "None Assigned")]])
       (when (subset? #{{:uri "/courier"
                         :method "PUT"}}
                      @accessible-routes)
         [SubmitDismissConfirmGroup
          {:confirming? confirming?
           :editing? editing?
           :retrieving? retrieving?
           :submit-fn submit-on-click
           :dismiss-fn dismiss-fn}])
       (when (subset? #{{:uri "/courier"
                         :method "PUT"}}
                      @accessible-routes)
         (if (and @confirming?
                  (not-every? nil?
                              (diff-msg-gen @edit-courier @current-courier)))
           [ConfirmationAlert
            {:confirmation-message
             (fn []
               [:div (str "Do you want to make the following changes to "
                          (:name @current-courier) "?")
                (map (fn [el]
                       ^{:key el}
                       [:h4 el])
                     (diff-msg-gen @edit-courier @current-courier))])
             :cancel-on-click dismiss-fn
             :confirm-on-click
             (fn [_]
               (entity-save
                ;; this needs changed
                @edit-courier
                "courier"
                "PUT"
                retrieving?
                (edit-on-success "courier" edit-courier current-courier
                                 alert-success
                                 :aux-fn
                                 #(reset! confirming? false))
                (edit-on-error edit-courier
                               :aux-fn
                               #(reset! confirming? false))))
             :retrieving? retrieving?}]
           (reset! confirming? false)))
       ;; success alert
       (when-not (empty? @alert-success)
         [AlertSuccess {:message @alert-success
                        :dismiss #(reset! alert-success "")}])])))
(defn orders-per-courier
  [props]
  (let [id-datatype {"t0" :hourly
                     "t1" :daily
                     "t2" :weekly}
        selected-timeframe (r/cursor state [:orders-data :selected-timeframe])]
    (fn [props]
      [:div {:class "row"}
       [:div {:class "col-lg-12"}
        [:h2 "Completed Orders per "
         [Select {:value selected-timeframe
                  :options #{{:id "t0" :time "Hour"}
                             {:id "t1" :time "Day"}
                             {:id "t2" :time "Week"}}
                  :display-key :time
                  :sort-keyword :id}]]
        [Plotly {:data [(merge @(r/cursor state
                                          [:orders-data :data
                                           (id-datatype @selected-timeframe)])
                               {:type "scatter"})]
                 :layout @(r/cursor state [:orders-data :layout])
                 :config @(r/cursor state [:orders-data :config])}]]])))

(defn courier-panel
  "Display detailed and editable fields for an courier. current-courier is an
  r/atom"
  [current-courier]
  (let [google-marker (atom nil)
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        current-page (r/cursor state [:courier-orders-current-page])
        page-size 5
        toggle (r/cursor state [:tab-content-toggle])
        orders-view-toggle? (r/cursor toggle [:orders-view])
        push-view-toggle? (r/cursor toggle [:push-view])]
    (fn [current-courier]
      (let [editing-zones? (r/atom false)
            zones-error-message (r/atom "")
            zones-input-value (r/atom (-> (:zones @current-courier)
                                          sort
                                          clj->js
                                          .join))
            sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            
            orders
            ;; filter out the orders to only those assigned
            ;; to the courier
            (->> @datastore/orders
                 (filter (fn [order]
                           (= (:id @current-courier)
                              (:courier_id order)))))
            sorted-orders (->> orders
                               sort-fn
                               (partition-all page-size))
            paginated-orders (pager-helper! sorted-orders current-page)
            show-orders? (fn []
                           (and (subset? #{{:uri "/orders-since-date"
                                            :method "POST"}}
                                         @accessible-routes)
                                (> (count paginated-orders) 0)))]
        ;; create and insert courier marker
        (when (:lat @current-courier)
          (when @google-marker
            (.setMap @google-marker nil))
          (reset! google-marker (js/google.maps.Marker.
                                 (clj->js {:position
                                           {:lat (:lat @current-courier)
                                            :lng (:lng @current-courier)}
                                           :map (second (get-cached-gmaps
                                                         :couriers))
                                           }))))
        ;; Make sure that orders view is not selected when a courier has no orders
        (when (and (<= (count paginated-orders)
                       0)
                   @orders-view-toggle?)
          (select-toggle-key! toggle :info-view))
        ;; Make sure that push-view is not selected when a courier has push
        ;; notifications turned off
        (when (and (s/blank? (:arn_endpoint @current-courier))
                   @push-view-toggle?)
          (select-toggle-key! toggle :info-view))
        ;; populate the current courier with additional information
        [:div {:class "panel-body"}
         [:div {:class "row"}
          [:div {:class "col-xs-12 col-lg-12 pull-left"}
           [:div [:h3 {:style {:margin-top 0}} (:name @current-courier)]]
           ;; courier info tab navigation
           [:ul {:class "nav nav-tabs"}
            [Tab {:default? true
                  :toggle-key :info-view
                  :toggle toggle
                  :on-click-tab on-click-tab}
             "Info"]
            (when (> (count paginated-orders)
                     0)
              [Tab {:default? false
                    :toggle-key :orders-view
                    :toggle toggle}
               "Orders"])
            (when (and (not (s/blank? (:arn_endpoint @current-courier)))
                       (subset? #{{:uri "/send-push-to-user"
                                   :method "POST"}}
                                @accessible-routes))
              [Tab {:default? false
                    :toggle-key :push-view
                    :toggle toggle}
               "Push Notification"])
            ;; [Tab {:default? true
            ;;       :toggle-key :history-view
            ;;       :toggle toggle}
            ;;  "History"]
            ]
           ;; main display panel
           [:div {:class "tab-content"}
            [TabContent
             {:toggle (r/cursor toggle [:info-view])}
             [:div {:class "panel-body"}
              [:div {:class "row"}
               [:div {:class "col-xs-12 col-lg-12"}
                [:div {:class "row"}
                 [:div {:class "col-xs-12 col-lg-3"}
                  [courier-form current-courier]]
                 [:div {:class "col-xs-12 col-lg-3"}
                  [gmap {:id :couriers
                         :style {:height "300px"
                                 :margin "10px"}
                         :center {:lat (:lat @current-courier)
                                  :lng (:lng @current-courier)}}]]]]]]]
            [TabContent {:toggle (r/cursor toggle [:push-view])}
             [:div {:class "row"}
              [:div {:class "col-lg-6 col-xs-12"}
               [users/user-push-notification @current-courier]]]]
            [TabContent
             {:toggle (r/cursor toggle [:orders-view])}
             [:div {:class "row"}
              [:div {:class "col-lg-12 col-xs-12"}
               [:div {:class "table-responsive"}
                [StaticTable
                 {:table-header [courier-orders-header
                                 {:sort-keyword sort-keyword
                                  :sort-reversed? sort-reversed?}]
                  :table-row (courier-orders-row)}
                 paginated-orders]]
               [TablePager
                {:total-pages (count sorted-orders )
                 :current-page current-page}]]]]
            ;; [TabContent
            ;;  {:toggle (r/cursor toggle [:history-view])}
            ;;  [orders-per-courier]]
            ]]]]))))

(defn couriers-panel
  "Display a table of selectable couriers with an indivdual courier panel
  for the selected courier. couriers is set of couriers."
  [couriers]
  (let [current-courier (r/cursor state [:current-courier])
        edit-courier (r/cursor state [:edit-courier])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        page-size 15
        filters {"Active" {:filter-fn :active}
                 "Deactivated" {:filter-fn #(not (:active %))}
                 "Online" {:filter-fn #(and (:connected %)
                                            (:on_duty %)
                                            (:active %))}}
        selected-filter (r/atom "Online")]
    (fn [couriers]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-couriers couriers
            sorted-couriers (fn []
                              (->> displayed-couriers
                                   sort-fn
                                   (filter
                                    (:filter-fn (get filters @selected-filter)))
                                   (partition-all page-size)))
            paginated-couriers (fn []
                                 (-> (sorted-couriers)
                                     (nth (- @current-page 1)
                                          '())))
            refresh-fn (fn [saving?]
                         (reset! saving? true)
                         (retrieve-url
                          (str base-url "couriers")
                          "POST"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             (let [couriers (:couriers
                                             (js->clj
                                              response
                                              :keywordize-keys true))]
                               ;; update the couriers atom
                               (put! datastore/modify-data-chan
                                     {:topic "couriers"
                                      :data  couriers})
                               (reset! saving? false))))))
            table-pager-on-click (fn []
                                   (reset! current-courier
                                           (first (paginated-couriers))))]
        ;; reset the current-courier if it is nil
        (when (nil? @current-courier)
          (table-pager-on-click))
        (reset-edit-courier! edit-courier current-courier)
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div {:class "col-lg-12"}
           [courier-panel current-courier]]
          [:div {:class "col-lg-12"
                 :style {:margin-top "1em"}}
           [:div {:class "btn-toolbar pull-left"
                  :role "toolbar"}
            [TableFilterButtonGroup {:hide-counts #{}
                                     :on-click (fn [_]
                                                 (reset! current-page 1)
                                                 (table-pager-on-click))
                                     :filters filters
                                     :data couriers
                                     :selected-filter selected-filter}]]
           [:div {:class "btn-toolbar"
                  :role "toolbar"}
            [:div {:class "btn-group"
                   :role "group"}
             [RefreshButton {:refresh-fn refresh-fn}]]]]
          [:div {:class "col-lg-12"}
           [:div {:class "table-responsive"}
            [StaticTable
             {:table-header [courier-table-header
                             {:sort-keyword sort-keyword
                              :sort-reversed? sort-reversed?}]
              :table-row (courier-row current-courier)}
             (paginated-couriers)]]]
          [TablePager
           {:total-pages (count (sorted-couriers))
            :current-page current-page
            :on-click table-pager-on-click}]]]))))
