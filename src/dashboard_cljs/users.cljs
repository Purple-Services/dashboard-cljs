(ns dashboard-cljs.users
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton KeyVal StarRating]]
            [clojure.string :as s]))


(defn user-row
  "A table row for an user in a table. current-user is the one currently 
  being viewed"
  [current-user]
  (fn [user]
    [:tr {:class (when (= (:id user)
                          (:id @current-user))
                   "active")
          :on-click #(reset! current-user user)}
     ;; name
     [:td (:name user)]
     ;; market
     [:td ;;(markets (quot (first (:zones user)) 50))
      "market placeholder"
      ]
     ;; orders count
     [:td
      ;; (->> @datastore/orders
      ;;      (filter (fn [order] (= (:id user)
      ;;                             (:user_id order))))
      ;;      (filter (fn [order] (not (contains? #{"cancelled" "complete"}
      ;;                                          (:status order)))))
      ;;      count)
      "orders count placeholder"
      ]
     ;; email
     [:td (:email user)]
     ;; phone
     [:td (:phone_number user)]
     ;; joined
     [:td (unix-epoch->fmt (:timestamp_created user) "M/D/YYYY")]]))

(defn user-table-header
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
                    :font-weight "normal"}} "Orders"]
      [TableHeadSortable
       (conj props {:keyword :email})
       "Email"] 
      [TableHeadSortable
       (conj props {:keyword :phone_number})
       "Phone"] 
      [TableHeadSortable
       (conj props {:keyword :timestamp_created})
       "Joined"]]]))

(defn user-orders-header
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
       (conj props {:keyword :courier_name})
       "Courier Name"]
      ;; [TableHeadSortable
      ;;  (conj props {:keyword :customer_phone_number})
      ;;  "Phone #"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Payment"]
      [TableHeadSortable
       (conj props {:keyword :status})
       "Status"]
      [TableHeadSortable
       (conj props {:keyword :number_rating})
       "Rating"]]]))

(defn user-orders-row
  "Table row to display a users orders"
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

(defn user-panel
  "Display detailed and editable fields for an user. current-user is an
  r/atom"
  [current-user]
  (let [sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        show-orders? (r/atom false)
        ]
    (fn [current-user]
      (let [;;editing-zones? (r/atom false)
            ;;zones-error-message (r/atom "")
            ;; zones-input-value (r/atom (-> (:zones @current-user)
            ;;                               sort
            ;;                               clj->js
            ;;                               .join))
            sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            
            orders
            ;; filter out the orders to only those assigned
            ;; to the user
            (->> @datastore/orders
                 (filter (fn [order]
                           (= (:id @current-user)
                              (:user_id order)))))
            sorted-orders (->> orders
                               sort-fn)]
        ;; create and insert user marker
        ;; (when (:lat @current-user)
        ;;   (when @google-marker
        ;;     (.setMap @google-marker nil))
        ;;   (reset! google-marker (js/google.maps.Marker.
        ;;                          (clj->js {:position
        ;;                                    {:lat (:lat @current-user)
        ;;                                     :lng (:lng @current-user)
        ;;                                     }
        ;;                                    :map (second (get-cached-gmaps
        ;;                                                  :users))
        ;;                                    }))))
        ;; populate the current user with additional information
        [:div {:class "panel-body"}
         [:div [:h3 (:name @current-user)]
          ;; [:div {:class "pull-right"}
          ;;  [RefreshButton {:refresh-fn
          ;;                  #(.log js/console "user refresh hit")}]]
          ]
         ;; google map
         [:div {:class "row"}
          ;; [:div 
          ;;  [gmap {:id :users
          ;;         :style {:height 300
          ;;                 :width 300}
          ;;         :center {:lat (:lat @current-user)
          ;;                  :lng (:lng @current-user)}}]]
          ;; main display panel
          [:div 
           ;; email
           [KeyVal "Email" (:email @current-user)]
           ;; phone number
           [KeyVal "Phone Number" (:phone_number @current-user)]
           ;; date started
           [KeyVal "Registered" (unix-epoch->fmt
                                   (:timestamp_created @current-user)
                                   "M/D/YYYY")]
           ;; last active (last ping)
           [KeyVal "Last Active"
            ;; (unix-epoch->fmt
            ;;  (:last_ping @current-user)
            ;;  "M/D/YYYY h:mm A"
            ;;  )
            ;; last active will be the date of the last order
            "last active placeholder"
            ]
           [KeyVal "Credit Card"
            ;; default card?
            "credit card placeholer"
            ]
           ;; zones the user is currently assigned to
           ;; [user-zones-comp {:editing? editing-zones?
           ;;                      ;;:zones (:zones @current-user)
           ;;                      :input-value zones-input-value
           ;;                      :error-message zones-error-message
           ;;                      :user current-user}]
           ]]
         ;; Table of orders for current user
         [:div {:class "row"}
          [:button {:type "button"
                    :class "btn btn-sm btn-default"
                    :on-click #(swap! show-orders? not)
                    }
           (if @show-orders?
             "Hide Orders"
             "Show Orders")]
          [:div {:class "table-responsive"
                 :style (if @show-orders?
                          {}
                          {:display "none"})}
           [StaticTable
            {:table-header [user-orders-header
                            {:sort-keyword sort-keyword
                             :sort-reversed? sort-reversed?}]
             :table-row (user-orders-row)}
            sorted-orders]
           ]]]))))

(defn users-panel
  "Display a table of selectable coureirs with an indivdual user panel
  for the selected user"
  [users]
  (let [current-user (r/atom nil)
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        selected-filter (r/atom "show-all")]
    (fn [users]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (cond (= @selected-filter
                               "declined")
                            (fn [user]
                              (and (not (:paid user))
                                   (= (:status user) "complete")
                                   (> (:total_price user))))
                            :else (fn [user] true))
            displayed-users users
            sorted-users (->> displayed-users
                               sort-fn
                               ;;(filter filter-fn)
                               )
            refresh-fn (fn [saving?]
                         (reset! saving? true)
                         (retrieve-url
                          (str base-url "users")
                          "GET"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             ;; update the users atom
                             (put! datastore/modify-data-chan
                                   {:topic "users"
                                    :data (js->clj response :keywordize-keys
                                                   true)})
                             (reset! saving? false)))))]
        (when (nil? @current-user)
          (reset! current-user (first sorted-users)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [user-panel current-user]
          [:h3 "Users"]
          [:div {:class "btn-toolbar"
                 :role "toolbar"
                 :aria-label "Toolbar with button groups"}
           [:div {:class "btn-group"
                  :role "group"
                  :aria-label "refresh group"}
            ;;[refresh-button]
            [RefreshButton {:refresh-fn
                               refresh-fn}]
            ]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [user-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (user-row current-user)}
           sorted-users]]]))))

