(ns dashboard-cljs.marketing
  (:require [reagent.core :as r]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [base-url]]
            [dashboard-cljs.components :refer [ConfirmationAlert]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]))

(def state (r/atom {:confirming? false}))

(defn push-to-table-view-panel
  "A component for sending push notifications to users"
  []
  (let [table-view     (r/atom "InactiveUsersWithPush")
        confirming?    (r/cursor state [:confirming?])
        retrieving?    (r/atom false)
        message        (r/atom (str))
        alert-success  (r/atom (str))
        alert-error    (r/atom (str))]
    (fn []
      (let []
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:div [:h4 {:class "pull-left"} "Send Push Notification"]
           [:div {:class "btn-toolbar"
                  :role "toolbar"}
            [:div {:class "btn-group"
                   :role "group"}
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (= @table-view "InactiveUsersWithPush")
                                     "active"))
                       :on-click (fn [e]
                                   (reset! table-view "InactiveUsersWithPush")
                                   (reset! confirming? false))}
              "Inactive Users"]
             [:button {:type "button"
                       :class (str "btn btn-default "
                                   (when (= @table-view "ActiveUsersWithPush")
                                     "active"))
                       :on-click (fn [e]
                                   (reset! table-view "ActiveUsersWithPush")
                                   (reset! confirming? false))}
              "Active Users"]]]]
          (if @confirming?
            ;; confirmation
            [ConfirmationAlert
             {:cancel-on-click (fn [e]
                                 (reset! confirming? false)
                                 (reset! message ""))
              :confirm-on-click
              (fn [e]
                (reset! retrieving? true)
                (retrieve-url
                 (str base-url "send-push-to-table-view")
                 "POST"
                 (js/JSON.stringify
                  (clj->js {:message @message
                            :table-view @table-view}))
                 (partial
                  xhrio-wrapper
                  (fn [response]
                    (reset! retrieving? false)
                    (let [success? (:success
                                    (js->clj response
                                             :keywordize-keys
                                             true))]
                      (when success?
                        ;; confirm message was sent
                        (reset! alert-success "Sent!"))
                      (when (not success?)
                        (reset! alert-error
                                (str "Something went wrong."
                                     " Push notifications may or may"
                                     " not have been sent. Wait until"
                                     " sure before trying again."))))
                    (reset! confirming? false)
                    (reset! message "")))))
              :confirmation-message
              (fn [] [:div (str "Are you sure you want to send the following "
                                "message to the table view"
                                @table-view
                                "?")
                      [:h4 [:strong @message]]])
              :retrieving? retrieving?}]
            ;; Message form
            [:form
             [:div {:class "form-group"}
              [:label {:for "notification-message"} "Message"]
              [:input {:type "text"
                       :defaultValue ""
                       :class "form-control"
                       :placeholder "Message"
                       :on-change (fn [e]
                                    (reset! message (-> e
                                                        (aget "target")
                                                        (aget "value")))
                                    (reset! alert-error "")
                                    (reset! alert-success ""))}]]
             [:button {:type "submit"
                       :class "btn btn-default"
                       :on-click (fn [e]
                                   (.preventDefault e)
                                   (when (not (empty? @message))
                                     (reset! confirming? true)))}
              "Send Notification"]
             ])
          ;; alert message
          (when (not (empty? @alert-success))
            [:div {:class "alert alert-success alert-dismissible"}
             [:button {:type "button"
                       :class "close"
                       :aria-label "Close"}
              [:i {:class "fa fa-times"
                   :on-click #(reset! alert-success "")}]]
             [:strong @alert-success]])
          ;; alert error
          (when (not (empty? @alert-error))
            [:div {:class "alert alert-danger"}
             @alert-error])
          ;; selected users table
          ]]))))
