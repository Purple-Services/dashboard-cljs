(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build "src"
    {:output-to "release/dashboard_cljs.js"
     :output-dir "release"
     :optimizations :advanced
     :verbose true
     :foreign-libs [{:file "resources/js/pikaday.js"
                     :provides ["pikaday"]}
                    {:file "resources/js/moment.js"
                     :provides ["moment"]}
                    {:file "resources/js/maplabel.js"
                     :provides ["maplabel"]}
                    ]})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
