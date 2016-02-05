(ns dashboard-cljs.couriers
  (:require [cljs.core.async :refer [put!]]
            [cljsjs.moment]
            [clojure.set :refer [subset?]]
            [clojure.string :as s]
            [cljs.reader :refer [read-string]]
            [reagent.core :as r]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton KeyVal StarRating
                                               ErrorComp TablePager]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [unix-epoch->fmt base-url markets
                                          accessible-routes]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [get-cached-gmaps gmap]]))

(defn courier-row
  "A table row for an courier in a table. current-courier is an r/atom."
  [current-courier]
  (fn [courier]
    [:tr {:class (when (= (:id courier)
                          (:id @current-courier))
                   "active")
          :on-click #(reset! current-courier courier)}
     ;; name
     [:td (:name courier)]
     ;; market
     [:td (markets (quot (first (:zones courier)) 50))]
     ;; orders count
     [:td (->> @datastore/orders
               (filter (fn [order] (= (:id courier)
                                      (:courier_id order))))
               (filter (fn [order] (not (contains? #{"cancelled" "complete"}
                                                   (:status order)))))
               count)]
     ;; phone
     [:td (:phone_number courier)]
     ;; joined
     [:td (unix-epoch->fmt (:timestamp_created courier) "M/D/YYYY")]
     ;; status
     [:td
      (let [connected? (:connected courier)]
        [:div
         [:i {:class
              (str "fa fa-circle "
                   (if connected?
                     "courier-active"
                     "courier-inactive"
                     ))}]
         (if connected?
           " Connected"
           " Disconnected")])]]))

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
       (conj props {:keyword :name})
       "Name"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Market"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Current Orders"]
      [TableHeadSortable
       (conj props {:keyword :phone_number})
       "Phone"] 
      [TableHeadSortable
       (conj props {:keyword :timestamp_created})
       "Joined"] 
      [TableHeadSortable
       (conj props {:keyword :connected})
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
       (conj props {:keyword :address_street})
       "Order Address"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Username"]
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Phone #"]
      [TableHeadSortable
       (conj props {:keyword :email})
       "Email"]
      [TableHeadSortable
       (conj props {:keyword :status})
       "Status"]
      [TableHeadSortable
       (conj props {:keyword :number_rating})
       "Courier Rating"]]]))

(defn courier-orders-row
  "Table row to display a couriers orders"
  []
  (fn [order]
    [:tr
     ;; order address
     [:td [:i {:class "fa fa-circle"
               :style {:color (:zone-color order)}}]
      (:address_street order)]
     ;; username
     [:td (:customer_name order)]
     ;; phone #
     [:td (:customer_phone_number order)]
     ;; email
     [:td [:a {:href (str "mailto:" (:email order))} (:email order)]]
     ;; status
     [:td (:status order)]
     ;; star rating
     [:td
      (let [number-rating (:number_rating order)]
        (when number-rating
          [StarRating number-rating]))]]))


(defn save-button
  "props is:
  {
  :editing?    ; ratom boolean, is the field currently being edited?
  :retrieving? ; ratom boolean, is the data being retrieved?
  :on-click    ; fn, button on-click fn
  }"
  [props]
  (fn [{:keys [editing? retrieving? on-click]}]
    ;; save/edit button
    [:button {:type "button"
              :class "btn btn-xs btn-default"
              :on-click on-click}
     (cond
       @retrieving?
       [:i {:class "fa fa-spinner fa-pulse"}]
       @editing?
       "Save"
       :else
       "Edit")]))

(defn text-input
  "props is:
  {
  :error?        ; boolean, is there an error for the field?
  :default-value ; str, defaultValue for field
  :on-change     ; fn, fn for handling on-change events
  }"
  [props]
  (fn [{:keys [error? default-value on-change]}]
    [:input {:type "text"
             :defaultValue default-value
             :class (str " "
                         (when error?
                           "error"))
             :on-change on-change}]))

(defn courier-zones-comp
  "Component to edit a courier's assigned zones
  props is:
  {
  :editing?         ; ratom boolean, is the field currently being edited?
  :input-value      ; ratom str, the current input-value
  :error-message    ; ratom str, error message, if any, associated with input
  :courier          ; ratom, currently selected courier
  }
  "
  [props]
  (let [retrieve-courier (fn [editing? retrieving? courier]
                           (retrieve-url
                            (str base-url "courier/" (:id @courier))
                            "GET"
                            {}
                            (partial xhrio-wrapper
                                     #(let [response (js->clj % :keywordize-keys
                                                              true)]
                                        ;; the response is valid
                                        (when (not (empty? response))
                                          ;; update the datastore
                                          (put! datastore/modify-data-chan
                                                {:topic "couriers"
                                                 :data response})
                                          ;; update the courier
                                          (reset! courier (first response))
                                          ;; no longer retrieving
                                          (reset! retrieving? false)
                                          ;; no longer editing
                                          (reset! editing? false))
                                        ;; there was an error
                                        (when (:success response))))))
        update-courier (fn [editing? retrieving? error-message retrieve-courier
                            courier input-value]
                         (retrieve-url
                          (str base-url "courier")
                          "POST"
                          (js/JSON.stringify (clj->js
                                              {:id (:id @courier)
                                               :zones @input-value}))
                          (partial xhrio-wrapper
                                   #(let [response (js->clj % :keywordize-keys
                                                            true)]
                                      (when (:success response)
                                        ;; no longer editing courier
                                        ;;(reset! editing? false)
                                        (retrieve-courier editing? retrieving?
                                                          courier))
                                      (when (not (:success response))
                                        ;; should still be editing
                                        (reset! editing? true)
                                        ;; not retrieving
                                        (reset! retrieving? false)
                                        ;; error message
                                        (reset! error-message
                                                (:message response)))))))
        save-button-on-click (fn [editing? retrieving? input-value current-value
                                  error-message courier retrieve-courier]
                               (fn [e]
                                 (if @editing?
                                   (do
                                     ;; the error message is always reset
                                     (reset! error-message "")
                                     (when (= @input-value current-value)
                                       (reset! editing? false))
                                     (when (not= @input-value current-value)
                                       ;; retrieving from the server
                                       (reset! retrieving? true)
                                       ;; attempt to update the courier
                                       (update-courier
                                        editing? retrieving?
                                        error-message retrieve-courier
                                        courier input-value)))
                                   (reset! editing? true))))
        text-input-on-change (fn [input-value]
                               (fn [e]
                                 (let [input-val (-> e
                                                     (aget "target")
                                                     (aget "value"))]
                                   (reset! input-value input-val))))]
    (fn [{:keys [editing?
                 input-value
                 error-message
                 courier]}]
      (let [current-value (-> (:zones @courier)
                              sort
                              clj->js
                              .join)
            retrieving? (r/atom false)]
        [:h5 [:span {:class "info-window-label"} "Zones: "]
         (when (not @editing?)
           (str current-value " "))
         (when @editing?
           [text-input {:error? (not (s/blank? @error-message))
                        :default-value @input-value
                        :on-change (text-input-on-change input-value)}])
         (when (subset? #{{:uri "/dashboard/courier"
                           :method "POST"}}
                        @accessible-routes)
           [save-button {:editing? editing?
                         :retrieving? retrieving?
                         :on-click (save-button-on-click
                                    editing?
                                    retrieving?
                                    input-value
                                    current-value
                                    error-message
                                    courier
                                    retrieve-courier)}])
         (when (not (s/blank? @error-message))
           [ErrorComp (str "Courier could not be assigned! Reason: "
                           @error-message)])]))))


(defn courier-panel
  "Display detailed and editable fields for an courier. current-courier is an
  r/atom"
  [current-courier]
  (let [google-marker (atom nil)
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        show-orders? (r/atom false)
        pagenumber (r/atom 1)
        page-size 5]
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
            paginated-orders (-> sorted-orders
                                 (nth (- @pagenumber 1)
                                      '()))]
        ;; create and insert courier marker
        (when (:lat @current-courier)
          (when @google-marker
            (.setMap @google-marker nil))
          (reset! google-marker (js/google.maps.Marker.
                                 (clj->js {:position
                                           {:lat (:lat @current-courier)
                                            :lng (:lng @current-courier)
                                            }
                                           :map (second (get-cached-gmaps
                                                         :couriers))
                                           }))))
        ;; populate the current courier with additional information
        [:div {:class "panel-body"}
         [:div [:h3 (:name @current-courier)]]
         ;; google map
         [:div {:class "row"}
          [:div 
           [gmap {:id :couriers
                  :style {:height 300
                          :width 300}
                  :center {:lat (:lat @current-courier)
                           :lng (:lng @current-courier)}}]]
          ;; main display panel
          [:div 
           ;; email
           [KeyVal "Email" (:email @current-courier)]
           ;; phone number
           [KeyVal "Phone Number" (:phone_number @current-courier)]
           ;; date started
           [KeyVal "Date Started" (unix-epoch->fmt
                                   (:timestamp_created @current-courier)
                                   "M/D/YYYY")]
           ;; last active (last ping)
           [KeyVal "Last Active" (unix-epoch->fmt
                                  (:last_ping @current-courier)
                                  "M/D/YYYY h:mm A"
                                  )]
           ;; zones the courier is currently assigned to
           [courier-zones-comp {:editing? editing-zones?
                                ;;:zones (:zones @current-courier)
                                :input-value zones-input-value
                                :error-message zones-error-message
                                :courier current-courier}]]]
         ;; Table of orders for current courier
         (when (subset? #{{:uri "/dashboard/orders-since-date"
                           :method "POST"}}
                        @accessible-routes)
           [:div {:class "row"}
            (when (> (count sorted-orders)
                     0)
              [:button {:type "button"
                        :class "btn btn-sm btn-default"
                        :on-click #(swap! show-orders? not)
                        }
               (if @show-orders?
                 "Hide Orders"
                 "Show Orders")])
            [:div {:class "table-responsive"
                   :style (if @show-orders?
                            {}
                            {:display "none"})}
             [StaticTable
              {:table-header [courier-orders-header
                              {:sort-keyword sort-keyword
                               :sort-reversed? sort-reversed?}]
               :table-row (courier-orders-row)}
              paginated-orders]]
            (when @show-orders?
              [TablePager
               {:total-pages (count sorted-orders )
                :pagenumber pagenumber}])
            ])]))))

(defn couriers-panel
  "Display a table of selectable couriers with an indivdual courier panel
  for the selected courier. couriers is set of couriers"
  [couriers]
  (let [current-courier (r/atom nil)
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        selected-filter (r/atom "show-all")
        pagenumber (r/atom 1)
        page-size 5]
    (fn [couriers]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (cond (= @selected-filter
                               "declined")
                            (fn [courier]
                              (and (not (:paid courier))
                                   (= (:status courier) "complete")
                                   (> (:total_price courier))))
                            :else (fn [courier] true))
            displayed-couriers couriers
            sorted-couriers (->> displayed-couriers
                                 sort-fn
                                 (partition-all page-size))
            paginated-couriers (-> sorted-couriers
                                 (nth (- @pagenumber 1)
                                      '()))
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
                               (reset! saving? false))))))]
        (when (nil? @current-courier)
          (reset! current-courier (first paginated-couriers)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [courier-panel current-courier]
          [:h3 "Couriers"]
          [:div {:class "btn-toolbar"
                 :role "toolbar"
                 :aria-label "Toolbar with button groups"}
           [:div {:class "btn-group"
                  :role "group"
                  :aria-label "refresh group"}
            [RefreshButton {:refresh-fn
                            refresh-fn}]]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [courier-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (courier-row current-courier)}
           paginated-couriers]]
         [TablePager
          {:total-pages (count sorted-couriers)
           :pagenumber pagenumber}]]))))
