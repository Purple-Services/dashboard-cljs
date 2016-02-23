(defproject dashboard-cljs "0.2.0"
  :description "A dashboard written in cljs for Purple"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [weasel "0.7.0" :exclusions [org.clojure/clojurescript]]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]
                 [org.clojure/core.async "0.2.374"]
                 [cljsjs/google-maps "3.18-1"]
                 [cljsjs/moment "2.10.6-0"]
                 [cljsjs/pikaday "1.3.2-0"]
                 [crate "0.2.5"]
                 [reagent "0.6.0-alpha"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :plugins [[lein-cljsbuild "1.1.2"]
            [lein-npm "0.6.1"]]
  :npm {:dependencies [[source-map-support "0.3.2"]]}
  :source-paths ["src" "target/classes"]
  :clean-targets ["out" "release"]
  :target-path "target"
  :cljsbuild
  {
   :builds [
            {:id "dev"
             :source-paths ["src"]
             :compiler {
                        :main dashboard-cljs.dev
                        :output-to "out/dashboard_cljs.js"
                        :output-dir "out"
                        :optimizations :none
                        :source-map true
                        :verbose true
                        :foreign-libs [;; https://github.com/googlemaps/js-map-label
                                       {:file "resources/js/maplabel.js"
                                        :provides ["maplabel"]}
                                       ]}}
            {:id "release"
             :source-paths ["src"]
             :compiler {:main dashboard-cljs.core
                        :output-to "../dashboard-service/src/public/js/dashboard_cljs.js"
                        :optimizations :advanced
                        :verbose true
                        :foreign-libs [{:file "resources/js/maplabel.js"
                                        :provides ["maplabel"]}]}
             }]
   })
