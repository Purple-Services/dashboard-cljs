(ns dashboard-cljs.fleet
  (:require [cljs.core.async :refer [put! take!]]
            [cljsjs.moment]
            [clojure.string :as s]
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
                                               UserCrossLink DatePicker]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error retrieve-entity]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf base-url in?
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
                                          subscription-id->name]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.googlemaps :refer [gmap get-cached-gmaps
                                               on-click-tab]]
            [dashboard-cljs.state :refer [landing-state users-state]]))

(enable-console-print!)

(def state (r/atom {:selected-orders #{}
                    :currently-viewed-orders #{}
                    :delete-confirming? false
                    :busy false
                    :alert-success ""}))

(defn in-selected-orders?
  [id]
  (let [selected-orders (r/cursor state [:selected-orders])]
    (in? @selected-orders id)))

(defn toggle-select-order
  [order]
  (let [selected-orders (r/cursor state [:selected-orders])]
    (swap! selected-orders
           #(if (in-selected-orders? (:id order))
              (disj % (:id order))
              (conj % (:id order)))
           (:id order))))

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

(def orders-table-vecs
  [[[:input {:type "checkbox"
             :id "select-deselect-all-checkbox"
             :on-click evt-select-deselect-all
             :style {:cursor "pointer"}}]
    nil (fn [order] [OrderCheckbox order])]
   ["Location" :fleet_location_name :fleet_location_name]
   ["Plate/Stock" :license_plate :license_plate]
   ["VIN" :vin :vin]
   ["Gallons" :gallons :gallons]
   ["Type" :gas_type :gas_type]
   ["Top Tier?" :is_top_tier :is_top_tier]
   ["PPG" :gas_price #(cents->$dollars (:gas_price %))]
   ["Fee" :service_fee #(cents->$dollars (:service_fee %))]
   ["Total" :total_price #(cents->$dollars (:total_price %))]
   ["Date" :timestamp_created #(unix-epoch->hrf
                                (/ (.getTime (js/Date. (:timestamp_created %)))
                                   1000))]])

(defn account-names
  [orders]
  (keys (group-by :account_name orders)))

;; excuse the misnomer "orders" and "order" (actually "deliveries")
(defn fleet-panel
  [orders state]
  (let [currently-viewed-orders (r/cursor state [:currently-viewed-orders])
        default-sort-keyword :timestamp_created
        sort-keyword (r/atom default-sort-keyword)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        page-size 20
        from-date (r/atom (-> (js/moment)
                              (.subtract 30 "days")
                              (.unix)))
        to-date (r/atom (now))
        selected-primary-filter (r/atom "In Review")
        primary-filters {"In Review" #(and (not (:approved %)) (not (:deleted %)))
                         "Approved" #(and (:approved %) (not (:deleted %)))
                         "Deleted" #(:deleted %)}
        selected-account-filter (r/atom (:account_name (first orders)))        
        selected-orders (r/cursor state [:selected-orders])
        delete-confirming? (r/cursor state [:delete-confirming?])
        busy? (r/cursor state [:busy?])]
    (fn [orders]
      (let [orders-filtered-primary (filter (get primary-filters @selected-primary-filter) orders)
            account-filters (->> orders-filtered-primary
                                 account-names
                                 sort
                                 (map (fn [x] [x #(= (:account_name %) x)]))
                                 (into {}))
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
            refresh-fn (fn [refreshing?]
                         (reset! refreshing? true)
                         (retrieve-url
                          (str base-url "fleet-deliveries-since-date")
                          "POST"
                          (js/JSON.stringify
                           (clj->js
                            {:from-date (-> @from-date
                                            (js/moment.unix)
                                            (.endOf "day")
                                            (.format "YYYY-MM-DD"))
                             :to-date (-> @to-date
                                          (js/moment.unix)
                                          (.endOf "day")
                                          (.format "YYYY-MM-DD"))}))
                          (partial xhrio-wrapper
                                   (fn [response]
                                     (let [parsed-data (js->clj response :keywordize-keys true)]
                                       (put! datastore/modify-data-chan
                                             {:topic "fleet-deliveries"
                                              :data parsed-data})
                                       (reset! refreshing? false))))))]
        (add-watch from-date :from-date-watch
                   (fn [_ _ old-value new-value]
                     (when (not= old-value new-value)
                       (refresh-fn busy?))))
        (add-watch to-date :to-date-watch
                   (fn [_ _ old-value new-value]
                     (when (not= old-value new-value)
                       (refresh-fn busy?))))
        [:div {:class "panel panel-default"}
         [:div {:class "form-group"
                :style {:margin-left "1px"}}
          [:label {:for "expires?"
                   :class "control-label"}
           [:div {:style {:display "inline-block"}}
            [:div
             [:div {:class "input-group"}
              [DatePicker from-date]]]]]
          [:span {:style {:font-size "3em"
                          :color "grey"}} " - "]
          [:label {:for "expires?"
                   :class "control-label"}
           [:div {:style {:display "inline-block"}}
            [:div
             [:div {:class "input-group"}
              [DatePicker to-date]]]]]]
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
               ^{:key (first f)}
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
             [RefreshButton {:refresh-fn refresh-fn
                             :refreshing? busy?}]
             [:button {:type "submit"
                       :class "btn btn-success"
                       :disabled (< (count @selected-orders) 1)
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
                                               (refresh-fn busy?))
                                           (do (.log js/console "success false")
                                               (reset! busy? false))))))))}
              (str "Approve"
                   (when (pos? (count @selected-orders))
                     (str " (" (count @selected-orders) ")")))]
             [:button {:type "submit"
                       :class "btn btn-danger"
                       :disabled (< (count @selected-orders) 1)
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (reset! delete-confirming? true))}
              (str "Delete"
                   (when (pos? (count @selected-orders))
                     (str " (" (count @selected-orders) ")")))]
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
                        :disabled (< (count @selected-orders) 1)}
               (str "Download CSV"
                    (when (pos? (count @selected-orders))
                      (str " (" (count @selected-orders) ")")))]]
             ;; [:button {:type "submit"
             ;;           :class "btn btn-default"
             ;;           :disabled (< (count @selected-orders) 1)
             ;;           :on-click (fn [e]
             ;;                       (.preventDefault e)
             ;;                       (println "Email CSV"))}
             ;;  (str "Email CSV"
             ;;       (when (pos? (count @selected-orders))
             ;;         (str " (" (count @selected-orders) ")")))]
             ])]
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
                                              (refresh-fn busy?))
                                          (do (.log js/console "success false")
                                              (reset! busy? false))))))))
              :retrieving? busy?}]])
         [:div {:class "table-responsive"}
          [DynamicTable {:tr-props-fn
                         (fn [order _]
                           {:class (str (when (in-selected-orders? (:id order)) "active"))
                            :on-click #(toggle-select-order order)})
                         :sort-keyword sort-keyword
                         :sort-reversed? sort-reversed?
                         :table-vecs orders-table-vecs}
           orders-paginated]]
         [TablePager
          {:total-pages (count orders-partitioned)
           :current-page current-page
           :on-click table-pager-on-click}]]))))
