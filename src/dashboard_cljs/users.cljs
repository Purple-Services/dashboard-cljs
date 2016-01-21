(ns dashboard-cljs.users
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton]]
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
          ;;[user-panel current-user]
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

