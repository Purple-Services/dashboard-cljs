(require '[cljs.build.api :as b])

(b/watch "src"
  {:main 'dashboard-cljs.core
   :output-to "out/dashboard_cljs.js"
   :output-dir "out"})
