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
                                               ConfirmationAlert AlertSuccess
                                               SubmitDismissConfirmGroup
                                               TextAreaInput Select]]
            [dashboard-cljs.forms :refer [entity-save edit-on-success
                                          edit-on-error]]
            [clojure.string :as s]))

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
                    :selected "active"}))

(def default-zone-config
  {:hours [[[]] ; Su
           [[]] ; M
           [[]] ; T
           [[]] ; W
           [[]] ; Th
           [[]] ; F
           [[]] ; Sa
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

(def days-of-week ["S" "M" "T" "W" "Th" "F" "Sa"])

(def default-server-zone-hours
  [[[450 1350]] ; Su
   [[450 1350]] ; M
   [[450 1350]] ; T
   [[450 1350]] ; W
   [[450 1350]] ; Th
   [[450 1350]] ; F
   [[450 1350]] ; Sa
   ])

(def default-server-config-gas-price
  {:87 299
   :91 319})

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
                        (s/join " "
                                (map #(let [from-time (first %)
                                            to-time (second %)]
                                        (str (minute-count->hrf-time from-time)
                                             " - "
                                             (minute-count->hrf-time to-time)))
                                     times)))))
               days-of-week)))

(defn server-config-gas-price->form-config-gas-price
  [server-config-gas-price]
  (let [price-87 (:87 server-config-gas-price)
        price-91 (:91 server-config-gas-price)]
    {:87 (cents->dollars price-87)
     :91 (cents->dollars price-91)}))

(defn form-config-gas-price->hrf-string
  [form-config-gas-price]
  (let [price-87 (:87 form-config-gas-price)
        price-91 (:91 form-config-gas-price)]
    (str "87 Octane: $" price-87 " "
         "91 Octane: $" price-91)))

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
       (let [price-87 (:87 gas-price)
             price-91 (:91 gas-price)]
         (assoc-in % [:config :gas-price]
                   {:87  (if (parse-to-number? price-87)
                           (dollars->cents
                            price-87)
                           price-87)
                    :91 (if (parse-to-number? price-91)
                          (dollars->cents
                           price-91)
                          price-91)}))
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
          ]
      [:div {:class "row"}
       [:div {:class "col-lg-12"}
        [KeyVal "Name" (:name zone)]
        [KeyVal "Rank" (:rank zone)]
        [KeyVal "Active" (if (:active zone)
                           "Yes"
                           "No")]
        (when (or  gas-price-87
                   gas-price-91)
          [KeyVal "Gas Price" (str
                               "87 "
                               (if (int? gas-price-87)
                                 (str "$" (cents->dollars gas-price-87))
                                 gas-price-87)
                               " | "
                               "91 "
                               (if (int? gas-price-91)
                                 (str "$" (cents->dollars gas-price-91))
                                 gas-price-91))])
        [KeyVal "Zip Codes" (:zips zone)]
        (when (get-in zone [:config :hours])
          [KeyVal "Hours" (-> hours
                              (server-day-hours->form-day-hours)
                              (form-zone-hours->hrf-string))])]])))

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
    (reset! from-minutes (minute-count->minutes from-time))
    (reset! from-period (:id (minute-count->time-period-option from-time)))
    ;; to
    (reset! to-hours (minute-count->standard-hours to-time))
    (reset! to-minutes (minute-count->minutes to-time))
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
        (.log js/console "hours: " (clj->js @hours))
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

(defn insert-time!
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

(defn delete-time!
  "Delete a time from days-atom"
  [day days-atom hours-map-atom]
  (let [day-hours (r/cursor days-atom [day])
        hour-id (:id @hours-map-atom)]
    (swap! day-hours dissoc hour-id)))

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
      (if-not (= hour-id "t0")
        [:a {:href "#"
             :on-click (fn [e]
                         (.preventDefault e)
                         (delete-time! day days-atom hours-map-atom))}
         "(-)"]))))

(defn DaysTimeRangeComp
  [hours zone]
  (let [days-atom (r/cursor zone [:config :hours])]
    (fn [hours]
      (let []
        (.log js/console "days-atom:" (clj->js @days-atom))
        (.log js/console "converted days-atom"
              (clj->js (form-day-hours->server-day-hours @days-atom)))
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
          price-87 (r/cursor gas-price [:87])
          price-91 (r/cursor gas-price [:91])]
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
         [TextInput {:value @rank
                     :on-change #(let [value (get-input-value %)]
                                   (reset! rank (if (parse-to-number? value)
                                                  (js/parseInt value)
                                                  value)))}]]
        ;; active
        [FormGroup {:label "Active"
                    :errors (:active @errors)}
         [:input {:type "checkbox"
                  :checked @active
                  :style {:margin-left "4px"}
                  :on-change (fn [e] (reset!
                                      active
                                      (-> e
                                          (.-target)
                                          (.-checked))))}]]
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
           ;; hours defined
           [:div [:button {:type "button"
                           :class "btn btn-sm btn-default"
                           :on-click (fn [e]
                                       (.preventDefault e)
                                       (swap! config dissoc :gas-price))}
                  "Remove Gas Prices"]
            ;; 87 Price
            [FormGroup {:label-for "87 price"
                        :label "87 Octane"
                        :errors (:price-87 @errors)
                        :input-group-addon [:div {:class "input-group-addon"}
                                            "$"]}
             [TextInput {:value @price-87
                         :on-change #(reset! price-87 (-> %
                                                          (aget "target")
                                                          (aget "value")))}]]
            ;; 91 price
            [FormGroup {:label-for "91 price"
                        :label "91 Octane"
                        :errors (:price-91 @errors)
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
            [DaysTimeRangeComp @hours zone]])]

        ]])))

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
                          :zips "Zip Codes"
                          :gas-price "Gas Price"
                          :hours "Hours"}
            diff-msg-gen (fn [edit current]
                           (diff-message
                            edit
                            current
                            diff-key-str))
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
                                       ))
            diff-msg-gen-zone (fn [edit-zone current-zone]
                                (.log js/console "edit-zone: "
                                      (clj->js edit-zone))
                                (.log js/console "current-zone: "
                                      (clj->js (server-zone->form-zone
                                                current-zone)))
                                (diff-msg-gen
                                 (zone->diff-msg-zone edit-zone)
                                 (zone->diff-msg-zone
                                  (server-zone->form-zone current-zone))))
            confirm-msg (fn []
                          [:div (str "The following changes will be made to "
                                     (:name @current-zone))
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
                                  ;; reset edit zone
                                  (reset! edit-zone
                                          (server-zone->form-zone @zone))
                                  ;; get rid of alert-success
                                  (reset! alert-success "")
                                  (reset! editing? true))))
            confirm-on-click (fn [_]
                               (.log js/console "edit-zone" (clj->js @edit-zone))
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
                         (reset! edit-zone current-zone)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:div
         (when-not @editing?
           [DisplayedZoneComp @current-zone])
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
                   (not-every? nil? (diff-msg-gen @edit-zone @current-zone)))
            [ConfirmationAlert
             {:confirmation-message confirm-msg
              :cancel-on-click dismiss-fn
              :confirm-on-click confirm-on-click
              :retrieving? retrieving?}]
            (reset! confirming? false))
          (when-not (empty? @alert-success)
            [AlertSuccess {:message @alert-success
                           :dismiss #(reset! alert-success)}])]]))))

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
                             (when (:gas-price config)
                               [:h4 "Gas Price: "
                                (form-config-gas-price->hrf-string
                                 (:gas-price config))])
                             [:h4 "Zip Codes: " zips]
                             (when (:hours config)
                               [:h4 "Hours: "
                                (form-zone-hours->hrf-string (:hours config))])
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
       (conj props {:keyword :rank})
       "Rank"]
      [TableHeadSortable
       (conj props {:keyword :active})
       "Active"]
      [TableHeadSortable
       (conj props {:keyword :name})
       "Name"]
      [TableHeadSortable
       (conj props {:keyword :zip_count})
       "# of Zips"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Zips"]]]))

(defn zone-row
  "A table row for a zone."
  [current-zone]
  (fn [zone]
    [:tr {:class (when (= (:id zone)
                          (:id @current-zone))
                   "active")
          :on-click (fn [_]
                      (reset! current-zone zone)
                      (reset! (r/cursor state [:editing?]) false)
                      (reset! (r/cursor state [:alert-success]) ""))}
     ;; Rank
     [:td (-> zone
              :rank)]
     ;; Active
     [:td (if (-> zone
                  :active)
            "Yes"
            "No")]
     ;; name
     [:td (-> zone
              :name)]
     ;; # of zips
     [:td (-> zone
              :zip_count)]
     ;; Zips
     [:td {:style {:overflow "scroll"}}
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
        page-size 15]
    (fn [zones]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            displayed-zones zones
            sorted-zones (fn []
                           (->> displayed-zones
                                sort-fn
                                ;; we're not displaying the Earth Zone
                                (filter #(not= (:id %) 1))
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
                                           (first (paginated-zones))))]
        (when (nil? @current-zone)
          (table-pager-on-click))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            (when (subset? #{{:uri "/zone"
                              :method "PUT"}}
                           @accessible-routes)
              [:div {:class "panel-body"}
               [:h2 [:i {:class "fa fa-circle"
                         :style {:color
                                 (:color @current-zone)}}]
                (str " " (:name @current-zone))]
               [EditZoneFormComp current-zone]])]]
          [:br]
          [:div {:class "row"}
           [:div {:class "col-lg-12"}
            [:div [:h3 {:class "pull-left"
                        :style {:margin-top "4px"
                                :margin-bottom 0}}
                   "Zones"]]
            [:div {:class "btn-toolbar"
                   :role "toolbar"}
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
            [CreateZoneFormComp]]]]]))))
