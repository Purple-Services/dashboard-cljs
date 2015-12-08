(ns dashboard-cljs.tables
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]
            [sablono.core :as html :refer-macros [html]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf continous-update]]
            [cljs.core.async :refer [chan pub put! sub <! >!]]
            ))

(def state (atom {:couriers (array)
                  :timeout-interval 1000}))

;; the base url to use for server calls
(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(defn save-courier [courier owner]
  (reify
    om/IRender
    (render [this]
      (html/html [:input {:id "save-courier"
                          :type "submit"
                          :value "Save Changes"
                          :on-click
                          #(do
                             (.preventDefault %)
                             (om/update! courier :editing? false)
                             (put! (:pub-chan (om/get-shared owner))
                                   {:topic (:id courier) :data %})
                             nil)}]))))

(defn edit-courier [courier owner]
  (reify
    om/IRender
    (render [this]
      ;; (html/html  [:a {:id "edit-courier" :class "btn btn-default edit-icon"
      ;;                 :href "#"
      ;;                 :on-click
      ;;                 #(;;do
      ;;                   ;;(.preventDefault %)
      ;;                    ;;(om/update! courier :editing? true)
      ;;                    ;;(.log js/console courier)
      ;;                    ;;(.log js/console owner)
      ;;                    ;;(om/update! owner [:editing?] true)
      ;;                   (.log js/console (om/cursor? courier))
      ;;                   ;; (put! (:pub-chan (om/get-shared owner))
      ;;                   ;;       {:topic (:id courier) :data %})
      ;;                   ;;nil
      ;;                   )}
      ;;             [:i {:class "fa fa-pencil"}]])
      (dom/a ;; {:class "btn btn-default edit-icon"
               ;;  :id "courier"
               ;;  ;; :href "#"
               ;;  ;; :on-click (fn [_]
               ;;  ;;             (om/update! courier :editing? true))
       ;;  }
       #js {:id "edit-courier"
            :className "btn btn-default edit-icon"
            :onClick #(.log js/console (om/cursor? courier))
            :href "#"
            }
       (dom/i #js {:className "fa fa-pencil"})))))


(defn courier-view [courier owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [events (sub (:notif-chan (om/get-shared owner)) (:id courier) (chan))]
        (go-loop [e (<! events)]
          (.log js/console (:data e))
          (recur (<! events)))))
    om/IRenderState
    (render-state [this state]
      ;; (html/html [:tr
      ;;             [:td {:class "opacity-hover"}
      ;;              (if (:editing? courier)
      ;;                (om/build save-courier courier)
      ;;                (om/build edit-courier courier))]
      ;;             (if (:connected courier)
      ;;               [:td {:class "currently-connected connected"} "Yes"]
      ;;               [:td {:class "currently-not-connected connected"} "No"])
      ;;             [:td (:name courier)]
      ;;             [:td (:phone_number courier)]
      ;;             [:td (if (:busy courier) "Yes" "No")]
      ;;             [:td (unix-epoch->hrf (:last_ping courier))]
      ;;             [:td (:lateness courier)]
      ;;             [:td (.join (clj->js (:zones courier)))]])
      (dom/tr nil
              (dom/td nil (if (om/cursor? courier)
                            "true"
                            "false"))
              (dom/td nil
                      (om/build edit-courier courier))
              ))))

(defn update-couriers-state
  [state]
  (retrieve-url
   (str base-url "couriers")
   "POST"
   {}
   (partial xhrio-wrapper
            #(om/update!
              state [:couriers]
              (mapv (fn [courier] (assoc courier :editing? false)) (reverse
                    (sort-by :last_ping (js->clj (aget % "couriers")
                                                 :keywordize-keys true))))))))

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
      ;; (html/html [:table {:id "couriers"}
      ;;             [:thead
      ;;              [:tr
      ;;               [:td]
      ;;               [:td "Connected"] [:td "Name"] [:td "Phone"] [:td "Busy?"]
      ;;               [:td "Last Seen"]
      ;;               [:td {:id "couriers-on-time-col-header"} "On Time %"]
      ;;               [:td "Zones"]]]
      ;;             [:tbody
      ;;              (om/build-all courier-view (:couriers state)
      ;;                            {:init-state state
      ;;                             :key :id})]])
      (dom/table
       #js {:id "couriers"}
       (dom/thead nil
                  (dom/tr nil
                          (dom/td nil (do
                                        (.log js/console @state)
                                        (if (om/cursor? state)
                                            "true"
                                            "false")))))
       (apply dom/tbody nil
              (om/build-all courier-view (get state :couriers)
                            {:init-state state
                             :state state
                             :key :id})
              ;; (map #(om/build courier-view % {:key :id})
              ;;      state)
              )))))

(defn couriers-table-header
  []
  (reify
    om/IRenderState
    (render-state [this state]
      (html/html [:h2 {:class "couriers"} "Couriers "
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
  (let [req-chan (chan)
        pub-chan (chan)
        notif-chan (pub pub-chan :topic)]
    (om/root root-component state
             {:shared {:notif-chan notif-chan
                       :pub-chan pub-chan}
              :target (.getElementById js/document "app")})))
