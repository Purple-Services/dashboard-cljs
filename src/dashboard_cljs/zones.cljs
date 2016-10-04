(ns dashboard-cljs.zones
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [clojure.set :refer [subset?]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          parse-to-number? accessible-routes
                                          diff-message get-input-value]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton TablePager
                                               FormGroup TextInput KeyVal
                                               EditFormSubmit DismissButton
                                               ConfirmationAlert AlertSuccess
                                               SubmitDismissConfirmGroup]]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [clojure.string :as s]))

(def default-zone
  {:errors nil
   :confirming? false
   :editing? false
   :retrieving? false
   :name "" ; required
   :rank 1000 ; required
   :active true ; required
   :zips ""
   ;; below is in :config
   :hours [[[]] ; Su
           [[]] ; M
           [[]] ; T
           [[]] ; W
           [[]] ; Th
           [[]] ; F
           [[]] ; Sa
           ]
   :gas-price {}
   :gas-price-diff-percent {}
   :gas-price-diff-fixed {}
   :gallon-choices {:0 7.5
                    :1 10
                    :2 15}
   :default-gallong-choice :2
   :time-choices {:0 60
                  :1 180
                  :2 300}
   :default-time-choice 180
   :constrain-num-one-hour? false
   :delivery-fee {60 599
                  180 399
                  300 299}
   :delivery-fee-diff-percent {}
   :delivery-fee-diff-fixed {}
   :tire-pressure-price 700
   :manually-closed? false
   :closed-message ""})

(defn zone->form-zone
  [zone]
  {:name (:name zone)
   :rank (:rank zone)
   :active (:active zone)
   :id (:id zone)})

(def state (r/atom {:current-zone nil
                    :confirming-edit? false
                    :alert-success ""
                    :confirming? false
                    :editing? false
                    :retrieving? false
                    :create-confirming? false
                    :create-editing? false
                    :create-retrieving? false
                    :edit-zone default-zone
                    :create-edit-zone default-zone
                    :selected "active"}))

(defn DisplayedZoneComp
  "Display a zone's information"
  [zone]
  (fn [zone]
    [:div {:class "row"}
     [:div {:class "col-lg-12"}
      [KeyVal "Name" (:name zone)]
      [KeyVal "Rank" (:rank zone)]
      [KeyVal "Active" (if (:active zone)
                         "Yes"
                         "No")]
      (let [gas-price-87 (get-in zone [:config :gas-price :87])
            gas-price-91 (get-in zone [:config :gas-price :91])]
        [KeyVal "Gas Price" (str
                             "87 "
                             (if (int? gas-price-87)
                               (str "$" (cents->dollars gas-price-87))
                               gas-price-87)
                             " | "
                             "91 "
                             (if (int? gas-price-91)
                               (str "$" (cents->dollars gas-price-91))
                               gas-price-91))])
      [KeyVal "Zips" (:zips zone)]]]))

(defn ZoneFormComp
  "Create or edit a zone"
  [props]
  (fn [{:keys [zone errors]} props]
    (let [name (r/cursor zone [:name])
          rank (r/cursor zone [:rank])
          active (r/cursor zone [:active])]
      [:div {:class "row"}
       [:div {:class "col-lg-12"}
        ;; name
        [FormGroup {:label "Name"
                    :errors (:name @errors)}
         [TextInput {:value @name
                     :on-change #(reset! name
                                         (get-input-value %))}]]
        ;; rank
        [FormGroup {:label "Rank"
                    :errors (:rank @errors)}
         [TextInput {:value @rank
                     :on-change #(let [value (get-input-value %)]
                                   (reset! rank (if (parse-to-number? value)
                                                  (js/parseInt value)
                                                  value)))}]]
        ;; active
        [FormGroup {:label "Active"
                    :errors (:active @errors)}
         [:input {:type "checkbox"
                  :checked @active
                  :style {:margin-left "4px"}
                  :on-change (fn [e] (reset!
                                      active
                                      (-> e
                                          (.-target)
                                          (.-checked))))}]]]])))

(defn EditZoneFormComp
  [zone]
  (let [;; internal state
        confirming? (r/cursor state [:confirming?])
        editing? (r/cursor state [:editing?])
        retrieving? (r/cursor state [:retrieving?])
        errors (r/atom {})
        alert-success (r/atom "")]
    (fn [zone]
      (let [current-zone zone ; before changes are made to zone
            edit-zone (r/cursor state [:edit-zone])
            ;; atoms for the editable zone
            name (r/cursor edit-zone [:name])
            rank (r/cursor edit-zone [:rank])
            active (r/cursor edit-zone [:active])
            ;; helper fns
            diff-key-str {:name "Name"
                          :rank "Rank"
                          :active "Active"}
            diff-msg-gen (fn [edit current]
                           (diff-message
                            edit
                            current
                            diff-key-str))
            confirm-msg (fn []
                          [:div (str "The following changes will be made to "
                                     (:name @current-zone))
                           (map (fn [el]
                                  ^{:key el}
                                  [:h4 el])
                                (diff-msg-gen @edit-zone @current-zone))])
            submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (if (every? nil? (diff-msg-gen @edit-zone
                                                               @current-zone))
                                  ;; there isn't a diff message, no changes
                                  (reset! editing? false)
                                  ;; there is a diff message, confirm change
                                  (reset! confirming? true))
                                (do
                                  ;; reset edit zone
                                  (reset! edit-zone (zone->form-zone @zone))
                                  ;; get rid of alert-success
                                  (reset! alert-success "")
                                  (reset! editing? true))))
            confirm-on-click (fn [_]
                               (entity-save
                                @edit-zone
                                "zone"
                                "PUT"
                                retrieving?
                                (edit-on-success "zone"
                                                 edit-zone
                                                 current-zone
                                                 alert-success
                                                 :aux-fn
                                                 (fn []
                                                   (reset! confirming? false)
                                                   (reset! retrieving? false)
                                                   (reset! editing? false)))
                                (edit-on-error edit-zone
                                               :aux-fn
                                               (fn []
                                                 (reset! confirming? false)
                                                 (reset! retrieving? false)
                                                 (reset! alert-success ""))
                                               :response-fn
                                               (fn [response]
                                                 (reset! errors response)))))
            dismiss-fn (fn [e]
                         ;; reset any errors
                         (reset! errors nil)
                         ;; no longer editing
                         (reset! editing? false)
                         ;; reset edit-zone
                         (reset! edit-zone (zone->form-zone zone))
                         ;; reset confirming
                         (reset! confirming? false))]
        [:div
         (when-not @editing?
           [DisplayedZoneComp @current-zone])
         [:form {:class "form-horizontal"}
          (when @editing?
            [ZoneFormComp {:zone edit-zone
                           :errors errors}])
          [SubmitDismissConfirmGroup
           {:confirming? confirming?
            :editing? editing?
            :retrieving? retrieving?
            :submit-fn submit-on-click
            :dismiss-fn dismiss-fn}]
          (if (and @confirming?
                   (not-every? nil? (diff-msg-gen @edit-zone @current-zone)))
            [ConfirmationAlert
             {:confirmation-message confirm-msg
              :cancel-on-click dismiss-fn
              :confirm-on-click confirm-on-click
              :retrieving? retrieving?}]
            (reset! confirming? false))
          (when-not (empty? @alert-success)
            [AlertSuccess {:message @alert-success
                           :dismiss #(reset! alert-success)}])]]))))

(defn CreateZoneFormComp
  []
  (let [;; internal state
        confirming? (r/cursor state [:create-confirming?])
        editing? (r/cursor state [:create-editing?])
        retrieving? (r/cursor state [:create-retrieving?])
        errors (r/atom {})
        alert-success (r/atom "")]
    (fn []
      (let [edit-zone (r/cursor state [:create-edit-zone])
            ;; atoms for the editable zone
            name (r/cursor edit-zone [:name])
            rank (r/cursor edit-zone [:rank])
            active (r/cursor edit-zone [:active])
            ;; helper fns
            confirm-msg (fn [zone]
                          (let [{:keys [name rank active]} zone]
                            (.log js/console active)
                            [:div
                             (str "Are you sure you want to create new zone "
                                  "with the following value?")
                             [:h4 "Name: " name]
                             [:h4 "Rank: " rank]
                             [:h4 "Active: " (if  active
                                               "Yes"
                                               "No")]]))
            submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (do
                                  (reset! confirming? true))
                                (do
                                  (reset! alert-success "")
                                  (reset! editing? true))))
            confirm-on-click (fn [_]
                               (entity-save
                                @edit-zone
                                "zone"
                                "POST"
                                retrieving?
                                (edit-on-success "zone"
                                                 edit-zone
                                                 (r/atom {})
                                                 alert-success
                                                 :aux-fn
                                                 (fn []
                                                   (reset! confirming? false)
                                                   (reset! retrieving? false)
                                                   (reset! editing? false)))
                                (edit-on-error edit-zone
                                               :aux-fn
                                               (fn []
                                                 (reset! confirming? false)
                                                 (reset! retrieving? false)
                                                 (reset! alert-success ""))
                                               :response-fn
                                               (fn [response]
                                                 (reset! errors response)))))
            dismiss-fn (fn [e]
                         ;; reset any errors
                         (reset! errors nil)
                         ;; no longer editing
                         (reset! editing? false)
                         ;; reset edit-zone
                         (reset! edit-zone default-zone)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:div
         [:form {:class "form-horizontal"}
          (when @editing?
            [ZoneFormComp {:zone edit-zone
                           :errors errors}])
          [SubmitDismissConfirmGroup
           {:confirming? confirming?
            :editing? editing?
            :retrieving? retrieving?
            :submit-fn submit-on-click
            :dismiss-fn dismiss-fn
            :edit-btn-content "Create a New Zone"}]
          (when @confirming?
            [ConfirmationAlert
             {:confirmation-message (fn [] (confirm-msg @edit-zone))
              :cancel-on-click dismiss-fn
              :confirm-on-click confirm-on-click
              :retrieving? retrieving?}])
          (when-not (empty? @alert-success)
            [AlertSuccess {:message @alert-success
                           :dismiss #(reset! alert-success)}])]]))))

(defn zone-table-header
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
       (conj props {:keyword :rank})
       "Rank"]
      [TableHeadSortable
       (conj props {:keyword :active})
       "Active"]
      [TableHeadSortable
       (conj props {:keyword :name})
       "Name"]
      [TableHeadSortable
       (conj props {:keyword :zip_count})
       "# of Zips"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Zips"]]]))

(defn zone-row
  "A table row for a zone."
  [current-zone]
  (fn [zone]
    [:tr {:class (when (= (:id zone)
                          (:id @current-zone))
                   "active")
          :on-click (fn [_]
                      (reset! current-zone zone)
                      (reset! (r/cursor state [:editing?]) false)
                      (reset! (r/cursor state [:alert-success]) ""))}
     ;; Rank
     [:td (-> zone
              :rank)]
     ;; Active
     [:td (if (-> zone
                  :active)
            "Yes"
            "No")]
     ;; name
     [:td (-> zone
              :name)]
     ;; # of zips
     [:td ;;(count (-> zone))
      (-> zone
          :zip_count)]
     ;; Zips
     [:td {:style {:overflow "scroll"}}
      (let [zips-string (:zips zone)
            subs-string (subs zips-string 0 68)]
        (if (> (count zips-string) 68)
          (str subs-string ", ...")
          zips-string))]]))

(defn zones-panel
  "Display a table of zones"
  [zones]
  (let [current-zone (r/cursor state [:current-zone])
        edit-zone (r/cursor state [:edit-zone])
        sort-keyword (r/atom :rank)
        sort-reversed? (r/atom true)
        selected (r/cursor state [:selected])
        current-page (r/atom 1)
        page-size 15]
    (fn [zones]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-zones zones
            sorted-zones (fn []
                           (->> displayed-zones
                                sort-fn
                                (partition-all page-size)))
            paginated-zones (fn []
                              (-> (sorted-zones)
                                  (nth (- @current-page 1)
                                       '())))
            refresh-fn (fn [refreshing?]
                         (reset! refreshing? true)
                         (retrieve-url
                          (str base-url "zones")
                          "GET"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             ;; update the users atom
                             (put! datastore/modify-data-chan
                                   {:topic "zones"
                                    :data (js->clj response :keywordize-keys
                                                   true)})
                             (reset! refreshing? false)))))
            table-pager-on-click (fn []
                                   (reset! current-zone
                                           (first (paginated-zones))))]
        (when (nil? @current-zone)
          (table-pager-on-click))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            (when (subset? #{{:uri "/zone"
                              :method "PUT"}}
                           @accessible-routes)
              [:div {:class "panel-body"}
               [:h2 [:i {:class "fa fa-circle"
                         :style {:color
                                 (:color @current-zone)}}]
                (str " " (:name @current-zone))]
               [EditZoneFormComp current-zone]])]]
          [:br]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:div [:h3 {:class "pull-left"
                        :style {:margin-top "4px"
                                :margin-bottom 0}}
                   "Zones"]]
            [:div {:class "btn-toolbar"
                   :role "toolbar"}
             [:div {:class "btn-group"
                    :role "group"}
              [RefreshButton {:refresh-fn
                              refresh-fn}]]]]]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:div {:class "table-responsive"}
             [StaticTable
              {:table-header [zone-table-header
                              {:sort-keyword sort-keyword
                               :sort-reversed? sort-reversed?}]
               :table-row (zone-row current-zone)}
              (paginated-zones)]]
            [TablePager
             {:total-pages (count (sorted-zones))
              :current-page current-page
              :on-click table-pager-on-click}]]]
          [:br]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [CreateZoneFormComp]]]]]))))

