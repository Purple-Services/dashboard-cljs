(ns dashboard-cljs.login
  (:require [crate.core :as crate]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.cookies :as cookies]
            ))

(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(defn process-login
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
  []
  (let [submit-button (crate/html
                       [:input {:id "login" :type "submit" :value "Login"}])
        login-form (crate/html
                    [:div {:id "login-form"}
                     [:div "Email Address: " [:input
                                              {:id "email" :type "text"}]]
                     [:div "Password: " [:input
                                         {:id "password" :type "password"}]]
                     submit-button
                     [:div {:id "error-message"}]])]
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
  []
  (let [login-div (.getElementById js/document "login")]
    (.appendChild login-div (login-form))))
