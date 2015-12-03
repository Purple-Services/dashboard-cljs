(ns dashboard-cljs.cookies
  (:require [goog.net.Cookies]))

(defn get-cookie
  "Get cookie with name"
  [name]
  (let [cookie (goog.net.Cookies. js/document)]
    (.get cookie name)))

(defn set-cookie!
  "Create cookie with name and value with optional max-age"
  [name value & [max-age]]
  (let [cookie (goog.net.Cookies. js/document)
        max-age (or max-age -1)]
    (.set cookie name value max-age)))

(defn change-cookie!
  "Change the value and optionally max-age of cookie
with name. Returns nil if no cookie was set."
  [name value & [max-age]]
  (let [cookie (goog.net.Cookies. js/document)
        max-age (or max-age -1)]
    (if (.get cookie name)
      (.set cookie name value max-age)
      nil)))

(defn remove-cookie!
  "Remove the cookie with name."
  [name]
  (let [cookie (goog.net.Cookies. js/document)]
    (.remove cookie name)))
        
