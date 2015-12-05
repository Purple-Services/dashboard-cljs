(ns dashboard-cljs.tables
  (:require [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf continous-update]]
            ))

(def state (atom {:couriers (array)
                  :timeout-interval 1000}))

;; the base url to use for server calls
(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))


(defn courier-view [courier owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (html/html [:tr 
                  (if (:connected courier)
                    [:td {:class "currently-connected connected"} "Yes"]
                    [:td {:class "currently-not-connected connected"} "No"])
                  [:td (:name courier)]
                  [:td (:phone_number courier)]
                  [:td (if (:busy courier) "Yes" "No")]
                  [:td (unix-epoch->hrf (:last_ping courier))]
                  [:td (:lateness courier)]
                  [:td (.join (clj->js (:zones courier)))]]))))

(defn update-couriers-state
  [state]
  (retrieve-url
   (str base-url "couriers")
   "POST"
   {}
   (partial xhrio-wrapper
            #(om/update!
              state [:couriers]
              (reverse
               (sort-by :last_ping (js->clj (aget % "couriers")
                                            :keywordize-keys true)))))))

(defn couriers-table [state owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (update-couriers-state state))
    om/IWillUpdate
    (will-update [_ next-props next-state]
      (continous-update #(update-couriers-state state)
                        (:timeout-interval @state)))
    om/IRender
    (render [this]
      (html/html [:table {:id "couriers"}
                  [:thead
                   [:tr
                    [:td "Connected"] [:td "Name"] [:td "Phone"] [:td "Busy?"]
                    [:td "Last Seen"]
                    [:td {:id "couriers-on-time-col-header"} "On Time %"]
                    [:td "Zones"]]]
                  [:tbody 
                   (om/build-all courier-view (:couriers state)
                                 {:init-state state
                                  :key :id})]]))))
(defn couriers-table-header
  []
  (reify
    om/IRenderState
    (render-state [this state]
      (html/html [:h2 {:class "couriers"} "Couriers "
                  [:input {:type "submit" :value "Edit"}]
                  [:a {:class "fake-link" :target "_blank"
                       :href (str base-url "dash-map-couriers")}
                   "[view couriers on map]"]]))))

(defn couriers-component
  [state owner]
  (reify
    om/IRender
    (render [_]
      (html/html
       [:div {:id "couriers-component"}
        (om/build couriers-table-header state)
        (om/build couriers-table state)]))))

(defn root-component
  [state owner]
  (reify
    om/IRender
    (render [_]
      (om/build couriers-component state))))

(defn init-tables []
  (om/root root-component state
           {:target (.getElementById js/document "app")}))
