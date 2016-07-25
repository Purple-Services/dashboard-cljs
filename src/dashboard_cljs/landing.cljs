(ns dashboard-cljs.landing
  (:require [reagent.core :as r]
            [clojure.string :as s]
            [clojure.set :refer [subset?]]
            [cljsjs.moment]
            [dashboard-cljs.utils :refer [base-url update-values
                                          unix-epoch->hrf accessible-routes
                                          new-orders-count
                                          same-timestamp?]]
            [dashboard-cljs.components :refer [Tab TabContent DownloadCSVLink
                                               LoadScreen]]
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
            [dashboard-cljs.googlemaps :refer [get-cached-gmaps on-click-tab]]
            [dashboard-cljs.state :refer [landing-state]]))

;;(def tab-content-toggle (r/atom {}))

(def tab-content-toggle (r/cursor landing-state [:tab-content-toggle]))

(def state landing-state)

(defn top-navbar-comp
  "A navbar for the top of the application
  props is:
  {:nav-bar-collapse ; r/atom boolean
  }"
  []
  (let [nav-bar-collapse (r/cursor state [:nav-bar-collapse])]
    (fn []
      [:div
       [:div {:class "navbar-header"}
        [:button {:type "button"
                  :class "navbar-toggle"
                  :on-click #(do (.preventDefault %)
                                 (swap! nav-bar-collapse not))
                  :data-toggle "collapse"
                  :data-target ".navbar-collapse"}
         [:span {:class "sr-only"} "Toggle Navigation"]
         [:span {:class "icon-bar"}]
         [:span {:class "icon-bar"}]
         [:span {:class "icon-bar"}]]
        [:a {:class "navbar-brand" :href "#"}
         [:img {:src (str base-url "/images/logo-white.png")
                :alt "PURPLE"
                :class "purple-logo"}]]]
       [:ul {:class "nav navbar-right top-nav hidden-xs hidden-sm"}
        [:li
         [:form {:class "navbar-form" :role "search"}
          [search/search-bar {:tab-content-toggle
                              tab-content-toggle}]]]
        [:li
         [:a {:href (str base-url "logout")} "Logout"]]]])))

(defn side-navbar-comp
  "Props contains:
  {
  :tab-content-toggle ; reagent atom, toggles the visibility of tab-content
  :toggle             ; reagent atom, toggles if tab is active
  :toggle-key         ; keyword, the keyword associated with tab in :toggle
  }
  "
  [props]
  (let [nav-bar-collapse (r/cursor state [:nav-bar-collapse])
        on-click-tab (fn []
                       (.scrollTo js/window 0 0)
                       (on-click-tab)
                       (reset! nav-bar-collapse true))]
    (fn []
      [:div {:class (str "collapse navbar-collapse sidebar-nav "
                         (when-not @nav-bar-collapse
                           "in"))}
       ;; navbar-ex1-collapse
       [:ul {:class "nav navbar-nav side-nav side-nav-color"}
        [:li {:class "hidden-lg hidden-md"}
         [:a {:href (str base-url "logout")} "Logout"]]
        [:li {:class "hidden-lg hidden-md"
              :style {:padding "15px"}}
         [search/search-bar {:tab-content-toggle
                             tab-content-toggle
                             :nav-bar-collapse
                             nav-bar-collapse}]]
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

;; based on https://github.com/IronSummitMedia/startbootstrap-sb-admin
(defn app
  []
  (let []
    (fn []
      (if-not (and @datastore/orders
                   (:processed (meta datastore/orders)))
        [LoadScreen]
        [:div {:id "wrapper"}
         [:nav {:class (str "navbar navbar-default navbar-fixed-top "
                            "navbar-inverse nav-bar-color")
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
            (when (subset? #{{:uri "/users"
                              :method "GET"
                              }} @accessible-routes)
              [:div
               [users/users-count-panel users/state]
               [users/search-bar users/state]
               [users/search-results users/state]
               [users/cross-link-result users/state]])]
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
             (when (subset? #{{:uri "/orders-since-date"
                               :method "POST"}}
                            @accessible-routes)
               [:div
                [orders/orders-panel @datastore/orders orders/state]])]]
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
                 [analytics/analytics-panel]
                 )]]]]
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
