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
  "Convert a unix epoch (in seconds"
  [unix-epoch]
  (unix-epoch->fmt unix-epoch "M/D/YYYY h:mm A"))

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
