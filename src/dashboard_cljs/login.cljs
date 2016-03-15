(ns dashboard-cljs.login
  (:require [crate.core :as crate]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.cookies :as cookies]
            [dashboard-cljs.utils :refer [base-url]]))

(defn process-login
  "Process the response used when logging in"
  [response]
  (let [error-div (.querySelector js/document "#error-message")
        cljs-response (js->clj response :keywordize-keys true)]
    (if (:success cljs-response)
      (do
        (cookies/set-cookie! "token" (:token cljs-response))
        (cookies/set-cookie! "user-id" (get-in cljs-response
                                               [:user :id]))
        (aset js/window "location" base-url))
      (aset error-div "textContent"
            (str "Error: " (:message cljs-response))))))

(defn login-form
  "A form for logging into the dashboard"
  []
  (let [submit-button (crate/html
                       [:button {:id "login"
                                 :type "submit"
                                 :class "btn btn-default"
                                 } "Login"])
        login-form (crate/html
                    [:div {:id "login-form"}
                     [:div {:class "form-group"}
                      [:label {:for "email-address"}
                       "Email Address"]
                      [:input
                       {:id "email"
                        :type "text"
                        :class "form-control"
                        :placeholder "Email"}]]
                     [:div {:class "form-group"}
                      [:label {:for "password"} "Password"]
                      [:input
                       {:id "password"
                        :type "password"
                        :class "form-control"
                        :placeholder "Password"}]]
                     submit-button
                     [:div {:class "has-error"}
                      [:div {:id "error-message"
                             :class "control-label"}]]])]
    (.addEventListener
     submit-button
     "click"
     #(let [email (aget (.querySelector js/document "#email")
                        "value")
            password (aget (.querySelector js/document "#password")
                           "value")]
        (retrieve-url
         (str base-url "login")
         "POST"
         (js/JSON.stringify (clj->js {:email email
                                      :password password}))
         (partial xhrio-wrapper process-login))))
    login-form))

(defn login
  "Set up the login form"
  []
  (let [login-div (.getElementById js/document "login")]
    (.appendChild login-div
                  (crate/html
                   [:div {:class "container-fluid"}
                    [:div {:class "row"}
                     [:div {:class "col-lg-6"}
                      [:div {:class "panel panel-default"}
                       [:div {:class "panel-body"}
                        (login-form)]]]]]))))
