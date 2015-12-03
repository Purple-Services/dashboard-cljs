(ns dashboard-cljs.xhr
  (:require [goog.net.XhrIo]))

(defn send-xhr
  "Send a xhr to url using callback and HTTP method."
  [url callback method & [data headers timeout]]
  (.send goog.net.XhrIo url callback method data headers timeout))

(defn xhrio-wrapper
  "A callback for processing the xhrio response event. If
  response.target.isSuccess() is true, call f on the json response"
  [f response]
  (let [target (.-target response)]
    (if (.isSuccess target)
      (f (.getResponseJson target))
      (.log js/console
            (str "xhrio-wrapper error:" (aget target "lastError_"))))))

(defn retrieve-url
  "Retrieve and process json response with f from url using HTTP method and json
  data. Optionally, define a timeout in ms."
  [url method data f & [timeout]]
  (let [header (clj->js {"Content-Type" "application/json"})]
    (send-xhr url f "POST" data header timeout)))
