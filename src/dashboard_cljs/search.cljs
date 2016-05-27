(ns dashboard-cljs.search
  (:require [clojure.string :as s]
            [cljs.core.async :refer [put!]]
            [reagent.core :as r]
            [dashboard-cljs.components :refer [StaticTable TablePager]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.orders :as orders]
            [dashboard-cljs.users :as users]
            [dashboard-cljs.utils :refer [base-url]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]))

(def default-user {:editing? false
                   :retrieving? false
                   :referral_comment ""
                   :errors nil})

(def status->next-status orders/status->next-status)

(def cancellation-reasons orders/cancellation-reasons)

(def state (r/atom {:current-user nil
                    :edit-user default-user
                    :current-order nil
                    :edit-order orders/default-order
                    :recent-search-term ""
                    :search-results #{}
                    :search-retrieving? false
                    :search-term ""
                    :view-log? false}))

(defn search-orders-results-panel
  "Display a table of selectable orders with an individual order panel for the
  select order."
  [order state]
  (let [current-order (r/cursor state [:current-order])
        edit-order    (r/cursor state [:edit-order])
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        page-size 20]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            search-results-orders (filter
                                   #(contains?
                                     (set (map
                                           :id (:orders
                                                (:search-results @state))))
                                     (:id %)) @dashboard-cljs.datastore/orders)
            sorted-orders (->> search-results-orders
                               sort-fn
                               (partition-all page-size))
            paginated-orders  (-> sorted-orders
                                  (nth (- @current-page 1)
                                       '()))]
        (when (nil? @current-order)
          (reset! current-order (first paginated-orders)))
        [:div {:class "panel panel-default"}
         [orders/order-panel {:order current-order
                              :state state
                              :gmap-keyword :search-orders}]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [orders/order-table-header
                           {:sort-keyword sort-keyword
                            :sort-reversed? sort-reversed?}]
            :table-row (orders/order-row current-order)}
           paginated-orders]]
         [TablePager
          {:total-pages (count sorted-orders)
           :current-page current-page}]]))))

(defn search-users-results-panel
  "Display a table of selectable users with an indivdual user panel
  for the selected user"
  [users state]
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
            sorted-users (->> users
                              sort-fn
                              (partition-all page-size))
            paginated-users (-> sorted-users
                                (nth (- @current-page 1)
                                     '()))]
        (when (nil? @current-user)
          (reset! current-user (first paginated-users)))
        (users/reset-edit-user! edit-user current-user)
        ;; set the edit-user values to match those of current-user
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [users/user-panel current-user state]]
         [:div {:class "panel"
                :style {:margin-top "15px"}}
          [:div {:class "table-responsive"}
           [StaticTable
            {:table-header [users/user-table-header
                            {:sort-keyword sort-keyword
                             :sort-reversed? sort-reversed?}]
             :table-row (users/user-row current-user)}
            paginated-users]]]
         [TablePager
          {:total-pages (count sorted-users)
           :current-page current-page}]]))))



(defn search-bar
  [props]
  (let [retrieving?        (r/cursor state [:search-retrieving?])
        search-results     (r/cursor state [:search-results])
        recent-search-term (r/cursor state [:recent-search-term])
        search-term        (r/cursor state [:search-term])
        retrieve-results (fn [search-term]
                           (retrieve-url
                            (str base-url "search")
                            "POST"
                            (js/JSON.stringify (clj->js {:term search-term}))
                            (partial
                             xhrio-wrapper
                             (fn [r]
                               (let [response (js->clj
                                               r :keywordize-keys true)]
                                 (reset! retrieving? false)
                                 (reset! recent-search-term search-term)
                                 (reset! search-results response)
                                 (put! datastore/modify-data-chan
                                       {:topic "orders"
                                        :data (:orders response)}))))))]
    (fn [{:keys [tab-content-toggle nav-bar-collapse]} props]
      [:div {:class "input-group"}
       [:input {:type "text"
                :class "form-control"
                :placeholder "Search"
                :on-change (fn [e]
                             (reset! search-term
                                     (-> e
                                         (aget "target")
                                         (aget "value"))))}]
       [:div {:class "input-group-btn"}
        [:button {:class "btn btn-default"
                  :type "submit"
                  :on-click (fn [e]
                              (.preventDefault e)
                              (when-not (s/blank? @search-term)
                                (reset! retrieving? true)
                                (retrieve-results @search-term)
                                (reset! tab-content-toggle
                                        {:search-results-view true})
                                (when nav-bar-collapse
                                  (swap! nav-bar-collapse not))))}
         [:i {:class "fa fa-search"}]]]])))

(defn search-results
  "Display search results"
  [props]
  (fn [props]
    (let [search-term (r/cursor state [:search-term])
          retrieving? (r/cursor state [:search-retrieving?])
          recent-search-term (r/cursor state [:recent-search-term])
          search-results (r/cursor state [:search-results])
          users-search-results (r/cursor search-results [:users])
          orders-search-results (r/cursor search-results [:orders])]
      [:div
       (when @retrieving?
         (.scrollTo js/window 0 0)
         [:h4 "Retrieving results for \""
          [:strong
           {:style {:white-space "pre"}}
           @search-term]
          "\" "
          [:i {:class "fa fa-spinner fa-pulse"
               :style {:color "black"}}]])
       (when-not @retrieving?
         [:div
          ;; orders results
          [:div {:class "panel panel-default"}
           [:div {:class "panel-body"}
            [:div
             (when (and (empty? @orders-search-results)
                        (not (s/blank? @recent-search-term))
                        (not @retrieving?))
               [:h4 "Your search - \""
                [:strong {:style {:white-space "pre"}}
                 @recent-search-term]
                "\" - did not match any orders."])
             (when-not (empty? @orders-search-results)
               [:div
                [:h4 "Orders matching - \""
                 [:strong {:style {:white-space "pre"}}
                  @recent-search-term] "\""]
                [search-orders-results-panel @dashboard-cljs.datastore/orders
                 state]])]]]
          ;; users results
          [:div {:class "panel panel-default"}
           [:div {:class "panel-body"}
            [:div
             (when (and (empty? @users-search-results)
                        (not (s/blank? @recent-search-term))
                        (not @retrieving?))
               [:h4 "Your search - \""
                [:strong {:style {:white-space "pre"}}
                 @recent-search-term]
                \"" - did not match any users."])
             (when-not (empty? @users-search-results)
               [:div
                [:h4 "Users matching - \""
                 [:strong {:style {:white-space "pre"}}
                  @recent-search-term] "\""]
                [search-users-results-panel @users-search-results state]])]]]])])))
