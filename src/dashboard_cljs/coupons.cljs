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
                                          accessible-routes expired-coupon?
                                          diff-message]]
            [dashboard-cljs.components :refer [DynamicTable
                                               RefreshButton DatePicker
                                               TablePager TableFilterButtonGroup
                                               FormGroup TextInput
                                               KeyVal AlertSuccess
                                               SubmitDismissConfirmGroup
                                               ConfirmationAlert]]
            [clojure.string :as s]))

(def default-new-coupon
  {:code nil
   :value "10.00"
   :expiration_time (-> (js/moment)
                        (.endOf "day")
                        (.unix))
   :only_for_first_orders true
   :expires? false})

(def default-edit-coupon
  {:errors nil
   :retrieving? false
   :editing? false})


(def state (r/atom {:current-coupon nil
                    :confirming-edit? false
                    :confirming-create? false
                    :alert-success ""
                    :new-coupon-alert-success ""
                    :new-coupon (merge default-new-coupon
                                       default-edit-coupon)
                    :edit-coupon
                    default-edit-coupon
                    :selected "active"}))

(defn displayed-coupon
  [coupon]
  (let [{:keys [code value expiration_time only_for_first_orders expires?]}
        coupon]
    (assoc coupon
           :code
           code
           :value (cents->dollars
                   (.abs js/Math
                         value))
           :expiration_time (unix-epoch->fmt expiration_time "M/D/YYYY")
           :only_for_first_orders only_for_first_orders
           :expires? (not= expiration_time 1999999999))))

(defn reset-edit-coupon!
  [edit-coupon current-coupon]
  (reset! edit-coupon
          ;; expiration_time is displayed automatically in the correct
          ;; format by DatePicker, but it needs unix-epoch
          (assoc (displayed-coupon @current-coupon)
                 :expiration_time (:expiration_time @current-coupon))))

(defn reset-new-coupon!
  []
  (reset! (r/cursor state [:new-coupon])
          (merge default-new-coupon
                 default-edit-coupon)))

(defn coupon->server-req
  [coupon]
  (let [{:keys [value expiration_time expires?]} coupon]
    (assoc coupon
           :value
           (if (parse-to-number? value)
             (dollars->cents
              value)
             value)
           :expiration_time
           (if expires?
             expiration_time
             1999999999))))

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

(defn coupon-form-render
  [props]
  (fn [{:keys [errors code value expires? expiration-time only-for-first-order
               code-editable?]} props]
    [:div {:class "row"}
     [:div {:class "col-sm-2"}
      ;; promo code
      (when code-editable?
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
                                      (format-coupon-code)))}]])
      (when-not code-editable?
        [KeyVal "Code" @code])
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
      [:div {:class "form-group"
             :style {:margin-left "1px"}}
       [:label {:for "expires?"
                :class "control-label"}
        "Expires "
        [:div {:style {:display "inline-block"}}
         [:input {:type "checkbox"
                  :checked @expires?
                  :style {:margin-left "4px"}
                  :on-change (fn [e]
                               (reset!
                                expires?
                                (-> e
                                    (aget "target")
                                    (aget "checked")))
                               (when @expires?
                                 (reset! expiration-time
                                         (-> (js/moment)
                                             (.endOf "day")
                                             (.unix))))
                               (when-not @expires?
                                 (reset! expiration-time
                                         1999999999)))}]]]
       [:div
        [:div {:class "input-group"}
         (when @expires?
           [DatePicker expiration-time])]
        (when (:expiration_time @errors)
          [:div {:class "alert alert-danger"}
           (first (:expiration_time @errors))])]]
      ;; first tine only?
      [FormGroup {:label (str "First Order Only?     ")
                  :label-for "first time only?"}
       [:input {:type "checkbox"
                :checked @only-for-first-order
                :style {:margin-left "4px"}
                :on-change #(reset!
                             only-for-first-order
                             (-> %
                                 (aget "target")
                                 (aget "checked")))}]]]]))

(defn edit-coupon-comp
  [coupon]
  (let [edit-coupon (r/cursor state [:edit-coupon])
        
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
                      :only_for_first_orders "First Order Only?"
                      :expiration_time "Expiration Date"}
        diff-msg-gen (fn [edit current]
                       (diff-message
                        (assoc edit :expiration_time
                               (unix-epoch->fmt (:expiration_time edit)
                                                "M/D/YYYY"))
                        (displayed-coupon current)
                        diff-key-str))]
    (fn [coupon]
      (let [submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (if (every? nil? (diff-msg-gen @edit-coupon
                                                               @current-coupon))
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
                         (reset-edit-coupon! edit-coupon current-coupon)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:form {:class "form-horizontal"}
         (cond @editing?
               [coupon-form-render {:errors errors
                                    :code code
                                    :value value
                                    :expires? expires?
                                    :expiration-time expiration-time
                                    :only-for-first-order only-for-first-order
                                    :code-editable? false}]
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
         [SubmitDismissConfirmGroup {:confirming? confirming?
                                     :editing? editing?
                                     :retrieving? retrieving?
                                     :submit-fn submit-on-click
                                     :dismiss-fn dismiss-fn}]
         (if (and @confirming?
                  (not-every? nil? (diff-msg-gen
                                    @edit-coupon @current-coupon)))
           [ConfirmationAlert
            {:confirmation-message
             (fn []
               [:div (str "The following changes will be made to "
                          (:code @current-coupon))
                (map (fn [el]
                       ^{:key el}
                       [:h4 el])
                     (diff-msg-gen
                      @edit-coupon @current-coupon))])
             :cancel-on-click dismiss-fn
             :confirm-on-click (fn [_]
                                 (entity-save
                                  (coupon->server-req @edit-coupon)
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
             :retrieving? retrieving?}]
           (reset! confirming? false))
         (when-not (empty? @alert-success)
           [AlertSuccess {:message @alert-success
                          :dismiss #(reset! alert-success "")}])]))))

(defn create-coupon-comp
  "The panel for creating a new coupon"
  []
  (fn []
    (let [new-coupon (r/cursor state [:new-coupon])

          code (r/cursor new-coupon [:code])
          value (r/cursor new-coupon [:value])
          expiration-time (r/cursor new-coupon [:expiration_time])
          expires? (r/cursor new-coupon [:expires?])
          only-for-first-order (r/cursor new-coupon [:only_for_first_orders])

          editing?    (r/cursor new-coupon [:editing?])
          retrieving? (r/cursor new-coupon [:retrieving?])
          errors      (r/cursor new-coupon [:errors])

          alert-success (r/cursor state [:new-coupon-alert-success])
          confirming? (r/cursor state [:confirming-create?])

          confirm-msg (fn [coupon]
                        (let [{:keys [code value expiration_time
                                      only_for_first_orders]} @new-coupon]
                          [:div
                           (str "Are you sure you want to create a new coupon "
                                "with the following values?")
                           [:h4 "Code: " code]
                           [:h4 "Amount: " value]
                           [:h4 "Expires: " (if (= expiration_time
                                                   1999999999)
                                              "No"
                                              (unix-epoch->fmt
                                               expiration_time
                                               "M/D/YYYY"))]
                           [:h4 "First Order Only?: " (if only_for_first_orders
                                                        "Yes"
                                                        "No")]]))
          submit-on-click (fn [e]
                            (.preventDefault e)
                            (if @editing?
                              (do
                                ;; confirm
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
                       ;; reset new coupon
                       (reset-new-coupon!)
                       ;; reset confirming
                       (reset! confirming? false))]
      (when-not @expires? (reset! expiration-time 1999999999))
      [:div {:class "panel panel-default"}
       [:div {:class "panel-body"}
        [:form {:class "form-horizontal"}
         (when @editing?
           [coupon-form-render {:errors errors
                                :code code
                                :value value
                                :expires? expires?
                                :expiration-time expiration-time
                                :only-for-first-order only-for-first-order
                                :code-editable? true}])
         [SubmitDismissConfirmGroup {:confirming? confirming?
                                     :editing? editing?
                                     :retrieving? retrieving?
                                     :submit-fn submit-on-click
                                     :dismiss-fn dismiss-fn
                                     :edit-btn-content "Create a New Coupon"}]
         (when @confirming?
           [ConfirmationAlert
            {:confirmation-message (fn [] (confirm-msg @new-coupon))
             :cancel-on-click dismiss-fn
             :confirm-on-click
             (fn [_]
               (entity-save
                (coupon->server-req @new-coupon)
                "coupon"
                "POST"
                retrieving?
                (edit-on-success "coupon"
                                 new-coupon
                                 (r/atom {})
                                 alert-success
                                 :aux-fn
                                 (fn [_]
                                   (reset! confirming? false)
                                   ;; this must be before new-coupon is reset
                                   (reset!
                                    alert-success
                                    (str @code " was successfully created!"))
                                   (reset-new-coupon!)))
                (edit-on-error new-coupon
                               :aux-fn
                               #(reset! confirming? false))))
             :retrieving? retrieving?}])
         (when-not (empty? @alert-success)
           [AlertSuccess {:message @alert-success
                          :dismiss #(reset! alert-success "")}])]]])))

(defn coupons-panel
  "Display a table of coupons"
  [coupons]
  (let [current-coupon (r/cursor state [:current-coupon])
        edit-coupon (r/cursor state [:edit-coupon])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        selected (r/cursor state [:selected])
        current-page (r/atom 1)
        page-size 15
        filters {"Show All" {:filter-fn (constantly true)}
                 "Active" {:filter-fn (complement expired-coupon?)}
                 "Expired" {:filter-fn expired-coupon?}}
        selected-filter (r/atom "Active")]
    (fn [coupons]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            ;; remove all groupon coupons
            displayed-coupons (filter #(not (re-matches #"GR.*" (:code %)))
                                      coupons)
            sorted-coupons (fn []
                             (->> displayed-coupons
                                  (filter (:filter-fn
                                           (get filters @selected-filter)))
                                  sort-fn
                                  (partition-all page-size)))
            paginated-coupons (fn []
                                (-> (sorted-coupons)
                                    (nth (- @current-page 1)
                                         '())))
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
            table-pager-on-click (fn []
                                   (reset! current-coupon
                                           (first (paginated-coupons))))]
        (if (nil? @current-coupon)
          (table-pager-on-click))
        ;; set the edit-coupon values to match those of current-coupon
        (reset-edit-coupon! edit-coupon current-coupon)
        [:div {:class "panel panel-default"}
         (when (subset? #{{:uri "/coupon"
                           :method "PUT"}}
                        @accessible-routes)
           [:div {:class "panel-body"}
            [edit-coupon-comp current-coupon]
            [:br]])
         [:div {:class "panel-body"}
          [:div {:class "btn-toolbar pull-left"
                 :role "toolbar"}
           [TableFilterButtonGroup {:hide-counts #{"Show All"}
                                    :on-click (fn [_]
                                                (reset! current-page 1)
                                                (table-pager-on-click))
                                    :filters filters
                                    :data displayed-coupons
                                    :selected-filter selected-filter}]]
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group"
                  :role "group"}
            [RefreshButton {:refresh-fn
                            refresh-fn}]]]]
         [:div {:class "table-responsive"}
          [DynamicTable
           {:current-item current-coupon
            :tr-props-fn
            (fn [coupon current-coupon]
              {:class (when (= (:id coupon)
                               (:id @current-coupon))
                        "active")
               :on-click (fn [_]
                           (reset! current-coupon coupon)
                           (reset! (r/cursor state [:alert-success]) ""))})
            :sort-keyword sort-keyword
            :sort-reversed? sort-reversed?
            :table-vecs
            [["Coupon Code" :code :code]
             ["Amount" :value #(cents->$dollars (.abs js/Math (:value %)))]
             ["Start Date" :timestamp_created
              #(unix-epoch->fmt (:timestamp_created %) "M/D/YYYY")]
             ["Expiration Date" :expiration_time
              #(unix-epoch->fmt (:expiration_time %) "M/D/YYYY")]
             ["# of Users"
              (fn [coupon]
                (-> coupon
                    :used_by_user_ids
                    (s/split #",")
                    (#(remove s/blank? %))
                    count))
              (fn [coupon]
                (-> coupon
                    :used_by_user_ids
                    (s/split #",")
                    (#(remove s/blank? %))
                    count))]
             ["First Order Only?" :only_for_first_orders
              #(if (:only_for_first_orders %)
                 "Yes"
                 "No")]]}
           (paginated-coupons)]]
         [TablePager
          {:total-pages (count (sorted-coupons))
           :current-page current-page
           :on-click table-pager-on-click}]]))))
