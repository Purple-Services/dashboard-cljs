(ns dashboard-cljs.datastore
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [chan pub put! sub <! >!]]
            [clojure.set :refer [difference intersection project union
                                 subset?]]
            [reagent.core :as r]
            [cljsjs.moment]
            [dashboard-cljs.utils :refer [base-url continuous-update get-by-id
                                          accessible-routes oldest-current-order
                                          parse-timestamp]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]))

;; This namespace contains the global state of the app and the fn's associated
;; with managing this data.

;; There are a few large sets of data, stored in an r/atom, upon which the app
;; is built around. These include sets such as users and couriers. The data in
;; these sets is managed by sync-state!
;;
;; data flow for large sets
;;
;; reagent atoms are used to read data
;; r/atom -> components
;;
;; core.async channels are used to modify data
;; r/atom <- sync-state! <- chan <- component, fn's, etc.
;;
;; Currently, new data is put! on 'modify-data-chan'
;; as a map. :topic corresponds to the dataset atom to update.
;; :data is a coll of maps.
;;
;; ex.:
;; (put! modify-data-chan
;;       {:topic "orders"
;;        :data order})
;;
;; "orders" is the name of atom which contains the data set.
;;  order   is a vector with one order map

(defn set-ids
  "Given a set, return a set of just the vals for key :id"
  [xrel]
  (set (map :id xrel)))

(defn get-by-ids
  "Given a set of ids, return the subset of s1 :id vals that are contained
  in ids"
  [ids s1]
  (filter #(contains? ids (:id %)) s1))

(defn sync-sets
  "Given a set s1 and s2 of maps where each map has a unique val for its :id
  keyword, return a new set with maps from s1 updated with s2.
  'updated' is defined as the union of:

  stable set - maps in s1 whose :id's vals are not in s2
  mod set    - maps whose :id's vals are shared between s1 and s2,
               taken from s2
  new set    - maps in s2 whose :id's vals are not in s1"
  [s1 s2]
  (let [new-ids    (difference (project s2 [:id]) (project s1 [:id]))
        mod-ids    (intersection (project s1 [:id]) (project s2 [:id]))
        stable-ids (difference (project s1 [:id]) mod-ids)
        new-set    (get-by-ids (set-ids new-ids) s2)
        stable-set (get-by-ids (set-ids stable-ids) s1)
        mod-set    (get-by-ids (set-ids mod-ids) s2)]
    (set (union stable-set mod-set new-set))))

;; Not currently utilized. Included for possible future use
(defn sync-element!
  "Given an atom and el, sync the element with the one in atom. If el does not
  exist in atom, add it to the set. Assumes atom is a set composed of maps with
  unique values for the key :id"
  [atom el]
  (let [atom-el (get-by-id @atom (:id el))]
    (if (nil? atom-el)
      (swap! atom conj el)
      (reset! atom (conj (disj @atom atom-el) el)))))

(defn sync-state!
  "Sync the state of atom with data from chan. atom is a set of maps. It should
  be a reagent atom so that any components derefing atom automatically update.
  Each map is assumed to have an :id key with a unique val. chan is a sub 
  channel where data is sent as the same format as atom. New values are added
  to atom, old values are used to modify the current state.

  example usage: 

  (def modify-data-chan (chan))
  (def read-data-chan (pub modify-data-chan :topic))

  (def users (r/atom #{}))

  (sync-state users (sub read-data-chan \"users\" (chan)))

  ;; put data on the channel
  (put! modify-data-chan {:topic \"users\"
                          :data #{{:id 1 :active? false}
                                  {:id 2 :active? true}}})"
  [atom chan]
  (go-loop [data (:data (<! chan))]
    (let [old-state @atom
          new-state (sync-sets old-state data)]
      (reset! atom new-state)
      (reset-meta! atom {:processed true}))
    (recur (:data (<! chan)))))

(defn sync-state-always-reset!
  "Same as sync-state!, but just always resets the entire data rather than taking
  a union."
  [atom chan]
  (go-loop [data (:data (<! chan))]
    (let [old-state @atom
          new-state data]
      (reset! atom new-state)
      (reset-meta! atom {:processed true}))
    (recur (:data (<! chan)))))

;; below are the definitions for the app's datastore

(def modify-data-chan (chan))
(def read-data-chan (pub modify-data-chan :topic))

;; orders
(def orders (r/atom #{}))
(def most-recent-order (r/atom {}))
(def last-acknowledged-order (r/atom {}))

;; fleet
(def fleet-deliveries (r/atom #{}))

;; couriers
(def couriers (r/atom #{}))

(defn sync-couriers!
  []
  (retrieve-url
   (str base-url "couriers")
   "POST"
   {}
   (partial xhrio-wrapper
            (fn [response]
              (put! modify-data-chan
                    {:topic "couriers"
                     :data (:couriers (js->clj response :keywordize-keys
                                               true))})))))
;; users
(def users (r/atom #{}))

;; coupons
(def coupons (r/atom #{}))

;; zones
(def zones (r/atom #{}))

(defn retrieve-zones!
  []
  (retrieve-url
   (str base-url "zones")
   "GET"
   {}
   (partial xhrio-wrapper
            (fn [response]
              (put! modify-data-chan
                    {:topic "zones"
                     :data (js->clj response :keywordize-keys
                                    true)})))))
(defn process-orders
  "Given a set of orders, handle putting them on modify-data-chan
  and resetting the most-recent-order and last-acknowledged-order atoms"
  [orders initializing?]
  (put! modify-data-chan
        {:topic "orders"
         :data orders})
  ;; update the most recent order atom
  (reset!
   most-recent-order
   (last (sort-by
          :target_time_start orders)))
  ;; initialize the last-acknowledged-order atom
  (when initializing?
    (reset!
     last-acknowledged-order
     (last (sort-by
            :target_time_start orders)))))

(defn init-datastore
  "Initialize the datastore for the app. Should be called once when launching
  the app."
  []
  (let [orders-response-fn (fn [response & [initializing?]]
                             (let [orders (js->clj response
                                                   :keywordize-keys true)]
                               (when (> (count orders)
                                        0)
                                 (process-orders orders initializing?))))]
    ;; orders
    (when (subset? #{{:uri "/orders-since-date"
                      :method "POST"}} @accessible-routes)
      ;; keep data synced with the data channel
      (sync-state! orders (sub read-data-chan "orders" (chan)))
      ;; initialize orders
      (retrieve-url
       (str base-url "orders-since-date")
       "POST"
       (js/JSON.stringify
        (clj->js
         ;; just retrieve the last 20 days worth of orders
         {:date (-> (js/moment)
                    (.subtract 30 "days")
                    (.format "YYYY-MM-DD"))}))
       (partial xhrio-wrapper
                #(orders-response-fn % true)))
      ;; periodically check server for updates in orders
      (continuous-update
       ;; prevent retrieval of more orders
       ;; until init orders has completed
       #(when ;;(:timestamp_created @most-recent-order)
            (first @orders)
          (retrieve-url
           (str base-url "orders-since-date")
           "POST"
           (js/JSON.stringify
            (clj->js
             {:date (if-let [timestamp (:timestamp_created
                                        (oldest-current-order @orders))]
                      (parse-timestamp timestamp)
                      (parse-timestamp (:timestamp_created @most-recent-order)))
              :unix-epoch? true}))
           (partial xhrio-wrapper
                    orders-response-fn)))
       10000))
    ;; couriers
    (when (subset? #{{:uri "/couriers"
                      :method "POST"}} @accessible-routes)
      (sync-state! couriers (sub read-data-chan "couriers" (chan)))
      ;; initialize couriers
      (sync-couriers!)
      ;; key couriers in sync
      ;; note: turned this off until form is not reset
      ;; on each update of courier!
      ;; (continuous-update
      ;;  sync-couriers! 10000)
      )
    ;; users
    ;; nothing here
    ;; coupons
    (when (subset? #{{:uri "/coupons"
                      :method "GET"}} @accessible-routes)
      (sync-state! coupons (sub read-data-chan "coupons" (chan)))
      ;; initialize coupons
      (retrieve-url
       (str base-url "coupons")
       "GET"
       {}
       (partial xhrio-wrapper
                (fn [response]
                  (put! modify-data-chan
                        {:topic "coupons"
                         :data (js->clj response :keywordize-keys
                                        true)})))))
    ;; fleet
    (when (subset? #{{:uri "/fleet-deliveries-since-date"
                      :method "POST"}} @accessible-routes)
      ;; keep data synced with the data channel
      (sync-state-always-reset! fleet-deliveries (sub read-data-chan "fleet-deliveries" (chan)))
      ;; initialize orders
      (retrieve-url
       (str base-url "fleet-deliveries-since-date")
       "POST"
       (js/JSON.stringify
        (clj->js
         {:from-date (-> (js/moment)
                         (.subtract 30 "days")
                         (.endOf "day")
                         (.format "YYYY-MM-DD"))
          :to-date (-> (js/moment)
                         (.endOf "day")
                         (.format "YYYY-MM-DD"))}))
       (partial xhrio-wrapper
                (fn [response]
                  (let [parsed-data (js->clj response :keywordize-keys true)]
                    (put! modify-data-chan
                          {:topic "fleet-deliveries"
                           :data parsed-data}))))))
    ;; zones
    (when (subset? #{{:uri "/zones"
                      :method "GET"}} @accessible-routes)
      (sync-state! zones (sub read-data-chan "zones" (chan)))
      ;; initialize zones
      (retrieve-zones!)
      ;; periodically retrieval of zones
      (continuous-update
       #(when (first @zones)
          (retrieve-zones!))
       10000))))
