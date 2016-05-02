(ns dashboard-cljs.landing
  (:require [reagent.core :as r]
            [clojure.string :as s]
            [clojure.set :refer [subset?]]
            [cljsjs.moment]
            [dashboard-cljs.utils :refer [base-url update-values
                                          unix-epoch->hrf accessible-routes
                                          new-orders-count
                                          same-timestamp?]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.home :as home]
            [dashboard-cljs.couriers :as couriers]
            [dashboard-cljs.users :as users]
            [dashboard-cljs.coupons :as coupons]
            [dashboard-cljs.search :as search]
            [dashboard-cljs.zones :as zones]
            [dashboard-cljs.orders :as orders]
            [dashboard-cljs.analytics :as analytics]
            [dashboard-cljs.googlemaps :refer [get-cached-gmaps]]))

(def tab-content-toggle (r/atom {}))

(defn top-navbar-comp
  "Props contains:
  {
  :side-bar-toggle ; reagent/atom boolean
  }"
  [props]
  (fn []
    [:div
     [:div {:class "navbar-header"}
      [:button {:type "button"
                :class "navbar-toggle"
                :on-click #(do (.preventDefault %)
                               (swap! (:side-bar-toggle props) not))
                }
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

(defn Tab
  "Tab component inserts child into its anchor element. props is a map of the
  following form:
  {
  :toggle (reagent/atom map) ; required
  :toggle-key keyword        ; required
  :side-bar-toggle boolean   ; required, used to show/hide sidebar
  :default? boolean          ; optional
  }

  The anchor elements action when clicked is to set the val associated with
  :toggle-key to true, while setting all other vals of :toggle to false. It will
  also mark the current anchor as active."
  [props child]
  (when (:default? props)
    (swap! (:toggle props) assoc (:toggle-key props) true))
  (fn [props child]
    [:li [:a {:on-click
              #(do
                 (.scrollTo js/window 0 0)
                 (.preventDefault %)
                 (swap! (:toggle props) update-values (fn [el] false))
                 (swap! (:toggle props) assoc (:toggle-key props) true)
                 (reset! (:side-bar-toggle props) false)
                 ;; reset the google map
                 ;; there is a problem with google maps rendering when divs are
                 ;; not visible on page
                 ;; see: http://stackoverflow.com/questions/10197128/google-maps-api-v3-not-rendering-competely-on-tabbed-page-using-twitters-bootst?lq=1
                 ;; http://stackoverflow.com/questions/1428178/problems-with-google-maps-api-v3-jquery-ui-tabs?rq=1
                 ;; a call to a function is too fast, must be delayed slightly
                 (js/setTimeout (fn []
                                  ;; when google maps does a resize, it
                                  ;; changes its center ever so-slightly
                                  ;; therefore, the current center is cached
                                  ;; see: http://stackoverflow.com/questions/8558226/recenter-a-google-map-after-container-changed-width
                                  (let [gmap (second (get-cached-gmaps :orders))
                                        center (.getCenter gmap)]
                                    (js/google.maps.event.trigger gmap
                                                                  "resize")
                                    (.setCenter gmap center)))
                                300)
                 (js/setTimeout (fn []
                                  (let [gmap (second (get-cached-gmaps :couriers))
                                        center (.getCenter gmap)]
                                    (js/google.maps.event.trigger gmap
                                                                  "resize")
                                    (.setCenter gmap center)))
                                300))
              :href "#"
              :class
              (str (when ((:toggle-key props) @(:toggle props)) "active"))}
          child]]))


(defn TabContent
  "TabContent component, presumably controlled by a Tab component.
  props is:
  {
  :toggle ; reagent atom, boolean
  }
  val in props is a reagent atom. When the val of :toggle is true, the content
  is active and thus viewable. Otherwise, when the val of :toggle is false, the
  content is not displayed."
  [props content]
  (fn [props content]
    [:div {:class (str "tab-pane "
                       (when @(:toggle props) "active"))}
     content]))


(defn side-navbar-comp
  "Props contains:
  {
  :tab-content-toggle ; reagent atom, toggles the visibility of tab-content
  :side-bar-toggle    ; reagent atom, toggles visibility of sidebar
  :toggle             ; reagent atom, toggles if tab is active
  :toggle-key         ; keyword, the keyword associated with tab in :toggle
  }
  "
  [props]
  (fn []
    [:div {:class (str "collapse navbar-collapse navbar-ex1-collapse "
                       (when @(:side-bar-toggle props) "in"))}
     [:ul {:class "nav navbar-nav side-nav side-nav-color"}
      (when (subset? #{{:uri "/orders-since-date"
                        :method "POST"}}
                     @accessible-routes)
        [Tab {:default? true
              :toggle-key :dashboard-view
              :toggle (:tab-content-toggle props)
              :side-bar-toggle (:side-bar-toggle props)}
         [:div "Home"]])
      (when (subset? #{{:uri "/orders-since-date"
                        :method "POST"}}
                     @accessible-routes)
        [Tab {:default? false
              :toggle-key :orders-view
              :toggle (:tab-content-toggle props)
              :side-bar-toggle (:side-bar-toggle props)}
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
              :side-bar-toggle (:side-bar-toggle props)}
         [:div "Users"]])
      (when (subset? #{{:uri "/couriers"
                        :method "POST"}}
                     @accessible-routes)
        [Tab {:default? false
              :toggle-key :couriers-view
              :toggle (:tab-content-toggle props)
              :side-bar-toggle (:side-bar-toggle props)}
         [:div
          "Couriers"]])
      (when (subset? #{{:uri "/coupons"
                        :method "GET"}}
                     @accessible-routes)
        [Tab {:default? false
              :toggle-key :coupons-view
              :toggle (:tab-content-toggle props)
              :side-bar-toggle (:side-bar-toggle props)}
         [:div "Coupons"]])
      (when (subset? #{{:uri "/zones"
                        :method "GET"}}
                     @accessible-routes)
        [Tab {:default? false
              :toggle-key :zones-view
              :toggle (:tab-content-toggle props)
              :side-bar-toggle (:side-bar-toggle props)}
         [:div "Zones"]])
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
              :side-bar-toggle (:side-bar-toggle props)}
         [:div "Analytics"]])
      (when (subset? #{{:uri "/dash-map-couriers"
                        :method "GET"}}
                     @accessible-routes)
        [:li ;;{:class "hidden-xs"}
         [:a {:href (str base-url "dash-map-couriers")
              :target "_blank"}
          "Real-time Map"]])]]))

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
  (let [side-bar-toggle (r/atom false)]
    (fn []
      (if-not (and @datastore/orders
                   (:processed (meta datastore/orders)))
        [LoadScreen]
        [:div {:id "wrapper"}
         [:nav {:class "navbar navbar-inverse navbar-fixed-top nav-bar-color"
                :role "navigation"}
          [top-navbar-comp {:side-bar-toggle side-bar-toggle}]
          [side-navbar-comp {:tab-content-toggle tab-content-toggle
                             :side-bar-toggle side-bar-toggle}]]
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
             [:div {:class "col-lg-12"}
              (when (subset? #{{:uri "/couriers"
                                :method "POST"}}
                             @accessible-routes)
                [couriers/couriers-panel @datastore/couriers])]]]
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
                  [analytics/orders-by-hour]])]]]]
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
