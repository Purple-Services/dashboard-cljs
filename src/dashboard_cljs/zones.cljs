(ns dashboard-cljs.zones
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put!]]
            [clojure.set :refer [subset?]]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt markets
                                          json-string->clj cents->$dollars
                                          cents->dollars dollars->cents
                                          parse-to-number? accessible-routes
                                          diff-message get-input-value]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton TablePager
                                               FormGroup TextInput KeyVal
                                               EditFormSubmit DismissButton
                                               TableFilterButton
                                               ConfirmationAlert AlertSuccess
                                               SubmitDismissConfirmGroup
                                               TextAreaInput Select
                                               ProcessingIcon]]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [clojure.string :as s]))

;; Work Flow for adding options:
;; 1. Edit [DisplayedZoneComp] to show item. (be sure it exists in the DB)
;;    i. make a human readable translator, if needed
;;       Note: form-item->hrf NOT server-item->hrf !!!
;;       In [DisplayedZoneComp] you will do a (-> item (server-item->form-item)
;;                                                     (form-item->hrf))
;;    ii. add item for display
;; 2. If needed, edit server-zone->form-zone and form-zone->server-zone
;;    Note: When converting, best to use a flat map structure for r/cursor
;;    Note: This will probably require writing a separate conversion
;;          fn for the new item
;; 3. Make changes to [ZoneFormComp] to include new item.
;;    i. possibly write new sub-component for this
;; 4. Edit [EditZoneFormComp]
;;    i. diff-key-str
;;    ii. zone->diff-msg-zone
;;    iii. may need a item->hrf translator
;; 5. Edit [CreateZoneFormComp]
;;    i. edit confirm-msg

(def default-zone
  {:errors nil
   :confirming? false
   :editing? false
   :retrieving? false
   :name "" ; required
   :rank 1000 ; required
   :active true ; required
   :zips ""})

(def state (r/atom {:current-zone nil
                    :confirming-edit? false
                    :alert-success ""
                    :confirming? false
                    :editing? false
                    :retrieving? false
                    :create-confirming? false
                    :create-editing? false
                    :create-retrieving? false
                    :edit-zone default-zone
                    :create-edit-zone default-zone
                    :selected "active"
                    :search-results #{}}))

(def zone-search-results (r/cursor state [:search-results]))

(def default-zone-config
  {:hours [[[]] ; M
           [[]] ; T
           [[]] ; W
           [[]] ; Th
           [[]] ; F
           [[]] ; Sa
           [[]] ; Su
           ]
   :gas-price {}
   :gas-price-diff-percent {}
   :gas-price-diff-fixed {}
   :gallon-choices {:0 7.5
                    :1 10
                    :2 15}
   :default-gallong-choice :2
   :time-choices {:0 60
                  :1 180
                  :2 300}
   :default-time-choice 180
   :constrain-num-one-hour? false
   :delivery-fee {60 599
                  180 399
                  300 299}
   :delivery-fee-diff-percent {}
   :delivery-fee-diff-fixed {}
   :tire-pressure-price 700
   :manually-closed? false
   :closed-message ""})

(def days-of-week ["M" "Tu" "W" "Th" "F" "Sa" "Su"])

;; all of these defaults should be pulled from the server
;; ideally, from the Earth Zone
(def default-server-zone-hours
  [[[450 1350]] ; M
   [[450 1350]] ; Tu
   [[450 1350]] ; W
   [[450 1350]] ; Th
   [[450 1350]] ; F
   [[450 1350]] ; Sa
   [[450 1350]] ; Su
   ])

(def default-server-config-gas-price
  {:87 299
   :91 319})

;; when more time options are available, this will have to be modified
(def default-server-time-choices
  {:0 60
   :1 180
   :2 300})

(def default-server-delivery-fee
  {:60 599
   :180 399
   :300 299})

(defn minute-count->standard-hours
  "Given a count of minutes elapsed in day, 
  convert it to standard hours"
  [minute-count]
  (let [hours (quot minute-count 60)]
    (cond (= hours 0) 12
          (> hours 12) (- hours 12)
          (<= hours 12) hours)))

(defn minute-count->minutes
  "Given a count of minutes elapsed in day,
  convert it to minutes of day"
  [minute-count]
  (mod minute-count 60))

(defn minute-count->period
  "Given a count of minutes elapsed in day,
  determine if it is in the AM or PM"
  [minute-count]
  (if (< (quot minute-count 60) 12) "AM" "PM"))

(defn server-time-choices->form-time-choices
  [server-time-choices]
  (->> default-server-time-choices
       vals
       (map-indexed (fn [idx itm]
                      {(keyword (str "t" idx))
                       {:minutes itm
                        :selected (boolean
                                   (some #(= itm %)
                                         (vals server-time-choices)))}}))
       (apply merge)))

(defn form-time-choices->server-time-choices
  [form-time-choices]
  (->> form-time-choices
       vals
       (map #(if (:selected %) (:minutes %)))
       (filter #(not (nil? %)))
       sort
       (zipmap (map (comp keyword str) (range)))))

(defn server-time-choices->hrf
  [time-choices-server]
  (->> time-choices-server
       vals
       (map minute-count->standard-hours)
       (map #(str % " Hour"))
       sort
       (s/join ", ")))

(defn form-time-choices->hrf
  [form-time-choices]
  (->> form-time-choices
       form-time-choices->server-time-choices
       server-time-choices->hrf))

;; even though the server is technically saving
;; the edn as {"60" <price> "180" <price> "300" <price>}
;; when pulling in the json, :keywordize-keys true
;; is used

;; note: if additional delivery options are added,
;; this will need to be updated!
(defn server-delivery-fee->form-delivery-fee
  [server-delivery-fee]
  (let [service-fee-60 (server-delivery-fee :60)
        service-fee-180 (server-delivery-fee :180)
        service-fee-300 (server-delivery-fee :300)]
    {"60"  (cents->dollars service-fee-60)
     "180" (cents->dollars service-fee-180)
     "300" (cents->dollars service-fee-300)
     }))


(defn form-delivery-fee->hrf
  [form-delivery-fee]
  (let [service-fee-60 (form-delivery-fee "60")
        service-fee-180 (form-delivery-fee "180")
        service-fee-300 (form-delivery-fee "300")]
    ;; this could not be hard-coded
    (str "1 Hour Delivery Fee: $" service-fee-60 " "
         "3 Hour Delivery Fee: $" service-fee-180 " "
         "5 Hour Delivery Fee: $" service-fee-300)))

(defn standard-time->minute-count
  [standard-hours minutes period]
  (cond
    ;; from 12:00-12:59 AM
    (and (= standard-hours 12)
         (= period "AM"))
    minutes
    ;; from 12:00-12:59 PM
    (and (= standard-hours 12)
         (= period "PM"))
    (+ (* 12 60) minutes)
    ;; from 1:00PM-11:59PM
    (= period "PM")
    (+ (* standard-hours 60) (* 12 60) minutes)
    ;; from 1:00-11:59AM
    (= period "AM")
    (+ (* standard-hours 60) minutes)))

(defn server-hours->form-hours
  "Convert a day's vec of vec hours into a map"
  [server-hours]
  (apply merge (map-indexed
                (fn [idx itm]
                  (let [id (str "t" idx)]
                    {id
                     {:id id
                      :hours itm}}))
                server-hours)))

(defn server-day-hours->form-day-hours
  "Convert hours from the server into a map used in forms"
  [server-day-hours]
  (apply merge (map-indexed
                (fn [idx itm]
                  {(nth days-of-week idx)
                   (server-hours->form-hours (nth server-day-hours idx))})
                days-of-week)))

(defn single-digit->two-digit-str
  [digit]
  (if (< digit 10)
    (str "0" digit)
    (str digit)))

(defn minute-count->hrf-time
  [minute-count]
  (str (minute-count->standard-hours minute-count) ":"
       (single-digit->two-digit-str (minute-count->minutes minute-count)) " "
       (minute-count->period minute-count)))

(defn form-zone-hours->hrf-string
  [form-zone-hours]
  (s/join "\n"
          (map (fn [day]
                 (let [hours (vals (form-zone-hours day))
                       times (map :hours hours)]
                   (str day " "
                        (if hours
                          (s/join " "
                                  (map #(let [from-time (first %)
                                              to-time (second %)]
                                          (str (minute-count->hrf-time from-time)
                                               " - "
                                               (minute-count->hrf-time to-time)))
                                       times))
                          "Closed"))))
               days-of-week)))

(defn form-zone-hours->hrf-html
  [form-zone-hours]
  [:table
   [:tbody
    (map (fn [day]
           (let [hours (vals (form-zone-hours day))
                 times (map :hours hours)]
             ^{:key day}
             [:tr
              [:td {:style {:font-weight "bold" :padding-right "5px"}} day]
              [:td
               (if hours
                 (s/join " "
                         (map #(let [from-time (first %)
                                     to-time (second %)]
                                 (str (minute-count->hrf-time from-time)
                                      " - "
                                      (minute-count->hrf-time to-time)))
                              times))
                 "Closed")]]))
         days-of-week)]])

;; even though the server is technically saving
;; the edn as {"87" <price> "91" <price>}
;; when pulling in the json, :keywordize-keys true
;; is used
(defn server-config-gas-price->form-config-gas-price
  [server-config-gas-price]
  (let [price-87 (server-config-gas-price :87)
        price-91 (server-config-gas-price :91)]
    {"87" (cents->dollars price-87)
     "91" (cents->dollars price-91)}))

(defn form-config-gas-price->hrf-string
  [form-config-gas-price]
  (let [price-87 (form-config-gas-price "87")
        price-91 (form-config-gas-price "91")]
    (str "87 Octane: $" price-87 " "
         "91 Octane: $" price-91)))

(defn form-config-gas-price->hrf-html
  [form-config-gas-price]
  (let [price-87 (form-config-gas-price "87")
        price-91 (form-config-gas-price "91")]
    [:div
     "87 Octane: $" price-87
     [:br]
     "91 Octane: $" price-91]))

(defn form-hours->server-hours
  [form-hours]
  (into [] (map :hours (sort-by :id (vals form-hours)))))

(defn form-day-hours->server-day-hours
  [form-day-hours]
  (mapv (fn [day] (form-hours->server-hours (form-day-hours day)))
        days-of-week))

;; this function converts a zone to a edit-form zone
(defn server-zone->form-zone
  "Convert a server-zone to form-zone"
  [zone]
  (->>
   zone
   (#(if-let [hours (get-in % [:config :hours])]
       (assoc-in % [:config :hours]
                 (server-day-hours->form-day-hours hours))
       %))
   (#(if-let [gas-price (get-in % [:config :gas-price])]
       (assoc-in % [:config :gas-price]
                 (server-config-gas-price->form-config-gas-price gas-price))
       %))
   (#(if-let [manually-closed? (get-in % [:config :manually-closed?])]
       (assoc-in % [:config :manually-closed?]
                 manually-closed?)
       (assoc-in % [:config :manually-closed?] false)))
   (#(if-let [time-choices (get-in % [:config :time-choices])]
       (assoc-in % [:config :time-choices]
                 (server-time-choices->form-time-choices time-choices))
       %))
   (#(if-let [delivery-fee (get-in % [:config :delivery-fee])]
       (assoc-in % [:config :delivery-fee]
                 (server-delivery-fee->form-delivery-fee delivery-fee))
       %))
   ))

(defn form-zone->server-zone
  [zone]
  (->>
   zone
   (#(if-let [hours (get-in % [:config :hours])]
       (assoc-in % [:config :hours]
                 (form-day-hours->server-day-hours hours))
       %))
   (#(if-let [gas-price (get-in % [:config :gas-price])]
       (let [price-87 (gas-price "87")
             price-91 (gas-price "91")]
         (assoc-in % [:config :gas-price]
                   {"87"  (if (parse-to-number? price-87)
                            (dollars->cents
                             price-87)
                            price-87)
                    "91" (if (parse-to-number? price-91)
                           (dollars->cents
                            price-91)
                           price-91)}))
       %))
   (#(if-let [delivery-fee (get-in % [:config :delivery-fee])]
       (let [service-fee-60 (delivery-fee "60")
             service-fee-180 (delivery-fee "180")
             service-fee-300 (delivery-fee "300")]
         (assoc-in % [:config :delivery-fee]
                   {(js/Number "60")  (if (parse-to-number? service-fee-60)
                                        (dollars->cents
                                         service-fee-60)
                                        service-fee-60)
                    (js/Number "180") (if (parse-to-number? service-fee-180)
                                        (dollars->cents
                                         service-fee-180)
                                        service-fee-180)
                    (js/Number "300") (if (parse-to-number? service-fee-300)
                                        (dollars->cents
                                         service-fee-300)
                                        service-fee-300)}))
       %))
   (#(if-let [manually-closed? (get-in % [:config :manually-closed?])]
       (assoc-in % [:config :manually-closed?]
                 manually-closed?)
       (assoc-in % [:config :manually-closed?] false)))
   (#(if-let [time-choices (get-in % [:config :time-choices])]
       (let [server-time-choices
             (form-time-choices->server-time-choices time-choices)]
         (if-not (empty? server-time-choices)
           ;; there are still time choices
           (assoc-in % [:config :time-choices]
                     server-time-choices)
           ;; there are not time choices
           (assoc % :config (dissoc (:config %) :time-choices))))
       %))
   (#(assoc % :config
            (str (:config %))))))

(defn DisplayedZoneComp
  "Display a zone's information"
  [zone]
  (fn [zone]
    (let [hours (get-in zone [:config :hours])
          gas-price-87 (get-in zone [:config :gas-price :87])
          gas-price-91 (get-in zone [:config :gas-price :91])
          time-choices (get-in zone [:config :time-choices])
          default-time-choice (get-in zone [:config :default-time-choice])
          delivery-fee (get-in zone [:config :delivery-fee])
          closed-message (get-in zone [:config :closed-message])
          ]
      [:div {:class "row"}
       [:div {:class "col-lg-12"}
        [KeyVal "Name (ID)" (str (:name zone) " (" (:id zone) ")")]
        [KeyVal "Rank" (:rank zone)]
        [KeyVal "Active?" (if (:active zone)
                            "Yes"
                            "No")]
        [KeyVal "Manually Closed?" (if (get-in zone [:config :manually-closed?])
                                     "Yes"
                                     "No")]
        (when closed-message
          [KeyVal "Custom Closed Message" closed-message])
        (when time-choices
          [KeyVal "Delivery Times Available"
           (form-time-choices->hrf
            (server-time-choices->form-time-choices
             time-choices))])
        ;; come back to this later
        ;; (when default-time-choice
        ;;   [KeyVal "Default Delivery Time" (str (minute-count->standard-hours
        ;;                                         default-time-choice)
        ;;                                        " Hour")])
        (when delivery-fee
          [KeyVal "Delivery Fees" (form-delivery-fee->hrf
                                   (server-delivery-fee->form-delivery-fee
                                    delivery-fee))])
        (when (or gas-price-87
                  gas-price-91)
          [KeyVal "Gas Price" [:div
                               "87 Octane: "
                               (if (int? gas-price-87)
                                 (str "$" (cents->dollars gas-price-87))
                                 gas-price-87)
                               [:br]
                               "91 Octane: "
                               (if (int? gas-price-91)
                                 (str "$" (cents->dollars gas-price-91))
                                 gas-price-91)]])
        [KeyVal "Zip Codes" (:zips zone)]
        (when (get-in zone [:config :hours])
          [KeyVal "Hours" [:div
                           (-> hours
                               (server-day-hours->form-day-hours)
                               (form-zone-hours->hrf-html))]])]])))

(defn TimePickerComp
  [minutes-bracket]
  (let [hours (r/cursor minutes-bracket [:hours])
        from-hours (r/atom "12")
        from-minutes (r/atom "00")
        from-period (r/atom {:id 0 :period "AM"})
        to-hours (r/atom "12")
        to-minutes (r/atom "05")
        to-period (r/atom {:id 0 :period "AM"})
        from-time (first @hours)
        to-time (second @hours)
        time-period-options #{{:id 0 :period "AM"}
                              {:id 1 :period "PM"}}
        minute-count->time-period-option
        (fn [time-period]
          (first (filter #(= (minute-count->period time-period)
                             (:period %)) time-period-options)))
        id->time-period-option
        (fn [id]
          (first (filter #(= (js/Number id)
                             (:id %)) time-period-options)))]
    ;; from
    (reset! from-hours (minute-count->standard-hours from-time))
    (reset! from-minutes (single-digit->two-digit-str
                          (minute-count->minutes from-time)))
    (reset! from-period (:id (minute-count->time-period-option from-time)))
    ;; to
    (reset! to-hours (minute-count->standard-hours to-time))
    (reset! to-minutes (single-digit->two-digit-str
                        (minute-count->minutes to-time)))
    (reset! to-period (:id (minute-count->time-period-option to-time)))
    (fn [minutes-bracket]
      (let [all-times-parse-to-number?
            (fn []
              (and (parse-to-number? @from-hours)
                   (parse-to-number? @from-minutes)
                   (parse-to-number? @to-hours)
                   (parse-to-number? @to-minutes)))
            standard-times->minute-count-brackets
            (fn []
              (if (all-times-parse-to-number?)
                ;; only do something if everything parses correctly
                (let [from-minute-count
                      (standard-time->minute-count
                       (js/Number @from-hours)
                       (js/Number @from-minutes)
                       (:period (id->time-period-option @from-period)))
                      to-minute-count
                      (standard-time->minute-count
                       (js/Number @to-hours)
                       (js/Number @to-minutes)
                       (:period (id->time-period-option @to-period)))]
                  (reset! hours [from-minute-count to-minute-count]))))
            ;; whenever the hours are updated, change the hours bracket
            track-hours @(r/track standard-times->minute-count-brackets)]
        ;;(.log js/console "hours: " (clj->js @hours))
        [:div {:style {:display "inline-block"}}
         [:div {:style {:max-width "3em"
                        :display "inline-block"}}
          [TextInput {:value @from-hours
                      :placeholder "12"
                      :on-change #(reset! from-hours
                                          (get-input-value %))}]]
         ":"
         [:div {:style {:max-width "3em"
                        :display "inline-block"}}
          [TextInput {:value  @from-minutes
                      :placeholder "00"
                      :on-change #(reset! from-minutes
                                          (get-input-value %))}]]
         [:div {:style {:display "inline-block"}}
          [Select {:value from-period
                   :options #{{:id 0 :period "AM"}
                              {:id 1 :period "PM"}}
                   :display-key :period
                   :sort-keyword :id}]]
         " - "
         [:div {:style {:max-width "3em"
                        :display "inline-block"}}
          [TextInput {:value @to-hours
                      :placeholder "12"
                      :on-change #(reset! to-hours
                                          (get-input-value %))}]]
         ":"
         [:div {:style {:max-width "3em"
                        :display "inline-block"}}
          [TextInput {:value @to-minutes
                      :placeholder "00"
                      :on-change #(reset! to-minutes
                                          (get-input-value %))}]]
         [:div {:style {:display "inline-block"}}
          [Select {:value to-period
                   :options #{{:id 0 :period "AM"}
                              {:id 1 :period "PM"}}
                   :display-key :period
                   :sort-keyword :id}]]]))))

(defn insert-time-not-nil!
  "Insert a time into days-atom"
  [day days-atom]
  (let [day-hours (r/cursor days-atom [day])
        ids (sort (keys @day-hours))
        last-id (last ids)
        next-id (str "t" (->> last-id
                              (re-find #"\d")
                              (js/Number)
                              inc))
        next-hours-map {:id next-id
                        :hours [450 1350]}]
    (swap! day-hours assoc next-id next-hours-map)))

(defn insert-time!
  "Insert a time into days-atom"
  [day days-atom]
  (let [day-hours (r/cursor days-atom [day])
        next-hours-map {"t0"
                        {:id "t0"
                         :hours [450 1350]}}]
    (if-not (nil? @day-hours)
      (insert-time-not-nil! day days-atom)
      (reset! day-hours next-hours-map))))

(defn delete-time!
  "Delete a time from days-atom"
  [day days-atom hours-map-atom]
  (let [day-hours (r/cursor days-atom [day])
        hour-id (:id @hours-map-atom)]
    (if-not (= (:id hours-map-atom) "t0")
      (swap! day-hours dissoc hour-id)
      (reset! (r/cursor days-atom [day]) nil))))


(defn AddTimeComp
  [day days-atom]
  (fn [day days-atom]
    [:div [:a {:href "#"
               :on-click (fn [e]
                           (.preventDefault e)
                           (insert-time! day days-atom))}
           "(+)"]]))

(defn DeleteTimeComp
  [day days-atom hours-map-atom]
  (fn [day days-atom hours-map-atom]
    (let [hour-id (:id @hours-map-atom)]
      [:a {:href "#"
           :on-click (fn [e]
                       (.preventDefault e)
                       (delete-time! day days-atom hours-map-atom))}
       "(-)"])))

(defn DaysTimeRangeComp
  [hours zone]
  (let [days-atom (r/cursor zone [:config :hours])]
    (fn [hours]
      (let []
        ;;(.log js/console "days-atom:" (clj->js @days-atom))
        ;; (.log js/console "converted days-atom"
        ;;       (clj->js (form-day-hours->server-day-hours @days-atom)))
        [:div
         (doall
          (map
           (fn [day]
             ^{:key day}
             [:div day [AddTimeComp day days-atom]
              (map (fn [el]
                     (let [hours-map-atom (r/cursor days-atom [day el])]
                       ^{:key el}
                       [:div
                        [TimePickerComp hours-map-atom]
                        [DeleteTimeComp day days-atom hours-map-atom]
                        [:br]]))
                   (keys (@days-atom day)))])
           days-of-week))]))))

(defn ZoneFormComp
  "Create or edit a zone"
  [{:keys [zone errors]}]
  (fn [{:keys [zone errors]}]
    (let [name (r/cursor zone [:name])
          rank (r/cursor zone [:rank])
          active (r/cursor zone [:active])
          zips (r/cursor zone [:zips])
          config (r/cursor zone [:config])
          hours (r/cursor config [:hours])
          gas-price (r/cursor config [:gas-price])
          price-87 (r/cursor gas-price ["87"])
          price-91 (r/cursor gas-price ["91"])
          manually-closed? (r/cursor config [:manually-closed?])
          closed-message (r/cursor config [:closed-message])
          time-choices (r/cursor config [:time-choices])
          default-time-choice (r/cursor config [:default-time-choice])
          delivery-fee (r/cursor config [:delivery-fee])
          service-fee-60 (r/cursor delivery-fee ["60"])
          service-fee-180 (r/cursor delivery-fee ["180"])
          service-fee-300 (r/cursor delivery-fee ["300"])]
      [:div {:class "row"}
       [:div {:class "col-lg-12"}
        ;; name
        [FormGroup {:label "Name"
                    :errors (:name @errors)}
         [TextInput {:value @name
                     :on-change #(reset! name
                                         (get-input-value %))}]]
        ;; rank
        [FormGroup {:label "Rank"
                    :errors (:rank @errors)}
         [TextInput {:style {:max-width "60px"}
                     :value @rank
                     :on-change #(let [value (get-input-value %)]
                                   (reset! rank (if (parse-to-number? value)
                                                  (js/parseInt value)
                                                  value)))}]]
        ;; active
        [FormGroup {:label "Active? "
                    :errors (:active @errors)}
         [:input {:type "checkbox"
                  :checked @active
                  :style {:margin-left "4px"}
                  :on-change (fn [e] (reset!
                                      active
                                      (-> e
                                          (.-target)
                                          (.-checked))))}]]
        ;;manually closed
        [FormGroup {:label "Manually Closed? "
                    :errors (get-in @errors [:config :manually-closed?])}
         [:input {:type "checkbox"
                  :checked @manually-closed?
                  :style {:margin-left "4px"}
                  :on-change (fn [e] (reset!
                                      manually-closed?
                                      (-> e
                                          (.-target)
                                          (.-checked))))}]]
        ;; closed message
        [FormGroup {:label "Custom Closed Message"
                    :errors (get-in  @errors [:config :closed-message])}
         (if-not @closed-message
           ;; closed message not defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click
                           (fn [e]
                             (.preventDefault e)
                             (reset! closed-message
                                     (str @name " is currently closed")))}
                  "Add Custom Closed Message"]]
           ;; closed message defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (swap! config dissoc :closed-message))}
                  "Remove Custom Closed Message"]
            [:br]
            [:br]
            ;; Close message
            [TextInput {:value @closed-message
                        :on-change #(reset! closed-message
                                            (-> %
                                                (aget "target")
                                                (aget "value")))}]])]
        ;; time choices
        [FormGroup {:label "Delivery Times Available"
                    :errors (get-in @errors [:config :time-choices])}
         (if-not @time-choices
           ;; time-choices not define
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click
                           (fn [e]
                             (.preventDefault e)
                             (reset! time-choices
                                     (server-time-choices->form-time-choices
                                      default-server-time-choices)))}
                  "Add Delivery Times"]
            [:br]]
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (swap! config dissoc :time-choices))}
                  "Remove Delivery Times"]
            [:br]
            [:br]
            ;; time choice picker
            [:div
             (doall
              (map
               (fn [choice]
                 ^{:key (get-in @time-choices [choice :minutes])}
                 [:div
                  (str (minute-count->standard-hours
                        (get-in @time-choices [choice :minutes])) " Hour ")
                  [:input {:type "checkbox"
                           :disabled
                           (if (> (minute-count->standard-hours
                                   (get-in @time-choices [choice :minutes]))
                                  1)
                             true
                             false)
                           :checked (get-in @time-choices [choice :selected])
                           :on-change (fn [e]
                                        (reset!
                                         (r/cursor time-choices
                                                   [choice :selected])
                                         (-> e
                                             (.-target)
                                             (.-checked))))}]])
               (keys @time-choices)))
             (when (empty?
                    (form-time-choices->server-time-choices @time-choices))
               [:div {:class "alert alert-danger"}
                (str "Removing all time options does not neccesarily close the "
                     "zone! Use the 'Closed' option to close the zone.")])
             ]])]
        ;; delivery-fee
        [FormGroup {:label "Delivery Fees"
                    :errors (get-in @errors [:config :delivery-fee])}
         (if (empty? @delivery-fee)
           ;; delivery fee not defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (reset!
                                        delivery-fee
                                        (server-delivery-fee->form-delivery-fee
                                         default-server-delivery-fee)))}
                  "Add Delivery Fees"]]
           ;; delivery fee defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (swap! config dissoc :delivery-fee))}
                  "Remove Delivery Fees"]
            ;; once again, these should be automatically generated
            ;; not hardcoded
            ;; 1 hour fee
            [FormGroup {:label "1 Hour Delivery Fee"
                        :errors (get-in @errors [:delivery-fee "60"])}
             [TextInput {:value @service-fee-60
                         :on-change #(reset! service-fee-60
                                             (-> %
                                                 (aget "target")
                                                 (aget "value")))}]]
            ;; 3 hour fee
            [FormGroup {:label "3 Hour Delivery Fee"
                        :errors (get-in @errors [:delivery-fee "180"])}
             [TextInput {:value @service-fee-180
                         :on-change #(reset! service-fee-180
                                             (-> %
                                                 (aget "target")
                                                 (aget "value")))}]]
            ;; 5 hour fee
            [FormGroup {:label "5 Hour Delivery Fee"
                        :errors (get-in @errors [:delivery-fee "300"])}
             [TextInput {:value @service-fee-300
                         :on-change #(reset! service-fee-300
                                             (-> %
                                                 (aget "target")
                                                 (aget "value")))}]]])]
        ;; gas price
        [FormGroup {:label "Gas Prices"
                    :errors (get-in  @errors [:config :gas-price])}
         (if (empty? @gas-price)
           ;; gas not defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click
                           (fn [e]
                             (.preventDefault e)
                             (reset!
                              gas-price
                              (server-config-gas-price->form-config-gas-price
                               default-server-config-gas-price)))}
                  "Add Gas Prices"]]
           ;; gas defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (swap! config dissoc :gas-price))}
                  "Remove Gas Prices"]
            ;; 87 Price
            [FormGroup {:label-for "87 price"
                        :label "87 Octane"
                        :errors (get-in @errors [:gas-price "87"])
                        :input-group-addon [:div {:class "input-group-addon"}
                                            "$"]}
             [TextInput {:value @price-87
                         :on-change #(reset! price-87 (-> %
                                                          (aget "target")
                                                          (aget "value")))}]]
            ;; 91 price
            [FormGroup {:label-for "91 price"
                        :label "91 Octane"
                        :errors (get-in @errors [:gas-price "91"])
                        :input-group-addon [:div {:class "input-group-addon"}
                                            "$"]}
             [TextInput {:value @price-91
                         :on-change #(reset! price-91 (-> %
                                                          (aget "target")
                                                          (aget "value")))}]]])]
        ;; zips
        [FormGroup {:label "Zip Codes"
                    :errors (:zips @errors)}
         [TextAreaInput {:value @zips
                         :rows 2
                         :on-change (fn [e]
                                      (reset!
                                       zips
                                       (get-input-value e)))}]]
        ;; Hours
        [FormGroup {:label "Hours of Operation"
                    :errors (get-in  @errors [:config :hours])}
         ;; below should be refactored into a reusable component
         (if (empty? @hours)
           ;; hours not defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (reset! hours
                                               (server-day-hours->form-day-hours
                                                default-server-zone-hours)))}
                  "Add Hours"]]
           ;; hours defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (swap! config dissoc :hours))}
                  "Remove Hours"]
            [DaysTimeRangeComp @hours zone]])]]])))

(defn EditZoneFormComp
  [zone]
  (let [;; internal state
        confirming? (r/cursor state [:confirming?])
        editing? (r/cursor state [:editing?])
        retrieving? (r/cursor state [:retrieving?])
        errors (r/atom {})
        alert-success (r/atom "")]
    (fn [zone]
      (let [current-zone zone ; before changes are made to zone
            config (r/cursor current-zone [:config])
            edit-zone (r/cursor state [:edit-zone])
            ;; atoms for the editable zone
            name (r/cursor edit-zone [:name])
            rank (r/cursor edit-zone [:rank])
            active (r/cursor edit-zone [:active])
            ;; helper fns
            diff-key-str {:name "Name"
                          :rank "Rank"
                          :active "Active"
                          :manually-closed? "Closed"
                          :closed-message "Closed Message"
                          :time-choices "Delivery Times Available"
                          :delivery-fee "Delivery Fees"
                          :zips "Zip Codes"
                          :gas-price "Gas Price"
                          :hours "Hours"
                          }
            diff-msg-gen (fn [edit current]
                           (diff-message
                            edit
                            current
                            (select-keys diff-key-str (concat (keys edit)
                                                              (keys current)))))
            zone->diff-msg-zone (fn [zone]
                                  (->> zone
                                       (#(if-let [hours
                                                  (get-in % [:config :hours])]
                                           (assoc
                                            %
                                            :hours
                                            (form-zone-hours->hrf-string hours))
                                           %))
                                       (#(if-let [gas-price
                                                  (get-in % [:config
                                                             :gas-price])]
                                           (assoc
                                            %
                                            :gas-price
                                            (form-config-gas-price->hrf-string
                                             gas-price))
                                           %))
                                       (#(if-let
                                             [manually-closed?
                                              (get-in % [:config
                                                         :manually-closed?])]
                                           (assoc
                                            %
                                            :manually-closed?
                                            (if manually-closed?
                                              "Yes"
                                              "No"))
                                           (assoc % :manually-closed? "No")))
                                       (#(if-let
                                             [closed-message
                                              (get-in % [:config
                                                         :closed-message])]
                                           (assoc
                                            %
                                            :closed-message
                                            closed-message)
                                           %))
                                       (#(if-let [time-choices
                                                  (get-in % [:config
                                                             :time-choices])]
                                           (assoc
                                            %
                                            :time-choices
                                            (form-time-choices->hrf
                                             time-choices))
                                           %))
                                       (#(if-let [delivery-fee
                                                  (get-in % [:config
                                                             :delivery-fee])]
                                           (assoc
                                            %
                                            :delivery-fee
                                            (form-delivery-fee->hrf
                                             delivery-fee))
                                           %))
                                       ))
            diff-msg-gen-zone (fn [edit-zone current-zone]
                                ;; (.log js/console "edit-zone: "
                                ;;       (clj->js edit-zone))
                                ;; (.log js/console "current-zone: "
                                ;;       (clj->js (server-zone->form-zone
                                ;;                 current-zone)))
                                ;; (.log js/console "edit-zone: "
                                ;;       (clj->js (zone->diff-msg-zone edit-zone)))
                                ;; (.log js/console "edit-zone: "
                                ;;       (clj->js (zone->diff-msg-zone
                                ;;                 (server-zone->form-zone
                                ;;                  current-zone))))
                                (diff-msg-gen
                                 (zone->diff-msg-zone edit-zone)
                                 (zone->diff-msg-zone
                                  (server-zone->form-zone current-zone))))
            confirm-msg (fn []
                          [:div "The following changes will be made to "
                           (:name @current-zone)
                           (map (fn [el]
                                  ^{:key el}
                                  [:h4 el])
                                (diff-msg-gen-zone @edit-zone @current-zone))])
            submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (if (every? nil?
                                            (diff-msg-gen-zone @edit-zone
                                                               @current-zone))
                                  ;; there isn't a diff message, no changes
                                  (reset! editing? false)
                                  ;; there is a diff message, confirm change
                                  (reset! confirming? true))
                                (do
                                  ;;(.log js/console "there is a diff message")
                                  ;; reset edit zone
                                  (reset! edit-zone
                                          (server-zone->form-zone @zone))
                                  ;; get rid of alert-success
                                  (reset! alert-success "")
                                  (reset! editing? true))))
            confirm-on-click (fn [_]
                               ;;(.log js/console "edit-zone" (clj->js @edit-zone))
                               (entity-save
                                (form-zone->server-zone @edit-zone)
                                "zone"
                                "PUT"
                                retrieving?
                                (edit-on-success "zone"
                                                 edit-zone
                                                 current-zone
                                                 alert-success
                                                 :aux-fn
                                                 (fn []
                                                   (reset! confirming? false)
                                                   (reset! retrieving? false)
                                                   (reset! editing? false)))
                                (edit-on-error edit-zone
                                               :aux-fn
                                               (fn []
                                                 (reset! confirming? false)
                                                 (reset! retrieving? false)
                                                 (reset! alert-success ""))
                                               :response-fn
                                               (fn [response]
                                                 (reset! errors response)))))
            dismiss-fn (fn [e]
                         ;; reset any errors
                         (reset! errors nil)
                         ;; no longer editing
                         (reset! editing? false)
                         ;; reset edit-zone
                         (reset! edit-zone
                                 (server-zone->form-zone @current-zone))
                         ;; reset confirming
                         (reset! confirming? false))]
        [:div
         (when-not @editing?
           [DisplayedZoneComp @current-zone])
         (when (subset? #{{:uri "/zone"
                           :method "PUT"}}
                        @accessible-routes)
           [:form {:class "form-horizontal"}
            (when @editing?
              [ZoneFormComp {:zone edit-zone
                             :errors errors}])
            [SubmitDismissConfirmGroup
             {:confirming? confirming?
              :editing? editing?
              :retrieving? retrieving?
              :submit-fn submit-on-click
              :dismiss-fn dismiss-fn}]
            (if (and @confirming?
                     (not-every? nil? (diff-msg-gen-zone
                                       @edit-zone @current-zone)))
              [ConfirmationAlert
               {:confirmation-message confirm-msg
                :cancel-on-click dismiss-fn
                :confirm-on-click confirm-on-click
                :retrieving? retrieving?}]
              (reset! confirming? false))
            (when-not (empty? @alert-success)
              [AlertSuccess {:message @alert-success
                             :dismiss #(reset! alert-success)}])])]))))

(defn CreateZoneFormComp
  []
  (let [;; internal state
        confirming? (r/cursor state [:create-confirming?])
        editing? (r/cursor state [:create-editing?])
        retrieving? (r/cursor state [:create-retrieving?])
        errors (r/atom {})
        alert-success (r/atom "")]
    (fn []
      (let [edit-zone (r/cursor state [:create-edit-zone])
            ;; atoms for the editable zone
            name (r/cursor edit-zone [:name])
            rank (r/cursor edit-zone [:rank])
            active (r/cursor edit-zone [:active])
            ;; helper fns
            confirm-msg (fn [zone]
                          (let [{:keys [name rank active zips config]} zone]
                            [:div
                             (str "Are you sure you want to create new zone "
                                  "with the following value?")
                             [:h4 "Name: " name]
                             [:h4 "Rank: " rank]
                             [:h4 "Active: " (if  active
                                               "Yes"
                                               "No")]
                             [:h4 "Closed: " (if (:manually-closed? config)
                                               "Yes"
                                               "No")]
                             (when (:closed-message config)
                               [:h4 "Closed Message: "
                                (:closed-message config)])
                             (when (:time-choices config)
                               [:h4 "Delivery Times Available"
                                (form-time-choices->hrf
                                 (:time-choices config))])
                             (when (:delivery-fee config)
                               [:h4 "Delivery Fees"
                                (form-delivery-fee->hrf (:delivery-fee config))])
                             (when (:gas-price config)
                               [:h4 "Gas Price: "
                                (form-config-gas-price->hrf-string
                                 (:gas-price config))])
                             [:h4 "Zip Codes: " zips]
                             (when (:hours config)
                               [:h4 "Hours: "
                                (form-zone-hours->hrf-html (:hours config))])
                             ]))
            submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (do
                                  (reset! confirming? true))
                                (do
                                  (reset! alert-success "")
                                  (reset! editing? true))))
            confirm-on-click (fn [_]
                               (entity-save
                                (form-zone->server-zone @edit-zone)
                                "zone"
                                "POST"
                                retrieving?
                                (edit-on-success "zone"
                                                 edit-zone
                                                 (r/atom {})
                                                 alert-success
                                                 :aux-fn
                                                 (fn []
                                                   (reset! confirming? false)
                                                   (reset! retrieving? false)
                                                   (reset! editing? false)))
                                (edit-on-error edit-zone
                                               :aux-fn
                                               (fn []
                                                 (reset! confirming? false)
                                                 (reset! retrieving? false)
                                                 (reset! alert-success ""))
                                               :response-fn
                                               (fn [response]
                                                 (reset! errors response)))))
            dismiss-fn (fn [e]
                         ;; reset any errors
                         (reset! errors nil)
                         ;; no longer editing
                         (reset! editing? false)
                         ;; reset edit-zone
                         (reset! edit-zone default-zone)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:div
         [:form {:class "form-horizontal"}
          (when @editing?
            [ZoneFormComp {:zone edit-zone
                           :errors errors}])
          [SubmitDismissConfirmGroup
           {:confirming? confirming?
            :editing? editing?
            :retrieving? retrieving?
            :submit-fn submit-on-click
            :dismiss-fn dismiss-fn
            :edit-btn-content "Create a New Zone"}]
          (when @confirming?
            [ConfirmationAlert
             {:confirmation-message (fn [] (confirm-msg @edit-zone))
              :cancel-on-click dismiss-fn
              :confirm-on-click confirm-on-click
              :retrieving? retrieving?}])
          (when-not (empty? @alert-success)
            [AlertSuccess {:message @alert-success
                           :dismiss #(reset! alert-success)}])]]))))

(defn zone-table-header
  "props is:
  {
  :sort-keyword   ; reagent atom keyword used to sort table
  :sort-reversed? ; reagent atom boolean for determing if the sort is reversed
  }"
  [props]
  (fn [props]
    [:thead
     [:tr
      [TableHeadSortable
       (conj props {:keyword :rank
                    :title "Zone rules are applied to a ZIP by starting with the lowest rank. Higher ranking zones supersede lower ranking zones when the rules are in conflict."
                    :style {:cursor "help"
                            :border-bottom "none"}})
       "Rank"]
      [TableHeadSortable
       (conj props {:keyword :name
                    :title "Zones that are manually closed are shown in light-gray."
                    :style {:cursor "help"
                            :border-bottom "none"}})
       "Name"]
      [TableHeadSortable
       (conj props {:keyword :zip_count
                    :style {:border-bottom "none"}})
       "No. Zips"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"
                    :border-bottom "none"}}
       "Zips"]]]))

(defn zone-row
  "A table row for a zone."
  [current-zone]
  (fn [zone]
    [:tr (merge {:class (when (= (:id zone)
                                 (:id @current-zone))
                          "active")
                 :on-click (fn [_]
                             (reset! current-zone zone)
                             (reset! (r/cursor state [:editing?]) false)
                             (reset! (r/cursor state [:alert-success]) ""))}
                (when (:manually-closed? (:config zone))
                  {:style {:color "#bbb"}}))
     ;; Rank
     [:td (str (:rank zone)
               (case (:rank zone)
                 100 " (mrkt)"
                 ""))]
     ;; name
     [:td [:i {:class "fa fa-circle"
               :style {:color (:color zone)}}]
      " " (:name zone)]
     ;; # of zips
     [:td (-> zone
              :zip_count)]
     ;; Zips
     [:td ;; {:style {:overflow "scroll"}}
      (let [zips-string (:zips zone)
            subs-string (subs zips-string 0 68)]
        (if (> (count zips-string) 68)
          (str subs-string ", ...")
          zips-string))]]))

(defn zones-panel
  "Display a table of zones"
  [zones]
  (let [current-zone (r/cursor state [:current-zone])
        edit-zone (r/cursor state [:edit-zone])
        sort-keyword (r/atom :rank)
        sort-reversed? (r/atom true)
        selected (r/cursor state [:selected])
        current-page (r/atom 1)
        page-size 15
        selected-filter (r/atom "Active")
        filters {"Active" :active
                 "Inactive" (complement :active)}]
    (fn [zones]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-zones zones
            sorted-zones (fn []
                           (->> displayed-zones
                                sort-fn
                                ;; we're not displaying the Earth Zone
                                (filter (every-pred #(not= (:id %) 1)
                                                    (get filters @selected-filter)))
                                (partition-all page-size)))
            paginated-zones (fn []
                              (-> (sorted-zones)
                                  (nth (- @current-page 1)
                                       '())))
            refresh-fn (fn [refreshing?]
                         (reset! refreshing? true)
                         (retrieve-url
                          (str base-url "zones")
                          "GET"
                          {}
                          (partial
                           xhrio-wrapper
                           (fn [response]
                             ;; update the users atom
                             (put! datastore/modify-data-chan
                                   {:topic "zones"
                                    :data (js->clj response :keywordize-keys
                                                   true)})
                             (reset! refreshing? false)))))
            table-pager-on-click (fn []
                                   (reset! current-zone
                                           (first (paginated-zones)))
                                   (reset! (r/cursor state [:editing?]) false)
                                   (reset! (r/cursor state [:alert-success]) ""))
            table-filter-button-on-click (fn []
                                           (reset! sort-keyword :rank)
                                           (reset! current-page 1)
                                           (table-pager-on-click))]
        (when (nil? @current-zone)
          (table-pager-on-click))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:div {:class "panel-body"}
             [:h2 [:i {:class "fa fa-circle"
                       :style {:color
                               (:color @current-zone)}}]
              (str " " (:name @current-zone))]
             [EditZoneFormComp current-zone]]]]
          [:br]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:div {:class "btn-toolbar"
                   :role "toolbar"}
             [:div {:class "btn-group" :role "group"}
              [TableFilterButton {:text "Active"
                                  :filter-fn :active
                                  ;:hide-count true
                                  :on-click  (fn []
                                               (reset! sort-reversed? false)
                                               (table-filter-button-on-click))
                                  :data zones
                                  :selected-filter selected-filter}]
              [TableFilterButton {:text "Inactive"
                                  :filter-fn (complement :active)
                                  ;:hide-count false
                                  :on-click (fn []
                                              (reset! sort-reversed? true)
                                              (table-filter-button-on-click))
                                  :data zones
                                  :selected-filter selected-filter}]]
             [:div {:class "btn-group"
                    :role "group"}
              [RefreshButton {:refresh-fn
                              refresh-fn}]]]]]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:div {:class "table-responsive"}
             [StaticTable
              {:table-header [zone-table-header
                              {:sort-keyword sort-keyword
                               :sort-reversed? sort-reversed?}]
               :table-row (zone-row current-zone)}
              (paginated-zones)]]
            [TablePager
             {:total-pages (count (sorted-zones))
              :current-page current-page
              :on-click table-pager-on-click}]]]
          [:br]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            (when (subset? #{{:uri "/zone"
                              :method "POST"}}
                           @accessible-routes)
              (.log js/console "user is allowed to create zones")
              [CreateZoneFormComp])]]]]))))

(def zips-search-state (r/atom {:search-retrieving? false
                                :recent-search-term ""
                                :search-term ""}))

(defn ZipsSearchBarComp
  [state]
  (let [state zips-search-state
        retrieving? (r/cursor state [:search-retrieving?])
        search-results (r/cursor state [:search-results])
        recent-search-term (r/cursor state [:recent-search-term])
        search-term (r/cursor state [:search-term])
        retrieve-results (fn [zip]
                           (retrieve-url
                            (str base-url "zips/" zip)
                            "GET"
                            {}
                            (partial
                             xhrio-wrapper
                             (fn [r]
                               (let [response (js->clj
                                               r :keywordize-keys true)]
                                 (reset! retrieving? false)
                                 (reset! recent-search-term @search-term)
                                 (reset! search-results response))))))]

    (fn []
      [:div {:class "row"}
       [:div {:class "col-lg-6 col-xs-12"}
        [:form
         [:div {:class "input-group"}
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "Zip Info"
                   :on-change (fn [e]
                                (reset! search-term
                                        (-> e
                                            (aget "target")
                                            (aget "value"))))
                   :value @search-term}]
          [:div {:class "input-group-btn"}
           [:button {:class "btn btn-default"
                     :type "submit"
                     :on-click
                     (fn [e]
                       (.preventDefault e)
                       (when-not (s/blank? @search-term)
                         (reset! retrieving? true)
                         (retrieve-results @search-term)
                         (reset! (r/cursor state [:current-user]) nil)
                         (reset! (r/cursor state [:current-order]) nil)))}
            [:i {:class "fa fa-search"}]]]]]]])))

(defn ZipsSearchResults
  "Display result"
  [state]
  (fn []
    (let [search-term (r/cursor state [:search-term])
          retrieving? (r/cursor state [:search-retrieving?])
          recent-search-term (r/cursor state [:recent-search-term])
          search-results (r/cursor state [:search-results])]
      [:div
       (when @retrieving?
         (.scrollTo js/window 0 0)
         [:h4 "Retrieving results for \""
          [:strong
           {:style {:white-space "pre"}}
           @search-term]
          "\" "
          [ProcessingIcon]])
       (when-not (nil? (and @search-term @recent-search-term))
         [:div {:class "row" :id "search-results"}
          [:div {:class "col-lg-12 col-lg-12"}
           (when-not @retrieving?
             [:div
              (when (and (empty? @search-results)
                         (not (empty? @recent-search-term))
                         (not @retrieving?))
                [:div [:h4 "Your search - \""
                       [:strong {:style {:white-space "pre"}}
                        @recent-search-term]
                       \"" - did not match any Zips."]])
              (when-not (empty? @search-results)
                (let [{:keys [closed-message
                              default-gallon-choice
                              delivery-fee
                              gallon-choices
                              gas-price
                              hours
                              manually-closed?
                              one-hour-constraining-zone-id
                              time-choices
                              tire-pressure-price
                              zone-ids
                              zone-names]} @search-results]
                  [:div
                   [:h3 @recent-search-term]
                   [KeyVal "Zones"
                    (if zone-names
                      (s/join " < "
                              (map #(str (val %) " (" (key %) ")")
                                   (zipmap zone-ids zone-names)))
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Gas Price"
                    (if gas-price
                      [:div (form-config-gas-price->hrf-html
                             (server-config-gas-price->form-config-gas-price
                              gas-price))]
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Hours"
                    (if hours
                      [:div (-> hours
                                (server-day-hours->form-day-hours)
                                (form-zone-hours->hrf-html))]
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Delivery Times Available"
                    (if time-choices
                      (form-time-choices->hrf
                       (server-time-choices->form-time-choices
                        time-choices))
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Delivery Fees"
                    (if delivery-fee
                      (form-delivery-fee->hrf
                       (server-delivery-fee->form-delivery-fee delivery-fee))
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Gallon Choices"
                    (if gallon-choices
                      (str
                       (s/join ", "
                               (vals gallon-choices))
                       " gallons")
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Default Gallon Choice"
                    (if default-gallon-choice
                      default-gallon-choice
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Tire Pressure Price"
                    (if tire-pressure-price
                      (str "$" (cents->dollars tire-pressure-price))
                      [:span {:style {:color "rgb(217, 83, 79)"}} "Missing!"])]
                   [KeyVal "Manually Closed?"
                    (if manually-closed?
                      "Yes"
                      "No")]
                   [KeyVal "Closed Message" closed-message]
                   ]))])]])])))
