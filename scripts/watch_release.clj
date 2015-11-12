(require '[cljs.build.api :as b])

(b/watch "src"
         {:output-to "release/dashboard_cljs.js"
          :output-dir "release"
          :optimizations :advanced
          :verbose true
          :foreign-libs [;; https://github.com/googlemaps/js-map-label
                         {:file "resources/js/maplabel.js"
                          :provides ["maplabel"]}
                         ]})
