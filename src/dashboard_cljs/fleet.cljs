(ns dashboard-cljs.fleet
  (:require [cljs.core.async :refer [put!]]
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
                                               UserCrossLink]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error retrieve-entity]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf base-url
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

(def state (r/atom {:current-order nil
                    :alert-success ""}))

(def orders-table-vecs
  [["Location" :fleet_location_name :fleet_location_name]
   ["Plate/Stock" :license_plate :license_plate]
   ["VIN" :vin :vin]
   ["Gallons" :gallons :gallons]
   ["Octane" :gas_type :gas_type]
   ["Top Tier?" :is_top_tier :is_top_tier]
   ["PPG" :gas_price #(cents->$dollars (:gas_price %))]
   ["Fee" :service_fee #(cents->$dollars (:service_fee %))]
   ["Total" :total_price #(cents->$dollars (:total_price %))]
   ["Date" :timestamp_created #(unix-epoch->hrf
                                (/ (.getTime (js/Date. (:timestamp_created %)))
                                   1000))]])

(defn account-names
  [orders]
  (keys (group-by :account_name orders))
  ;; ["Jim Falk Lexus"
  ;;  "AA Global"
  ;;  "Green Guard Services"
  ;;  "SKURT"
  ;;  "Pacific BMW"
  ;;  "Environmental Landscape Development"
  ;;  "Bemus"]
  )

(defn fleet-panel
  [orders state]
  (let [current-order (r/cursor state [:current-order])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        page-size 20
        selected-filter (r/atom (:account_name (first orders)))
        filters (into {}
                      (map (fn [x] [x #(= (:account_name %) x)])
                           (account-names orders)))
        
        ;; {(:account_name (first orders)) (constantly true)
        ;;  "Also Always True" (constantly true)}

        ]
    (fn [orders]
      (let [sort-fn (fn []
                      (if @sort-reversed?
                        (partial sort-by @sort-keyword)
                        (comp reverse (partial sort-by @sort-keyword))))

            
            displayed-orders ;; (filter #(<= (:target_time_start %)
            ;;              (:target_time_start
            ;;               @datastore/last-acknowledged-order))
            orders ;)

            
            sorted-orders  (fn []
                             (->> displayed-orders
                                  (filter (get filters @selected-filter))
                                  ((sort-fn))
                                  (partition-all page-size)))
            paginated-orders (fn []
                               (-> (sorted-orders)
                                   (nth (- @current-page 1)
                                        '())))
            table-pager-on-click (fn []
                                   (reset! current-order
                                           (first (paginated-orders))))
            table-filter-button-on-click (fn []
                                           (reset! sort-keyword
                                                   :timestamp_created)
                                           (reset! current-page 1)
                                           (table-pager-on-click))]
        (when (nil? @current-order)
          (table-pager-on-click))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"
                :style {:margin-top "15px"}}
          [:div {:class "btn-toolbar"
                 :role "toolbar"}
           [:div {:class "btn-group" :role "group"}
            (for [f filters]
              [TableFilterButton {:text (first f)
                                  :filter-fn (second f)
                                  :hide-count false
                                  :on-click (fn []
                                              (table-filter-button-on-click))
                                  :data orders
                                  :selected-filter selected-filter}])]]]
         [:div {:class "table-responsive"}
          [DynamicTable {:current-item current-order
                         :tr-props-fn
                         (fn [order current-order]
                           {:class (str (when (= (:id order)
                                                 (:id @current-order))
                                          "active"))
                            :on-click
                            (fn [_]
                              (reset! current-order order))})
                         :sort-keyword sort-keyword
                         :sort-reversed? sort-reversed?
                         :table-vecs
                         orders-table-vecs}
           (paginated-orders)]]
         [TablePager
          {:total-pages (count (sorted-orders))
           :current-page current-page
           :on-click table-pager-on-click}]]))))
