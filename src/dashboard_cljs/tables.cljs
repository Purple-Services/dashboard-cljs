(ns dashboard-cljs.tables
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]
            [sablono.core :as html :refer-macros [html]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [unix-epoch->hrf continous-update]]
            [cljs.core.async :refer [chan pub put! sub <! >!]]
            ))

;; (def editable-fields {:editable-fields {:zones {:editing? true} :name {:editing? false}}})
;; (some identity (mapcat vals (vals (:editable-fields editable-fields))))
(def state (atom {:couriers (array)
                  :timeout-interval 100000}))

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
                        :data (js->clj response :keywordize-keys true)} ; convert it to clj
                       ))))))

(defn editable-field [row owner {:keys [field-key server-update-fn] :as opts}]
  "Make an editable field for field-key in row. Row must contain field-key.
server-update-fn will be called on row after the field-key val is set to the 
components state's value, whicih corresponds to the value of the text-input
field."
  (reify
    om/IInitState
    (init-state [_]
      {:editing? false
       :value ((keyword field-key) row)
       :error? false
       :error-message ""
       })
    om/IWillReceiveProps
    (will-receive-props [this next-props]
      (let [row (om/get-props owner)]
        (.log js/console "row is: " (clj->js row))
        (.log js/console "before value is:" (om/get-state owner [:value]))
        (om/set-state! owner :value ((keyword field-key) row))
        (.log js/console "after value is:" (om/get-state owner [:value]))
        ))
    om/IDidMount
    (did-mount [_]
      (let [events (sub (:notif-events-chan (om/get-shared owner)) (:id row) (chan))
            resps  (sub (:notif-resp-chan   (om/get-shared owner)) (:id row) (chan))
            put-state #(put! (:events-chan (om/get-shared owner))
                             {:topic (:id row)
                              :data {:tx-comp "editable-field"
                                     :field-key (keyword field-key)
                                     :comp-state (om/get-state owner)}})
            update-state #(do
                            (om/set-state! owner :error? %1)
                            (om/set-state! owner :editing? %2)
                            (om/set-state! owner :error-message %3))
            ]
        ;; loop that handles event channel messages
        (go-loop [e (<! events)]
          (let [data (:data e)
                tx-comp (:tx-comp data)
                editing? (:editing? data)]
            ;; we only care about messages from 'modify-row'
            (if (= tx-comp "modify-row") 
              (do
                ;; set own state to match that of 'modify-row'
                (om/set-state! owner :editing? editing?)
                ;; tell the channel our editing status
                (put-state)
                ;; if we are no longer editing..
                (if (not (om/get-state owner :editing?))
                  (do
                    ;; (.log js/console "value before: " (om/get-state owner :value))
                    ;; (.log js/console "row before update: " (clj->js row))
                    ;; update the value in the row of the global state
                    (om/update! row [(keyword field-key)]
                                (om/get-state owner :value))
                    ;; (.log js/console "row after update: " (clj->js row))
                    ;; (.log js/console "value after: " (om/get-state owner :value))
                    ;; the server must also be updated
                    ;;(server-update-fn row)
                    )))))
          (recur (<! events)))
        ;; loop that handles server response channel messages
        (go-loop [resp (<! resps)] ; handle the responses from the server
          (let [data (:data resp)
                success? (:success data)]
            (if success?
              (do
                ;; our own editing field should be false
                (update-state false false "")
                ;; value of the field should be updated
                ;;(om/set-state owner :value ((keyword field-key) row))
                )
              ;; was not successful, handle error message
              (do
                (update-state true true (:message data))))
            ;; tell the events channel our state
            (put-state)
            (recur (<! resps))))))
    om/IRender
    (render [this]
      (do
        (html/html
         (if (om/get-state owner [:editing?])
             ;; field is being edited
             [:div [:input {:type "text"
                            :class (if (om/get-state owner :error?)
                                     "error")
                            :on-change #(do
                                          (om/set-state!
                                           owner
                                           :value
                                           (-> %
                                               (aget "target")
                                               (aget "value"))))
                            :value (om/get-state owner :value)}
                    (if (om/get-state owner :error?)
                      [:div {:class "error"}
                       (str "Error: " (om/get-state owner :error-message))])]]
             ;; field is not being edited
             [:span ((keyword field-key) row)]))))))


(defn modify-row [row owner {:keys [editable-fields] :as opts}]
  "Given a row with an id (which the row MUST contain), create an interface for
modifying the row. editable-fields is a map of the following form:
{{:field-name1 {:editing? boolean}}
 {:field-name2 {:editing? boolean}}
 ...
 {:field-nameN {:editing? boolean}}
}

where field-nameN corresponds to the value of the field-key in the :opts map of
the editable field."
  (reify
    om/IInitState
    (init-state [_]
      {:editing? false
       :editable-fields editable-fields})
    om/IDidMount
    (did-mount [_]
      (let [events (sub (:notif-events-chan (om/get-shared owner)) (:id row) (chan))]
        (go-loop [e (<! events)]
          (let [data (:data e)
                tx-comp (:tx-comp data)
                field-key (:field-key data)
                comp-state (:comp-state data)
                keys-set (set (keys (om/get-state owner :editable-fields)))]
            ;; we only care about messages from 'editable-field'
            (if (= tx-comp "editable-field")
              (do
                ;; is this field being considered?
                (if (contains? keys-set field-key)
                  ;; ..then set the local components state
                  (om/set-state! owner [:editable-fields field-key]
                                 comp-state))
                ;; determine the editing? status of this component
                (om/set-state! owner :editing?
                               (boolean
                                (some identity
                                      (map :editing?
                                           (vals
                                            (om/get-state owner :editable-fields)))))))))
          (recur (<! events)))))
    om/IRender
    (render [this]
      (html/html
       (if (om/get-state owner [:editing?])
         ;; the row is being edited
         [:input {:id "save-row"
                  :type "submit"
                  :value "Save Changes"
                  :on-click
                  #(do
                     (.preventDefault %)
                     (om/set-state! owner :editing? false)
                     (put! (:events-chan (om/get-shared owner))
                           {:topic (:id row) :data {:tx-comp "modify-row"
                                                    :editing?
                                                    (om/get-state owner :editing?)}})
                     nil)}]
         ;; the row is not being edited
         [:a {:id "edit-row" :class "btn btn-default edit-icon"
              :on-click
              #(do
                 (.preventDefault %)
                 (om/set-state! owner :editing? true)
                 (put! (:events-chan (om/get-shared owner))
                       {:topic (:id row) :data {:tx-comp "modify-row"
                                                :editing? (om/get-state owner :editing?)}})
                 nil)}
          [:i {:class "fa fa-pencil"}]])))))


(defn courier-view [courier owner]
  (reify
    om/IInitState
    (init-state [_]
      {:editing? false})
    om/IRenderState
    (render-state [this state]
      (html/html [:tr
                  [:td {:class "opacity-hover"}
                   (om/build modify-row courier {:opts {:editable-fields
                                                        {:zones {:editable? false}}}})]
                  (if (:connected courier)
                    [:td {:class "currently-connected connected"} "Yes"]
                    [:td {:class "currently-not-connected connected"} "No"])
                  [:td (:name courier)]
                  [:td (:phone_number courier)]
                  [:td (if (:busy courier) "Yes" "No")]
                  [:td (unix-epoch->hrf (:last_ping courier))]
                  [:td (:lateness courier)]
                  [:td (om/build
                        editable-field
                        courier
                        {:opts {:field-key :zones
                                :server-update-fn
                                (partial update-courier-server
                                         (:resp-chan (om/get-shared owner)))}})]]))))

(defn get-server-couriers
  [state]
  (retrieve-url
   (str base-url "couriers")
   "GET"
   {}
   (partial xhrio-wrapper
            #(om/update!
              state [:couriers]
              (mapv
               (fn [courier]
                 (update-in courier [:zones] (fn [zone] (.join (clj->js (sort zone))))))
               (vec (reverse
                          (sort-by :last_ping (js->clj (aget % "couriers")
                                                       :keywordize-keys true))))
                    
                    )))))

(defn couriers-table [state owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (get-server-couriers state))
    om/IDidMount
    (did-mount [_]
      (continous-update #(get-server-couriers state)
                        (:timeout-interval @state)))
    om/IRender
    (render [this]
      (html/html [:table {:id "couriers"}
                  [:thead
                   [:tr
                    [:td]
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
  (let [events-chan (chan)
        resp-chan (chan)
        notif-events-chan (pub events-chan :topic)
        notif-resp-chan   (pub resp-chan :topic)
        ]
    (om/root root-component state
             {:shared {:notif-events-chan notif-events-chan
                       :notif-resp-chan   notif-resp-chan
                       :events-chan events-chan
                       :resp-chan resp-chan}
              :target (.getElementById js/document "app")})))
