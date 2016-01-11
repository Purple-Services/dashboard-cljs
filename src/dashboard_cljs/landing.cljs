(ns dashboard-cljs.landing
  (:require [reagent.core :as r]
            [clojure.string :as s]
            [cljsjs.moment]
            [dashboard-cljs.utils :refer [base-url update-values]]
            [dashboard-cljs.components :refer [count-panel]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.orders :as orders]
            [dashboard-cljs.utils :refer [unix-epoch->hrf]]
            ))

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
      [:a {:class "navbar-brand" :href "index.html"}
       [:img {:src "http://purpledelivery.com/images/purple_logoWh.png"
              :alt "PURPLE"
              :class "purple-logo"}]]]
     [:ul {:class "nav navbar-right top-nav"}
      [:li {:class "dropdown"}
       [:a {:href (str base-url "logout")} "Logout"]]]
     ]))

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
  (fn []
    [:li [:a {:on-click
              #(do
                 (.preventDefault %)
                 (swap! (:toggle props) update-values (fn [el] false))
                 (swap! (:toggle props) assoc (:toggle-key props) true)
                 (reset! (:side-bar-toggle props) false))
              :href "#"
              :class
              (str (when ((:toggle-key props) @(:toggle props)) "active"))
              } child]]))

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
      [Tab {:toggle-key :dashboard-view
            :toggle (:tab-content-toggle props)
            :side-bar-toggle (:side-bar-toggle props)}
       [:div [:i {:class "fa fa-home fa-fw"}] "Home"]]
      [Tab {:toggle-key :users-view
            :toggle (:tab-content-toggle props)
            :side-bar-toggle (:side-bar-toggle props)}
       [:div [:i {:class "fa fa-fw fa-users"}] "Users"]]
      [Tab {:default? true
            :toggle-key :orders-view
            :toggle (:tab-content-toggle props)
            :side-bar-toggle (:side-bar-toggle props)}
       [:div [:i {:class "fa fa-fw fa-shopping-cart"}] "Orders"]]]]))

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


;; based on https://github.com/IronSummitMedia/startbootstrap-sb-admin
(defn app
  []
  (let [tab-content-toggle (r/atom {:dashboard-view false
                                    :users-view false
                                    :orders-view false})
        side-bar-toggle (r/atom false)]
    (fn []
      [:div {:id "wrapper"}
       [:nav {:class "navbar navbar-inverse navbar-fixed-top nav-bar-color"
              :role "navigation"}
        [top-navbar-comp {:side-bar-toggle side-bar-toggle}]
        [side-navbar-comp {:tab-content-toggle tab-content-toggle
                           :side-bar-toggle side-bar-toggle}]]
       [:div {:id "page-wrapper"
              :class "page-wrapper-color"}
        [:div {:class "container-fluid tab-content"}
         [TabContent
          {:toggle (r/cursor tab-content-toggle [:dashboard-view])}
          [:div
           [:div {:class "row"}
            [:div {:class "col-lg-3 col-md-6"}
             ;; todays order count panel
             (let [new-orders (fn [orders]
                                (let [today-begin (-> (js/moment)
                                                      (.startOf "day")
                                                      (.unix))
                                      complete-time (fn [order]
                                                      (-> (str
                                                           "kludgeFix 1|"
                                                           (:event_log order))
                                                          (s/split #"\||\s")
                                                          (->> (apply hash-map))
                                                          (get "complete")))]
                                  (->> orders
                                       (filter #(= (:status %) "complete"))
                                       (map
                                        #(assoc % :time-completed
                                                (complete-time %)))
                                       (filter #(>= (:time-completed %)
                                                    today-begin)))))]
               [count-panel {:data (new-orders @datastore/orders)
                             :description "completed orders today!"
                             :panel-class "panel-primary"
                             :icon-class  "fa-shopping-cart"
                             }])]]]]
         [TabContent
          {:toggle (r/cursor tab-content-toggle [:users-view])}
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:h2 "Users tables go here"]
            ]]]
         [TabContent
          {:toggle (r/cursor tab-content-toggle [:orders-view])}
          [:div
           [:div {:class "row"}
            [:div {:class "col-lg-12"}
             [orders/orders-panel @datastore/orders]
             ]]]
          ]
         ]]]
      )))

(defn init-landing
  []
  (r/render-component [app] (.getElementById js/document "app")))

