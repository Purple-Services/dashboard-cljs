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

(defn remove-by-id
  "Remove the element with id from state. Assumes all elements have an
:id key with a unique value"
  [state id]
  (set (remove #(= id (:id %)) state)))

(defn update-element! [state el]
  "Update the el in state. el is assumed to have a :id key with a unique value"
  (swap! state
         (fn [old-state]
           (map #(if (= (:id %) (:id el))
                   el
                   %) old-state))))

(defn update-courier-server!
  "Update courier on server and update the row-state error message."
  [courier row-state]
  (do
    (.log js/console "update-courier-server courier:" (clj->js courier))
    (retrieve-url
     (str base-url "couriers")
     "POST"
     (js/JSON.stringify (clj->js courier))
     (partial xhrio-wrapper
              #(let [response %
                     clj-response (js->clj response :keywordize-keys true)
                     ]
                 ;; (put! chan
                 ;;       {:topic (:id courier)
                 ;;        :data (js->clj response :keywordize-keys true)}
                 ;;       )
                 (when (:success clj-response)
                   (reset! row-state (assoc @row-state
                                            :error? false
                                            :editing? false
                                            :saving? false
                                            :error-message ""))
                   ;; update the local state
                   (update-element! couriers courier))
                 (when (not (:success clj-response))
                   (reset! row-state (assoc @row-state
                                            :error? true
                                            :editing? true
                                            :saving? false
                                            :error-message
                                            (:message clj-response)))))))))


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
  (if (or (:editing? @atom)
          (:saving? @atom))
    [:div
     [:input {:type "text"
              :disabled (:saving? @atom)
              :value (get @atom key)
              :on-change (field-input-handler atom key)
              :class (when (:error? @atom)
                       "error")}]
     [:div {:class (when (:error? @atom)
                     "error")}
      (when (:error? @atom)
        (:error-message @atom))]]
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
                           :saving? false
                           :zones (:zones courier)
                           :error? false
                           :error-message ""
                           })]
    (fn [courier]
      (.log js/console "saving?: " (:saving? @row-state))
      (when (and
             (not (:editing? @row-state))
             (not (:saving? @row-state)))
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
       [:td [editable-input row-state :zones]]
       [:td [:button
             {:disabled (:saving? @row-state)
              :on-click
              (fn []
                (when (:editing? @row-state)
                  (let [new-courier (assoc courier
                                           :zones (:zones @row-state))]
                    (swap! row-state assoc :saving? true)
                    ;; update the courier on the server
                    (update-courier-server! new-courier row-state)))
                (swap! row-state update-in [:editing?] not))}
             (cond
               (:saving? @row-state)
               "Save"
               (:editing? @row-state)
               "Save"
               :else
               "Edit")]
        (if (:saving? @row-state)
          [:i {:class "fa fa-spinner fa-pulse"}])]])))

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
