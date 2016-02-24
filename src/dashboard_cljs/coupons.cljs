(ns dashboard-cljs.coupons
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [clojure.set :refer [subset?]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          format-coupon-code parse-to-number?
                                          accessible-routes]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton DatePicker
                                               TablePager]]
            [clojure.string :as s]))

(def default-new-coupon
  {:code nil
   :value "1.00"
   :expiration_time (-> (js/moment)
                        (.endOf "day")
                        (.unix))
   :only_for_first_orders true
   :errors nil
   :retrieving? false
   :alert-success ""})

(def state (r/atom {:current-coupon nil
                    :new-coupon
                    default-new-coupon
                    :edit-coupon
                    default-new-coupon
                    :selected "active"}))

(defn create-on-click
  "on-click fn for creating a new coupon on the server"
  [coupon]
  (let [retrieving? (r/cursor coupon [:retrieving?])
        errors      (r/cursor coupon [:errors])
        code        (r/cursor coupon [:code])]
    (fn [e]
      (.preventDefault e)
      ;; we are retrieving
      (reset! retrieving? true)
      ;; send response to server
      (retrieve-url
       (str base-url "coupon")
       "POST"
       (js/JSON.stringify
        (clj->js (assoc @coupon
                        :value
                        (let [value (:value @coupon)]
                          (if (parse-to-number? value)
                            (dollars->cents
                             value)
                            value)))))
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
                     coupon
                     (assoc
                      default-new-coupon
                      :alert-success
                      (str "Coupon '" @code
                           "' successfully created!")))))))))))))))

(defn edit-on-click
  "on-click fn for updating a coupon on the server"
  [coupon current-coupon]
  (let [retrieving? (r/cursor coupon [:retrieving?])
        errors      (r/cursor coupon [:errors])
        code        (r/cursor coupon [:code])]
    (fn [e]
      (.preventDefault e)
      ;; we are retrieving
      (reset! retrieving? true)
      ;; send response to server
      (retrieve-url
       (str base-url "coupon")
       "PUT"
       (js/JSON.stringify
        (clj->js (assoc @coupon
                        :value
                        (let [value (:value @coupon)]
                          (if (parse-to-number? value)
                            (dollars->cents
                             value)
                            value)))))
       (partial
        xhrio-wrapper
        (fn [r]
          (let [response (js->clj r
                                  :keywordize-keys
                                  true)]
            (when (not (:success response))
              ;; we obviously have an error, there shouldn't
              ;; be a success message!
              (reset! (r/cursor coupon [:alert-success])
                      ""
                      )
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
                    ;; reset edit-coupon
                    (reset!
                     coupon
                     (assoc
                      @coupon
                      :retrieving? false
                      :errors nil
                      ))
                    ;; reset the current-coupon
                    (reset! current-coupon (assoc
                                            (first response)
                                            :alert-success
                                            (str "Coupon '" @code
                                                 "' successfully updated!")))
                    ))))))))))))

(defn coupon-form-submit
  "Button for submitting a coupon form for coupon, using on-click
  and label for submit button"
  [coupon on-click label]
  (fn []
    (let [retrieving? (r/cursor coupon [:retrieving?])
          errors      (r/cursor coupon [:errors])
          code        (r/cursor coupon [:code])]
      ;; submit
      [:button {:type "submit"
                :class "btn btn-default"
                :on-click on-click
                }
       (if @retrieving?
         [:i {:class "fa fa-lg fa-refresh fa-pulse "}]
         label)])))

(defn coupon-form
  "Form for a new coupon using submit-button"
  [coupon submit-button]
  (let [code (r/cursor coupon [:code])
        value (r/cursor coupon [:value])
        only-for-first-order (r/cursor coupon
                                       [:only_for_first_orders])
        errors (r/cursor coupon [:errors])
        retrieving? (r/cursor coupon [:retrieving?])
        alert-success (r/cursor coupon [:alert-success])]
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
                                               ))}]
         (when (:code @errors)
           [:div {:class "alert alert-danger"}
            (first (:code @errors))])]]
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
                   :value @value
                   :on-change #(reset! value (-> %
                                                 (aget "target")
                                                 (aget "value")))}]]
         (when (:value @errors)
           [:div {:class "alert alert-danger"}
            (first (:value @errors))])]]
       ;; exp date
       [:div {:class "form-group"}
        [:label {:for "amount"
                 :class "col-sm-2 control-label"}
         "Expiration Date"]
        [:div {:class "col-sm-10"}
         [:div {:class "input-group"}
          [DatePicker (r/cursor coupon [:expiration_time])]]
         (when (:expiration_time @errors)
           [:div {:class "alert alert-danger"}
            (first (:expiration_time @errors))])]]
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
       submit-button
       (when (not (empty? @alert-success))
         [:div {:class "alert alert-success alert-dismissible"}
          [:button {:type "button"
                    :class "close"
                    :aria-label "Close"}
           [:i {:class "fa fa-times"
                :on-click #(reset! alert-success "")}]]
          [:strong @alert-success]])])))

(defn new-coupon-panel
  "The panel for creating a new coupon"
  []
  (fn []
    (let [coupon (r/cursor state [:new-coupon])
          retrieving? (r/cursor coupon [:retrieving?])
          errors      (r/cursor coupon [:errors])
          code        (r/cursor coupon [:code])]
      [:div {:class "panel panel-default"}
       [:div {:class "panel-body"}
        [:h3 "Create Promo Code"]
        [coupon-form (r/cursor state [:new-coupon])
         [coupon-form-submit coupon (create-on-click coupon) "Create"]]]])))

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
      [:td {:style {:font-size "16px" :font-height "normal"}}
       "# of Users"]
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
     ;; number of users
     [:td (-> coupon :used_by_user_ids (s/split #",") count)]
     ;; first order only?
     [:td (if (:only_for_first_orders coupon)
            "Yes"
            "No")]]))

(defn coupons-panel
  "Display a table of coupons"
  [coupons]
  (let [current-coupon (r/cursor state [:current-coupon])
        edit-coupon (r/cursor state [:edit-coupon])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        selected (r/cursor state [:selected])
        current-page (r/atom 1)
        page-size 5]
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
                                ;; remove all groupon coupons
                                (filter #(not (re-matches #"GR.*" (:code %))))
                                (filter filter-fn)
                                sort-fn
                                (partition-all page-size))
            paginated-coupons (-> sorted-coupons
                                  (nth (- @current-page 1)
                                       '()))
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
          (reset! current-coupon (first paginated-coupons)))
        ;; set the edit-coupon values to match those of current-coupon
        (reset! edit-coupon (assoc default-new-coupon
                                   :code (:code @current-coupon)
                                   :value (cents->dollars
                                           (.abs js/Math
                                                 (:value @current-coupon)))
                                   :expiration_time (:expiration_time
                                                     @current-coupon)
                                   :only_for_first_orders
                                   (:only_for_first_orders
                                    @current-coupon)
                                   :alert-success (:alert-success
                                                   @current-coupon)))
        [:div {:class "panel panel-default"}
         (when (subset? #{{:uri "/coupon"
                           :method "PUT"}}
                        @accessible-routes)
           [:div {:class "panel-body"}
            [coupon-form edit-coupon
             [coupon-form-submit edit-coupon (edit-on-click edit-coupon
                                                            current-coupon)
              "Update"]]])
         [:div {:class "panel-body"}
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
                                            "active")
                                     "active"))
                       :on-click #(reset! selected "active")}
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
            [RefreshButton {:refresh-fn
                            refresh-fn}]]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [coupon-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (coupon-row current-coupon)}
           paginated-coupons]]
         [TablePager
          {:total-pages (count sorted-coupons)
           :current-page  current-page}]
         ]))))
