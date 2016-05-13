(ns dashboard-cljs.utils
  (:require [cljsjs.moment]
            [moment-timezone]
            [cljs.reader :refer [read-string]]
            [clojure.data :refer [diff]]
            [clojure.string :as s]
            [reagent.core :as r]))

(def timezone (r/atom "America/Los_Angeles"))

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
  "Get an element by its id from coll."
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

(defn parse-timestamp
  "Given a mysql timestamp, convert it to unix epoch seconds"
  [timestamp]
  (quot (.parse js/Date timestamp) 1000))

(defn new-orders-count
  "Given a set of orders and a last-acknowledged-order, return the
  amount of new orders"
  [orders last-acknowledged-order]
  (- (count orders)
     (count (filter
             #(<= (parse-timestamp (:timestamp_created %))
                  (parse-timestamp (:timestamp_created
                                    last-acknowledged-order)))
             orders))))

(defn same-timestamp?
  "Given two maps with a :timestamp_created key, determine if they have the same
  value"
  [m1 m2]
  (= (parse-timestamp (:timestamp_created m1))
     (parse-timestamp (:timestamp_created m2))))

(defn oldest-current-order
  "Given a coll of orders, return the oldest order that is is not complete
  or cancelled"
  [orders]
  (->> orders
       (filter (every-pred
                #(not= "complete" (:status %))
                #(not= "cancelled" (:status %))))
       (sort  #(compare (:timestamp_created %1)
                        (:timestamp_created %2)))
       first))

(defn declined-payment?
  "Was the payment (if any) on this order declined?"
  [order]
  (and (= (:status order) "complete")
       (not= 0 (:total_price order))
       (not (:paid order))))

(defn current-order?
  "Is this a order that is currently in process?"
  [order]
  (contains? #{"unassigned"
               "assigned"
               "accepted"
               "enroute"
               "servicing"}
             (:status order)))

(defn expired-coupon?
  [coupon]
  (<= (:expiration_time coupon)
      (-> (js/moment)
          (.unix))))

(defn get-event-time
  "Get time of event from event log as unix timestamp integer.
  If event hasn't occurred yet, then nil."
  [event-log event]
  (some-> event-log
          (#(if (s/blank? %) nil %))
          (s/split #"\||\s")
          (->> (apply hash-map))
          (get event)
          js/parseInt))

;; http://stackoverflow.com/questions/2901102/how-to-print-a-number-with-commas-as-thousands-separators-in-javascript
(defn integer->comma-sep-string
  "Insert commas into an integer number and return a string representation"
  [number]
  (-> number .toString
      (.split ".")
      first
      (clojure.string/replace #"\B(?=(\d{3})+(?!\d))" ",")))

(defn diff-message
  "Given old and new hashmap, create message based on their diff"
  [old new key-str]
  (let [[new-map old-map unchanged] (clojure.data/diff old new)
        concern (into [] (keys key-str))]
    (filter
     (comp not nil?)
     (map #(when-not (contains? unchanged %)
             (str (% key-str) " : " (% old-map) " -> " (% new-map)))
          concern))))

(defn now
  "Get the current time in unix epoch seconds"
  []
  (.unix (js/moment)))

(defn orders-per-hour
  "Given orders, count the amount of orders placed at each hour. return the
  result in a coll of hashmaps."
  [orders]
  (let [order-count-per-hour-non-zero
        (map #(hash-map (key %) (count (val %)))
             (group-by #(unix-epoch->fmt
                         (:target_time_start %) "h:00 A") orders))
        hours-of-day-in-seconds (map #(unix-epoch->fmt % "h:00 A")
                                     (range 0 (* 24 60 60) (* 60 60)))]
    (->>
     (merge-with +
                 (apply merge
                        (map #(hash-map (key %) (count (val %)))
                             (group-by #(unix-epoch->fmt (:target_time_start %)
                                                         "h:00 A") orders)))
                 (apply merge (map #(hash-map % 0) hours-of-day-in-seconds)))
     (sort-by #(.format (js/moment. (first %) "h:mm A") "HH:mm")))))

;; (clj->js (into [] (map first (orders-per-hour (orders-within-dates @dashboard-cljs.datastore/orders 1459468800 1462165199)))))

(defn orders-per-day
  "Given orders, count the amount of orders completed each day. return the
  result in a coll of hashmaps."
  [orders]
  (let [completed-order-count-per-day-non-zero
        (map #(hash-map (key %) (count (val %)))
             (group-by
              #(unix-epoch->fmt
                ;;(get-event-time (:event_log %) "complete")
                (:target_time_start %)
                "M-D-YYYY") orders))]
    ;; assuming there are orders everyday, for now
    (sort-by first completed-order-count-per-day-non-zero)))
