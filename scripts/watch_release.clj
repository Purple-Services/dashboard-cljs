(require '[cljs.build.api :as b])

(b/watch "src"
         {:output-to "release/dashboard_cljs.js"
          :output-dir "release"
          :optimizations :advanced
          :verbose true
          :foreign-libs [{:file "resources/js/pikaday.js"
                          :provides ["pikaday"]}
                         {:file "resources/js/moment.js"
                          :provides ["moment"]}
                         ]})
