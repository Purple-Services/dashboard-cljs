(ns dashboard-cljs.utils
  (:require [cljsjs.moment]))

(defn unix-epoch->hrf
  "Convert a unix epoch (in seconds) to a human readable format"
  [unix-epoch]
  (-> (js/moment.unix unix-epoch)
      (.format "M/D hh:mm A")))

(defn cents->dollars
  "Converts an integer value of cents to dollar string"
  [cents]
  (str "$" (-> cents (/ 100) (.toFixed 2))))

(defn continous-update
  "Call f continously every n seconds"
  [f n]
  (js/setTimeout #(do (f)
                      (continous-update f n))
                 n))
