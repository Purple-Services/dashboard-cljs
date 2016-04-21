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
                                               GoogleMapLink]]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [unix-epoch->fmt base-url markets
                                          accessible-routes pager-helper!
                                          diff-message]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [get-cached-gmaps gmap]]))

(def default-courier {:editing? false
                      :retrieving? false
                      :zones ""
                      :errors nil})

(def state (r/atom {:edit-courier default-courier
                    :current-courier nil
                    :confirimng-edit? false
                    :alert-success ""}))

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
     [:td [TelephoneNumber (:phone_number courier)]]
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
      " "
      [GoogleMapLink (:address_street order) (:lat order) (:lng order)]]
     ;; username
     [:td (:customer_name order)]
     ;; phone #
     [:td [TelephoneNumber (:customer_phone_number order)]]
     ;; email
     [:td [Mailto (:email order)]]
     ;; status
     [:td (:status order)]
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
        zones->str (fn [zones] (-> zones
                                   sort
                                   clj->js
                                   .join))
        diff-key-str {:zones "Assigned Zones"}
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
       [KeyVal "Email" [Mailto (:email @current-courier)]]
       ;; phone number
       [KeyVal "Phone Number" [TelephoneNumber
                               (:phone_number @current-courier)]]
       ;; date started
       [KeyVal "Date Started" (unix-epoch->fmt
                               (:timestamp_created @current-courier)
                               "M/D/YYYY")]
       ;; last active (last ping)
       [KeyVal "Last Active" (unix-epoch->fmt
                              (:last_ping @current-courier)
                              "M/D/YYYY h:mm A")]
       ;; courier zones
       (if @editing?
         [:div
          [FormGroup {:label "Assigned Zones"
                      :label-for "courier zones"
                      :errors (:zones @errors)}
           [TextInput {:value @zones
                       :default-value @zones
                       :on-change #(reset!
                                    zones
                                    (-> %
                                        (aget "target")
                                        (aget "value")))}]]]
         [KeyVal "Assigned Zones" (zones->str (:zones @current-courier))])
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

(defn courier-panel
  "Display detailed and editable fields for an courier. current-courier is an
  r/atom"
  [current-courier]
  (let [google-marker (atom nil)
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        show-orders? (r/atom false)
        current-page (r/atom 1)
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
            paginated-orders (pager-helper! sorted-orders current-page)]
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
         [:div {:class "row"}
          [:div {:class "col-xs-6 pull-left"}
           [:div [:h3 {:style {:margin-top 0}} (:name @current-courier)]]
           ;; main display panel
           [:div
            [courier-form current-courier]
            [:br]
            [:button {:type "button"
                      :class "btn btn-sm btn-default"
                      :on-click #(swap! show-orders? not)}
             (if @show-orders?
               "Hide Orders"
               "Show Orders")]]]
          [:div {:class "pull-right hidden-xs"}
           [gmap {:id :couriers
                  :style {:height 300
                          :width 300}
                  :center {:lat (:lat @current-courier)
                           :lng (:lng @current-courier)}}]]]
         ;; Table of orders for current courier
         (when (subset? #{{:uri "/orders-since-date"
                           :method "POST"}}
                        @accessible-routes)
           (when (> (count paginated-orders)
                    0)
             [:div {:class "row"}
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
                  :current-page current-page}])]))]))))

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
        filters {"Show All" (constantly true)
                 "Connected" :connected}
        selected-filter (r/atom "Connected")]
    (fn [couriers]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-couriers couriers
            sorted-couriers (->> displayed-couriers
                                 sort-fn
                                 (filter (get filters @selected-filter))
                                 (filter :active)
                                 (partition-all page-size))
            paginated-couriers (-> sorted-couriers
                                   (nth (- @current-page 1)
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
        ;; reset the current-courier if it is nil
        (when (nil? @current-courier)
          (reset! current-courier (first paginated-couriers)))
        (reset-edit-courier! edit-courier current-courier)
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [courier-panel current-courier]
          [:div {:class "btn-toolbar pull-left"
                 :role "toolbar"}
           [TableFilterButtonGroup {:hide-counts #{}}
            filters couriers selected-filter]]
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group"
                  :role "group"}
            [RefreshButton {:refresh-fn refresh-fn}]]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [courier-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (courier-row current-courier)}
           paginated-couriers]]
         [TablePager
          {:total-pages (count sorted-couriers)
           :current-page current-page}]]))))
