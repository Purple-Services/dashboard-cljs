(ns dashboard-cljs.tables
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf continous-update
                                          cents->dollars unix-epoch->fuller]]
            [cljs.core.async :refer [chan pub put! sub <! >!]]
            [cljs.reader :refer [read-string]]
            [clojure.set :refer [subset?]]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [reagent.core :as r]
            [cljsjs.moment]
            ))

;; Note: Only the couriers table can use "continous-update"
;; to constantly modify its rows and still be edited with proper error
;; handling. All other tables will need modifications in order to do this
;; properly.

(def couriers (r/atom #{}))

(def users (r/atom #{}))

(def orders (r/atom #{}))

(def coupons (r/atom #{}))

(def zones (r/atom #{}))

(def postal-code-test (atom (array)))

(def timeout-interval 1000)

(def status->next-status {"unassigned"  "assigned"
                          "assigned"    "accepted"
                          "accepted"    "enroute"
                          "enroute"     "servicing"
                          "servicing"   "complete"
                          "complete"    nil
                          "cancelled"   nil})

;; the base url to use for server calls
(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(def pub-chan (chan))
(def notify-chan (pub pub-chan :topic))

(defn remove-by-id
  "Remove the element with id from state. Assumes all elements have an
  :id key with a unique value"
  [state id]
  (set (remove #(= id (:id %)) state)))

(defn get-by-id
  "Get an element by its id from coll"
  [coll id]
  (first (filter #(= (:id %) id) coll)))

(defn update-element! [state el]
  "Update the el in state. el is assumed to have a :id key with a unique value"
  (swap! state
         (fn [old-state]
           (map #(if (= (:id %) (:id el))
                   el
                   %) old-state))))

(defn update-courier-server!
  "Update courier on server and update the row-state error message."
  [courier row-state]
  (retrieve-url
   (str base-url "courier")
   "POST"
   (js/JSON.stringify (clj->js courier))
   (partial xhrio-wrapper
            #(let [response %
                   clj-response (js->clj response :keywordize-keys true)]
               (when (:success clj-response)
                 (reset! row-state (assoc @row-state
                                          :error? false
                                          :editing? false
                                          :saving? false
                                          :error-message ""))
                 ;; update the local state
                 (update-element! couriers courier))
               (when (not (:success clj-response))
                 (reset! row-state (assoc @row-state
                                          :error? true
                                          :editing? true
                                          :saving? false
                                          :error-message
                                          (:message clj-response))))))))

;; note: this will not sync the local state perfectly with the server
(defn cancel-order-server!
  "Cancel an order on the server and update the local order"
  [order]
  (retrieve-url
   (str base-url "cancel-order")
   "POST"
   (js/JSON.stringify (clj->js {:user_id (:user_id order)
                                :order_id (:id order)}))
   (partial xhrio-wrapper
            #(let [response %
                   clj-response (js->clj response :keywordize-keys true)]
               (when (:success clj-response)
                 ;; update the local state
                 (update-element! orders (assoc-in order
                                                   [:status] "cancelled"))
                 ;; alert the user
                 (js/alert "Order Cancelled!"))
               (when (not (:success clj-response))
                 ;; just alert the user that soemthing went wrong,
                 ;; this should be very rare
                 (js/alert (str "Something went wrong. "
                                "Order has NOT been cancelled!"))
                 )))))

(defn update-order-status!
  "Update the status of an order on the server and update the local order"
  [order]
  (retrieve-url
   (str base-url "update-status")
   "POST"
   (js/JSON.stringify (clj->js {:order_id (:id order)}))
   (partial xhrio-wrapper
            #(let [response %
                   clj-response (js->clj response :keywordize-keys true)]
               (when (:success clj-response)
                 ;; update the local state
                 (update-element! orders (assoc-in
                                          order
                                          [:status]
                                          (status->next-status
                                           (:status order)))))
               (when (not (:success clj-response))
                 ;; just alert the user that soemthing went wrong,
                 ;; this should be very rare
                 (js/alert (str "The order could not be advanced!\n "
                                "Server Message: "
                                (:message clj-response))))))))

(defn change-order-assignment!
  "Change the courier assignment for the order and update the local order"
  [order]
  (let [order-id (:id order)
        courier-id (:courier_id order)
        courier-name (:courier_name order)]
    (retrieve-url
     (str base-url "assign-order")
     "POST"
     (js/JSON.stringify (clj->js {:order_id order-id
                                  :courier_id courier-id}))
     (partial xhrio-wrapper
              #(let [response %
                     clj-response (js->clj response :keywordize-keys true)]
                 (when (:success clj-response)
                   ;; update the local state
                   (update-element! orders (assoc order
                                                  :courier_id
                                                  courier-id
                                                  :courier_name
                                                  courier-name))
                   (js/alert (str "This order has been assigned to "
                                  courier-name)))
                 (when (not (:success clj-response))
                   ;; just alert the user that soemthing went wrong,
                   ;; this should be very rare
                   (js/alert (str "The order could not be reassigned!\n"
                                  "Server Message: "
                                  (:message clj-response)))))))))

(defn send-push-to-all-active-users
  "Send message to all active users"
  [message]
  (retrieve-url
   (str base-url "send-push-to-all-active-users")
   "POST"
   (js/JSON.stringify (clj->js (:message message)))
   (partial xhrio-wrapper
            #(let [response (js->clj % :keywordize-keys true)]
               (when (:success response)
                 (js/alert (str "Sent!")))
               (when (not (:success response))
                 (js/alert (str "Something went wrong."
                                " Push notifications may or may"
                                " not have been sent. Wait until"
                                " sure before trying again.")))))))

(defn send-push-to-users
  "Send message to all users in set"
  [message users]
  (retrieve-url
   (str base-url "send-push-to-all-active-users")
   "POST"
   (js/JSON.stringify (clj->js {:message message
                                :user-ids users}))
   (partial xhrio-wrapper
            #(let [response (js->clj % :keywordize-keys true)]
               (when (:success response)
                 (js/alert (str "Sent!")))
               (when (not (:success response))
                 (js/alert (str "Something went wrong."
                                " Push notifications may or may"
                                " not have been sent. Wait until"
                                " sure before trying again.")))))))

(defn update-zone-server!
  "Update a zone on the server and update the row-state error message."
  [zone row-state]
  (retrieve-url
   (str base-url "zone")
   "POST"
   (js/JSON.stringify (clj->js zone))
   (partial xhrio-wrapper
            #(let [response %
                   clj-response (js->clj response :keywordize-keys true)]
               (when (:success clj-response)
                 (reset! row-state (assoc @row-state
                                          :error? false
                                          :editing? false
                                          :saving? false
                                          :error-message ""))
                 ;; update the local state
                 (update-element! zones zone))
               (when (not (:success clj-response))
                 (reset! row-state (assoc @row-state
                                          :error? true
                                          :editing? true
                                          :saving? false
                                          :error-message
                                          (:message clj-response))))))))

(defn postal-code-for-lat-lng
  "Obtain the postal code associated with lat, lng and call f on the result"
  [lat lng f]
  (let [google-api-base-url "https://maps.googleapis.com/maps/api/geocode/json?"
        ;; chris@purpledelivery.com account
        google-api-key  "AIzaSyAflsl4cNXHnO-HaZhfGC2gcGoIxt19UW4"
        request-url (str google-api-base-url
                         "latlng="
                         lat "," lng
                         "&key="
                         google-api-key)
        get-zip (fn [geocode-resp]
                  (if (not= (:status geocode-resp)
                            "ZERO_RESULTS")
                    (->> geocode-resp
                         :results
                         ;; get all of the address_components
                         (mapcat :address_components)
                         ;; only get those that are of type postal_code
                         (filter #(contains? (set (:types %)) "postal_code"))
                         ;; short_name should contain the zip code string
                         (map :short_name)
                         ;; determine which zip occurs the most often in the
                         ;; resp this is needed because a lat,lng can reverse
                         ;; geocode to more than one zip!
                         (frequencies)
                         (apply max-key val)
                         key)
                    "ZERO_RESULTS"))]
    (retrieve-url
     request-url
     "GET"
     {}
     (partial xhrio-wrapper
              #(let [response %
                     clj-response (js->clj response :keywordize-keys true)
                     zip-code (get-zip clj-response)
                     ]
                 (f zip-code))))))

(defn get-status-stats-csv
  [stats-state]
  (retrieve-url
   (str base-url "status-stats-csv")
   "GET"
   {}
   (partial xhrio-wrapper
            #(let [clj-response (js->clj % :keywordize-keys true)]
               (reset! stats-state
                       (assoc @stats-state
                              :processing? (:processing clj-response)
                              :timestamp  (:timestamp clj-response)))))))
(defn field-input-handler
  "Returns a handler that updates value in atom map,
  under key, with value from on-change event. Optionally transforms
  value with f"
  [atom key & [f]]
  (fn [e]
    (swap! atom
           assoc key
           (-> e
               (aget "target")
               (aget "value")
               (#(if f
                   (f %)
                   %))))))

(defn editable-input [atom key & [f]]
  "Change the val of key in atom to this form's input val. Input val will be
transformed by f, if given"
  (if (or (:editing? @atom)
          (:saving? @atom))
    [:div
     [:input {:type "text"
              :disabled (:saving? @atom)
              :value (get @atom key)
              :on-change (field-input-handler atom key f)
              :class (when (:error? @atom)
                       "error")}]
     [:div {:class (when (:error? @atom)
                     "error")}
      (when (:error? @atom)
        (:error-message @atom))]]
    [:p (get @atom key)]))

(defn get-server-couriers
  [state]
  (retrieve-url
   (str base-url "couriers")
   "POST"
   {}
   (partial xhrio-wrapper
            #(reset!
              state
              (mapv
               ;; convert json arrays to a string
               (fn [courier]
                 (update-in courier [:zones]
                            (fn [zone]
                              (.join (clj->js (sort zone))))))
               (vec (reverse
                     (sort-by :last_ping
                              (js->clj (aget % "couriers")
                                       :keywordize-keys true)))))))))

(defn get-server-users
  [users-atom]
  (retrieve-url
   (str base-url "users")
   "GET"
   {}
   (partial xhrio-wrapper
            #(reset!
              users-atom
              (sort-by :timestamp_created
                       >
                       (js->clj % :keywordize-keys true))))))

(defn get-server-orders
  [orders-atom date]
  (retrieve-url
   (str base-url "orders-since-date")
   "POST"
   (js/JSON.stringify
    (clj->js {:date date}))
   (partial xhrio-wrapper
            #(reset!
              orders-atom
              (sort-by :target_time_start
                       >
                       (js->clj % :keywordize-keys true))))))

(defn get-server-coupons
  [coupons-atom]
  (retrieve-url
   (str base-url "coupons")
   "GET"
   {}
   (partial xhrio-wrapper
            #(reset!
              coupons-atom
              (->>
               (js->clj % :keywordize-keys true)
               ;; how many times was coupon used?
               (map (fn [coupon] (assoc coupon :times_used
                                        (-> (:used_by_license_plates coupon)
                                            (s/split #",")
                                            (->> (remove s/blank?))
                                            count))))
               ;; filter out coupons that are not expired
               (filter (fn [coupon]
                         (> (:expiration_time coupon)
                            (quot (.now js/Date) 1000)))))))))

(defn get-server-zones
  [zones-atom]
  (retrieve-url
   (str base-url "zones")
   "GET"
   {}
   (partial xhrio-wrapper
            #(reset!
              zones-atom
              (->>
               (js->clj % :keywordize-keys true)
               (map (fn [zone] (update-in
                                zone
                                [:service_time_bracket]
                                read-string
                                ))))))))

(defn courier-row [courier]
  (let [row-state (r/atom {:editing? false
                           :saving? false
                           :zones (:zones courier)
                           :error? false
                           :error-message ""
                           :zone-color ""
                           })
        courier-zone-color (fn [zip]
                             (:color
                              (first
                               (filter
                                #(contains?
                                  (-> (:zip_codes %) (s/split #",") set)
                                  zip) @zones))))]
    (fn [courier]
      (when (and
             (not (:editing? @row-state))
             (not (:saving? @row-state)))
        (swap! row-state assoc :zones (:zones courier)))
      ;; set the zone-color, if any
      (postal-code-for-lat-lng (:lat courier)
                               (:lng courier)
                               #(swap! row-state assoc :zone-color
                                       (courier-zone-color %)))
      [:tr
       (if (:connected courier)
         [:td {:class "currently-connected connected"} "Yes"]
         [:td {:class "currently-not-connected connected"} "No"])
       [:td (:name courier)]
       [:td (:phone_number courier)]
       [:td (if (:busy courier) "Yes" "No")]
       [:td (unix-epoch->hrf (:last_ping courier))]
       [:td (:lateness courier)]
       [:td [editable-input row-state :zones]]
       [:td {:style {:background-color (:zone-color @row-state)}}
        [:a {:target "_blank"
             :style {:color
                     (if (and (= 0 (:lat courier))
                              (= 0 (:lng courier)))
                       "red"
                       "white")}
             :href (str "https://maps.google.com/?q="
                        (:lat courier)
                        ","
                        (:lng courier))}
         "View On Map"]]
       [:td [:button
             {:disabled (:saving? @row-state)
              :on-click
              (fn []
                (when (:editing? @row-state)
                  (let [new-courier (assoc courier
                                           :zones (:zones @row-state))]
                    (swap! row-state assoc :saving? true)
                    ;; update the courier on the server
                    (update-courier-server! new-courier row-state)))
                (swap! row-state update-in [:editing?] not))}
             (cond
               (:saving? @row-state)
               "Save"
               (:editing? @row-state)
               "Save"
               :else
               "Edit")]
        (if (:saving? @row-state)
          [:i {:class "fa fa-spinner fa-pulse"}])]])))

(defn couriers-table-header
  []
  [:thead
   [:tr
    [:td "Connected"] [:td "Name"] [:td "Phone"] [:td "Busy?"]
    [:td "Last Seen"]
    [:td {:id "couriers-on-time-col-header"} "On Time %"]
    [:td "Zones"]
    [:td "Location"]
    [:td]]])

(defn couriers-table
  [table-state]
  (fn []
    ;; maybe a defonce isn't needed, instead
    ;; should be put above this fn!!
    ;; crucial to use defonce here so that only ONE
    ;; call will be made!
    ;; (defonce courier-updater
    ;;   (continous-update #(get-server-couriers couriers)
    ;;                     timeout-interval))
    ;; but let's still get the state setup
    (defonce init-couriers
      (get-server-couriers couriers))
    [:table {:id "couriers"
             :class (when (:showing-all? @table-state)
                      "showing-all")}
     [couriers-table-header]
     [:tbody
      (map (fn [courier]
             ^{:key (:id courier)}
             [courier-row courier])
           @couriers)]]))

(defn couriers-header
  [table-state]
  [:h2 {:class "couriers"} "Couriers "
   [:span {:class "show-all"
           :on-click #(swap! table-state update-in [:showing-all?] not)}
    (if (:showing-all? @table-state)
      "[hide disconnected]"
      "[show all]")]
   [:a {:class "fake-link" :target "_blank"
        :href (str base-url "dash-map-couriers")}
    " [view couriers on map]"]])

(defn couriers-component
  []
  (let [table-state (r/atom {:showing-all? false})]
    (fn []
      [:div {:id "couriers-component"}
       [couriers-header table-state]
       [couriers-table table-state]])))

(defn order-row
  [order]
  (let [row-state (r/atom {:assign-save-disabled? true
                           :courier_id (:courier_id order)
                           :assign-display? false})]
    (fn [order]
      [:tr
       ;; cancel order
       [:td (when (not (or (= (:status order)
                              "complete")
                           (= (:status order)
                              "cancelled")))
              [:input {:type "submit"
                       :value "Cancel Order"
                       :on-click #(if (js/confirm
                                       (str "Are you sure you want"
                                            " to cancel this order?"
                                            " (this cannot be undone) "
                                            "(customer will be notified via"
                                            " push notification)"))
                                    (cancel-order-server! order))}])]
       ;; order status
       [:td {:class (when (:was-late order)
                      "late")} (:status order) [:br]
        (when-not (contains? #{"complete" "cancelled" "unassigned"}
                             (:status order))
          [:input
           {:type "submit"
            :on-click #(if (js/confirm
                            (str "Are you sure you want to mark this "
                                 "order as '"
                                 (status->next-status (:status order))
                                 "'?"
                                 " (this cannot be undone)"
                                 " (customer will be notified"
                                 " via push notification)"))
                         (update-order-status! order))
            :value ({"accepted" "Start Route"
                     "enroute" "Begin Servicing"
                     "servicing" "Complete Order"}
                    (:status order))}])]
       ;; order assignment
       [:td
        [:div
         {:style {:display (if (or (:assign-display? @row-state)
                                   (= (:status order) "unassigned"))
                             "block"
                             "none")}}
         [:select
          {:on-change
           #(let [selected-courier (-> %
                                       (aget "target")
                                       (aget "value"))]
              (if (= selected-courier "Assign to Courier")
                (swap! row-state assoc
                       :assign-save-disabled? true
                       :courier_id "Assign to Courier")
                (swap! row-state assoc
                       :assign-save-disabled? false
                       :courier_id selected-courier)))
           :value (:courier_id @row-state)}
          [:option
           "Assign to Courier"]
          (map
           (fn [courier]
             ^{:key (:id courier)}
             [:option
              {:value (:id courier)}
              (:name courier)])
           ;; filter out the couriers to only those assigned
           ;; to the zone
           (filter #(contains? (-> (:zones %)
                                   (s/split #",")
                                   set)
                               (str (:zone order)))
                   @couriers))]
         [:input {:type "submit"
                  :value "Save"
                  :disabled (:assign-save-disabled? @row-state)
                  :on-click #(let [selected-courier-id (:courier_id @row-state)
                                   selected-courier-name
                                   (:name (get-by-id
                                           @couriers
                                           selected-courier-id))
                                   new-order (assoc
                                              order
                                              :courier_id selected-courier-id
                                              :courier_name
                                              selected-courier-name)]
                               (if (js/confirm
                                    (str "Are you sure you want to assign this "
                                         "order to "
                                         selected-courier-name
                                         "?"
                                         " (this cannot be undone)"
                                         " (courier(s) will be notified"
                                         " via push notification)"))
                                 (change-order-assignment!
                                  (if (= (:status order)
                                         "unassigned")
                                    (assoc new-order :status "accepted")
                                    order)))
                               (swap! row-state assoc :assign-display? false))}]
         ]
        [:div {:style {:display (if (:assign-display? @row-state)
                                  "none"
                                  "block"
                                  )}
               :on-double-click #(swap! row-state assoc :assign-display? true)
               } (:courier_name order)]]
       ;; order ETA
       [:td (map (fn [eta]
                   ^{:key (:name eta)}
                   [:p (str (:name eta) " - ")
                    [:span {:class (when (:busy eta)
                                     "late")}
                     (:minutes eta)]])
                 (sort-by :minutes (:etas order)))]
       ;; order placed
       [:td (unix-epoch->hrf (:target_time_start order))]
       ;; order deadline
       [:td (unix-epoch->hrf (:target_time_end order))]
       ;; order customer
       [:td (:customer_name order)]
       ;; customer phone number
       [:td (:customer_phone_number order)]
       ;; order street address
       [:td {:style {:background-color (:zone-color order)
                     :color "white"}
             :class "address_street"}
        [:a {:href (str "https://maps.google.com/?q="
                        (:lat order)
                        ","
                        (:lng order))
             :target "_blank"}
         (:address_street order)]]
       ;; order gallons
       [:td (:gallons order)]
       ;; order gas type
       [:td (:gas_type order)]
       ;; order vehicle description
       [:td (str (:color (:vehicle order))
                 " "
                 (:make (:vehicle order))
                 " "
                 (:model (:vehicle order)))]
       ;; order license plate
       [:td (:license_plate order)]
       ;; coupon code
       [:td (:coupon_code order)]
       ;; total price
       [:td {:class (if (and (or (s/blank? (:stripe_charge_id order))
                                 ;; Was paid field changed to a boolean?
                                 ;; currently is a 1 or 0 int, 'and'
                                 ;; always eval to false
                                 ;; (and (not (:paid order))
                                 ;;      (= (:status order) "complete"))
                                 )
                             (not= 0 (:total_price order)))
                      "late" ;; Payment failed!
                      "not-late")}
        (cents->dollars (:total_price order))]])))

(defn orders-table-header
  []
  [:thead
   [:tr
    [:td]
    [:td "Status"] [:td "Courier"] [:td "ETAs (mins)"] [:td "Order Placed"]
    [:td "Deadline"] [:td "Customer"] [:td "Phone"] [:td "Street"]
    [:td "Gallons"] [:td "Octane"] [:td "Vehicle"] [:td "Plate #"]
    [:td "Coupon"] [:td "Total Price"]]])

(defn orders-table
  [table-state]
  (fn []
    (defonce init-orders
      (get-server-orders orders (-> (js/moment)
                                    (.subtract 7 "days")
                                    (.format "YYYY-MM-DD"))))
    [:table {:id "orders"
             :class (str "hide-extra part-of-orders "
                         (when (:showing-all? @table-state)
                           "showing-all"))}
     [orders-table-header]
     [:tbody
      (map (fn [order]
             ^{:key (:id order)}
             [order-row order])
           @orders)]]))

(defn orders-header
  [table-state]
  [:h2 {:id "orders-heading"} "Orders "
   [:span {:class "show-all"
           :on-click #(swap! table-state update-in [:showing-all?] not)}
    (if (:showing-all? @table-state)
      "[hide after 7]"
      "[show all]")]
   [:a {:class "fake-link"
        :target "_blank"
        :href (str base-url "declined")}
    " [view all declined payments]"]
   [:a {:class "fake-link" :target "_blank"
        :href (str base-url "dash-map-orders")}
    " [view orders on map]"]])

(defn orders-component
  []
  (let [table-state (r/atom (:showing-all? false))]
    (fn []
      [:div {:id "orders-component"}
       [orders-header table-state]
       [orders-table table-state]])))

(defn user-row [table-state user]
  (let [row-state (r/atom {:checked? false})
        messages (sub notify-chan (:id user) (chan))
        this     (r/current-component)]
    (go-loop [m (<! messages)]
      (swap! row-state assoc :checked? false)
      (recur (<! messages)))
    (fn [table-state user]
      [:tr
       [:td {:class "name"} (:name user)]
       [:td {:class "email"} (:email user)]
       [:td {:class "phone_number"} (:phone_number user)]
       [:td {:class "has_added_card"} (if (s/blank? (:stripe_default_card user))
                                        "No"
                                        "Yes")]
       [:td {:class "push_set_up"}
        (if (s/blank? (:arn_endpoint user))
          [:div "No"]
          [:div "Yes "
           [:input {:type "checkbox"
                    :on-change #(let [checked? (-> %
                                                   (aget "target")
                                                   (aget "checked"))]
                                  (if checked?
                                    ;; add current user id
                                    (swap! table-state assoc
                                           :push-selected-users
                                           (conj (:push-selected-users
                                                  @table-state) (:id user)))
                                    ;; remove current user id
                                    (swap! table-state assoc
                                           :push-selected-users
                                           (disj (:push-selected-users
                                                  @table-state) (:id user))))
                                  (swap! row-state assoc
                                         :checked? (not
                                                    (:checked? @row-state))))
                    :checked (:checked? @row-state)
                    }]])]
       [:td {:class "os"} (:os user)]
       [:td {:class "app_version"} (:app_version user)]
       [:td {:class "timestamp_created"}
        (unix-epoch->hrf (:timestamp_created user))]])))

(defn users-table-header
  []
  [:thead
   [:tr
    [:td "Name"] [:td "Email"] [:td "Phone"] [:td "Card?"] [:td "Push?"]
    [:td "OS"] [:td "Version"] [:td "Joined"]]])

(defn users-table
  [table-state]
  (fn []
    (defonce init-users
      (get-server-users users))
    [:table {:id "users"
             :class (str "hide-extra "
                         (when (:showing-all? @table-state)
                           "showing-all"))}
     [users-table-header]
     [:tbody
      (map (fn [user]
             ^{:key (:id user)}
             [user-row table-state user])
           @users)]]))

(defn users-header
  [table-state]
  (let [users-count (r/atom "")]
    (retrieve-url
     (str base-url "users-count")
     "GET"
     {}
     (partial xhrio-wrapper
              #(let [clj-response (js->clj % :keywordize-keys true)]
                 (.log js/console "clj-response" clj-response)
                 (reset! users-count (:total (first clj-response))))))
    (fn [table-state]
      [:h2 {:id "users-heading"}
       "Users "
       [:span {:class "count"} (str "(" @users-count ") ")]
       [:span {:class "show-all"
               :on-click #(swap! table-state update-in [:showing-all?] not)
               }
        (if (:showing-all? @table-state)
          "[hide after 7]"
          "[show all]")]
       [:span {:class "fake-link"
               :on-click
               #(let [message (js/prompt (str "Push notification message"
                                              " to send to all active users:"))]
                  (if (not (s/blank? message))
                    (if (js/confirm
                         (str "!!! Are you sure you want to send this message"
                              " to all active users?: "
                              message))
                      (send-push-to-all-active-users message))))}
        " [send push notification to all active users]"]
       [:span {:class "fake-link"
               :on-click
               #(let [selected-users     (:push-selected-users @table-state)
                      message (js/prompt (str "Push notification message"
                                              " to send to all selected users"
                                              " (" (count selected-users)
                                              "):"
                                              ))]
                  (when (and (not (s/blank? message))
                             (not (empty? selected-users)))
                    (when (js/confirm
                           (str "!!! Are you sure you want to send this message"
                                " to all selected users?: "
                                message))
                      ;; send out the message
                      (send-push-to-users message selected-users)
                      ;; tell the rows with checkmarks to uncheck them
                      (mapv (fn [user]
                              (put! pub-chan {:topic user
                                              :data "force-update"}))
                            selected-users)
                      ;; clear out the current push-selected-users
                      (swap! table-state assoc :push-selected-users (set nil))
                      )))}
        " [send push to selected users]"]])))

(defn users-component
  []
  (let [table-state (r/atom {:showing-all? false
                             :push-selected-users (set nil)})]
    (fn []
      [:div {:id "users-component"}
       [users-header table-state]
       [users-table table-state]])))

(defn coupon-row
  [coupon]
  (fn [coupon]
    [:tr
     [:td (:code coupon)]
     [:td (cents->dollars (.abs js/Math (:value coupon)))]
     [:td (unix-epoch->fuller (:expiration_time coupon))]
     [:td (:times_used coupon)]
     [:td (if (:only_for_first_orders coupon) "Yes" "No")]]))

(defn coupons-table-header
  []
  [:thead
   [:tr
    [:td "Code"] [:td "Value"] [:td "Expiration"] [:td "# of Users"]
    [:td "For First Order Only?"]]])

(defn coupons-table
  [table-state]
  (fn []
    (defonce init-coupons
      (get-server-coupons coupons))
    [:table {:id "coupons"
             :class (str "hide-extra "
                         (when (:showing-all? @table-state)
                           "showing-all"))}
     [coupons-table-header]
     [:tbody
      (map (fn [coupon]
             ^{:key (:id coupon)}
             [coupon-row coupon])
           @coupons)]]))

(defn coupons-header
  [table-state]
  [:h2 {:id "coupons-heading"}
   "Coupons "
   [:span {:class "show-all"
           :on-click #(swap! table-state update-in [:showing-all?] not)}
    (if (:showing-all? @table-state)
      "[hide after 7]"
      "[show all]")]])

(defn coupons-component
  []
  (let [table-state (r/atom {:showing-all? false})]
    (fn []
      [:div {:id "coupons-component"}
       [coupons-header table-state]
       [coupons-table table-state]])))

(defn zone-row
  [zone]
  ;; this is initial state
  (let [row-state (r/atom {:editing? false
                           :saving? false
                           :87 (:87 (:fuel_prices zone))
                           :91 (:91 (:fuel_prices zone))
                           :60 (:60 (:service_fees zone))
                           :180 (:180 (:service_fees zone))
                           :service-starts (first (:service_time_bracket zone))
                           :service-ends   (second (:service_time_bracket zone))
                           :error? false
                           :error-message ""
                           })]
    (fn [zone]
      (let [swap-state! #(swap!
                          row-state assoc
                          :87 (:87 (:fuel_prices zone))
                          :91 (:91 (:fuel_prices zone))
                          :60 (:60 (:service_fees zone))
                          :180 (:180 (:service_fees zone))
                          :service-starts (first (:service_time_bracket zone))
                          :service-ends
                          (second (:service_time_bracket zone)))]
        (when (and
               (not (:editing? @row-state))
               (not (:saving? @row-state)))
          (swap-state!))
        [:tr
         [:td (:id zone)]
         [:td {:style {:background-color (:color zone)
                       :color "white"}} (:name zone)]
         [:td
          [editable-input row-state :87 read-string]]
         [:td
          [editable-input row-state :91 read-string]]
         [:td
          [editable-input row-state :60 read-string]]
         [:td
          [editable-input row-state :180 read-string]]
         [:td
          [editable-input row-state :service-starts read-string]]
         [:td
          [editable-input row-state :service-ends read-string]]
         [:td (:zip_codes zone)]
         [:td [:button
               {:disabled (:saving? @row-state)
                :on-click
                (fn []
                  (when (:editing? @row-state)
                    (let [new-zone (assoc zone
                                          :fuel_prices
                                          {:87 (:87 @row-state)
                                           :91 (:91 @row-state)}
                                          :service_fees
                                          {:60  (:60 @row-state)
                                           :180 (:180 @row-state)}
                                          :service_time_bracket
                                          [(:service-starts @row-state)
                                           (:service-ends   @row-state)])]
                      (swap! row-state assoc :saving? true)
                      ;; update the zone on the server
                      (update-zone-server! new-zone row-state)))
                  (swap! row-state update-in [:editing?] not))}
               (cond
                 (:saving? @row-state)
                 "Save"
                 (:editing? @row-state)
                 "Save"
                 :else
                 "Edit")]
          (if (:saving? @row-state)
            [:i {:class "fa fa-spinner fa-pulse"}])]]))))

(defn zones-table-header
  []
  [:thead
   [:tr
    [:td "ID"] [:td "Name"] [:td "87 Price"] [:td "91 Price"] [:td "1 Hour Fee"]
    [:td "3 Hour Fee"] [:td "Service Starts"] [:td "Service Ends"]
    [:td "Zip Codes"] [:td]]])

(defn zones-table
  []
  (fn []
    (defonce init-zones
      (get-server-zones zones))
    [:table {:id "zones"}
     [zones-table-header]
     [:tbody
      (map (fn [zone]
             ^{:key (:id zone)}
             [zone-row zone])
           @zones)]]))

(defn zones-header
  []
  [:h2 {:id "zones-header"}
   "Zones"])

(defn zones-component
  []
  [:div {:id "zones-component"}
   [zones-header]
   [zones-table]])


(defn stats-component
  []
  (let [stats-state (r/atom {:processing? true
                             :timestamp ""})]
    (get-status-stats-csv stats-state)
    (fn []
      [:div {:class "stats"} "stats.csv "
       [:span {:class "fake-link"
               :on-click
               #(do
                  (retrieve-url
                   (str base-url "generate-stats-csv") "GET" {} (fn []))
                  (js/alert (str "stats.csv generation initiated."
                                 " Please refresh the page in a minute or so,"
                                 " then try to download the CSV file.."))
                  (get-status-stats-csv stats-state))}
        " [initiate a refresh]"]
       (when (not (:processing? @stats-state))
         [:a {:class "fake-link"
              :href (str base-url "download-stats-csv")}
          (str " [download " (unix-epoch->hrf
                              (:timestamp @stats-state)) "]")])])))

;; a map of component names, the respective component and the required urls
;; for that component
(def comp-req-urls [{:comp couriers-component
                     :comp-name "couriers-component"
                     :required-routes #{"/dashboard/couriers"}}
                    {:comp orders-component
                     :comp-name "orders-component"
                     :required-routes #{"/dashboard/orders-since-date"}}
                    {:comp users-component
                     :comp-name "users-component"
                     :required-routes #{"/dashboard/users"}}
                    {:comp coupons-component
                     :comp-name "coupons-component"
                     :required-routes #{"/dashboard/coupons"}}
                    {:comp zones-component
                     :comp-name "zones-component"
                     :required-routes #{"/dashboard/zones"}}
                    {:comp stats-component
                     :comp-name "stats-component"
                     :required-routes #{"/dashboard/generate-stats-csv"
                                        "/dashboard/download-stats-csv"}}
                    ])

(defn app
  []
  (let [accessible-routes (-> (.getElementById js/document "accessible-routes")
                              (.getAttribute "value")
                              (read-string))]
    (fn []
      [:div
       [:div "Last Updated: " (unix-epoch->hrf (/ (.now js/Date)
                                                  1000))
        [:a {:href (str base-url "logout")
             :class "fake-link"} " Logout"]]
       (map #(when (subset? (:required-routes %) accessible-routes)
               ^{:key (:comp-name %)} [(:comp %)])
            comp-req-urls)])))

(defn init-tables
  []
  (r/render-component [app] (.getElementById js/document "app")))
