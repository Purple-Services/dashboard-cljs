(ns dashboard-cljs.orders
  (:require [cljs.core.async :refer [put!]]
            [reagent.core :as r]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [unix-epoch->hrf base-url]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            ))

(defn StaticTable
  "props contains:
  {
  :table-header  ; reagenet component to render the table header with
  :table-row     ; reagent component to render a row
  }
  data is the reagent atom to display with this table."
  [props data]
  (fn [props data]
    (let [table-data data
          sort-fn   (if (nil? (:sort-fn props))
                      (partial sort-by :id)
                      (:sort-fn props))
          ]
      [:table {:class "table table-bordered table-hover table-striped"}
       (:table-header props)
       [:tbody
        (map (fn [element]
               ^{:key (:id element)}
               [(:table-row props) element])
             data)]])))

(defn TableHeadSortable
  "props is:
  {
  :keyword        ; keyword associated with this field to sort by
  :sort-keyword   ; reagent atom keyword
  :sort-reversed? ; is the sort being reversed?
  }
  text is the text used in field"  
  [props text]
  (fn [props text]
    [:th
     {:class "fake-link"
      :on-click #(do
                   (reset! (:sort-keyword props) (:keyword props))
                   (swap! (:sort-reversed? props) not))}
     text
     (when (= @(:sort-keyword props)
              (:keyword props))
       [:i {:class (str "fa fa-fw "
                        (if @(:sort-reversed? props)
                          "fa-angle-down"
                          "fa-angle-up"))}])]))

(defn EditButton
  "Button for toggling editing? button"
  [editing?]
  (fn [editing?]
    [:button {:type "button"
              :class (str "btn btn-sm btn-default pull-right")
              :on-click #(swap! editing? not)
              }
     (if @editing?
       "Save"
       "Edit")]))

(defn order-row
  "A table row for an order in a table. current-order is the one currently being
  viewed"
  [current-order]
  (fn [order]
    [:tr {:class (when (= (:id order)
                          (:id @current-order))
                   "active")
          :on-click #(reset! current-order order)}
     [:td (:status order)]
     [:td (:courier_name order)]
     [:td (unix-epoch->hrf
           (:target_time_start order))]
     [:td (- (:target_time_end order)
             (:target_time_start order))]
     [:td (:customer_name order)]
     [:td (:customer_phone_number order)]
     [:td "customer@purple.com"]
     [:td (:address_street order)]]))


(defn order-table-header
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
       (conj props {:keyword :status})
       "Status"]
      [TableHeadSortable
       (conj props {:keyword :courier_name})
       "Courier"
       ]
      [TableHeadSortable
       (conj props {:keyword :target_time_start})
       "Order Placed"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Delivery Time"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Username"] 
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Phone #"] 
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Email"]
      [TableHeadSortable
       (conj props {:keyword :address_street})
       "Street Address"]]]))

(defn order-courier-select
  "Select for assigning a courier
  props is:
  {
  :select-courier ; reagent atom, id of currently selected courier
  :couriers        ; set of courier maps
  }"
  [props]
  (fn [{:keys [selected-courier couriers]} props]
    [:select
     {:value (if @selected-courier
               @selected-courier
               (:id (first couriers)))
      :on-change
      #(do (reset! selected-courier
                   (-> %
                       (aget "target")
                       (aget "value"))))}
     (map
      (fn [courier]
        ^{:key (:id courier)}
        [:option
         {:value (:id courier)}
         (:name courier)])
      couriers)]))

(defn order-courier-comp
  "Component for the courier filed of an order panel
  props is:
  {
  :editing?         ; ratom, is the field currently being edited?
  :assigned-courier ; id of the courier who is currently assigned to the order
  :selected-courier ; ratom, id of the currently selected courier
  :couriers         ; set of courier maps
  :order            ; currently selected order
  }
  "
  [props]
  (fn [{:keys [editing? assigned-courier selected-courier couriers order]}
       props]
    (let [selected-courier (r/atom assigned-courier)]
      [:h5 [:span {:class "info-window-label"} "Courier: "]
       ;; courier assigned (if any)
       (when (not @editing?)
         [:span (str (:courier_name order) " ")])
       ;; assign courier button
       (when (not @editing?)
         [:button {:type "button"
                   :class "btn btn-xs btn-default"
                   :on-click #(reset! editing? true)}
          (if assigned-courier
            "Reassign Courier"
            "Assign Courier"
            )])
       ;; courier select
       (when @editing?
         [order-courier-select {:selected-courier
                                selected-courier
                                :couriers couriers}])
       ;; save assignment
       " "
       (when (and @editing?)
         [:button {:type "button"
                   :class "btn btn-xs btn-default"
                   :on-click
                   #(do
                      (reset! editing? false)
                      (retrieve-url
                       (str base-url "assign-order")
                       "POST"
                       (js/JSON.stringify (clj->js {:order_id (:id order)
                                                    :courier_id @selected-courier}))
                       (partial xhrio-wrapper
                                (fn [response]
                                  (when (:success (js->clj response :keywordize-keys true))
                                    (let [updated-order (assoc
                                                         order
                                                         :courier_id @selected-courier
                                                         :courier_name
                                                         (:name
                                                          (first (filter (fn [courier]
                                                                           (= @selected-courier
                                                                              (:id courier))) couriers)))
                                                         )]
                                      (put! datastore/modify-data-chan
                                            {:topic "orders"
                                             :data
                                             #{updated-order}}))
                                    )))
                       ))
                   }
          "Save assignment"
          ])
       
       ])))

(defn status-comp
  "Component for the status field of an order
  props is:
  {
  :editing? ; ratom, is the field currently being edited?
  :status   ; string, the status of the order
  }"
  [props]
  (let [status->next-status {"unassigned"  "assigned"
                             "assigned"    "accepted"
                             "accepted"    "enroute"
                             "enroute"     "servicing"
                             "servicing"   "complete"
                             "complete"    nil
                             "cancelled"   nil}]
    
    (fn [{:keys [editing? status]} props]
      [:h5 [:span {:class "info-window-label"} "Status: "]
       (str status " ")
       (when
           (and (not @editing?)
                (not (contains? #{"complete" "cancelled"}
                                status)))
         [:button {:type "button"
                   :class "btn btn-xs btn-default btn-danger"
                   :on-click #(.log js/console "cancel button hit")}
          "Cancel Order"])])))

(defn order-cancel
  "fill in when edited"
  [props]
  (fn [{:keys [editing? order]} props]
    (when editing?
      [:button {:type "button"
                :class "btn btn-sm btn-default btn-danger"
                :on-click #(.log js/console "cancel order")
                }])))

(defn order-panel
  "Display detailed and editable fields for an order"
  [current-order]
  (fn [current-order]
    (let [order @current-order
          editing-assignment? (r/atom false)
          editing-status?     (r/atom false)
          couriers
          ;; filter out the couriers to only those assigned
          ;; to the zone
          (sort-by :name (filter #(contains? (set (:zones %))
                                             (:zone order))
                                 @datastore/couriers))
          assigned-courier (:id (first (filter #(= (:courier_name order)
                                                   (:name % )) couriers)))
          selected-status (r/atom (:status order))
          ]
      [:div {:class "panel-body"}
       [:h3 "Order Details"]
       [:div 
        [:h5 [:span {:class "info-window-label"} "Username: "]
         (:customer_name order)]
        [:h5 [:span {:class "info-window-label"} "Phone: "]
         (:customer_phone_number order)]
        [:h5 [:span {:class "info-window-label"} "Email: "]
         "email@placeholder.com"]
        [:h5 [:span {:class "info-window-label"} "Address: "]
         (:address_street order)]
        [:h5 [:span {:class "info-window-label"} "Special Instructions: "]
         "Special instructions placeholder"]
        [order-courier-comp {:editing? editing-assignment?
                             :assigned-courier assigned-courier
                             :couriers couriers
                             :order order}]
        
        [status-comp {:editing? editing-status?
                      :status (:status order)
                      :selected-status selected-status}]]])))

(defn orders-panel
  "Display a table of selectable orders with an indivdual order panel
  for the selected order"
  [orders]
  (let [current-order (r/atom nil)
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        ]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))

            sorted-orders (sort-fn orders)]
        (when (nil? @current-order)
          (reset! current-order (first sorted-orders)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [order-panel current-order]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [order-table-header {:sort-keyword sort-keyword
                                               :sort-reversed? sort-reversed?}]
            :table-row (order-row current-order)}
           sorted-orders]]]))))
