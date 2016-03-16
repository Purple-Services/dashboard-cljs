(ns dashboard-cljs.coupons
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [clojure.set :refer [subset?]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save retrieve-entity
                                          edit-on-error edit-on-success]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          format-coupon-code parse-to-number?
                                          accessible-routes diff-message]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton DatePicker
                                               TablePager FormGroup TextInput
                                               KeyVal FormSubmit AlertSuccess
                                               SubmitDismissGroup
                                               ConfirmationAlert]]
            [clojure.string :as s]))

(def default-new-coupon
  {:code nil
   :value "10.00"
   :expiration_time (-> (js/moment)
                        (.endOf "day")
                        (.unix))
   :only_for_first_orders true})

(def default-edit-coupon
  {:errors nil
   :retrieving? false
   :editing? false})

(def state (r/atom {:current-coupon nil
                    :confirming-edit? false
                    :alert-success ""
                    :new-coupon
                    default-new-coupon
                    :edit-coupon
                    default-edit-coupon
                    :selected "active"}))

(defn reset-edit-coupon!
  [edit-coupon current-coupon]
  (reset! edit-coupon
          (assoc @current-coupon
                 :code (:code @current-coupon)
                 :value (cents->dollars
                         (.abs js/Math
                               (:value @current-coupon)))
                 :expiration_time (:expiration_time
                                   @current-coupon)
                 :only_for_first_orders
                 (:only_for_first_orders
                  @current-coupon)
                 :expires? (not= (:expiration_time
                                  @current-coupon)
                                 1999999999)
                 )))

(defn process-coupon
  [coupon]
  (assoc @coupon
         :value
         (let [value (:value @coupon)]
           (if (parse-to-number? value)
             (dollars->cents
              value)
             value))
         :expiration_time
         (if (:expires? @coupon)
           (:expiration_time @coupon)
           1999999999)))

;; (defn reset-edit-coupon!
;;   "edit-coupon is an atom, current-coupon is a map"
;;   [edit-coupon current-coupon]
;;   (reset! edit-coupon
;;           (merge
;;            @edit-coupon
;;            {:code (:code current-coupon)
;;             :value (cents->dollars
;;                     (.abs js/Math
;;                           (:value current-coupon)))
;;             :expiration_time (:expiration_time
;;                               current-coupon)
;;             :only_for_first_orders
;;             (:only_for_first_orders
;;              current-coupon)
;;             :expires? (if (= (:expiration_time
;;                               current-coupon)
;;                              1999999999)
;;                         true)})))

(defn coupon-form-submit-button
  "Button for submitting a coupon form for coupon, using on-click
  and label for submit button"
  [coupon on-click label]
  (fn []
    (let [retrieving? (r/cursor coupon [:retrieving?])
          errors      (r/cursor coupon [:errors])
          code        (r/cursor coupon [:code])]
      [:button {:type "submit"
                :class "btn btn-default"
                :on-click on-click
                :disabled @retrieving?}
       (if @retrieving?
         [:i {:class "fa fa-lg fa-refresh fa-pulse "}]
         label)])))

(defn coupon-form
  "Form for a new coupon using submit-button"
  [coupon]
  (let [
        edit-coupon (r/cursor state [:edit-coupon])
        
        code (r/cursor edit-coupon [:code])
        value (r/cursor edit-coupon [:value])
        expiration-time (r/cursor edit-coupon [:expiration_time])
        expires? (r/cursor edit-coupon [:expires?])
        only-for-first-order (r/cursor edit-coupon
                                       [:only_for_first_orders])
        editing? (r/cursor edit-coupon [:editing?])
        retrieving? (r/cursor edit-coupon [:retrieving?])
        errors (r/cursor edit-coupon [:errors])
        
        current-coupon (r/cursor state [:current-coupon])
        alert-success (r/cursor state [:alert-success])
        confirming? (r/cursor state [:confirming-edit?])
        
        diff-key-str {:code "Code"
                      :value "Value"
                      :only_for_first_orders "First Time Only?"
                      :expiration_time"Expiration Date"}]
    (fn [coupon]
      (let [submit-on-click (fn [e]
                              (.preventDefault e)
                              (.log js/console "submit-on-click activated")
                              (if @editing?
                                (if (every? nil? (diff-message
                                                  @edit-coupon @current-coupon
                                                  diff-key-str))
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
                         ;; reset current coupon
                         ;;(reset-edit-zone! edit-zone current-zone)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:form {:class "form-horizontal"}
         (cond @editing?
               [:div
                ;; promo code
                [FormGroup {:label "Code"
                            :label-for "promo code"
                            :errors (:code @errors)}
                 [TextInput {:value @code
                             :default-value @code
                             :placeholder "Code"
                             :on-change #(reset!
                                          code
                                          (-> %
                                              (aget "target")
                                              (aget "value")
                                              (format-coupon-code)))}]]
                ;; amount
                [FormGroup {:label "Amount"
                            :label-for "amount"
                            :errors (:value @errors)}
                 [TextInput {:value @value
                             :placeholder "Amount"
                             :on-change #(reset!
                                          value (-> %
                                                    (aget "target")
                                                    (aget "value")))}]]
                ;; exp date
                [:div {:class "form-group"}
                 [:label {:for "expires?"
                          :class "col-sm-2 control-label"}
                  "Expires? "
                  [:input {:type "checkbox"
                           :checked @expires?
                           :on-change #(reset!
                                        expires?
                                        (-> %
                                            (aget "target")
                                            (aget "checked")))}]]
                 [:div {:class "col-sm-10"}
                  [:div {:class "input-group"}
                   (when @expires?
                     [DatePicker expiration-time])]
                  (when (:expiration_time @errors)
                    [:div {:class "alert alert-danger"}
                     (first (:expiration_time @errors))])]]
                ;; first tine only?
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
                                             (aget "checked")))}]]]]]
               (and (not @editing?)
                    (not (s/blank? @code)))
               [:div
                [KeyVal "Code" (-> @coupon
                                   :code)]
                [KeyVal "Amount" (->> @coupon
                                     :value
                                     (.abs js/Math)
                                     (cents->$dollars))]
                [KeyVal "Expiration Date" (-> @coupon
                                              :expiration_time
                                              (unix-epoch->fmt "M/D/YYYY"))]
                [KeyVal "First Order Only?" (if (:only_for_first_orders @coupon)
                                              "Yes"
                                              "No")]])
         ;;submit-button
         [SubmitDismissGroup {:confirming? confirming?
                              :editing? editing?
                              :retrieving? retrieving?
                              :submit-fn submit-on-click
                              :dismiss-fn dismiss-fn}]
         (when (and @confirming?
                    (not-every? nil? (diff-message
                                      @edit-coupon @current-coupon
                                      diff-key-str)))
           [ConfirmationAlert
            {:confirmation-message
             (fn []
               [:div (str "The following changes will be made to "
                          (:code @current-coupon))
                (map (fn [el]
                       ^{:key el}
                       [:h4 el])
                     (diff-message
                      @edit-coupon @current-coupon
                      diff-key-str))])
             :cancel-on-click dismiss-fn
             :confirm-on-click (fn [_]
                                 (entity-save
                                  ;;(zone->server-req @edit-zone)
                                  @edit-coupon
                                  "coupon"
                                  "PUT"
                                  retrieving?
                                  (edit-on-success "coupon"
                                                   edit-coupon
                                                   current-coupon
                                                   alert-success
                                                   :aux-fn
                                                   #(reset! confirming? false))
                                  (edit-on-error edit-coupon
                                                 :aux-fn
                                                 #(reset! confirming? false))))
             :retrieving? retrieving?}])
         (when-not (empty? @alert-success)
           [AlertSuccess {:message @alert-success
                          :dismiss #(reset! alert-success "")}])]))))

;; (defn new-coupon-panel
;;   "The panel for creating a new coupon"
;;   []
;;   (fn []
;;     (let [coupon (r/cursor state [:new-coupon])
;;           retrieving? (r/cursor coupon [:retrieving?])
;;           errors      (r/cursor coupon [:errors])
;;           code        (r/cursor coupon [:code])
;;           on-success #(retrieve-entity
;;                        "coupon"
;;                        (:id %)
;;                        (fn [response]
;;                          (reset! retrieving? false)
;;                          ;; update the coupon in the datastore
;;                          (put! datastore/modify-data-chan
;;                                {:topic "coupons"
;;                                 :data response})
;;                          ;; reset the coupon errors
;;                          (reset! coupon
;;                                  (assoc
;;                                   default-new-coupon
;;                                   :alert-success "Successfully created!"))))
;;           on-error     (fn [res]
;;                          (reset! retrieving? false)
;;                          ;; there is an error, should not have alert-success
;;                          (reset! (r/cursor coupon [:alert-success]) "")
;;                          ;; handle errors
;;                          (reset! errors (first (:validation res))))]
;;       [:div {:class "panel panel-default"}
;;        [:div {:class "panel-body"}
;;         [:h3 "Create Coupon Code"]
;;         [coupon-form (r/cursor state [:new-coupon])
;;          [FormSubmit
;;           [coupon-form-submit-button coupon
;;            (fn [e]
;;              (.preventDefault e)
;;              (entity-save (process-coupon coupon)
;;                           "coupon"
;;                           "POST"
;;                           retrieving?
;;                           on-success
;;                           on-error))
;;            "Create"]]]]])))

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
       "Coupon Code"]
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
          :on-click #(do (reset! current-coupon coupon)
                         ;; (reset-edit-coupon! (r/cursor state [:edit-coupon])
                         ;;                     @current-coupon)
                         )}
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
                             (reset! refreshing? false)))))]
        (if (nil? @current-coupon)
          (reset! current-coupon (first paginated-coupons)))
        ;; set the edit-coupon values to match those of current-coupon
        (reset-edit-coupon! edit-coupon current-coupon)
        [:div {:class "panel panel-default"}
         (when (subset? #{{:uri "/coupon"
                           :method "PUT"}}
                        @accessible-routes)
           [:div {:class "panel-body"}
            [coupon-form current-coupon]])
         [:div {:class "panel-body"}
          
          [:div {:class "btn-toolbar pull-left"
                 :role "toolbar"}
           [:div {:class "btn-group"
                  :role "group"}
            [:button {:type "button"
                      :class (str "btn btn-default "
                                  (when (= @selected
                                           "all")
                                    "active"))
                      :on-click #(reset! selected "all")}
             "Show All"]
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
             "Expired"]]]
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group"
                  :role "group"}
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
           :current-page  current-page}]]))))
