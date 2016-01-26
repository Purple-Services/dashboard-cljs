(ns dashboard-cljs.coupons
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->dollars]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton]]
            [clojure.string :as s]))

(def state (r/atom {:current-coupon nil}))

(defn coupon-table-header
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
       (conj props {:keyword :code})
       "Promo Code"]
      [TableHeadSortable
       (conj props {:keyword :value})
       "Amount"]
      [TableHeadSortable
       (conj props {:keyword :timestamp_created})
       "Start Date"]
      [TableHeadSortable
       (conj props {:keyword :expiration_time})
       "Expiration Date"]
      [TableHeadSortable
       (conj props {:keyword :only_for_first_orders})
       "First Order Only?"]]]))

(defn coupon-row
  "A table row for a coupon."
  [current-coupon]
  (fn [coupon]
    [:tr {:class (when (= (:id coupon)
                          (:id @current-coupon))
                   "active")
          :on-click #(reset! current-coupon coupon)}
     ;; code
     [:td (:code coupon)]
     ;; amount
     [:td (cents->dollars (.abs js/Math (:value coupon)))]
     ;; start date
     ;; !!!NEEDS TO BE CONVERTED!
     [:td (:timestamp_created coupon)]
     ;; expiration date
     [:td (unix-epoch->fmt (:expiration_time coupon) "M/D/YYYY")]
     ;; first order only?
     [:td (if (:only_for_first_orders coupon)
            "Yes"
            "No")]]))

(defn coupons-panel
  "Display a table of coupons"
  [coupons]
  (let [current-coupon (r/cursor state [:current-coupon])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)]
    (fn [coupons]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-coupons coupons
            sorted-coupons (->> displayed-coupons
                                sort-fn)
            refresh-fn (fn [refreshing?]
                         (reset! refreshing? true)
                         (retrieve-url
                          (str base-url "coupons")
                          "GET"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             ;; update the users atom
                             (put! datastore/modify-data-chan
                                   {:topic "coupons"
                                    :data (js->clj response :keywordize-keys
                                                   true)})
                             (reset! refreshing? false)))))
            ]
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          ;;[user-panel current-user]
          [:h3 "Coupons"]
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
           {:table-header [coupon-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (coupon-row current-coupon)}
           sorted-coupons]]]
        ))))
