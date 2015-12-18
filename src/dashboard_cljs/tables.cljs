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
            [reagent.core :as r]))

(def couriers (r/atom #{}))

(def users (r/atom #{}))

(def orders (r/atom #{}))

(def coupons (r/atom #{}))

(def zones (r/atom #{}))

(def postal-code-test (atom (array)))

(def timeout-interval 1000)

;; the base url to use for server calls
(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(defn remove-by-id
  "Remove the element with id from state. Assumes all elements have an
:id key with a unique value"
  [state id]
  (set (remove #(= id (:id %)) state)))

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
  (do
    (retrieve-url
     (str base-url "couriers")
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
                                            (:message clj-response)))))))))

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

(defn field-input-handler
  "Returns a handler that updates value in atom map,
  under key, with value from on-change event"
  [atom key]
  (fn [e]
    (swap! atom
           assoc key
           (-> e
               (aget "target")
               (aget "value")))))

(defn editable-input [atom key]
  (if (or (:editing? @atom)
          (:saving? @atom))
    [:div
     [:input {:type "text"
              :disabled (:saving? @atom)
              :value (get @atom key)
              :on-change (field-input-handler atom key)
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
   "GET"
   {}
   (partial xhrio-wrapper
            #(reset!
              state
              (set (mapv
                    ;; convert json arrays to a string
                    (fn [courier]
                      (update-in courier [:zones]
                                 (fn [zone]
                                   (.join (clj->js (sort zone))))))
                    (vec (reverse
                          (sort-by :last_ping
                                   (js->clj (aget % "couriers")
                                            :keywordize-keys true))))))))))

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
   "GET"
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
              (set
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
                             (quot (.now js/Date) 1000))))))))))

(defn get-server-zones
  [zones-atom]
  (retrieve-url
   (str base-url "zones")
   "GET"
   {}
   (partial xhrio-wrapper
            #(reset!
              zones-atom
              (set
               (->>
                (js->clj % :keywordize-keys true)))))))

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
         "View On Map"
         ]]
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
    " [view couriers on map]"]
   ])

(defn couriers-component
  []
  (let [table-state (r/atom {:showing-all? false})]
    (fn []
      [:div {:id "couriers-component"}
       [couriers-header table-state]
       [couriers-table table-state]])))

(defn order-row
  [order]
  (fn [order]
    [:tr
     [:td {:class (when (:was-late order)
                    "late")} (:status order)]
     [:td (:courier_name order)]
     [:td (map (fn [eta]
                 ^{:key (:name eta)}
                 [:p (str (:name eta) " - ")
                  [:span {:class (when (:busy eta)
                                   "late")}
                   (:minutes eta)]])
           (sort-by :minutes (:etas order)))]
     [:td (unix-epoch->hrf (:target_time_start order))]
     [:td (unix-epoch->hrf (:target_time_end order))]
     [:td (:customer_name order)]
     [:td (:customer_phone_number order)]
     [:td {:style {:background-color (:zone-color order)
                   :color "white"}
           :class "address_street"
           }
      [:a {:href (str "https://maps.google.com/?q="
                      (:lat order)
                      ","
                      (:lng order))
           :target "_blank"}
       (:address_street order)]]
     [:td (:gallons order)]
     [:td (:gas_type order)]
     [:td (str (:color (:vehicle order))
               " "
               (:make (:vehicle order))
               " "
               (:model (:vehicle order)))]
     [:td (:license_plate order)]
     [:td (:coupon_code order)]
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
      (cents->dollars (:total_price order))]]))

(defn orders-table-header
  []
  [:thead
   [:tr
    [:td "Status"] [:td "Courier"] [:td "ETAs (mins)"] [:td "Order Placed"]
    [:td "Deadline"] [:td "Customer"] [:td "Phone"] [:td "Street"]
    [:td "Gallons"] [:td "Octane"] [:td "Vehicle"] [:td "Plate #"]
    [:td "Coupon"] [:td "Total Price"]]])

(defn orders-table
  [table-state]
  (fn []
    (defonce init-orders
      (get-server-orders orders ""))
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

(defn user-row [user]
  (fn [user]
    [:tr
     [:td {:class "name"} (:name user)]
     [:td {:class "email"} (:email user)]
     [:td {:class "phone_number"} (:phone_number user)]
     [:td {:class "has_added_card"} (if (s/blank? (:stripe_default_card user))
                                      "No"
                                      "Yes")]
     [:td {:class "push_set_up"} (if (s/blank? (:arn_endpoint user))
                                   "No"
                                   "Yes")]
     [:td {:class "os"} (:os user)]
     [:td {:class "app_version"} (:app_version user)]
     [:td {:class "timestamp_created"}
      (unix-epoch->hrf (:timestamp_created user))]]))

(defn users-table-header
  []
  [:thead
   [:tr
    [:td "Name"] [:td "Email"] [:td "Phone"] [:td "Card?"] [:td "Push?"]
    [:td "OS"] [:td "Version"] [:td "Joined"]]])

(defn users-table
  [table-state]
  (fn []
    ;; (defonce users-updater
    ;;   (continous-update #(get-))
    ;;   )
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
             [user-row user])
           @users)]]))

(defn users-header
  [table-state]
  [:h2 {:id "users-heading" }
   "Users "
   [:span {:class "count"} (str "(" (count @users) ") ")]
   [:span {:class "show-all"
           :on-click #(swap! table-state update-in [:showing-all?] not)
           }
    (if (:showing-all? @table-state)
      "[hide after 7]"
      "[show all]")]])

(defn users-component
  []
  (let [table-state (r/atom {:showing-all? false})]
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
  []
  (fn []
    (defonce init-coupons
      (get-server-coupons coupons))
    [:table {:id "coupons"}
     [coupons-table-header]
     [:tbody
      (map (fn [coupon]
             ^{:key (:id coupon)}
             [coupon-row coupon])
           @coupons)]]))

(defn coupons-header
  []
  [:h2 {:id "coupons-heading"}
   "Coupons"])

(defn coupons-component
  []
  [:div {:id "coupons-component"}
   [coupons-header]
   [coupons-table]])

(defn zone-row
  [zone]
  (fn [zone]
    (let [service_time_bracket (read-string (:service_time_bracket zone))]
      [:tr
       [:td (:id zone)]
       [:td {:style {:background-color (:color zone)
                     :color "white"}} (:name zone)]
       [:td (:87 (:fuel_prices zone))]
       [:td (:91 (:fuel_prices zone))]
       [:td (:60 (:service_fees zone))]
       [:td (:180 (:service_fees zone))]
       [:td (first service_time_bracket)]
       [:td (second service_time_bracket)]
       [:td (:zip_codes zone)]])))

(defn zones-table-header
  []
  [:thead
   [:tr
    [:td "ID"] [:td "Name"] [:td "87 Price"] [:td "91 Price"] [:td "1 Hour Fee"]
    [:td "3 Hour Fee"] [:td "Service Starts"] [:td "Service Ends"]
    [:td "Zip Codes"]]])

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
                    ])

(defn app
  []
  (let [accessible-routes (-> (.getElementById js/document "accessible-routes")
                              (.getAttribute "value")
                              (read-string))]
    (fn []
      [:div
       (map #(when (subset? (:required-routes %) accessible-routes)
               ^{:key (:comp-name %)} [(:comp %)])
            comp-req-urls)])))

(defn init-tables
  []
  (r/render-component [app] (.getElementById js/document "app")))
