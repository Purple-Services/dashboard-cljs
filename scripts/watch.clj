(require '[cljs.build.api :as b])

(b/watch "src"
         {:main 'dashboard-cljs.core
          :output-to "out/dashboard_cljs.js"
          :output-dir "out"
          :verbose true
          :foreign-libs [;; https://github.com/dbushell/Pikaday
                         {:file "resources/js/pikaday.js"
                          :provides ["pikaday"]}
                         ;; https://github.com/moment/moment/
                         {:file "resources/js/moment.js"
                          :provides ["moment"]}
                         ;; https://github.com/googlemaps/js-map-label
                         {:file "resources/js/maplabel.js"
                          :provides ["maplabel"]}
                         ]})
