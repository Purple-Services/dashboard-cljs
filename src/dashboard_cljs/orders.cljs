(ns dashboard-cljs.orders
  (:require [cljs.core.async :refer [put!]]
            [cljsjs.moment]
            [clojure.string :as s]
            [clojure.set :refer [subset?]]
            [reagent.core :as r]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton ErrorComp
                                               TablePager ConfirmationAlert]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [unix-epoch->hrf base-url
                                          cents->$dollars json-string->clj
                                          accessible-routes
                                          new-orders-count
                                          parse-timestamp
                                          oldest-current-order
                                          same-timestamp?]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [gmap get-cached-gmaps]]))

(def status->next-status {"unassigned"  "assigned"
                          "assigned"    "accepted"
                          "accepted"    "enroute"
                          "enroute"     "servicing"
                          "servicing"   "complete"
                          "complete"    nil
                          "cancelled"   nil})
(defn EditButton
  "Button for toggling editing? button"
  [editing?]
  (fn [editing?]
    [:button {:type "button"
              :class (str "btn btn-sm btn-default pull-right")
              :on-click #(swap! editing? not)
              }
     (if @editing?
       "Save"
       "Edit")]))

(defn order-row
  "A table row for an order in a table. current-order is the one currently being
  viewed"
  [current-order]
  (fn [order]
    [:tr {:class (when (= (:id order)
                          (:id @current-order))
                   "active")
          :on-click #(reset! current-order order)}
     ;; order status
     [:td (:status order)]
     ;; courier assigned
     [:td (:courier_name order)]
     ;; order placed
     [:td (unix-epoch->hrf (:target_time_start order))]
     ;; order dealine
     [:td (unix-epoch->hrf (:target_time_end order))]
     ;; delivery time (TODO: this should show minutes if non-zero)
     [:td (str (.diff (js/moment.unix (:target_time_end order))
                      (js/moment.unix (:target_time_start order))
                      "hours")
               " Hr")]
     ;; username
     [:td (:customer_name order)]
     ;; phone #
     [:td (:customer_phone_number order)]
     ;; street address
     [:td
      [:i {:class "fa fa-circle"
           :style {:color (:zone-color order)}}]
      " "
      (:address_street order)]]))

(defn order-table-header
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
       (conj props {:keyword :courier_name})
       "Courier"
       ]
      [TableHeadSortable
       (conj props {:keyword :target_time_start})
       "Placed"]
      [TableHeadSortable
       (conj props {:keyword :target_time_end})
       "Deadline"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Limit"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Name"] 
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Phone"]
      [TableHeadSortable
       (conj props {:keyword :address_street})
       "Street Address"]]]))

(defn order-courier-select
  "Select for assigning a courier
  props is:
  {
  :select-courier ; reagent atom, id of currently selected courier
  :couriers       ; set of courier maps
  }"
  [props]
  (fn [{:keys [selected-courier couriers]} props]
    [:select
     {:value (if @selected-courier
               @selected-courier
               (:id (first couriers)))
      :on-change
      #(do (reset! selected-courier
                   (-> %
                       (aget "target")
                       (aget "value"))))}
     (map
      (fn [courier]
        ^{:key (:id courier)}
        [:option
         {:value (:id courier)}
         (:name courier)])
      couriers)]))

(defn assign-courier
  "Assign order to selected-courier from the list of couriers. error
  is an r/atom that contains any error associated with this call. editing?
  is an r/atom that sets whether or not the order is being edited. retrieving?
  is an r/atom boolean for determing if data is being retrieved."
  [editing? retrieving? order selected-courier couriers error]
  (retrieve-url
   (str base-url "assign-order")
   "POST"
   (js/JSON.stringify (clj->js {:order_id (:id @order)
                                :courier_id @selected-courier}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)]
                (reset! retrieving? false)
                (when (:success response)
                  (let [order-status (if (= (:status @order)
                                            "unassigned")
                                       "assigned"
                                       (:status @order))
                        
                        updated-order  (assoc
                                        @order
                                        :courier_id @selected-courier
                                        :courier_name
                                        (:name
                                         (first (filter (fn [courier]
                                                          (= @selected-courier
                                                             (:id courier)))
                                                        couriers)))
                                        :status order-status)]
                    (reset! order updated-order)
                    (reset! editing? false)
                    (reset! error "")
                    (put! datastore/modify-data-chan
                          {:topic "orders"
                           :data
                           #{updated-order}})))
                (when (not (:success response))
                  (reset! error (:message response))
                  ))))))

(defn order-courier-comp
  "Component for the courier field of an order panel
  props is:
  {
  :editing?         ; ratom, is the field currently being edited?
  :assigned-courier ; id of the courier who is currently assigned to the order
  :couriers         ; set of courier maps
  :order            ; ratom, currently selected order
  }
  "
  [props]
  (let [error-message (r/atom "")
        retrieving? (r/atom false)
        ]
    (fn [{:keys [editing? assigned-courier couriers order]}
         props]
      (let [selected-courier (r/atom assigned-courier)]
        [:h5 [:span {:class "info-window-label"} "Courier: "]
         ;; courier assigned (if any)
         (when (not @editing?)
           [:span (str (if ((comp not nil?)
                            (:courier_name @order))
                         (:courier_name @order)
                         "none assigned") " ")])
         ;; assign courier button
         (when (and (not @editing?)
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           [:button {:type "button"
                     :class "btn btn-xs btn-default"
                     :on-click #(reset! editing? true)}
            (if (nil? (:courier_name @order))
              "Assign Courier"
              "Reassign Courier")])
         ;; courier select
         (when (and @editing?
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           [order-courier-select {:selected-courier
                                  selected-courier
                                  :couriers couriers}])
         ;; save assignment
         " "
         (when (and @editing?
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           [:button {:type "button"
                     :class "btn btn-xs btn-default"
                     :on-click
                     #(when (not @retrieving?)
                        (reset! retrieving? true)
                        (assign-courier editing? retrieving? order
                                        selected-courier couriers
                                        error-message))
                     }
            (if @retrieving?
              [:i {:class "fa fa-spinner fa-pulse"}]
              "Save assignment")
            ])
         (when (not (s/blank? @error-message))
           [ErrorComp (str "Courier could not be assigned! Reason: "
                           @error-message
                           "\n"
                           "Try saving the assignment again"
                           )])
         ]))))


(defn refresh-order!
  "Given an order, retrieve its information from the server. Call
  order-fn on the resulting new-order if there is one."
  [order order-fn]
  (retrieve-url
   (str base-url "order")
   "POST"
   (js/JSON.stringify
    (clj->js
     {:id (:id @order)}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys
                                      true)
                    new-order (first response)]
                (when new-order
                  (order-fn new-order)
                  ))))))

(defn update-status
  "Update order with status on server. error-message is an r/atom to set
  any associated error messages. retrieving? is an r/atom with a boolean
  to indicate whether or not the client is currently retrieving data
  from the server. confirming? is a r/atom boolean"
  [order status error-message retrieving? confirming?]
  (retrieve-url
   (str base-url "update-status")
   "POST"
   (js/JSON.stringify (clj->js {:order_id (:id @order)}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)]
                (when (:success response)
                  (refresh-order! order
                                  (fn [new-order]
                                    ;; reset the order
                                    (reset! order new-order)
                                    ;; reset the order in the table
                                    (put! datastore/modify-data-chan
                                          {:topic "orders"
                                           :data #{new-order}})
                                    ;; no longer retrieving
                                    (reset! retrieving? false)
                                    ;; no longer confirming
                                    (reset! confirming? false)
                                    )))
                (when (not (:success response))
                  ;; give an error message
                  (reset! error-message (:message response))
                  ;; no longer retrieving
                  (reset! retrieving? false)
                  ;; no longer confirming
                  (reset! confirming? false)))))))

(defn cancel-order
  "Cancel order on the server. Any resulting error messages
  will be put in the error-message atom. retrieving? is a boolean
  r/atom. confirming? is a boolean r/atom."
  [order error-message retrieving? confirming?]
  (retrieve-url
   (str base-url "cancel-order")
   "POST"
   (js/JSON.stringify (clj->js {:user_id (:user_id @order)
                                :order_id (:id @order)}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)]
                (reset! retrieving? false)
                (reset! confirming? false)
                (when (:success response)
                  (let [updated-order
                        (assoc
                         @order
                         :status "cancelled")]
                    (reset! order updated-order)
                    (reset! error-message "")
                    (put! datastore/modify-data-chan
                          {:topic "orders"
                           :data #{updated-order}})))
                (when (not (:success response))
                  (reset! error-message (:message response))))))))

(defn status-comp
  "Component for the status field of an order
  props is:
  {
  :editing?        ; ratom, is the field currently being edited?
  :status          ; string, the status of the order
  :order           ; ratom, currently selected order
  }"
  [props]
  (let [error-message (r/atom "")
        retrieving? (r/atom false)
        confirming? (r/atom false)
        confirm-action (r/atom "")
        confirm-on-click (r/atom false)]
    (fn [{:keys [editing? status order]}
         props]
      [:h5 [:span {:class "info-window-label"} "Status: "]
       (str status " ")
       (if @confirming?
         ;; confirmation
         [ConfirmationAlert
          {:cancel-on-click (fn [e]
                              (reset! confirming? false))
           :confirm-on-click (fn [e]
                               (reset! retrieving? true)
                               (when (= @confirm-action "advance")
                                 (update-status order status error-message
                                                retrieving?
                                                confirming?))
                               (when (= @confirm-action "cancel")
                                 (cancel-order order error-message
                                               retrieving?
                                               confirming?)))
           :confirmation-message
           (fn [] [:div (str "Are you sure you want to "
                             (cond (= @confirm-action "advance")
                                   (str " advance this order's status to "
                                        (status->next-status status) "?")
                                   (= @confirm-action "cancel")
                                   (str "cancel this order?")))
                   [:br]])
           :retrieving? retrieving?}]
         [:div {:style {:display "inline-block"}}
          ;; advance order button
          (when-not (contains? #{"complete" "cancelled" "unassigned"}
                               status)
            [:button {:type "button"
                      :class "btn btn-xs btn-default"
                      :on-click
                      #(when (not @retrieving?)
                         (reset! confirming? true)
                         (reset! confirm-action "advance"))}
             (if (not @retrieving?)
               ({"assigned" "Force Accept"
                 "accepted" "Start Route"
                 "enroute" "Begin Servicing"
                 "servicing" "Complete Order"}
                status)
               [:i {:class "fa fa-spinner fa-pulse"}])])
          " "
          ;; cancel button
          (when
              (and (not @editing?)
                   (not (contains? #{"complete" "cancelled"}
                                   status))
                   (subset? #{{:uri "/cancel-order"
                               :method "POST"}}
                            @accessible-routes))
            [:button {:type "button"
                      :class "btn btn-xs btn-default btn-danger"
                      :on-click #(when (not @retrieving?)
                                   (reset! confirming? true)
                                   (reset! confirm-action "cancel"))}
             (if @retrieving?
               [:i {:class "fa fa-spinner fa-pulse"}]
               "Cancel Order")])])
       (when (not (s/blank? @error-message))
         [ErrorComp (str "Order status could be not be changed! Reason: "
                         @error-message)])])))

(defn get-etas-button
  "Given an order atom, refresh it with values from the server"
  [order]
  (let [retrieving? (r/atom false)]
    (fn [order]
      (let [get-etas (fn [order]
                       (retrieve-url
                        (str base-url "order")
                        "POST"
                        (js/JSON.stringify
                         (clj->js
                          {:id (:id @order)}))
                        (partial xhrio-wrapper
                                 (fn [r]
                                   (let [response (js->clj r :keywordize-keys
                                                           true)
                                         new-order (first response)]
                                     (reset! retrieving? false)
                                     (when new-order
                                       (reset! order new-order)))))))]
        [:button {:type "button"
                  :class "btn btn-default btn-xs"
                  :on-click #(when (not @retrieving?)
                               (reset! retrieving? true)
                               (get-etas order))}
         (if @retrieving?
           [:i {:class "fa fa-lg fa-spinner fa-pulse "}]
           "Get ETAs")]
        ))))

(defn order-panel
  "Display detailed and editable fields for current-order"
  [current-order]
  (let [google-marker (atom nil)]
    (fn [current-order]
      (let [editing-assignment? (r/atom false)
            editing-status?     (r/atom false)
            couriers
            ;; filter out the couriers to only those assigned
            ;; to the zone
            (sort-by :name (filter #(contains? (set (:zones %))
                                               (:zone @current-order))
                                   @datastore/couriers))
            assigned-courier (if (not (nil? (:courier_name @current-order)))
                               ;; there is a courier currently assigned
                               (:id (first (filter #(= (:courier_name
                                                        @current-order)
                                                       (:name % )) couriers)))
                               ;; no courier, assign the first one
                               (:id (first couriers)))
            order-status (:status @current-order)
            ]
        ;; create and insert order marker
        (when (:lat @current-order)
          (when @google-marker
            (.setMap @google-marker nil))
          (reset! google-marker (js/google.maps.Marker.
                                 (clj->js {:position
                                           {:lat (:lat @current-order)
                                            :lng (:lng @current-order)
                                            }
                                           :map (second (get-cached-gmaps :orders))
                                           }))))
        ;; populate the current order with additional information
        [:div {:class "panel-body"}
         [:div {:class "row"}
          [:div {:class "col-xs-6 pull-left"}
           [:h3 {:style {:margin-top 0}} "Order Details"]
           ;; order price
           [:h5 [:span {:class "info-window-label"} "Total Price: "]
            (cents->$dollars (:total_price @current-order))
            " "
            ;; declined payment?
            (if (and (= (:status @current-order)
                        "complete")
                     (not= 0 (:total_price @current-order))
                     (or (s/blank? (:stripe_charge_id @current-order))
                         (not (:paid @current-order))))
              [:span {:class "text-danger"} "Payment declined!"])]
           ;; payment info
           (let [payment-info (json-string->clj (:payment_info @current-order))]
             (when (not (nil? payment-info))
               [:h5 [:span {:class "info-window-label"} "Payment Info: "]
                (:brand payment-info)
                " "
                (:last4 payment-info)
                " "
                (:exp_month payment-info) "/" (:exp_year payment-info)
                ]))
           ;; coupon code
           (when (not (s/blank? (:coupon_code @current-order)))
             [:h5 [:span {:class "info-window-label"} "Coupon: "]
              (:coupon_code @current-order)])
           ;; gallons and type
           [:h5 [:span {:class "info-window-label"} "Gallons: "]
            (str (:gallons @current-order) " (" (:gas_type @current-order)
                 " Octane)")]
           ;; time order was placed
           [:h5 [:span {:class "info-window-label"} "Order Placed: "]
            (unix-epoch->hrf (:target_time_start @current-order))]
           ;; delivery time
           [:h5 [:span {:class "info-window-label"} "Delivery Time: "]
            (str (.diff (js/moment.unix (:target_time_end @current-order))
                        (js/moment.unix (:target_time_start @current-order))
                        "hours")
                 " Hr")]
           ;; special instructions field
           (when (not (s/blank? (:special_instructions @current-order)))
             [:h5 [:span {:class "info-window-label"} "Special Instructions: "]
              (:special_instructions @current-order)])
           ;;  name
           [:h5 [:span {:class "info-window-label"} "Customer: "]
            (:customer_name @current-order)]
           ;;  phone number
           [:h5 [:span {:class "info-window-label"} "Phone: "]
            (:customer_phone_number @current-order)]
           ;;  email
           [:h5 [:span {:class "info-window-label"} "Email: "]
            (:email @current-order)]
           ;; rating
           (let [number-rating (:number_rating @current-order)]
             (when number-rating
               [:h5 [:span {:class "info-window-label"} "Rating: "]
                (for [x (range number-rating)]
                  ^{:key x} [:i {:class "fa fa-star fa-lg"}])
                (for [x (range (- 5 number-rating))]
                  ^{:key x} [:i {:class "fa fa-star-o fa-lg"}])
                ]))
           ;; review
           (when (not (s/blank? (:text_rating @current-order)))
             [:h5 [:span {:class "info-window-label"} "Review: "]
              (:text_rating @current-order)])
           ;; delivery address
           [:h5
            [:span {:class "info-window-label"} "Address: "]
            [:i {:class "fa fa-circle"
                 :style {:color (:zone-color @current-order)}}]
            " "
            (:address_street @current-order)]
           ;; vehicle description
           [:h5
            [:span {:class "info-window-label"} "Vehicle: "]
            (str (:color (:vehicle @current-order))
                 " "
                 (:make (:vehicle @current-order))
                 " "
                 (:model (:vehicle @current-order)))]
           ;; license plate
           [:h5 [:span {:class "info-window-label"} "License Plate: "]
            (:license_plate @current-order)]
           ;; ETAs
           ;; note: the server only populates ETA values when the orders
           ;; are: "unassigned" "assigned" "accepted" "enroute"
           (when (contains? #{"unassigned" "assigned" "accepted" "enroute"}
                            (:status @current-order))
             [:h5
              [:span {:class "info-window-label"} "ETAs: "]
              ;; get etas button
              [get-etas-button current-order]
              (when (:etas @current-order)
                (map (fn [eta]
                       ^{:key (:name eta)}
                       [:p (str (:name eta) " - ")
                        [:strong {:class (when (:busy eta)
                                           "text-danger")}
                         (:minutes eta)]])
                     (sort-by :minutes (:etas @current-order))))])
           ;; assigned courier display and editing
           [order-courier-comp {:editing? editing-assignment?
                                :assigned-courier assigned-courier
                                :couriers couriers
                                :order current-order}]
           ;; status and editing
           [status-comp {:editing? editing-status?
                         :status order-status
                         :order current-order}]]
          [:div {:class "pull-right hidden-xs"}
           [gmap {:id :orders
                  :style {:height 300
                          :width 300}
                  :center {:lat (:lat @current-order)
                           :lng (:lng @current-order)}}]]]]))))

(defn orders-filter
  "A component for determing which orders to display. selected-filter is
  an r/atom containing a string which describes what filter to use."
  [selected-filter]
  (fn [selected-filter]
    [:div {:class "btn-group"
           :role "group"
           :aria-label "filter group"}
     [:button {:type "button"
               :class
               (str "btn btn-default "
                    (when (= @selected-filter
                             "show-all")
                      "active"))
               :on-click #(reset! selected-filter "show-all")}
      "Show All"]
     [:button {:type "button"
               :class
               (str "btn btn-default "
                    (when (= @selected-filter
                             "current")
                      "active"))
               :on-click #(reset! selected-filter "current")}
      "Current Orders"]
     [:button {:type "button"
               :class
               (str "btn btn-default "
                    (when (= @selected-filter
                             "declined")
                      "active"))
               :on-click #(reset! selected-filter "declined")}
      "Declined Payments"]]))

(defn new-orders-button
  "A component for allowing the user to see new orders on the server"
  []
  (fn []
    [:button {:type "button"
              :class "btn btn-default"
              :on-click
              #(reset! datastore/last-acknowledged-order
                       @datastore/most-recent-order)}
     (str "View " (new-orders-count @datastore/orders
                                    @datastore/last-acknowledged-order)
          " New Orders")]))


(defn orders-panel
  "Display a table of selectable orders with an indivdual order panel
  for the selected order"
  [orders]
  (let [current-order (r/atom nil)
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        selected-filter (r/atom "show-all")
        current-page (r/atom 1)
        page-size 20]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (cond (= @selected-filter
                               "declined")
                            (fn [order]
                              (and (not (:paid order))
                                   (= (:status order) "complete")
                                   (> (:total_price order))))
                            (= @selected-filter
                               "current")
                            (fn [order]
                              (contains? #{"unassigned"
                                           "assigned"
                                           "accepted"
                                           "enroute"
                                           "servicing"} (:status order)))
                            :else (fn [order] true))
            displayed-orders (filter #(<= (:target_time_start %)
                                          (:target_time_start
                                           @datastore/last-acknowledged-order))
                                     orders)
            sorted-orders  (->> displayed-orders
                                sort-fn
                                (filter filter-fn)
                                (partition-all page-size))
            paginated-orders  (-> sorted-orders
                                  (nth (- @current-page 1)
                                       '()))
            refresh-fn (fn [saving?]
                         (reset! saving? true)
                         (retrieve-url
                          (str base-url "orders-since-date")
                          "POST"
                          (js/JSON.stringify
                           (clj->js
                            ;; just update current orders
                            {:date (parse-timestamp (:timestamp_created
                                                     (oldest-current-order
                                                      @datastore/orders)))
                             :unix-epoch? true}))
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             (let [orders (js->clj
                                           response
                                           :keywordize-keys true)]
                               (when (> (count orders)
                                        0)
                                 ;; update the orders atom
                                 (put! datastore/modify-data-chan
                                       {:topic "orders"
                                        :data orders})
                                 ;; update the most recent order atom
                                 (reset! datastore/most-recent-order
                                         (last
                                          (sort-by
                                           :target_time_start orders)))
                                 ;; update the most
                                 ;; last-acknowledged-order atom
                                 (reset! datastore/last-acknowledged-order
                                         @datastore/most-recent-order)
                                 (reset! saving? false)))))))]
        (when (nil? @current-order)
          (reset! current-order (first paginated-orders)))
        [:div {:class "panel panel-default"}
         [order-panel current-order]
         [:div {:class "panel-body"
                :style {:margin-top "15px"}}
          [:div {:class "btn-toolbar"
                 :role "toolbar"
                 :aria-label "Toolbar with button groups"}
           [orders-filter selected-filter]
           [:div {:class "btn-group"
                  :role "group"
                  :aria-label "refresh group"}
            (when (not (same-timestamp? @datastore/most-recent-order
                                        @datastore/last-acknowledged-order))
              [new-orders-button])
            [RefreshButton {:refresh-fn
                            refresh-fn}]]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [order-table-header {:sort-keyword sort-keyword
                                               :sort-reversed? sort-reversed?}]
            :table-row (order-row current-order)}
           paginated-orders]]
         [TablePager
          {:total-pages (count sorted-orders)
           :current-page current-page}]]))))
