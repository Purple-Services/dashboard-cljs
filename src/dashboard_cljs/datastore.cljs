(ns dashboard-cljs.datastore
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [chan pub put! sub <! >!]]
            [clojure.set :refer [difference intersection project union]]
            [reagent.core :as r]
            [cljsjs.moment]
            [dashboard-cljs.utils :refer [base-url continuous-update get-by-id]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            ))

;; data flow
;;
;; reagent atoms are used to read data
;; r/atom -> components
;;
;; core.async channels are used to modify data
;; r/atom <- sync-state <- chan <- component, fn's, etc.

(defn set-ids
  "Given a set, return a set of just the vals for key :id"
  [xrel] (set (map :id xrel)))

(defn get-by-ids
  [ids s1]
  "Given a set of ids, return the subset of s1 :id vals that are contained
in ids"
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
      (reset! atom new-state))
    (recur (:data (<! chan)))))

;; below are the definitions for the app's datastore

(def modify-data-chan (chan))
(def read-data-chan (pub modify-data-chan :topic))

(def orders (r/atom #{}))

(defn init-datastore
  []
  ;; keep data synced with the data channel
  (sync-state! orders (sub read-data-chan "orders" (chan)))
  ;; periodically check server for updates in orders
  (continuous-update 
   #(retrieve-url
     (str base-url "orders-since-date")
     "POST"
     (js/JSON.stringify
      (clj->js
       {:date (-> (js/moment)
                  (.subtract 20 "days")
                  (.format "YYYY-MM-DD"))}))
     (partial xhrio-wrapper
              (fn [response]
                (put! modify-data-chan
                      {:topic "orders"
                       :data (js->clj response :keywordize-keys true)}))))
   1000))
