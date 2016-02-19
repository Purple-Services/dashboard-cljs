(ns dashboard-cljs.components
  (:require [reagent.core :as r]
            [cljsjs.pikaday.with-moment]
            [dashboard-cljs.datastore :as datastore]))

;; Reagent components

(defn CountPanel
  "Props is of the form:
{:data        coll   ; coll that count is called on 
 :description string ; string that describe the maps in set-atom
 :panel-class string ; additional classes to assign to root div
                     ; ex: panel-primary results in a blue panel
                     ;     panel-green   results in a green panel
 :icon-class         ; class for font awesome icon in panel
                     ; ex: fa-comments results in comments bubble
}
 Returns a panel that reports (count (:set-atom props))
"
  [props]
  (fn [props]
    [:div {:class (str "panel " (:panel-class props))}
     [:div {:class "panel-heading"}
      [:div {:class "row"}
       [:div {:class "col-xs-3"}
        [:i {:class (str "fa fa-5x " (:icon-class props))}]]
       [:div {:class "col-xs-9 text-right"}
        [:div {:class "huge"} (count (:data props))
         ]
        [:div (:description props)]]]]]))

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
                      (:sort-fn props))
          ]
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
  [error-message]
  (fn [error-messsage]
    [:div {:class "alert alert-danger"
           :role "alert"}
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
      (when (> total-pages 1)
        [:nav
         [:ul {:class "pagination"}
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! current-page 1))
                }
            ;; the symbol here is called a Guillemet
            ;; html character entity reference &laquo;
            "«"
            ]]
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
            "‹"
            ]]
          (doall (map (fn [page-number]
                        ^{:key page-number}
                        [:li {:class
                              (str "page-item "
                                   (when (= page-number
                                            @current-page)
                                     "active ")
                                   (when (= 1))
                                   )}
                         [:a {:href "#"
                              :class "page-link"
                              :on-click (fn [e]
                                          (.preventDefault e)
                                          (reset! current-page page-number))
                              }
                          page-number]])
                      ;;(range 1 (+ 1 total-pages))
                      displayed-pages
                      ))
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
            "›"
            ]]
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! current-page total-pages))}
            ;; html character entity reference &raquo;
            "»"]]]]))))
