(ns dashboard-cljs.components
  (:require [reagent.core :as r]
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
