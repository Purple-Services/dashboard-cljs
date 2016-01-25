(ns dashboard-cljs.users
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton KeyVal StarRating]]
            [clojure.string :as s]))

(def push-selected-users (r/atom (set nil)))

(defn user-row
  "A table row for an user in a table. current-user is the one currently 
  being viewed"
  [current-user]
  (fn [user]
    (let [orders (->> @datastore/orders
                      (filter (fn [order] (= (:id user)
                                             (:user_id order))))
                      (filter (fn [order] (contains? #{"complete"}
                                                     (:status order)))))]
      [:tr {:class (when (= (:id user)
                            (:id @current-user))
                     "active")
            :on-click #(reset! current-user user)}
       ;; name
       [:td (:name user)]
       ;; market
       [:td
        (-> orders
            first
            :zone
            (quot 50)
            markets)]
       ;; orders count
       [:td (count orders)]
       ;; email
       [:td (:email user)]
       ;; phone
       [:td (:phone_number user)]
       ;; joined
       [:td (unix-epoch->fmt (:timestamp_created user) "M/D/YYYY")]])))

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
                    :font-weight "normal"}} "Total Orders"]
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
     ;; courier name
     [:td (:courier_name order)]
     ;; payment info
     [:td (:customer_phone_number order)]
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
                               sort-fn)
            default-card-info (if (empty? (:stripe_cards @current-user))
                                nil
                                (->> (:stripe_cards @current-user)
                                     json-string->clj
                                     (filter #(= (:stripe_default_card
                                                  @current-user)
                                                 (:id %)))
                                     first))
            ]
        ;; create and insert user market
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
           (let [most-recent-order-time (->> orders
                                             (sort-by :target_time_start)
                                             first
                                             :target_time_start)]
             (when (not (nil? most-recent-order-time))
               [KeyVal "Last Active" (unix-epoch->fmt
                                      most-recent-order-time
                                      "M/D/YYYY")]))
           (when (not (nil? default-card-info))
             [KeyVal "Default Credit Card"
              (str
               (:brand default-card-info)
               " "
               (:last4 default-card-info)
               " "
               (when (not (empty? (:exp_month default-card-info)))
                 (:exp_month default-card-info)
                 "/"
                 (:exp_year default-card-info)))])
           ]]
         ;; Table of orders for current user
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

(defn user-notification-header
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
      [TableHeadSortable
       (conj props {:keyword :phone_number})
       "Phone"]
      [TableHeadSortable
       (conj props {:keyword :email})
       "Email"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Push?"]]]))

(defn user-notification-row
  "Table row to display a user"
  []
  (fn [user]
    [:tr
     ;; name
     [:td (:name user)]
     ;;phone
     [:td (:phone_number user)]
     ;; email
     [:td (:email user)]
     ;; push?
     [:td (if (s/blank? (:arn_endpoint user))
            [:div "No"]
            [:div "Yes "
             [:input {:type "checkbox"
                      :on-change #(let [this-checked? (-> %
                                                          (aget "target")
                                                          (aget "checked"))]
                                    (if this-checked?
                                      ;; add current user id
                                      (swap! push-selected-users
                                             (fn [selected-atom]
                                               (conj selected-atom
                                                     (:id user))))
                                      ;; remove current user id
                                      (swap! push-selected-users
                                             (fn [selected-atom]
                                               (disj selected-atom
                                                     (:id user))))))
                      :checked (contains? @push-selected-users (:id user))}]])]]))

(defn user-push-notification
  "A prop for sending push notifications to users"
  []
  (let [all-selected? (r/atom true)
        approved?     (r/atom false)
        confirming?   (r/atom false)
        message       (r/atom (str))
        alert-success (r/atom (str))
        alert-error   (r/atom (str))
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        ]
    (fn []
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-users @datastore/users
            sorted-users (->> displayed-users
                              sort-fn)
            ;;push-selected-users (r/atom (set nil))
            ]
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div [:h4 {:class "pull-left"} "Send Push Notification"]
           [:div {:class "btn-toolbar"
                  :role "toolbar"
                  :aria-label "Toolbar with button groups"}
            [:div {:class "btn-group"
                   :role "group"
                   :aria-label "Select all or some users"}
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when @all-selected?
                                     "active"))
                       :on-click #(reset! all-selected? true)
                       }
              "All"]
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (not @all-selected?)
                                     "active"))
                       :on-click #(reset! all-selected? false)
                       }
              "Selected"]]]]
          (if @confirming?
            ;; confirmation
            [:div {:class "alert alert-danger alert-dismissible"}
             [:button {:type "button"
                       :class "close"
                       :aria-label "Close"}
              [:i {:class "fa fa-times"
                   :on-click (fn [e]
                               (reset! confirming? false)
                               (reset! message ""))}]]
             (str "Are you sure you want to send the following message to "
                  (if @all-selected?
                    "all"
                    "all selected")
                  " users?")
             [:h4 [:strong @message]]
             [:button {
                       :type "button"
                       :class "btn btn-default"
                       :on-click
                       (fn [e]
                         (retrieve-url
                          (if @all-selected?
                            (str base-url "send-push-to-all-active-users")
                            (str base-url "send-push-to-users-list"))
                          "POST"
                          (js/JSON.stringify
                           (if @all-selected?
                             (clj->js {:message @message})
                             (clj->js {:message @message
                                       :user-ids @push-selected-users})))
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             (let [success? (:success
                                             (js->clj response
                                                      :keywordize-keys
                                                      true))]
                               (when success?
                                 ;; confirm message was sent
                                 (reset! alert-success "Sent!"))
                               (when (not success?)
                                 (reset! alert-error
                                         (str "Something went wrong."
                                              " Push notifications may or may"
                                              " not have been sent. Wait until"
                                              " sure before trying again."))))
                             (reset! confirming? false)
                             (reset! message "")))))
                       }
              "Confirm"]
             [:button {
                       :type "button"
                       :class "btn btn-default"
                       :on-click (fn [e]
                                   (reset! confirming? false)
                                   (reset! message ""))
                       }
              "Cancel"]]
            ;; Message form
            [:form
             [:div {:class "form-group"}
              [:label {:for "notification-message"} "Message"]
              [:input {:type "text"
                       :defaultValue ""
                       :class "form-control"
                       :placeholder "Message"
                       :on-change (fn [e]
                                    (reset! message (-> e
                                                        (aget "target")
                                                        (aget "value")))
                                    (reset! alert-error "")
                                    (reset! alert-success ""))}]]
             [:button {:type "submit"
                       :class "btn btn-default"
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (when (not (empty? @message))
                                     (reset! confirming? true)))
                       :disabled (and (not @all-selected?)
                                      (empty? @push-selected-users))
                       } "Send Notification"]
             ;; clear all selected users
             (when (not @all-selected?)
               [:button {:type "submit"
                         :class "btn btn-default"
                         :on-click (fn [e]
                                     (.preventDefault e)
                                     (reset! push-selected-users (set nil)))
                         } "Clear Selected Users"])
             ])
          ;; alert message
          (when (not (empty? @alert-success))
            [:div {:class "alert alert-success alert-dismissible"}
             [:button {:type "button"
                       :class "close"
                       :aria-label "Close"}
              [:i {:class "fa fa-times"
                   :on-click #(reset! alert-success "")}]
              ]
             [:strong @alert-success]])
          ;; alert error
          (when (not (empty? @alert-error))
            [:div {:class "alert alert-error"}
             @alert-error])
          ;; selected users table
          (when (not @all-selected?)
            [:div {:class "table-responsive"}
             [StaticTable
              {:table-header [user-notification-header
                              {:sort-keyword sort-keyword
                               :sort-reversed? sort-reversed?}]
               :table-row (user-notification-row)
               }
              sorted-users]])]]))))
