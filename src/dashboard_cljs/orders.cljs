(ns dashboard-cljs.orders
  (:require [cljs.core.async :refer [put!]]
            [cljsjs.moment]
            [clojure.string :as s]
            [clojure.set :refer [subset?]]
            [reagent.core :as r]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton ErrorComp
                                               TableFilterButtonGroup
                                               TablePager ConfirmationAlert
                                               KeyVal ProcessingIcon FormGroup
                                               TextAreaInput DismissButton
                                               SubmitDismissGroup Select
                                               TelephoneNumber
                                               Mailto GoogleMapLink]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf base-url
                                          cents->$dollars json-string->clj
                                          accessible-routes
                                          new-orders-count
                                          parse-timestamp
                                          oldest-current-order
                                          same-timestamp?
                                          declined-payment?
                                          current-order?
                                          get-event-time
                                          get-by-id now]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [gmap get-cached-gmaps]]))

(def status->next-status {"unassigned"  "assigned"
                          "assigned"    "accepted"
                          "accepted"    "enroute"
                          "enroute"     "servicing"
                          "servicing"   "complete"
                          "complete"    nil
                          "cancelled"   nil})

(def default-order {:retrieving? false
                    :notes ""
                    :errors nil})

(def state (r/atom {:confirming? false
                    :editing-notes? false
                    :editing-reason? false
                    :current-order nil
                    :edit-order default-order
                    :alert-success ""}))

(def cancellation-reasons #{{:id "r0" :reason "None"}
                            {:id "r1" :reason "Can't find Car"}
                            {:id "r2" :reason "Car is inaccessible"}
                            {:id "r3" :reason "Gas cap closed"}
                            {:id "r4" :reason "Customer request"}
                            {:id "r5" :reason "Customer moving vehicle (not-late)"}
                            {:id "r6" :reason "Customer moving vehicle (late)"}
                            {:id "r7" :reason "Other"}})

(defn id->reason  [id]
  (:reason (get-by-id cancellation-reasons
                      id)))

(defn order-row
  "A table row for an order in a table. current-order is the one currently being
  viewed"
  [current-order]
  (fn [order]
    [:tr {:class (str (when (= (:id order)
                               (:id @current-order))
                        "active"))
          :on-click (fn [_]
                      (reset! current-order order)
                      (reset! (r/cursor state [:editing-notes?]) false))}
     ;; order status
     [:td (:status order)]
     ;; courier assigned
     [:td (:courier_name order)]
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
     ;; delivery time (TODO: this should show minutes if non-zero)
     [:td (str (.diff (js/moment.unix (:target_time_end order))
                      (js/moment.unix (:target_time_start order))
                      "hours")
               " Hr")]
     ;; username
     [:td {:style (when-not (= 0 (:subscription_id order))
                    {:color "#5cb85c"})}
      (:customer_name order)]
     ;; phone #
     [:td [TelephoneNumber (:customer_phone_number order)]]
     ;; email
     [:td [Mailto (:email order)]]
     ;; street address
     [:td [GoogleMapLink (str (:address_street order)
                              ", " (:address_zip order))
           (:lat order) (:lng order)]]
     ;; market
     [:td [:i {:class "fa fa-circle"
               :style {:color (:zone-color order)}}] " "
      (->> (:zone order)
           (get-by-id @datastore/zones)
           :name)]]))

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
       "Courier"]
      [TableHeadSortable
       (conj props {:keyword :target_time_start})
       "Placed"]
      [TableHeadSortable
       (conj props {:keyword :target_time_end})
       "Deadline"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Completed"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Limit"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Name"] 
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Phone"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Email"]
      [TableHeadSortable
       (conj props {:keyword :address_street})
       "Street Address"]
      [TableHeadSortable
       (conj props {:keyword :zone})
       "Market"]]]))

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
        retrieving? (r/atom false)]
    (fn [{:keys [editing? assigned-courier couriers order]}
         props]
      (let [selected-courier (r/atom (or assigned-courier
                                         (:id (first couriers))))]
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
           (when-not @retrieving?
             [Select {:value selected-courier
                      :options couriers
                      :display-key :name
                      :sort-keyword :name}]))
         ;; save assignment
         " "
         (when (and @editing?
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           [:span {:style {:display "inline-block"}}
            [:button {:type "button"
                      :class "btn btn-xs btn-default"
                      :on-click
                      #(when (not @retrieving?)
                         (reset! retrieving? true)
                         (assign-courier editing? retrieving? order
                                         selected-courier couriers
                                         error-message))}
             (if @retrieving?
               [ProcessingIcon]
               "Save assignment")]
            " "
            (when-not  @retrieving?
              [DismissButton {:dismiss-fn #(when (not @retrieving?)
                                             (reset! editing? false))
                              :class "btn btn-xs btn-default"}])])
         (when (not (s/blank? @error-message))
           [ErrorComp {:error-message
                       (str "Courier could not be assigned! Reason: "
                            @error-message
                            "\n"
                            "Try saving the assignment again")
                       :dismiss-fn #(reset! error-message "")}])]))))


(defn refresh-order!
  "Given an order, retrieve its information from the server. Call
  order-fn on the resulting new-order if there is one."
  [order order-fn]
  (retrieve-url
   (str base-url "order/" (:id @order))
   "GET"
   {}
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys
                                      true)
                    new-order (first response)]
                (when new-order
                  (order-fn new-order)))))))

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
  [order error-message cancel-reason retrieving? confirming?]
  (retrieve-url
   (str base-url "cancel-order")
   "POST"
   (js/JSON.stringify (clj->js {:user_id (:user_id @order)
                                :order_id (:id @order)
                                :cancel-reason cancel-reason}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)]
                (reset! retrieving? false)
                (reset! confirming? false)
                (when (:success response)
                  (let [response-order (:order response)
                        updated-order (assoc
                                       @order
                                       :status "cancelled"
                                       :admin_event_log
                                       (:admin_event_log response-order))]
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
  [props state]
  (let [error-message (r/atom "")
        retrieving? (r/atom false)
        confirming? (r/cursor state [:confirming?])
        confirm-action (r/atom "")
        confirm-on-click (r/atom false)]
    (fn [{:keys [editing? status order]}
         props]
      (let [selected-reason (r/atom "r0")]
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
                                   (cancel-order
                                    order
                                    error-message
                                    (id->reason @selected-reason)
                                    retrieving?
                                    confirming?)))
             :confirmation-message
             (fn []
               [:div
                (cond (= @confirm-action "advance")
                      (str "Are you sure you want to advance this order's "
                           "status to '" (status->next-status status) "'?")
                      (= @confirm-action "cancel")
                      [:div
                       "Please select a cancellation reason"
                       [:br]
                       [:br]
                       [Select {:value selected-reason
                                :options cancellation-reasons
                                :display-key :reason}]
                       [:br]
                       [:br]
                       (str "Are you sure you want to cancel this order?")])
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
                        :class "btn btn-xs btn-default"
                        :on-click #(when (not @retrieving?)
                                     (reset! confirming? true)
                                     (reset! confirm-action "cancel"))}
               (if @retrieving?
                 [:i {:class "fa fa-spinner fa-pulse"}]
                 "Cancel Order")])])
         (when (not (s/blank? @error-message))
           [ErrorComp {:error-message
                       (str "Order status could be not be changed! Reason: "
                            @error-message)
                       :dismiss-fn #(reset! error-message "")}])]))))

(defn get-etas-button
  "Given an order atom, refresh it with values from the server"
  [order]
  (let [retrieving? (r/atom false)]
    (fn [order]
      (let [get-etas (fn [order]
                       (refresh-order! order (fn [new-order]
                                               (reset! retrieving? false)
                                               (reset! order new-order))))]
        [:button {:type "button"
                  :class "btn btn-default btn-xs"
                  :on-click #(when (not @retrieving?)
                               (reset! retrieving? true)
                               (get-etas order))}
         (if @retrieving?
           [:i {:class "fa fa-lg fa-spinner fa-pulse "}]
           "Get ETAs")]
        ))))

(defn order-notes-comp
  "Given an order r/atom, edit the order's notes"
  [order state]
  (let [edit-order     (r/cursor state [:edit-order])
        notes          (r/atom "")
        retrieving?    (r/atom false)
        editing-notes? (r/cursor state [:editing-notes?])
        alert-success  (r/cursor state [:alert-success])
        aux-fn         (fn [_]
                         (reset! editing-notes? (not @editing-notes?))
                         (reset! retrieving? false))]
    (fn [order]
      (when-not @editing-notes?
        (reset! notes (:notes @order)))
      [:form
       (if @editing-notes?
         [:div [FormGroup {:label "Notes"
                           :label-for "order notes"}
                [TextAreaInput {:value @notes
                                :rows 2
                                :on-change #(reset!
                                             notes
                                             (-> %
                                                 (aget "target")
                                                 (aget "value")))}]]
          [:br]]
         (when-not (s/blank? (:notes @order))
           [KeyVal "Notes"
            (into []
                  (concat
                   [:div {:style {:display "inline-table"}}]
                   (interpose [:br] (s/split (:notes @order) #"\n"))))]))
       [SubmitDismissGroup
        {:editing? editing-notes?
         :retrieving? retrieving?
         :edit-btn-content (if (s/blank? (:notes @order))
                             "Add Note"
                             "Edit Note")
         :submit-fn
         (fn [e]
           (.preventDefault e)
           (if @editing-notes?
             (entity-save
              (assoc @order :notes @notes)
              "order"
              "PUT"
              retrieving?
              (edit-on-success "order" edit-order order alert-success
                               :aux-fn aux-fn)
              (edit-on-error  edit-order
                              :aux-fn aux-fn))
             (do
               (reset! alert-success "")
               (reset! editing-notes?
                       (not @editing-notes?)))))
         :dismiss-fn
         (fn [e]
           ;; no longer editing
           (reset! editing-notes? false))}]])))


(defn cancel-reason-comp
  "Given an order r/atom, edit the order's cancel reason"
  [order state]
  (let [edit-order (r/cursor state [:edit-order])
        error-message (r/atom "")
        cancel-reason (r/atom "")
        retrieving? (r/atom false)
        alert-success (r/atom "")
        current-cancel-reason (fn [order]
                                (->> (:admin_event_log order)
                                     (filter #(= (:action %) "cancel-order"))
                                     (sort-by :timestamp)
                                     reverse
                                     first
                                     :comment))
        reason->id (fn [reason]
                     (:id (first (filter #(= (:reason %) reason)
                                         cancellation-reasons))))
        editing? (r/cursor state [:editing-reason?])
        aux-fn     (fn [_]
                     (reset! editing? (not @editing?))
                     (reset! retrieving? false))]
    (fn [order]
      (let [selected-reason (r/atom (or (reason->id
                                         (current-cancel-reason @order))
                                        "r0"))]
        [:h5 [:span {:class "info-window-label"} "Cancellation Reason: "]
         ;; reason assigned (if any)
         (when-not @editing?
           [:span (id->reason @selected-reason) " "])
         ;; change cancel reason button
         (when (and (not @editing?)
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           [:button {:type "button"
                     :class "btn btn-xs btn-default"
                     :on-click #(reset! editing? true)}
            "Change Reason"])
         ;; reason select
         (when (and @editing?
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           (when-not @retrieving?
             [Select {:value selected-reason
                      :options cancellation-reasons
                      :display-key :reason}]))
         ;; save reason
         " "
         (when (and @editing?
                    (subset? #{{:uri "/assign-order"
                                :method "POST"}}
                             @accessible-routes))
           [:span {:style {:display "inline-block"}}
            [:button {:type "button"
                      :class "btn btn-xs btn-default"
                      :on-click
                      (fn [e]
                        (.preventDefault e)
                        (if @editing?
                          (entity-save
                           (assoc @order :cancel_reason (id->reason
                                                         @selected-reason))
                           "order"
                           "PUT"
                           retrieving?
                           (edit-on-success "order" edit-order order
                                            alert-success
                                            :aux-fn aux-fn)
                           (edit-on-error  edit-order
                                           :aux-fn aux-fn))
                          (do
                            (reset! alert-success "")
                            (reset! editing?
                                    (not @editing?)))))}
             (if @retrieving?
               [ProcessingIcon]
               "Save Changes")]
            " "
            (when-not @retrieving?
              [DismissButton {:dismiss-fn #(when (not @retrieving?)
                                             (reset! editing? false))
                              :class "btn btn-xs btn-default"}])])
         (when (not (s/blank? @error-message))
           [ErrorComp {:error-message
                       (str "Reason could not be used due to: "
                            @error-message
                            "\n"
                            "Try changing reason again")
                       :dismiss-fn #(reset! error-message "")}])]))))

(defn order-panel
  "Display detailed and editable fields for current-order"
  [props]
  (let [google-marker (atom nil)]
    (fn [{:keys [order state gmap-keyword]} props]
      (let [editing-assignment? (r/atom false)
            editing-status?     (r/atom false)
            couriers
            ;; filter out the couriers to only those assigned
            ;; to the zone
            (->> @datastore/couriers
                 (filter #(contains? (set (:zones %))
                                     (:zone @order)))
                 (filter :active))
            assigned-courier (if (not (nil? (:courier_name @order)))
                               ;; there is a courier currently assigned
                               (:id (first (filter #(= (:courier_name
                                                        @order)
                                                       (:name % )) couriers)))
                               ;; no courier, assign the first one
                               (:id (first couriers)))
            order-status   (:status @order)]
        (reset! (r/cursor state [:confirming?]) false)
        ;; create and insert order marker
        (when (:lat @order)
          (when @google-marker
            (.setMap @google-marker nil))
          (reset! google-marker
                  (js/google.maps.Marker.
                   (clj->js {:position
                             {:lat (:lat @order)
                              :lng (:lng @order)}
                             :map
                             (second (get-cached-gmaps gmap-keyword))}))))
        ;; populate the current order with additional information
        [:div
         [:div {:class "row"}
          [:div {:class "col-xs-12 col-lg-12"}
           [:h2 {:style {:margin-top 0}} "Order Details"]]]
         [:div {:class "row"}
          [:div {:class "col-xs-12 col-lg-6"}
           [:div {:id "customer-info"
                  :style {:border "solid 2px #ddd"}}
            [:div {:class "row"}
             [:div {:class "col-xs-12 col-lg-6"}
              [:div {:id "customer-info-text"
                     :style {:padding-left "1em"
                             :padding-bottom "1em"}}
               [:h3 "Customer Info"]
               ;; map
               [:div {:id "customer-info-highlighted"
                      :style {:background-color "#efefff"
                              :border "solid 2px #ddd"
                              :padding-left "5px"}}
                ;;  name
                [:h5 (:customer_name @order)
                 (when-not (= 0 (:subscription_id @order))
                   [:span {:style {:color "#5cb85c"}}
                    " Purple Plus Member"])]
                [:h5 [:span [GoogleMapLink
                             (:address_street @order)
                             (:lat @order)
                             (:lng @order)] ", " (:address_zip @order)]]
                ;;  email
                [:h5 [Mailto (:email @order)]]
                ;;  phone number
                [:h5  [TelephoneNumber (:customer_phone_number @order)]]]
               ;; vehicle description
               (let [{:keys [year make model color]} (:vehicle @order)]
                 [KeyVal "Vehicle" (str year " " make " " model " " "(" color ")")])
               ;; license plate
               [KeyVal "License Plate" (:license_plate @order)]]]
             [:div {:class "col-lg-6 col-xs-12"}
              [gmap {:id gmap-keyword
                     :style {:height "300px"
                             :margin "10px"}
                     :center {:lat (:lat @order)
                              :lng (:lng @order)}}]]]]]
          [:div {:class "col-xs-12 col-lg-6"}
           [:div {:id "order-info"
                  :style {:border "solid 2px #ddd"
                          :padding-left "1em"
                          :padding-bottom "1em"}}
            [:h3 "Order Info"]
            ;; order id
            [KeyVal "Order ID" (:id @order)]
            ;; order price
            [KeyVal "Total Price"
             [:span
              (cents->$dollars (:total_price @order))
              " "
              ;; declined payment?
              (if (declined-payment? @order)
                [:span {:class "text-danger"} "Payment declined!"])]]
            ;; payment info
            (let [payment-info (json-string->clj (:payment_info @order))]
              (when (not (nil? payment-info))
                [KeyVal "Payment Info" (str (:brand payment-info)
                                            " "
                                            (:last4 payment-info)
                                            " "
                                            (:exp_month payment-info) "/"
                                            (:exp_year payment-info))]))
            ;; coupon code
            (when (not (s/blank? (:coupon_code @order)))
              [KeyVal "Coupon" (:coupon_code @order)])
            ;; gallons and type
            [KeyVal "Gallons" (str (:gallons @order)
                                   " ("
                                   (:gas_type @order)
                                   " Octane)")]
            ;; time order was placed
            [KeyVal "Order Placed" (unix-epoch->hrf (:target_time_start
                                                     @order))]
            ;; completion time
            (when-let [completion-time (get-event-time
                                        (:event_log @order) "complete")]
              [KeyVal "Completion Time" (unix-epoch->hrf completion-time)])
            ;; delivery time
            [KeyVal "Delivery Time"
             (str (.diff (js/moment.unix (:target_time_end @order))
                         (js/moment.unix (:target_time_start @order))
                         "hours")
                  " Hr")]
            ;; special instructions field
            (when (not (s/blank? (:special_instructions @order)))
              [KeyVal "Special Instructions" (:special_instructions
                                              @order)])
            ;; rating
            (let [number-rating (:number_rating @order)]
              (when number-rating
                [KeyVal "Rating"
                 [:span (for [x (range number-rating)]
                          ^{:key x} [:i {:class "fa fa-star fa-lg"}])
                  (for [x (range (- 5 number-rating))]
                    ^{:key x} [:i {:class "fa fa-star-o fa-lg"}])]]))
            ;; review
            (when (not (s/blank? (:text_rating @order)))
              [KeyVal "Review" (:text_rating @order)])
            ;; market
            [KeyVal "Market"
             [:span
              [:i {:class "fa fa-circle"
                   :style {:color (:zone-color @order)}}]
              " "
              (->> (:zone @order)
                   (get-by-id @datastore/zones)
                   :name)]]
            ;; tire pressure check
            [KeyVal "Tire Pressure Check"
             (if (:tire_pressure_check @order)
               [:span {:class "text-danger"}
                "Yes"]
               "No")]
            ;; ETAs
            ;; note: the server only populates ETA values when the orders
            ;; are: "unassigned" "assigned" "accepted" "enroute"
            (when (contains? #{"unassigned" "assigned" "accepted" "enroute"}
                             (:status @order))
              [KeyVal "ETAs"
               [:span [get-etas-button order]
                (when (:etas @order)
                  (map (fn [eta]
                         ^{:key (:name eta)}
                         [:p (str (:name eta) " - ")
                          [:strong {:class (when (:busy eta)
                                             "text-danger")}
                           (:minutes eta)]])
                       (sort-by :minutes (:etas @order))))]])
            ;; assigned courier display and editing
            [order-courier-comp {:editing? editing-assignment?
                                 :assigned-courier assigned-courier
                                 :couriers couriers
                                 :order order}]
            ;; status and editing
            [status-comp {:editing? editing-status?
                          :status order-status
                          :order order} state]
            ;; cancellation reason
            (when (= "cancelled" (:status @order))
              [cancel-reason-comp order state])
            ;; notes
            [order-notes-comp order state]
            [:div {:class "pull-right hidden-xs"}]]]]]))))

(defn new-orders-button
  "A component for allowing the user to see new orders on the server"
  []
  (fn []
    [:button {:type "button"
              :class "btn btn-default btn-danger"
              :on-click
              #(reset! datastore/last-acknowledged-order
                       @datastore/most-recent-order)}
     (str "View " (new-orders-count @datastore/orders
                                    @datastore/last-acknowledged-order)
          " New Orders")]))


(defn orders-panel
  "Display a table of selectable orders with an indivdual order panel
  for the selected order"
  [orders state]
  (let [current-order (r/cursor state [:current-order])
        edit-order    (r/cursor state [:edit-order])
        sort-keyword (r/atom :target_time_end)
        sort-reversed? (r/atom true)
        current-page (r/atom 1)
        page-size 20
        filters {"Show All" (constantly true)
                 "Current Orders" current-order?}
        selected-filter (r/atom "Current Orders")]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-orders (filter #(<= (:target_time_start %)
                                          (:target_time_start
                                           @datastore/last-acknowledged-order))
                                     orders)
            sorted-orders  (->> displayed-orders
                                sort-fn
                                (filter (get filters @selected-filter))
                                (partition-all page-size))
            paginated-orders (fn []
                               (-> sorted-orders
                                   (nth (- @current-page 1)
                                        '())))
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
                                 (datastore/process-orders orders true))
                               (reset! saving? false))))))
            table-pager-on-click (fn []
                                   (.log js/console "table-pager-on-click")
                                   (reset! current-order
                                           (first (paginated-orders))))]
        (when (nil? @current-order)
          (reset! current-order (first (paginated-orders))))
        [:div {:class "panel panel-default"}
         [order-panel {:order current-order
                       :state state
                       :gmap-keyword :orders}]
         [:div {:class "panel-body"
                :style {:margin-top "15px"}}
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [TableFilterButtonGroup {:hide-counts #{"Show All"}}
            filters orders selected-filter]
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
           (paginated-orders)]]
         [TablePager
          {:total-pages (count sorted-orders)
           :current-page current-page
           :on-click table-pager-on-click}]]))))
