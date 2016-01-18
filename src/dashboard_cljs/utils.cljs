(ns dashboard-cljs.utils
  (:require [cljsjs.moment]))

(defn unix-epoch->fmt
  "Convert a unix epoch (in seconds) to fmt"
  [unix-epoch fmt]
  (-> (js/moment.unix unix-epoch)
      (.format fmt)))

(defn unix-epoch->hrf
  "Convert a unix epoch (in seconds) to a human readable format"
  [unix-epoch]
  (unix-epoch->fmt unix-epoch "M/D h:mm A"))

(defn unix-epoch->fuller
  "Convert a unix epoch (in seconds)"
  [unix-epoch]
  (unix-epoch->fmt unix-epoch "M/D/YYYY h:mm A"))

(defn cents->dollars
  "Converts an integer value of cents to dollar string"
  [cents]
  (str "$" (-> cents (/ 100) (.toFixed 2))))

(defn continuous-update
  "Call f continously every n seconds"
  [f n]
  (js/setTimeout #(do (f)
                      (continuous-update f n))
                 n))



(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(defn json-string->clj
  "Convert a JSON string to a clj object"
  [s]
  (js->clj (JSON.parse s) :keywordize-keys true))

;; widely published fn,
;; see:
;; https://dzone.com/articles/clojure-apply-function-each
;;http://blog.jayfields.com/2011/08/clojure-apply-function-to-each-value-of.html
(defn update-values [m f & args]
  "Update all values in map with f and args"
  (reduce (fn [r [k v]] (assoc r k (apply f v args))) {} m))

(defn get-by-id
  "Get an element by its id from coll"
  [coll id]
  (first (filter #(= (:id %) id) coll)))
