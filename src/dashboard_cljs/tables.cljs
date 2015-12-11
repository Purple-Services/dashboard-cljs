(ns dashboard-cljs.tables
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf continous-update]]
            [cljs.core.async :refer [chan pub put! sub <! >!]]
            [reagent.core :as r]))

(def couriers (r/atom #{}))

(def timeout-interval 1000)

;; the base url to use for server calls
(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(defn update-courier-server
  "Update courier on server and put the response on chan."
  [chan courier]
  (do
    (.log js/console "update-courier-server courier:" (clj->js courier))
    (retrieve-url
     (str base-url "couriers")
     "POST"
     (js/JSON.stringify (clj->js courier))
     (partial xhrio-wrapper
              #(let [response %]
                 (put! chan
                       {:topic (:id courier)
                        :data (js->clj response :keywordize-keys true)}
                       ))))))

(defn field-input-handler
  "Returns a handler that updates value in atom map,
  under key, with value from on-change event"
  [atom key]
  (fn [e]
    (swap! atom
           assoc key
           (-> e
               (aget "target")
               (aget "value")))))

(defn editable-input [atom key]
  (if (:editing? @atom)
    [:input {:type "text"
             :value (get @atom key)
             :on-change (field-input-handler atom key)}]
    [:p (get @atom key)]))

(defn get-server-couriers
  [state]
  (retrieve-url
   (str base-url "couriers")
   "GET"
   {}
   (partial xhrio-wrapper
            #(reset!
              state
              (set (mapv
                    (fn [courier]
                      (update-in courier [:zones]
                                 (fn [zone]
                                   (.join (clj->js (sort zone))))))
                    (vec (reverse
                          (sort-by :last_ping
                                   (js->clj (aget % "couriers")
                                            :keywordize-keys true))))))))))

(defn courier-row [courier]
  (let [row-state (r/atom {:editing? false
                           :zones (:zones courier)})]
    (fn [courier]
      (when (not (:editing? @row-state))
        (swap! row-state assoc :zones (:zones courier)))
      [:tr
       (if (:connected courier)
         [:td {:class "currently-connected connected"} "Yes"]
         [:td {:class "currently-not-connected connected"} "No"])
       [:td (:name courier)]
       [:td (:phone_number courier)]
       [:td (if (:busy courier) "Yes" "No")]
       [:td (unix-epoch->hrf (:last_ping courier))]
       [:td (:lateness courier)]
       [:td [editable-input row-state :zones]
        ]
       [:td [:button
             {:on-click
              (fn []
                (when (:editing? @row-state)
                  ;; do something to update the courier
                  )
                (swap! row-state update-in [:editing?] not))}
             (if (:editing? @row-state) "Save" "Edit")]]])))

(defn couriers-table-header
  []
  [:thead
   [:tr
    [:td "Connected"] [:td "Name"] [:td "Phone"] [:td "Busy?"]
    [:td "Last Seen"]
    [:td {:id "couriers-on-time-col-header"} "On Time %"]
    [:td "Zones"]
    [:td]
    ]])

(defn couriers-table
  []
  (fn []
    ;; crucial to use defonce here so that only ONE
    ;; call will be made!
    (defonce courier-updater
      (continous-update #(get-server-couriers couriers)
                        timeout-interval))
    ;; but let's still get the state setup
    (defonce init-couriers
      (get-server-couriers couriers))
    (.log js/console "couriers-table updated!")
    [:table {:id "couriers"}
     [couriers-table-header]
     [:tbody
      (map (fn [a]
             ^{:key (:id a)}
             [courier-row a])
           @couriers)]]))

(defn couriers-header
  []
  [:h2 {:class "couriers"} "Couriers "
                  [:a {:class "fake-link" :target "_blank"
                       :href (str base-url "dash-map-couriers")}
                   "[view couriers on map]"]])
(defn couriers-component
  []
  [:div {:id "couriers-component"}
   [couriers-header]
   [couriers-table]])


(defn init-tables []
  (let [ ]
    (r/render-component [couriers-component]
                        (.-body js/document))))
