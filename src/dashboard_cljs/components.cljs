(ns dashboard-cljs.components
  (:require [reagent.core :as r]
            [dashboard-cljs.datastore :as datastore]))

;; Reagent components

(defn count-panel
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
