(ns dashboard-cljs.components
  (:require [reagent.core :as r]
            [cljsjs.pikaday.with-moment]
            [dashboard-cljs.datastore :as datastore]))

;; Reagent components

(defn CountPanel
  "Props is of the form:
  {:value       ; str, to display
   :caption     ; string, that describe the maps in set-atom
   :panel-class ; string, additional classes to assign to root div
                 ex: panel-primary results in a blue panel
                     panel-green   results in a green panel
  }
  Returns a panel that reports (count (:set-atom props))
  "
  [props]
  (fn [{:keys [value caption panel-class]} props]
    [:div
     [:span {:class "huge"} value]
     (str " " caption)]))

;; Table components

(defn StaticTable
  "props contains:
  {
  :table-header  ; reagenet component to render the table header with
  :table-row     ; reagent component to render a row
  }
  data is the reagent atom to display with this table."
  [props data]
  (fn [props data]
    (let [table-data data
          sort-fn   (if (nil? (:sort-fn props))
                      (partial sort-by :id)
                      (:sort-fn props))]
      [:table {:class "table table-bordered table-hover table-striped"}
       (:table-header props)
       [:tbody
        (map (fn [element]
               ^{:key (:id element)}
               [(:table-row props) element])
             data)]])))

(defn TableHeadSortable
  "props is:
  {
  :keyword        ; keyword associated with this field to sort by
  :sort-keyword   ; reagent atom keyword
  :sort-reversed? ; is the sort being reversed?
  }
  text is the text used in field"
  [props text]
  (fn [props text]
    [:th
     {:class "fake-link"
      :on-click #(do
                   (reset! (:sort-keyword props) (:keyword props))
                   (swap! (:sort-reversed? props) not))}
     text
     (when (= @(:sort-keyword props)
              (:keyword props))
       [:i {:class (str "fa fa-fw "
                        (if @(:sort-reversed? props)
                          "fa-angle-down"
                          "fa-angle-up"))}])]))
(defn RefreshButton
  "props is:
  {
  :refresh-fn ; fn, called when the refresh button is pressed
              ; is a function of refreshing? which is essentially
              ; just the status of the button
  }"
  [props]
  (let [refreshing? (r/atom false)]
    (fn [props]
      [:button
       {:type "button"
        :class "btn btn-default"
        :on-click
        #(when (not @refreshing?)
           ((:refresh-fn props) refreshing?))}
       [:i {:class (str "fa fa-lg fa-refresh "
                        (when @refreshing?
                          "fa-pulse"))}]])))

(defn KeyVal
  "Display key and val"
  [key val]
  (fn [key val]
    [:h5 [:span {:class "info-window-label"}
          (str key ": ")]
     val]))

(defn StarRating
  "Given n, display the star rating. The value of n is assumed to be within
  the range of 0-5"
  [n]
  (fn [n]
    [:div
     (for [x (range n)]
       ^{:key x} [:i {:class "fa fa-star fa-lg"}])
     (for [x (range (- 5 n))]
       ^{:key x} [:i {:class "fa fa-star-o fa-lg"}])]))

(defn ErrorComp
  "Given an error message, display it in an alert box"
  [props]
  (fn [{:keys [error-message dismiss-fn]} props]
    [:div {:class "alert alert-danger alert-dismissible"
           :role "alert"}
     (when dismiss-fn
       [:button {:type "button"
                 :class "close"
                 :aria-label "Close"}
        [:i {:class "fa fa-times"
             :on-click dismiss-fn}]])
     [:span {:class "sr-only"} "Error:"]
     error-message]))

(defn DatePicker
  "A component for picking dates. exp-date is an r/atom
  which is a unix epoch number"
  [exp-date]
  (let [pikaday-instance (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (reset!
         pikaday-instance
         (js/Pikaday.
          (clj->js {:field (r/dom-node this)
                    :format "M/D/YYYY"
                    :onSelect (fn [input]
                                (reset! exp-date
                                        (-> (js/moment input)
                                            (.endOf "day")
                                            (.unix))))
                    :onOpen #(when (not (nil? @pikaday-instance))
                               (.setMoment @pikaday-instance
                                           (-> @exp-date
                                               (js/moment.unix)
                                               (.endOf "day"))))}))))
      :reagent-render
      (fn []
        [:input {:type "text"
                 :class "form-control date-picker"
                 :placeholder "Choose Date"
                 :defaultValue (-> @exp-date
                                   (js/moment.unix)
                                   (.endOf "day")
                                   (.format "M/D/YYYY"))
                 :value (-> @exp-date
                            (js/moment.unix)
                            (.endOf "day")
                            (.format "M/D/YYYY"))
                 :on-change (fn [input]
                              (reset! exp-date
                                      (-> (js/moment input)
                                          (.endOf "day")
                                          (.unix))))}])})))
(defn TablePager
  "props is:
  {:total-pages  integer ; the amount of pages
   :current-page integer ; r/atom, the current page number we are on
  }"
  [props]
  (fn [{:keys [total-pages current-page]} props]
    (let [page-width 5
          pages (->> (range 1 (+ 1 total-pages))
                     (partition-all page-width))
          displayed-pages (->> pages
                               (filter (fn [i] (some #(= @current-page %) i)))
                               first)]
      ;; prevent overshooting of current-page for tables
      ;; of different sizes
      (when (> @current-page displayed-pages)
        (reset! current-page 1))
      (when (> total-pages 1)
        [:nav
         [:ul {:class "pagination"}
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! current-page 1))}
            ;; the symbol here is called a Guillemet
            ;; html character entity reference &laquo;
            "«"]]
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click
                (fn [e]
                  (.preventDefault e)
                  (let [new-current-page (- (first displayed-pages) 1)]
                    (if (< new-current-page 1)
                      (reset! current-page 1)
                      (reset! current-page new-current-page))))}
            ;; html character entity reference &lsaquo;
            "‹"]]
          (doall (map (fn [page-number]
                        ^{:key page-number}
                        [:li {:class
                              (str "page-item "
                                   (when (= page-number
                                            @current-page)
                                     "active ")
                                   (when (= 1)))}
                         [:a {:href "#"
                              :class "page-link"
                              :on-click (fn [e]
                                          (.preventDefault e)
                                          (reset! current-page page-number))}
                          page-number]])
                      ;;(range 1 (+ 1 total-pages))
                      displayed-pages))
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (let [new-current-page (+ (last displayed-pages) 1)]
                              (if (> new-current-page total-pages)
                                (reset! current-page total-pages)
                                (reset! current-page new-current-page))))}
            ;; html character entity reference &rsaquo;
            "›"]]
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! current-page total-pages))}
            ;; html character entity reference &raquo;
            "»"]]]]))))

(defn ConfirmationAlert
  "An alert for confirming or cancelling an action.
  props is
  {
  :cancel-on-click      fn  ; user clicks cancel, same fn used for dismissing
                            ; alert
  :confirm-on-click     fn  ; user clicks confirm
  :confirmation-message str ; message to display
  :retrieving?       r/atom ; boolean, are we still retrieving from the server?
  }"
  [props]
  (fn [{:keys [cancel-on-click confirm-on-click
               confirmation-message retrieving?]} props]
    [:div {:class "alert alert-danger alert-dismissible"}
     [:button {:type "button"
               :class "close"
               :aria-label "Close"}
      [:i {:class "fa fa-times"
           :on-click cancel-on-click}]]
     [:div
      [confirmation-message]
      (when (not @retrieving?)
        [:div
         [:button {:type "button"
                   :class "btn btn-default"
                   :on-click confirm-on-click}
          "Yes"]
         [:button {:type "button"
                   :class "btn btn-default"
                   :on-click cancel-on-click}
          "No"]])
      (when @retrieving?
        [:i {:class "fa fa-spinner fa-pulse"}])]]))

(defn TableFilterButton
  "Filter button for btn-group. Shows number of records that meet filter."
  [props data selected-filter]
  (fn [{:keys [text filter-fn hide-count]} data selected-filter]
    [:button {:type "button"
              :class (str "btn btn-default "
                          (when (= @selected-filter text) "active"))
              :on-click #(reset! selected-filter text)}
     text
     (when-not hide-count
       (str " (" (count (filter filter-fn data)) ")"))]))

(defn TableFilterButtonGroup
  "Group of filter buttons for a table."
  [props filters data selected-filter]
  (fn [{:keys [hide-counts]} filters data selected-filter]
    [:div {:class "btn-group" :role "group"}
     (for [f (map #(hash-map :text (key %)
                             :filter-fn (val %)
                             :hide-count (contains? hide-counts (key %)))
                  filters)]
       ^{:key (:text f)} [TableFilterButton f data selected-filter])]))

(defn LoadScreen
  ""
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

(defn AlertSuccess
  "An alert for when an action is successfully completed
  props is
  {
  :message str ; message to display
  :dismiss fn  ; user dismisses this dialgoue
  }"
  [props]
  (fn [{:keys [message dismiss]} props]
    [:div {:class "alert alert-success alert-dismissible"}
     [:button {:type "button"
               :class "close"
               :aria-label "Close"}
      [:i {:class "fa fa-times"
           :on-click dismiss}]]
     [:strong message]]))

(defn TextInput
  "props is:
  {
  :value          ; str
  :default-value  ; str
  :placeholder    ; str, optional
  :on-change      ; fn, fn to execute on change
  }
  "
  [props]
  (fn [{:keys [value default-value placeholder on-change]} props]
    [:input {:type "text"
             :class "form-control"
             :value value
             :defaultValue default-value
             :placeholder placeholder
             :on-change on-change}]))

(defn TextAreaInput
  "props is:
  {
  :value          ; str
  :default-value  ; str
  :placeholder    ; str, optional
  :on-change      ; fn, fn to execute on change
  :rows           ; number
  :cols           : number
  }
  "
  [props]
  (fn [{:keys [value default-value placeholder on-change rows cols]} props]
    [:textarea {:class "form-control"
                :rows rows
                :cols cols
                :value value
                :defaultValue default-value
                :placeholder placeholder
                :on-change on-change}]))

(defn Select
  "props is:
  {
  :value        ; r/atom, id of the currently selected atom
  :options      ; set of maps, #{{:id <value> :display-key str}, ...}
  :display-key  ; keyword, associated value is displayed
  :sort-keyword ; keyword, optional
  }
  "
  [props]
  (fn [{:keys [value options display-key sort-keyword]} props]
    (let [sort-keyword (or sort-keyword :id)]
      [:select
       {:value @value
        :on-change
        #(do (reset! value
                     (-> %
                         (aget "target")
                         (aget "value"))))}
       (map
        (fn [option]
          ^{:key (:id option)}
          [:option
           {:value (:id option)}
           (display-key option)])
        (sort-by sort-keyword options))])))

(defn FormGroup
  "props is:
  {
  :label                 ; str
  :label-for             ; str
  :errors                ; str
  :input-group-addon     ; optional, hiccup vector
  :input-container-class ; optional, str
  }
  input is hiccup-stype reagent input
  "
  [props input]
  (fn [props input]
    (let [{:keys [label label-for errors input-group-addon
                  input-container-class]} props]
      [:div {:class "form-group"
             :style {:margin-left "1px"}}
       [:label {:for label-for} label]
       input
       (when errors
         [:div {:class "alert alert-danger"}
          (first errors)])])))

(defn FormSubmit
  [button]
  (fn [button]
    [:div {:class "form-group"}
     button]))

(defn ProcessingIcon
  []
  (fn []
    [:i {:class "fa fa-lg fa-spinner fa-pulse "}]))

(defn EditFormSubmit
  [props]
  (fn [{:keys [retrieving? editing? on-click edit-btn-content]} props]
    [:button {:type "submit"
              :class "btn btn-sm btn-default"
              :disabled @retrieving?
              :on-click on-click}
     (cond @retrieving?
           [ProcessingIcon]
           @editing?
           "Save"
           (not @editing?)
           edit-btn-content)]))

(defn DismissButton
  "props is
  {
  dismiss-fn ; fn
  class      ; string
  }"
  [props]
  (fn [{:keys [dismiss-fn class]} props]
    (let [class (or class "btn btn-sm btn-default")]
      [:button {:type "button"
                :class class
                :on-click dismiss-fn}
       "Dismiss"])))

(defn SubmitDismiss
  [props submit dismiss]
  (fn [props submit dismiss]
    (let [{:keys [editing? retrieving?]} props]
      [:div {:class "btn-toolbar"}
       ;; edit button
       [:div {:class "btn-group"}
        submit]
       [:div {:class "btn-group"}
        ;; dismiss button
        (when-not (or @retrieving? (not @editing?))
          dismiss)]])))

(defn SubmitDismissGroup
  [props]
  (fn [{:keys [editing? retrieving? submit-fn dismiss-fn edit-btn-content]}
       props]
    (let [edit-btn-content (or edit-btn-content "Edit")]
      [SubmitDismiss
       {:editing? editing?
        :retrieving? retrieving?}
       [EditFormSubmit {:retrieving? retrieving?
                        :editing? editing?
                        :on-click submit-fn
                        :edit-btn-content edit-btn-content}]
       [DismissButton {:dismiss-fn dismiss-fn}]])))

(defn SubmitDismissConfirm
  [props submit-dismiss]
  (fn [{:keys [confirming?]} props]
    (when-not @confirming?
      submit-dismiss)))

(defn SubmitDismissConfirmGroup
  [props]
  (fn [{:keys [confirming? editing? retrieving? submit-fn dismiss-fn
               edit-btn-content]} props]
    [SubmitDismissConfirm {:confirming? confirming?}
     [SubmitDismissGroup {:editing? editing?
                          :retrieving? retrieving?
                          :submit-fn submit-fn
                          :dismiss-fn dismiss-fn
                          :edit-btn-content edit-btn-content}]]))

(defn ViewHideButton
  "A button toggling view/hide of information
  prop is:
  {
  :class        ; str, class for the button
  :view-content ; str, display when @view? is true
  :hide-content ; str, display when @view? is false
  :on-click     ; fn
  :view?        ; r/atom, boolean
  }
  "
  [props]
  (fn [{:keys [class view-content hide-content on-click view?]} props]
    [:button {:type "button"
              :class class
              :on-click on-click}
     (if @view?
       hide-content
       view-content)]))

(defn TelephoneNumber
  "A component that transforms a number into a href tel"
  [number]
  (fn [number]
    [:a {:href (str "tel:" number)} number]))

(defn Mailto
  "A component that transforms email into a href mailto"
  [email]
  (fn [email]
    [:a {:href (str "mailto:" email)} email]))

(defn GoogleMapLink
  "Given a lat, lng create google map link using tex"
  [text lat lng]
  [:a {:href (str "https://maps.google.com/?q=" lat "," lng)
       :target "_blank"}
   text])
