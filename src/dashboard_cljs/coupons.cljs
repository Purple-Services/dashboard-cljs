(ns dashboard-cljs.coupons
  (:require [reagent.core :as r]
            [crate.core :as crate]
            [cljs.core.async :refer [put!]]
            [cljsjs.pikaday.with-moment]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          format-coupon-code]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton]]
            [clojure.string :as s]))

(def default-new-coupon
  {:code nil
   :value "1.00"
   :expiration_time (-> (js/moment)
                        (.endOf "day")
                        (.unix))
   :only_for_first_orders false
   :errors nil
   :retrieving? false
   :alert-success ""})

(def state (r/atom {:current-coupon nil
                    :new-coupon
                    default-new-coupon
                    :selected "active"}))

(defn exp-date-picker
  []
  (let [exp-date (r/cursor state [:new-coupon :expiration_time])]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (js/Pikaday.
         (clj->js {:field (r/dom-node this)
                   :format "M/D/YYYY"
                   :onSelect (fn [input]
                               (reset! exp-date
                                       (-> (js/moment input)
                                           (.endOf "day")
                                           (.unix))))
                   })))
      :reagent-render
      (fn []
        [:input {:type "text"
                 :class "form-control date-picker"
                 :placeholder "Choose Date"
                 :defaultValue (-> @exp-date (js/moment.unix)
                                   (.format "M/D/YYYY"))
                 :value (-> @exp-date
                            (js/moment.unix)
                            (.format "M/D/YYYY"))
                 :on-change (fn [input]
                               (reset! exp-date
                                       (-> (js/moment input)
                                           (.endOf "day")
                                           (.unix))))
                 }])})))

(defn new-coupon-form
  "Form for a new coupon"
  []
  (let [code (r/cursor state [:new-coupon :code])
        value (r/cursor state [:new-coupon :value])
        only-for-first-order (r/cursor state
                                       [:new-coupon :only_for_first_orders])
        new-coupon (r/cursor state [:new-coupon])
        errors (r/cursor state [:new-coupon :errors])
        retrieving? (r/cursor state [:new-coupon :retrieving?])
        alert-success (r/cursor state [:new-coupon :alert-success])
        ]
    (fn []
      [:form {:class "form-horizontal"}
       ;; promo code
       [:div {:class "form-group"}
        [:label {:for "promo code"
                 :class "col-sm-2 control-label"}
         "Promo Code"]
        [:div {:class "col-sm-10"}
         [:input {:type "text"
                  :class "form-control"
                  :placeholder "Promo Code"
                  :defaultValue @code
                  :value @code
                  :on-change #(reset! code (-> %
                                               (aget "target")
                                               (aget "value")
                                               (format-coupon-code)
                                               ))
                  }]
         (when (:code @errors)
           [:div {:class "alert alert-danger"}
            (first (:code @errors))])
         ]]
       ;; amount
       [:div {:class "form-group"}
        [:label {:for "amount"
                 :class "col-sm-2 control-label"}
         "Amount"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:div {:class "input-group-addon"}
           "$"]
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "Amount"
                   ;; :default-value (cents->dollars @value)
                   :value @value
                   :on-change #(reset! value (-> %
                                                 (aget "target")
                                                 (aget "value")
                                                 ;;(dollars->cents)

                                                 ))
                   }]]
         (when (:value @errors)
           [:div {:class "alert alert-danger"}
            (first (:value @errors))])]
          ]
       ;; exp date
       [:div {:class "form-group"}
        [:label {:for "amount"
                 :class "col-sm-2 control-label"}
         "Expiration Date"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [exp-date-picker]]
         (when (:expiration_time @errors)
           [:div {:class "alert alert-danger"}
            (first (:expiration_time @errors))]
           )
         ]]
       ;; first time only?
       [:div {:class "form-group"}
        [:label {:for "first time only?"
                 :class "col-sm-2 control-label"}
         "First Time Only?"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [:input {:type "checkbox"
                   :checked @only-for-first-order
                   :on-change #(reset!
                                only-for-first-order
                                (-> %
                                    (aget "target")
                                    (aget "checked")))}]]]]
       ;; submit
       [:button {:type "submit"
                 :class "btn btn-default"
                 :on-click (fn [e]
                             (.preventDefault e)
                             ;; we are retrieving
                             (reset! retrieving? true)
                             ;; send response to server
                             (retrieve-url
                              (str base-url "coupon")
                              "POST"
                              (js/JSON.stringify
                               (clj->js (assoc @new-coupon
                                               :value (dollars->cents
                                                       (:value @new-coupon)))))
                              (partial
                               xhrio-wrapper
                               (fn [r]
                                 (let [response (js->clj r
                                                         :keywordize-keys
                                                         true)]
                                   (when (not (:success response))
                                     (reset! retrieving? false)
                                     ;; handle errors
                                     (reset! errors (first
                                                     (:validation response))))
                                   (when (:success response)
                                     ;; retrieve the new coupon
                                     (retrieve-url
                                      (str base-url "coupon/" @code)
                                      "GET"
                                      {}
                                      (partial
                                       xhrio-wrapper
                                       (fn [r]
                                         (let [response
                                               (js->clj
                                                r :keywordize-keys true)]
                                           (put! datastore/modify-data-chan
                                                 {:topic "coupons"
                                                  :data response})
                                           (reset!
                                            new-coupon
                                            (assoc
                                             default-new-coupon
                                             :alert-success
                                             (str "Coupon '" @code
                                                  "' successfully created!")))
                                           )))))
                                   ))
                               )))
                }
        (if @retrieving?
          [:i {:class "fa fa-lg fa-refresh fa-pulse "}]
          "Create")
        ]
       (when (not (empty? @alert-success))
         [:div {:class "alert alert-success alert-dismissible"}
          [:button {:type "button"
                    :class "close"
                    :aria-label "Close"}
           [:i {:class "fa fa-times"
                :on-click #(reset! alert-success "")}]
           ]
          [:strong @alert-success]])
       ])))

(defn new-coupon-panel
  []
  (fn []
    [:div {:class "panel panel-default"}
     [:div {:class "panel-body"}
      [:h3 "Create Promo Code"]
      [new-coupon-form]]]))

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
     [:td (cents->$dollars (.abs js/Math (:value coupon)))]
     ;; start date
     [:td (unix-epoch->fmt (:timestamp_created coupon) "M/D/YYYY")]
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
        sort-reversed? (r/atom false)
        selected (r/cursor state [:selected])
        ]
    (fn [coupons]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (condp = @selected
                        "active" (fn [coupon]
                                   (>= (:expiration_time coupon)
                                       (-> (js/moment)
                                           (.unix))))
                        "expired" (fn [coupon]
                                    (<= (:expiration_time coupon)
                                        (-> (js/moment)
                                            (.unix))))
                        "all" (fn [coupon]
                                true))
            displayed-coupons coupons
            sorted-coupons (->> displayed-coupons
                                (filter filter-fn)
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
        (if (nil? @current-coupon)
          (reset! current-coupon (first sorted-coupons)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          ;;[user-panel current-user]
          [:div [:h4 {:class "pull-left"} "Coupons"]
           [:div {:class "btn-toolbar"
                  :role "toolbar"
                  :aria-label "Toolbar with button groups"}
            [:div {:class "btn-group"
                   :role "group"
                   :aria-label "Select active, expired or all coupons"}
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (= @selected
                                            "active"
                                            )
                                     "active"))
                       :on-click #(reset! selected "active")
                       }
              "Active"]
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (= @selected
                                            "expired")
                                     "active"))
                       :on-click #(reset! selected "expired")
                       }
              "Expired"]
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (= @selected
                                            "all")
                                     "active"))
                       :on-click #(reset! selected "all")
                       }
              "See All"]
             ]]]
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
