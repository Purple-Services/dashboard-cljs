(ns dashboard-cljs.landing
  (:require [reagent.core :as r]
            [clojure.string :as s]
            [clojure.set :refer [subset?]]
            [cljsjs.moment]
            [dashboard-cljs.utils :refer [base-url update-values
                                          unix-epoch->hrf accessible-routes
                                          new-orders-count
                                          same-timestamp?]]
            [dashboard-cljs.components :refer [Tab TabContent DownloadCSVLink]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.home :as home]
            [dashboard-cljs.couriers :as couriers]
            [dashboard-cljs.users :as users]
            [dashboard-cljs.coupons :as coupons]
            [dashboard-cljs.search :as search]
            [dashboard-cljs.zones :as zones]
            [dashboard-cljs.marketing :as marketing]
            [dashboard-cljs.orders :as orders]
            [dashboard-cljs.analytics :as analytics]
            [dashboard-cljs.googlemaps :refer [get-cached-gmaps on-click-tab]]))

(def tab-content-toggle (r/atom {}))

(defn top-navbar-comp
  "A navbar for the top of the application"
  []
  (fn []
    [:div
     [:div {:class "navbar-header"}
      [:button {:type "button"
                :class "navbar-toggle"
                :on-click #(do (.preventDefault %))}
       [:span {:class "sr-only"} "Toggle Navigation"]
       [:span {:class "icon-bar"}]
       [:span {:class "icon-bar"}]
       [:span {:class "icon-bar"}]]
      [:a {:class "navbar-brand" :href "#"}
       [:img {:src (str base-url "/images/logo-white.png")
              :alt "PURPLE"
              :class "purple-logo"}]]]
     [:ul {:class "nav navbar-right top-nav"}
      [:li {:class "dropdown"}
       [search/search-bar {:tab-content-toggle
                           tab-content-toggle}]]
      [:li
       [:a {:href (str base-url "logout")} "Logout"]]]]))

(defn side-navbar-comp
  "Props contains:
  {
  :tab-content-toggle ; reagent atom, toggles the visibility of tab-content
  :toggle             ; reagent atom, toggles if tab is active
  :toggle-key         ; keyword, the keyword associated with tab in :toggle
  }
  "
  [props]
  (let [on-click-tab (fn []
                       (.scrollTo js/window 0 0)
                       (on-click-tab))]
    (fn []
      [:div {:class (str "collapse navbar-collapse navbar-ex1-collapse ")}
       [:ul {:class "nav navbar-nav side-nav side-nav-color"}
        (when (subset? #{{:uri "/orders-since-date"
                          :method "POST"}}
                       @accessible-routes)
          [Tab {:default? true
                :toggle-key :dashboard-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div "Home"]])
        (when (subset? #{{:uri "/orders-since-date"
                          :method "POST"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :orders-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           ;; this correlates with orders/new-orders-button
           (if (and (not (empty?
                          (and @datastore/most-recent-order
                               @datastore/last-acknowledged-order)))
                    (not (same-timestamp? @datastore/most-recent-order
                                          @datastore/last-acknowledged-order)))
             (let [num-new (new-orders-count @datastore/orders
                                             @datastore/last-acknowledged-order)]
               [:div "Orders "
                [:span {:style {:color "red"}}
                 "(" num-new ")"]])
             (let [_ (set! js/document.title (str "Dashboard - Purple"))]
               [:div "Orders"]))])
        (when (subset? #{{:uri "/users"
                          :method "GET"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :users-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div "Users"]])
        (when (subset? #{{:uri "/couriers"
                          :method "POST"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :couriers-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div
            "Couriers"]])
        (when (subset? #{{:uri "/coupons"
                          :method "GET"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :coupons-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div "Coupons"]])
        (when (subset? #{{:uri "/zones"
                          :method "GET"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :zones-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div "Zones"]])
        (when (subset? #{{:uri "/send-push-to-table-view"
                          :method "POST"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :marketing-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div "Marketing"]])
        (when (subset? #{{:uri "/generate-stats-csv"
                          :method "GET"}
                         {:uri "/download-stats-csv"
                          :method "GET"}
                         {:uri "/status-stats-csv"
                          :method "GET"}}
                       @accessible-routes)
          [Tab {:default? false
                :toggle-key :analytics-view
                :toggle (:tab-content-toggle props)
                :on-click-tab on-click-tab}
           [:div "Analytics"]])
        (when (subset? #{{:uri "/dash-map-couriers"
                          :method "GET"}}
                       @accessible-routes)
          [:li ;;{:class "hidden-xs"}
           [:a {:href (str base-url "dash-map-couriers")
                :target "_blank"}
            "Real-time Map"]])]])))

(defn LoadScreen
  []
  (fn []
    [:div {:style {:width "100%"
                   :height "100%"
                   :color "white"
                   :z-index "999"
                   :position "fixed"}}
     [:div {:style {:left "40%"
                    :top "40%"
                    :height "2em"
                    :position "fixed"}}
      [:h2 {:style {:display "inline-block"
                    :color "black"}}
       "Loading   " [:i {:class "fa fa-spinner fa-pulse"
                         :style {:color "black"}}]]]]))

;; based on https://github.com/IronSummitMedia/startbootstrap-sb-admin
(defn app
  []
  (let []
    (fn []
      (if-not (and @datastore/orders
                   (:processed (meta datastore/orders)))
        [LoadScreen]
        [:div {:id "wrapper"}
         [:nav {:class "navbar navbar-inverse navbar-fixed-top nav-bar-color"
                :role "navigation"}
          [top-navbar-comp {}]
          [side-navbar-comp {:tab-content-toggle tab-content-toggle}]]
         [:div {:id "page-wrapper"
                :class "page-wrapper-color"}
          [:div {:class "container-fluid tab-content"}
           ;; home page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:dashboard-view])}
            [:div
             [:div {:class "row"}
              [:div {:class "col-lg-12"}
               (when (subset? #{{:uri "/orders-since-date"
                                 :method "POST"}}
                              @accessible-routes)
                 [:div
                  ;;[home/cancelled-orders-panel]
                  [home/orders-count-panel]
                  ;;[home/percent-cancelled-panel]
                  [home/current-orders-panel @datastore/orders]])]]]]
           ;; couriers page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:couriers-view])}
            [:div {:class "row"}
             ;;[:div {:class "col-lg-12"}]
             (when (subset? #{{:uri "/couriers"
                               :method "POST"}}
                            @accessible-routes)
               [couriers/couriers-panel @datastore/couriers])]]
           ;; users page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:users-view])}
            [:div {:class "row"}
             [:div {:class "col-lg-12"}
              (when (subset? #{{:uri "/users"
                                :method "GET"
                                }} @accessible-routes)
                [:div
                 [users/users-panel @datastore/users users/state]
                 (when (subset?
                        #{{:uri "/send-push-to-all-active-users"
                           :method "POST"}
                          {:uri "/send-push-to-users-list"
                           :method "POST"}}
                        @accessible-routes)
                   [users/user-push-notification])])]]]
           ;; coupon code page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:coupons-view])}
            [:div {:class "row"}
             [:div {:class "col-lg-12"}
              (when (subset? #{{:uri "/coupons"
                                :method "GET"}}
                             @accessible-routes)
                [:div
                 [coupons/coupons-panel @datastore/coupons]
                 (when (subset? #{{:uri "/coupon"
                                   :method "POST"}}
                                @accessible-routes)
                   [coupons/create-coupon-comp])])]]]
           ;; zones page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:zones-view])}
            [:div {:class "row"}
             [:div {:class "col-lg-12"}
              (when (subset? #{{:uri "/zones"
                                :method "GET"}}
                             @accessible-routes)
                [:div
                 [zones/zones-panel @datastore/zones]])]]]
           ;; orders page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:orders-view])}
            [:div
             [:div {:class "row"}
              [:div {:class "col-lg-12"}
               (when (subset? #{{:uri "/orders-since-date"
                                 :method "POST"}}
                              @accessible-routes)
                 [:div
                  [orders/orders-panel @datastore/orders orders/state]])]]]]
           ;; marketing page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:marketing-view])}
            [:div {:class "row"}
             [:div {:class "col-lg-12"}
              (when (subset? #{{:uri "/send-push-to-table-view"
                                :method "POST"}}
                             @accessible-routes)
                [:div
                 [marketing/push-to-table-view-panel]])]]]
           ;; analytics page
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:analytics-view])}
            [:div
             [:div {:class "row"}
              [:div {:class "col-lg-12"}
               (when (subset? #{{:uri "/generate-stats-csv"
                                 :method "GET"}
                                {:uri "/download-stats-csv"
                                 :method "GET"}
                                {:uri "/status-stats-csv"
                                 :method "GET"}}
                              @accessible-routes)
                 [:div
                  [analytics/stats-panel]
                  ;;[analytics/orders-by-hour]
                  [analytics/total-orders-per-day-chart]
                  [analytics/DownloadCSV
                   (r/cursor analytics/state [:daily-total-orders])
                   "daily" analytics/retrieve-total-orders-per-timeframe]
                  [:h2 "Completed Orders Per Courier"]
                  [analytics/DownloadCSV
                   (r/cursor analytics/state [:weekly-order-per-courier])
                   "weekly" analytics/retrieve-orders-per-courier]
                  [analytics/DownloadCSV
                   (r/cursor analytics/state [:daily-order-per-courier])
                   "daily" analytics/retrieve-orders-per-courier]
                  [analytics/DownloadCSV
                   (r/cursor analytics/state [:hourly-order-per-courier])
                   "hourly" analytics/retrieve-orders-per-courier]])]]]]
           ;; Search Resuls
           [TabContent
            {:toggle (r/cursor tab-content-toggle [:search-results-view])}
            [:div
             [:div {:class "row"}
              [:div {:class "col-lg-12"}
               [search/search-results]]]]]]]]))))

(defn init-landing
  []
  (r/render-component [app] (.getElementById js/document "app")))
