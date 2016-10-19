(defproject dashboard-cljs "1.0.2"
  :description "Purple dashboard client. Connects to dashboard-service API."
  :url "https://dash.purpleapp.com"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]
                 [org.clojure/core.async "0.2.374"]
                 [cljsjs/google-maps "3.18-1"]
                 [cljsjs/moment "2.10.6-0"]
                 [cljsjs/pikaday "1.3.2-0"]
                 [cljsjs/plotly "1.10.0-0"]
                 [crate "0.2.5"]
                 [reagent "0.6.0-rc"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :plugins [[lein-cljsbuild "1.1.2"]
            [lein-npm "0.6.1"]
            [lein-figwheel "0.5.4-7"]]
  :npm {:dependencies [[source-map-support "0.4.0"]]}
  :source-paths ["src" "target/classes"]
  :clean-targets ["out" "release"]
  :target-path "target"
  :cljsbuild
  {:builds [{:id "dev"
             :source-paths ["src"]
             :figwheel {:on-jsload "dashboard-cljs.dev/on-jsload"}
             :compiler {:main dashboard-cljs.dev
                        :output-to "resources/public/js/dashboard_cljs.js"
                        :output-dir "resources/public/js/out"
                        :asset-path "js/out"
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
                                        :provides ["maplabel"]}
                                       ]}}]})
