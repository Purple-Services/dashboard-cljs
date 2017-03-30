(ns dashboard-cljs.fleet
  (:require [cljs.core.async :refer [put! take!]]
            [cljsjs.moment]
            [clojure.string :as s]
            [goog.string :as gstring]
            [goog.string.format]
            [clojure.set :refer [subset?]]
            [reagent.core :as r]
            [dashboard-cljs.components :refer [DynamicTable
                                               RefreshButton ErrorComp
                                               TableFilterButton
                                               TableFilterButtonGroup
                                               TablePager ConfirmationAlert
                                               KeyVal ProcessingIcon FormGroup
                                               TextAreaInput DismissButton
                                               SubmitDismissGroup Select
                                               TelephoneNumber
                                               Mailto GoogleMapLink
                                               UserCrossLink DatePicker TextInput]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error retrieve-entity]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf unix-epoch->standard
                                          base-url in? standard->unix-epoch
                                          cents->$dollars json-string->clj
                                          accessible-routes
                                          new-orders-count
                                          parse-timestamp
                                          oldest-current-order
                                          same-timestamp?
                                          declined-payment?
                                          current-order?
                                          get-event-time
                                          get-by-id now update-values
                                          select-toggle-key!
                                          subscription-id->name
                                          commaize-thousands]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [gmap get-cached-gmaps
                                               on-click-tab]]
            [dashboard-cljs.state :refer [landing-state users-state]]))

(enable-console-print!)

(def state (r/atom {:selected-orders #{}
                    :editing-fields #{}
                    :editing-value ""
                    :currently-viewed-orders #{}
                    :from-date (-> (js/moment)
                                   (.subtract 10 "days")
                                   (.unix))
                    :to-date (now)
                    :search-term ""
                    :delete-confirming? false
                    :busy false
                    :alert-success ""}))

(defn in-xatom-items?
  [atom id]
  (in? @atom id))

(defn toggle-xatom-select-item
  [atom item]
  (swap! atom
         #(if (in-xatom-items? atom item)
            (disj % item)
            (conj % item))
         nil))

(defn in-selected-orders?
  [id]
  (in-xatom-items? (r/cursor state [:selected-orders]) id))

(defn toggle-select-order
  [order]
  (toggle-xatom-select-item (r/cursor state [:selected-orders]) (:id order)))

(defn in-editing-fields?
  [order-id field-name]
  (in-xatom-items? (r/cursor state [:editing-fields]) [order-id field-name]))

(defn toggle-edit-field
  [order-id field-name]
  (swap! (r/cursor state [:editing-fields]) (comp set (partial filter (partial = [order-id field-name])))) ; only edit one at a time
  (toggle-xatom-select-item (r/cursor state [:editing-fields]) [order-id field-name]))

(defn OrderCheckbox
  [order]
  (fn [order]
    [:div
     [:input (merge {:type "checkbox"
                     ;; Actually, for now we just have the whole tr clickable.
                     :on-click nil
                     ;; :on-click #(toggle-select-order order)
                     }
                    (when (in-selected-orders? (:id order))
                      {:checked "checked"}))]]))

(defn evt-select-deselect-all
  "Toggles selection of all orders which are currently viewable in the table."
  [evt]
  (let [selected-orders (r/cursor state [:selected-orders])
        currently-viewed-orders (r/cursor state [:currently-viewed-orders])]
    (if (-> evt .-target .-checked)
      (swap! selected-orders (comp set concat) @currently-viewed-orders)
      (swap! selected-orders (partial apply disj) @currently-viewed-orders))))

;; Hacky way of keeping track the most recent refresh call.
;; Ideally, this system would be replaced with one that actually aborts all the current XHR requests.
(def most-recent-refresh-call-id (atom nil))

(defn refresh-fn
  [refreshing? from-date to-date search-term]
  (let [refresh-call-id (gensym "refresh-call")]
    (reset! most-recent-refresh-call-id refresh-call-id)
    (reset! refreshing? true)
    (retrieve-url
     (str base-url "fleet-deliveries-since-date")
     "POST"
     (js/JSON.stringify
      (clj->js
       {:from-date (-> from-date
                       (js/moment.unix)
                       (.endOf "day")
                       (.format "YYYY-MM-DD"))
        :to-date (-> to-date
                     (js/moment.unix)
                     (.endOf "day")
                     (.format "YYYY-MM-DD"))
        :search-term search-term}))
     (partial xhrio-wrapper
              (fn [response]
                (when (= refresh-call-id @most-recent-refresh-call-id)
                  (let [parsed-data (js->clj response :keywordize-keys true)]
                    (put! datastore/modify-data-chan
                          {:topic "fleet-deliveries"
                           :data parsed-data})
                    (reset! refreshing? false))))))))

(defn cell-save-button
  [order field-name & {:keys [reverse-transform-fn]}]
  [:span {:class "input-group-btn"}
   [:button {:type "submit"
             :class "btn btn-success"
             :on-click (fn [e]
                         (.preventDefault e)
                         (let [get-input-el #(.getElementById js/document (str field-name "-input-" (:id order)))
                               new-value ((or reverse-transform-fn identity)
                                          (aget (get-input-el) "value"))
                               _ (aset (get-input-el) "disabled" true)]
                           (if (not= new-value ((keyword field-name) order))
                             (let [busy? (r/cursor state [:busy?])]
                               (reset! busy? true)
                               (aset (get-input-el) "value" new-value)
                               (retrieve-url
                                (str base-url "update-fleet-delivery-field")
                                "PUT"
                                (js/JSON.stringify
                                 (clj->js {:fleet-delivery-id (:id order)
                                           :field-name field-name
                                           :value new-value}))
                                (partial
                                 xhrio-wrapper
                                 (fn [r]
                                   (let [response (js->clj r :keywordize-keys true)]
                                     (if (:success response)
                                       (do (toggle-edit-field (:id order) field-name)
                                           (refresh-fn busy?
                                                       (deref (r/cursor state [:from-date]))
                                                       (deref (r/cursor state [:to-date]))
                                                       (deref (r/cursor state [:search-term]))))
                                       (do (.log js/console "success false")
                                           (reset! busy? false)
                                           (js/setTimeout
                                            (fn []
                                              (aset (get-input-el) "value" new-value)
                                              (js/alert (:message response)))
                                            250)
                                           )))))))
                             (toggle-edit-field (:id order) field-name))))
             :dangerouslySetInnerHTML {:__html "&#10003;"}}]])

(defn text-input
  [field-name placeholder width order
   & {:keys [transform-fn reverse-transform-fn]}]
  [:div {:class "input-group"
         :style {:width (str width "px")}}
   [:input {:type "text"
            :id (str field-name "-input-" (:id order))
            :class "form-control"
            :defaultValue ((or transform-fn identity)
                           ((keyword field-name) order))
            ;; look into adding submit upon hitting Enter
            :placeholder placeholder
            :style {:font-size "13px"}
            :on-double-click (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e))}]
   (cell-save-button order field-name :reverse-transform-fn reverse-transform-fn)])

(defn datetime-input
  [field-name placeholder width transform-fn reverse-transform-fn order]
  (text-input field-name placeholder width order
              :transform-fn transform-fn
              :reverse-transform-fn reverse-transform-fn))

(defn select-input
  [field-name options width order]
  [:div {:class "input-group"
         :style {:width (str width "px")}}
   [:select {:id (str field-name "-input-" (:id order))
             :class "form-control"
             :defaultValue ((keyword field-name) order)}
    (doall
     (for [[option-value option-text ] options]
       ^{:key (gensym "key")}
       [:option {:value option-value} option-text]))]
   (cell-save-button order field-name)])

(defn orders-table-vecs
  [fleet-locations]
  [[[:input {:type "checkbox"
             :id "select-deselect-all-checkbox"
             :on-click evt-select-deselect-all
             :style {:cursor "pointer"}}]
    nil (fn [order] [OrderCheckbox order])]
   ["Location" :fleet_location_name :fleet_location_name "fleet_location_id"
    (partial select-input "fleet_location_id" fleet-locations 300)]
   ["Plate/Stock" :license_plate :license_plate "license_plate"
    (partial text-input "license_plate" "License Plate" 130)]
   ["VIN" :vin :vin "vin" (partial text-input "vin" "VIN" 200)]
   ["Gallons" :gallons :gallons "gallons" (partial text-input "gallons" "Gallons" 105)]
   ["Type" :gas_type :gas_type "gas_type"
    ;; if changing options, also consider updating app:view/Fleet.coffee:151
    (partial select-input "gas_type"
             [["87" "87"]
              ["89" "89"]
              ["91" "91"]
              ["Regular Diesel" "Regular Diesel"]
              ["Dyed Diesel" "Dyed Diesel"]]
             110)]
   ["Top Tier?" :is_top_tier #(if (:is_top_tier %) "Yes" "No") "is_top_tier"
    (partial select-input "is_top_tier"
             [[true "Yes"]
              [false "No"]]
             90)]
   ["PPG" :gas_price #(cents->$dollars (:gas_price %)) "gas_price" (partial text-input "gas_price" "PPG" 105)]
   ["Fee" :service_fee #(cents->$dollars (:service_fee %)) "service_fee" (partial text-input "service_fee" "Fee" 105)]
   ["Total" :total_price #(cents->$dollars (:total_price %))]
   ["Recorded" :timestamp_recorded #(unix-epoch->hrf (:timestamp_recorded %)) "timestamp_recorded"
    (partial datetime-input "timestamp_recorded" "YYYY-MM-DD HH:mm" 167
             unix-epoch->standard standard->unix-epoch)]
   ;; ["Date" :timestamp_created #(unix-epoch->hrf (/ (.getTime (js/Date. (:timestamp_created %))) 1000))]
   ])

(defn account-names
  [orders]
  (keys (group-by :account_name orders)))

;; excuse the misnomer "orders" and "order" (actually "deliveries")
(defn fleet-panel
  [orders state]
  (let [currently-viewed-orders (r/cursor state [:currently-viewed-orders])
        default-sort-keyword :timestamp_recorded
        sort-keyword (r/atom default-sort-keyword)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        page-size 15
        from-date (r/cursor state [:from-date])
        to-date (r/cursor state [:to-date])
        search-term (r/cursor state [:search-term])
        selected-primary-filter (r/atom "In Review")
        primary-filters {"Show All" #(not (:deleted %))
                         "In Review" #(and (not (:approved %)) (not (:deleted %)))
                         "Approved" #(and (:approved %) (not (:deleted %)))
                         "Deleted" #(:deleted %)}
        selected-account-filter (r/atom (:account_name (first orders)))
        selected-orders (r/cursor state [:selected-orders])
        delete-confirming? (r/cursor state [:delete-confirming?])
        busy? (r/cursor state [:busy?])]
    (add-watch from-date :from-date-watch
               (fn [_ _ old-value new-value]
                 (when (not= old-value new-value)
                   (do (swap! selected-orders empty)
                       (refresh-fn busy? new-value @to-date @search-term)))))
    (add-watch to-date :to-date-watch
               (fn [_ _ old-value new-value]
                 (when (not= old-value new-value)
                   (do (swap! selected-orders empty)
                       (refresh-fn busy? @from-date new-value @search-term)))))
    (add-watch search-term :search-term-watch
               (fn [_ _ old-value new-value]
                 (when (not= old-value new-value)
                   (do (swap! selected-orders empty)
                       (refresh-fn busy? @from-date @to-date new-value)))))
    (fn [orders]
      (let [orders-filtered-primary (filter (get primary-filters @selected-primary-filter) orders)
            account-filters (merge {"Show All" (constantly true)}
                                   (->> orders-filtered-primary
                                        account-names
                                        sort
                                        (map (fn [x] [x #(= (:account_name %) x)]))
                                        (into {})))
            _ (when (not (in? (keys account-filters) @selected-account-filter))
                (reset! selected-account-filter (:account_name (first orders-filtered-primary))))
            orders-filtered-secondary (filter (get account-filters @selected-account-filter) orders-filtered-primary)
            orders-sorted ((if @sort-reversed?
                             (partial sort-by @sort-keyword)
                             (comp reverse (partial sort-by @sort-keyword)))
                           orders-filtered-secondary)
            orders-partitioned (partition-all page-size orders-sorted)
            orders-paginated (nth orders-partitioned (- @current-page 1) '())
            ;; currently-viewed-orders keeps track of the orders that are visible on table at
            ;; any given time. it is used for the Select All feature
            _ (reset! currently-viewed-orders (set (map :id orders-paginated)))
            table-pager-on-click (fn [] (some-> (.getElementById js/document "select-deselect-all-checkbox")
                                                (aset "checked" "")))
            table-filter-button-on-click (fn []
                                           (reset! sort-keyword default-sort-keyword)
                                           (reset! current-page 1)
                                           (table-pager-on-click))
            any-cells-editing? (not (empty? (deref (r/cursor state [:editing-fields]))))
            orders-selected (filter (comp @selected-orders :id) orders)]
        [:div {:class "panel panel-default"}
         [:div {:class "form-group"}
          [:span {:style {:font-size "17px"
                          :color "grey"
                          :padding "0px 11px 0px 0px"
                          :display "inline-block"
                          :position "relative"
                          :top "-11px"}}
           "from "]
          [:label {:for "expires?"
                   :class "control-label"}
           [:div {:style {:display "inline-block"}}
            [:div
             [:div {:class "input-group"}
              [DatePicker from-date]]]]]
          [:span {:style {:font-size "17px"
                          :color "grey"
                          :padding "0px 11px"
                          :display "inline-block"
                          :position "relative"
                          :top "-11px"}}
           " to "]
          [:label {:for "expires?"
                   :class "control-label"}
           [:div {:style {:display "inline-block"}}
            [:div
             [:div {:class "input-group"}
              [DatePicker to-date]]]]]
          [:span {:style {:font-size "17px"
                          :color "grey"
                          :padding "0px 11px"
                          :display "inline-block"
                          :position "relative"
                          :top "-11px"}}
           " and contains "]
          [:div {:style {:display "inline-block"}}
           [:div
            [:div {:class "input-group"}
             [:input {:type "text"
                      :class "form-control"
                      :placeholder "Search VIN / Plate"
                      :on-change (fn [e]
                                   (reset! search-term
                                           (-> e
                                               (aget "target")
                                               (aget "value"))))}]]]]]
         [:div {:class "panel-body"
                :style {:margin-top "-15px"}}
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group" :role "group"}
            (doall
             (for [f primary-filters]
               ^{:key (first f)}
               [TableFilterButton {:text (first f)
                                   :filter-fn (second f)
                                   :hide-count false
                                   :on-click (fn [] (table-filter-button-on-click))
                                   :data orders
                                   :selected-filter selected-primary-filter}]))]]]
         [:div {:class "panel-body"
                :style {:margin-top "15px"}}
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group" :role "group"}
            (doall
             (for [f account-filters]
               ^{:key (gensym (first f))}
               [TableFilterButton {:text (first f)
                                   :filter-fn (second f)
                                   :hide-count false
                                   :on-click (fn [] (table-filter-button-on-click))
                                   :data orders-filtered-primary
                                   :selected-filter selected-account-filter}]))]]]
         
         [:div {:class "panel-body"
                :style {:margin-top "15px"}}
          (if @busy?
            [:i {:class "fa fa-lg fa-refresh fa-pulse"}]
            [:div {:class "btn-toolbar"
                   :role "toolbar"}
             [RefreshButton {:refresh-fn #(refresh-fn % @from-date @to-date @search-term)
                             :refreshing? busy?
                             :disabled any-cells-editing?}]
             [:button {:type "submit"
                       :class "btn btn-default"
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (swap! selected-orders (comp set concat) (map :id orders-sorted)))}
              "Select All Pages"] ;; not to be confused with the select-all terminology we are using elsewhere
             ;;                      which refers to "all" the deliveries on just the current page
             [:button {:type "submit"
                       :class "btn btn-default"
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (swap! selected-orders empty))}
              "Deselect All"]
             [:button {:type "submit"
                       :class "btn btn-success"
                       :disabled (or (< (count @selected-orders) 1)
                                     any-cells-editing?)
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (reset! busy? true)
                                   (retrieve-url
                                    (str base-url "approve-fleet-deliveries")
                                    "PUT"
                                    (js/JSON.stringify
                                     (clj->js {:fleet-delivery-ids @selected-orders}))
                                    (partial
                                     xhrio-wrapper
                                     (fn [r]
                                       (let [response (js->clj r :keywordize-keys true)]
                                         (if (:success response)
                                           (do (swap! selected-orders empty)
                                               (refresh-fn busy? @from-date @to-date @search-term))
                                           (do (.log js/console "success false")
                                               (reset! busy? false))))))))}
              (str "Approve"
                   (when (pos? (count @selected-orders))
                     (str " (" (commaize-thousands (count @selected-orders)) ")")))]
             [:button {:type "submit"
                       :class "btn btn-danger"
                       :disabled (or (< (count @selected-orders) 1)
                                     any-cells-editing?)
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (reset! delete-confirming? true))}
              (str "Delete"
                   (when (pos? (count @selected-orders))
                     (str " (" (commaize-thousands (count @selected-orders)) ")")))]
             [:form {:method "POST"
                     :style {:display "inline-block"
                             :float "left"
                             :margin-left "5px"}
                     :action (str base-url "download-fleet-deliveries")}
              [:input {:type "hidden"
                       :name "fleet-delivery-ids"
                       :value (js/JSON.stringify (clj->js @selected-orders))}]
              [:button {:type "submit"
                        :class "btn btn-default"
                        :disabled (or (< (count @selected-orders) 1)
                                      any-cells-editing?)}
               (str "Download CSV"
                    (when (pos? (count @selected-orders))
                      (str " (" (commaize-thousands (count @selected-orders)) ")")))]]
             ;; [:button {:type "submit"
             ;;           :class "btn btn-default"
             ;;           :disabled (or (< (count @selected-orders) 1)
             ;;                            any-cells-editing?)
             ;;           :on-click (fn [e]
             ;;                       (.preventDefault e)
             ;;                       (println "Email CSV"))}
             ;;  (str "Email CSV"
             ;;       (when (pos? (count @selected-orders))
             ;;         (str " (" (commaize-thousands (count @selected-orders)) ")")))]
             [:button {:type "submit"
                       :class "btn btn-info"
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (reset! busy? true)
                                   (retrieve-url
                                    (str base-url "add-blank-fleet-delivery")
                                    "PUT"
                                    (js/JSON.stringify
                                     ;; create new fleet deliveries with just a blank Fleet Location
                                     (clj->js {:fleet-location-id ""}))
                                    (partial
                                     xhrio-wrapper
                                     (fn [r]
                                       (let [response (js->clj r :keywordize-keys true)]
                                         (if (:success response)
                                           (do (reset! selected-primary-filter "In Review")
                                               (reset! selected-account-filter "Show All")
                                               (reset! sort-keyword :timestamp_recorded)
                                               (reset! current-page 1)
                                               (table-pager-on-click)
                                               (refresh-fn busy? @from-date @to-date @search-term))
                                           (do (.log js/console "success false")
                                               (reset! busy? false))))))))}
              [:i {:class "fa fa-lg fa-plus"}]]
             [:div {:style {:float "right"
                            :font-size "11px"
                            :line-height "13px"
                            :position "relative"
                            :top "-2px"
                            :text-align "right"}}
              [:span
               (commaize-thousands (count orders-selected)) " selected"
               [:br]
               (->> orders-selected
                    (map :gallons)
                    (reduce +)
                    (gstring/format "%.2f")
                    commaize-thousands)
               " gal."
               [:br]
               (->> orders-selected
                    (map :total_price)
                    (reduce +)
                    cents->$dollars)]]])]
         (when @delete-confirming?
           [:div {:class "panel-body"
                  :style {:margin-top "15px"}}
            [ConfirmationAlert
             {:confirmation-message (fn [_]
                                      (let [num-selected (count @selected-orders)]
                                        [:div [:h4 (str "Delete "
                                                        num-selected
                                                        (if (> num-selected 1)
                                                          " deliveries"
                                                          " delivery")
                                                        "?")]]))
              :cancel-on-click (fn [_] (reset! delete-confirming? false))
              :confirm-on-click (fn [_]
                                  (reset! delete-confirming? false)
                                  (reset! busy? true)
                                  (retrieve-url
                                   (str base-url "delete-fleet-deliveries")
                                   "DELETE"
                                   (js/JSON.stringify
                                    (clj->js {:fleet-delivery-ids @selected-orders}))
                                   (partial
                                    xhrio-wrapper
                                    (fn [r]
                                      (let [response (js->clj r :keywordize-keys true)]
                                        (if (:success response)
                                          (do (swap! selected-orders empty)
                                              (refresh-fn busy? @from-date @to-date @search-term))
                                          (do (.log js/console "success false")
                                              (reset! busy? false))))))))
              :retrieving? busy?}]])
         [:div {:class "table-responsive"}
          [DynamicTable {:tr-props-fn
                         (fn [order current-item]
                           {:class (str (when (in-selected-orders? (:id order)) " active")
                                        ;; (when (in-editing-orders? (:id order)) " editing")
                                        )
                            :on-click #(when-not any-cells-editing?
                                         (toggle-select-order order))})
                         :cell-props-fn
                         (fn [order field-name]
                           {:on-double-click #(when (not (nil? field-name))
                                                (toggle-edit-field (:id order) field-name))})
                         :is-cell-editing? (fn [order field-name]
                                             (in-editing-fields? (:id order) field-name))
                         :sort-keyword sort-keyword
                         :sort-reversed? sort-reversed?
                         :table-vecs (orders-table-vecs (map (juxt :id :name) @datastore/fleet-locations))
                         :style {:white-space "nowrap"}}
           orders-paginated]]
         [TablePager
          {:total-pages (count orders-partitioned)
           :current-page current-page
           :on-click table-pager-on-click}]]))))
