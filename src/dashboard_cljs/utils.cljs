(ns dashboard-cljs.utils
  (:require [cljsjs.moment]
            [cljs.reader :refer [read-string]]
            [clojure.string :as s]
            [reagent.core :as r]))

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
  "Converts an integer value of cents to dollars"
  [cents]
  (str (-> cents (/ 100) (.toFixed 2))))

(defn dollars->cents
  "Convert a dollar amount into cents"
  [dollars]
  (->  (* dollars 100) .toFixed js/parseInt))

(defn cents->$dollars
  "Converts an integer value of cents to dollar string"
  [cents]
  (str "$" (cents->dollars cents)))

(defn continuous-update
  "Call f continuously every n seconds"
  [f n]
  (js/setTimeout #(do (f)
                      (continuous-update f n))
                 n))

(defn continuous-update-until
  "Call f continuously every n seconds until pred is satisified. pred must be
  a fn."
  [f pred n]
  (js/setTimeout #(when (not (pred))
                    (f)
                    (continuous-update-until f pred n))
                 n))

(def base-url (-> (.getElementById js/document "base-url")
                  (.getAttribute "value")))

(def accessible-routes (r/atom #{}))

(def markets
  {0 "Los Angeles"
   1 "San Diego"
   2 "Orange County"
   3 "Seattle"})

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

(defn format-coupon-code
  "Capitilize and remove spaces from string code"
  [code]
  (s/replace (s/upper-case code) #" " ""))

(defn parse-to-number?
  "Will s parse to a number?
  ex:
  (parse-to-number? '1234') => true
  (parse-to-number? '1234aasdf') => false

  note: returns false for blank strings
  "
  [s]
  (if (s/blank? s)
    false
    (-> s
        js/Number
        js/isNaN
        not)))

(defn pager-helper!
  "When the amount of pages that a pager changes inside of a component,
  it is possible to overshot @current-page so that it points to an empty
  page collection.

  Given part-col and current-page atom, return an empty list if
  part-col is empty,  the corresponding partition if there is one,
  or reset the current-page to 1 and return the first partition of
  part-col"
  [part-col current-page]
  (let [part (nth part-col (- @current-page 1) '())]
    (cond
      (empty? part-col)
      '()
      ;; the part-col itself is not empty
      (empty? part)
      (do (reset! current-page 1)
          (nth part-col (- @current-page 1)))
      :else
      part)))
