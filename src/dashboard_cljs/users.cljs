(ns dashboard-cljs.users
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save retrieve-entity
                                          edit-on-success edit-on-error]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj pager-helper!
                                          integer->comma-sep-string
                                          parse-to-number? diff-message
                                          accessible-routes]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton KeyVal StarRating
                                               TablePager ConfirmationAlert
                                               FormGroup TextInput
                                               AlertSuccess
                                               SubmitDismissConfirmGroup
                                               TextAreaInput ViewHideButton]]
            [clojure.set :refer [subset?]]
            [clojure.string :as s]))

(def push-selected-users (r/atom (set nil)))

(def default-user {:editing? false
                   :retrieving? false
                   :referral_comment ""
                   :errors nil})

(def state (r/atom {:confirming? false
                    :confirming-edit? false
                    :search-term ""
                    :recent-search-term ""
                    :search-results #{}
                    :search-retrieving? false
                    :current-user nil
                    :edit-user default-user
                    :alert-success ""
                    :view-log? false
                    :users-count 0}))

(defn update-user-count
  []
  (retrieve-url
   (str base-url "users-count")
   "GET"
   {}
   (partial
    xhrio-wrapper
    (fn [response]
      (let [res (js->clj response :keywordize-keys true)]
        (reset! (r/cursor state [:users-count]) (integer->comma-sep-string
                                                 (:total (first res)))))))))

(defonce
  users-count-result
  (update-user-count))


(defn displayed-user
  [user]
  (let [{:keys [referral_gallons]} user]
    (assoc user
           :referral_gallons
           (str referral_gallons)
           :referral-comment "")))

(defn user->server-req
  [user]
  (let [{:keys [referral_gallons]} user]
    (assoc user
           :referral_gallons
           (if (parse-to-number? referral_gallons)
             (js/Number referral_gallons)
             referral_gallons))))

(defn reset-edit-user!
  [edit-user current-user]
  (reset! edit-user
          (displayed-user @current-user)))

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
            :on-click (fn [_]
                        (reset! current-user user)
                        (reset! (r/cursor state [:alert-success]) ""))}
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
       ;; card?
       [:td (if (s/blank? (:stripe_default_card user))
              "No"
              "Yes")]
       ;; push?
       [:td (if (s/blank? (:arn_endpoint user))
              "No"
              "Yes")]
       ;; os
       [:td (:os user)]
       ;; version
       [:td (:app_version user)]
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
                    :font-weight "normal"}}
       "Market"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Orders"]
      [TableHeadSortable
       (conj props {:keyword :email})
       "Email"] 
      [TableHeadSortable
       (conj props {:keyword :phone_number})
       "Phone"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Card?"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Push?"]
      [TableHeadSortable
       (conj props {:keyword :os})
       "OS"]
      [TableHeadSortable
       (conj props {:keyword :app_version})
       "Version"]
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
      ;; [:th {:style {:font-size "16px"
      ;;               :font-weight "normal"}} "Payment"]
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
      " "
      (:address_street order)]
     ;; courier name
     [:td (:courier_name order)]
     ;; payment info
     ;;[:td (:customer_phone_number order)]
     ;; status
     [:td (:status order)]
     ;; star rating
     [:td
      (let [number-rating (:number_rating order)]
        (when number-rating
          [StarRating number-rating]))]]))

(defn user-history-header
  "props
  {
  :sort-keyword   ; r/atom, keyword
  :sort-reversed? ; r/atom, boolean
  }"
  [props]
  (fn [props]
    [:thead
     [:tr
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Date"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Admin"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Adjustment"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Comment"]]]))

(defn user-history-row
  []
  (fn [user-log]
    [:tr
     ;; Date
     [:td (unix-epoch->fmt (:timestamp user-log) "M/D/YYYY h:mm A")]
     ;; Admin
     [:td (:admin_name user-log)]
     ;; Gallon adjustment
     [:td (str (:previous_value user-log) " -> " (:new_value user-log))]
     ;; comment
     [:td (:comment user-log)]]))

(defn user-form
  "Form for editing a user"
  [user]
  (let [edit-user (r/cursor state [:edit-user])
        current-user (r/cursor state [:current-user])
        retrieving? (r/cursor edit-user [:retrieving?])
        editing? (r/cursor edit-user [:editing?])
        confirming? (r/cursor state [:confirming-edit?])
        errors   (r/cursor edit-user [:errors])
        referral-gallons (r/cursor edit-user [:referral_gallons])
        comment (r/cursor edit-user [:referral_comment])
        alert-success (r/cursor state [:alert-success])
        diff-key-str {:referral_gallons "Referral Gallons"}
        diff-msg-gen (fn [edit current] (diff-message edit
                                                      (displayed-user current)
                                                      diff-key-str))]
    (fn [user]
      (let [default-card-info (if (empty? (:stripe_cards @edit-user))
                                nil
                                (->> (:stripe_cards @edit-user)
                                     json-string->clj
                                     (filter #(= (:stripe_default_card
                                                  @edit-user)
                                                 (:id %)))
                                     first))
            submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (if (every? nil?
                                            (diff-msg-gen @edit-user
                                                          @current-user))
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
                         (reset-edit-user! edit-user current-user)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:form {:class "form-horizontal"}
         ;; email
         [KeyVal "Email" (:email @user)]
         ;; phone number
         [KeyVal "Phone" (:phone_number @user)]
         ;; date started
         [KeyVal "Registered" (unix-epoch->fmt
                               (:timestamp_created @user)
                               "M/D/YYYY")]
         ;; last active (last ping)
         (when-not (nil? (:last_active @user))
           [KeyVal "Last Active" (unix-epoch->fmt
                                  (:last_active @user)
                                  "M/D/YYYY")])
         ;; default card
         (when (not (nil? default-card-info))
           [KeyVal "Default Card"
            (str
             (:brand default-card-info)
             " "
             (:last4 default-card-info)
             " "
             (when (not (empty? (:exp_month default-card-info)))
               (:exp_month default-card-info)
               "/"
               (:exp_year default-card-info)))])
         ;; Referral Gallons
         (if @editing?
           [:div
            [FormGroup {:label "Referral Gallons"
                        :label-for "referral gallons"
                        :errors (:referral_gallons @errors)
                        :input-container-class "col-sm-7"}
             [TextInput {:value @referral-gallons
                         :default-value @referral-gallons
                         :on-change #(reset!
                                      referral-gallons
                                      (-> %
                                          (aget "target")
                                          (aget "value")))}]]
            [FormGroup {:label "Referral Gallons Comment"
                        :label-for "referral gallons comment"
                        :input-container-class "col-sm-7"}
             [TextAreaInput {:value @comment
                             :rows 2
                             :cols 50
                             :on-change #(reset!
                                          comment
                                          (-> %
                                              (aget "target")
                                              (aget "value")))}]]]
           [KeyVal "Referral Gallons" (:referral_gallons @user)])
         (when (subset? #{{:uri "/user"
                           :method "PUT"}}
                        @accessible-routes)
           [SubmitDismissConfirmGroup
            {:confirming? confirming?
             :editing? editing?
             :retrieving? retrieving?
             :submit-fn submit-on-click
             :dismiss-fn dismiss-fn}])
         (when (subset? #{{:uri "/user"
                           :method "PUT"}}
                        @accessible-routes)
           (if (and @confirming?
                    (not-every? nil?
                                (diff-msg-gen @edit-user @current-user)))
             [ConfirmationAlert
              {:confirmation-message
               (fn []
                 [:div (str "The following changes will be made to "
                            (:name @current-user))
                  (map (fn [el]
                         ^{:key el}
                         [:h4 el])
                       (diff-msg-gen @edit-user @current-user))])
               :cancel-on-click dismiss-fn
               :confirm-on-click
               (fn [_]
                 (entity-save
                  (user->server-req @edit-user)
                  "user"
                  "PUT"
                  retrieving?
                  (edit-on-success "user" edit-user current-user
                                   alert-success
                                   :aux-fn
                                   #(reset! confirming? false))
                  (edit-on-error edit-user
                                 :aux-fn
                                 #(reset! confirming? false))))
               :retrieving? retrieving?}]
             (reset! confirming? false)))
         ;; success alert
         (when-not (empty? @alert-success)
           [AlertSuccess {:message @alert-success
                          :dismiss #(reset! alert-success "")}])]))))

(defn user-panel
  "Display detailed and editable fields for an user. current-user is an
  r/atom"
  [current-user]
  (let [sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        show-orders? (r/atom true)
        current-page (r/atom 1)
        page-size 5
        edit-user    (r/cursor state [:edit-user])
        view-log?    (r/cursor state [:view-log?])]
    (fn [current-user]
      (let [sort-fn (if @sort-reversed?
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
                               sort-fn
                               (partition-all page-size))
            paginated-orders (pager-helper! sorted-orders current-page)
            most-recent-order (->> orders
                                   (sort-by :target_time_start)
                                   first)]
        ;; edit-user should correspond to current-user
        (when-not (:editing? @edit-user)
          (reset! edit-user (assoc @edit-user
                                   :last_active (:target_time_start
                                                 most-recent-order))))
        (when-not (nil? @current-user)
          (reset! current-user
                  (assoc @current-user
                         :last_active (:target_time_start
                                       most-recent-order))))
        ;; populate the current user with additional information
        [:div {:class "panel-body"}
         [:div {:class "row"}
          [:div {:class "col-xs-3"}
           [:div [:h3 {:style {:margin-top 0}} (:name @current-user)]]
           ;; main display panel
           [user-form current-user]
           ;; below is for showing user logs,
           ;; implemented, but not used yet
           ;; [:br]
           ;; [ViewHideButton {:class "btn btn-sm btn-default"
           ;;                  :view-content "View Logs"
           ;;                  :hide-content "Hide Logs"
           ;;                  :on-click #(swap! view-log? not)
           ;;                  :view? view-log?}]
           ;; (when @view-log?
           ;;   [:div {:class "table-responsive"
           ;;          :style (if @view-log?
           ;;                   {}
           ;;                   {:display "none"})}
           ;;    [StaticTable
           ;;     {:table-header [user-history-header
           ;;                     {;;:sort-keyword sort-keyword-logs
           ;;                      ;;:sort-reversed? sort-reversed-logs?
           ;;                      }]
           ;;      :table-row (user-history-row)}
           ;;     (sort-by :timestamp (:admin_event_log @current-user))]])
           ]
          ;; Table of orders for current user
          (when (> (count paginated-orders)
                   0)
            [:div {:class "col-xs-9"}
             [:div {:class "table-responsive"
                    :style (if @show-orders?
                             {}
                             {:display "none"})}
              [StaticTable
               {:table-header [user-orders-header
                               {:sort-keyword sort-keyword
                                :sort-reversed? sort-reversed?}]
                :table-row (user-orders-row)}
               paginated-orders]]
             [:div {:style (if @show-orders?
                             {}
                             {:display "none"})}
              [TablePager
               {:total-pages (count sorted-orders)
                :current-page current-page}]]])]]))))

(defn users-panel
  "Display a table of selectable users with an indivdual user panel
  for the selected user"
  [users]
  (let [current-user (r/cursor state [:current-user])
        edit-user    (r/cursor state [:edit-user])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        page-size 15]
    (fn [users]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-users users
            sorted-users (->> displayed-users
                              sort-fn
                              (partition-all page-size))
            paginated-users (-> sorted-users
                                (nth (- @current-page 1)
                                     '()))
            refresh-fn (fn [saving?]
                         (reset! saving? true)
                         (update-user-count)
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
          (reset! current-user (first paginated-users)))
        (reset-edit-user! edit-user current-user)
        ;; set the edit-user values to match those of current-user 
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [user-panel current-user]]
         [:div {:class "panel"
                :style {:margin-top "15px"}}
          [:div [:h3 {:class "pull-left"
                      :style {:margin-top "4px"
                              :margin-bottom 0}}
                 (str "Users (" @(r/cursor state [:users-count]) ")")]]
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group"
                  :role "group"}
            [RefreshButton {:refresh-fn
                            refresh-fn}]]]
          [:div {:class "table-responsive"}
           [StaticTable
            {:table-header [user-table-header
                            {:sort-keyword sort-keyword
                             :sort-reversed? sort-reversed?}]
             :table-row (user-row current-user)}
            paginated-users]]]
         [TablePager
          {:total-pages (count sorted-users)
           :current-page current-page}]]))))

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
                      :checked (contains? @push-selected-users (:id user))}]])]
     ]))

(defn user-push-notification
  "A component for sending push notifications to users"
  []
  (let [all-selected?  (r/atom true)
        approved?      (r/atom false)
        confirming?    (r/cursor state [:confirming?])
        retrieving?    (r/atom false)
        message        (r/atom (str))
        alert-success  (r/atom (str))
        alert-error    (r/atom (str))
        sort-keyword   (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        current-page   (r/atom 1)
        page-size      5]
    (fn []
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-users @datastore/users
            sorted-users (->> displayed-users
                              sort-fn
                              (partition-all page-size))
            paginated-users (-> sorted-users
                                (nth (- @current-page 1)
                                     '()))]
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div [:h4 {:class "pull-left"} "Send Push Notification"]
           [:div {:class "btn-toolbar"
                  :role "toolbar"}
            [:div {:class "btn-group"
                   :role "group"}
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when @all-selected?
                                     "active"))
                       :on-click (fn [e]
                                   (reset! all-selected? true)
                                   (reset! confirming? false))}
              "All Converted Users"]
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (not @all-selected?)
                                     "active"))
                       :on-click (fn [e]
                                   (reset! all-selected? false)
                                   (reset! confirming? false))}
              "Selected Users"]]]]
          (if @confirming?
            ;; confirmation
            [ConfirmationAlert
             {:cancel-on-click (fn [e]
                                 (reset! confirming? false)
                                 (reset! message ""))
              :confirm-on-click
              (fn [e]
                (reset! retrieving? true)
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
                    (reset! retrieving? false)
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
              :confirmation-message
              (fn [] [:div (str "Are you sure you want to send the following "
                                "message to "
                                (if @all-selected?
                                  "all converted"
                                  "all selected")
                                " users?")
                      [:h4 [:strong @message]]])
              :retrieving? retrieving?}]
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
                       }
              "Send Notification"]
             ;; clear all selected users
             (when (not @all-selected?)
               [:button {:type "submit"
                         :class "btn btn-default"
                         :on-click (fn [e]
                                     (.preventDefault e)
                                     (reset! push-selected-users (set nil)))}
                "Clear Selected Users"])])
          ;; alert message
          (when (not (empty? @alert-success))
            [:div {:class "alert alert-success alert-dismissible"}
             [:button {:type "button"
                       :class "close"
                       :aria-label "Close"}
              [:i {:class "fa fa-times"
                   :on-click #(reset! alert-success "")}]]
             [:strong @alert-success]])
          ;; alert error
          (when (not (empty? @alert-error))
            [:div {:class "alert alert-danger"}
             @alert-error])
          ;; selected users table
          (when (not @all-selected?)
            [:div
             [:div {:class "table-responsive"}
              [StaticTable
               {:table-header [user-notification-header
                               {:sort-keyword sort-keyword
                                :sort-reversed? sort-reversed?}]
                :table-row (user-notification-row)}
               paginated-users]]
             [TablePager
              {:total-pages (count sorted-users)
               :current-page current-page}]])]]))))


(defn users-search
  "A component for searching users"
  []
  (let [retrieving?        (r/cursor state [:search-retrieving?])
        search-term        (r/cursor state [:search-term])
        recent-search-term (r/cursor state [:recent-search-term])
        search-results     (r/cursor state [:search-results])
        retrieve-users (fn [search-term]
                         (retrieve-url
                          (str base-url "users/search/" search-term)
                          "GET"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [r]
                             (let [response (js->clj
                                             r :keywordize-keys true)]
                               (reset! retrieving? false)
                               (reset! recent-search-term search-term)
                               (reset! search-results response))))))]
    (fn []
      [:form
       [:div {:class "form-group"}
        [:input {:type "text"
                 :defaultValue ""
                 :class "form-control"
                 :placeholder "Search Term"
                 :on-change (fn [e]
                              (reset! search-term
                                      (-> e
                                          (aget "target")
                                          (aget "value"))))}]]
       [:button {:type "submit"
                 :class (str "btn btn-default "
                             (when @retrieving?
                               "disabled")
                             (when (s/blank? @search-term)
                               "disabled"))
                 :disabled  (cond @retrieving?
                                  true
                                  (s/blank? @search-term)
                                  true
                                  :else false)
                 :on-click (fn [e]
                             (.preventDefault e)
                             (reset! retrieving? true)
                             (retrieve-users @search-term))}
        (if-not @retrieving?
          "Search Users"
          [:i {:class "fa fa-spinner fa-pulse"}])]])))

(defn search-panel
  []
  (let [retrieving?        (r/cursor state [:search-retrieving?])
        search-results     (r/cursor state [:search-results])
        recent-search-term (r/cursor state [:recent-search-term])]
    (fn []
      [:div {:class "panel panel-default"}
       [:div {:class "panel-body"}
        [:div [:h4 {:class "pull-left"} "Search Users"]
         [users-search]
         (when (and (empty? @search-results)
                    (not (s/blank? @recent-search-term))
                    (not @retrieving?))
           [:h5 "Your search - " [:strong @recent-search-term]
            " - did not match any users."])
         (when-not (empty? @search-results)
           [:div
            [:h5 "Users matching - " [:strong @recent-search-term]]
            [users-panel @search-results]])]]])))
