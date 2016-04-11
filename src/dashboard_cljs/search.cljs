(ns dashboard-cljs.search
  (:require [clojure.string :as s]
            [reagent.core :as r]
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

(defn search-bar
  [props]
  (let [retrieving?        (r/cursor state [:search-retrieving?])
        search-results     (r/cursor state [:search-results])
        recent-search-term (r/cursor state [:recent-search-term])
        search-term        (r/cursor state [:search-term])
        retrieve-users (fn [search-term]
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
                               (reset! search-results ;;(:users response)
                                       response))))))]
    (fn [{:keys [tab-content-toggle]} props]
      [:form {:class "navbar-form" :role "search"}
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
                               (reset! retrieving? true)
                               (retrieve-users @search-term)
                               (reset! tab-content-toggle
                                       {:search-results-view true}))}
          [:i {:class "fa fa-search"}]]]]])))

(defn search-results
  "Display search results"
  [props]
  (fn [props]
    (let [search-term (r/cursor state [:search-term])
          retrieving? (r/cursor state [:search-retrieving?])
          recent-search-term (r/cursor state [:recent-search-term])
          search-results (r/cursor state [:search-results])
          users-search-results (r/cursor search-results [:users])
          orders-search-results (r/cursor search-results [:orders])
          ]
      [:div
       ;; users results
       [:div {:class "panel panel-default"}
        [:div {:class "panel-body"}
         [:div
          (when (and (empty? ;;@search-results
                      @users-search-results)
                     (not (s/blank? @recent-search-term))
                     (not @retrieving?))
            [:h5 "Your search - " [:strong @recent-search-term]
             " - did not match any users."])
          (when-not (empty? ;;@search-results
                     @users-search-results)
            [:div
             [:h5 "Users matching - " [:strong @recent-search-term]]
             [users/users-panel ;;@search-results
              @users-search-results
              state]])]]]
       ;; orders results
       [:div {:class "panel panel-default"}
        [:div {:class "panel-body"}
         [:div
          (when (and (empty? ;;@search-results
                      @orders-search-results)
                     (not (s/blank? @recent-search-term))
                     (not @retrieving?))
            [:h5 "Your search - " [:strong @recent-search-term]
             " - did not match any orders."])
          (when-not (empty? ;;@search-results
                     @orders-search-results)
            [:div
             [:h5 "Orders matching - " [:strong @recent-search-term]]
             [orders/orders-panel ;;@search-results
              @orders-search-results
              state]])]]]
       
       ])))
