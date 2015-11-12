(defproject dashboard-cljs "0.1.0-SNAPSHOT"
  :description "A dashboard written in cljs for Purple"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.145" :classifier "aot"
                  :exclusion [org.clojure/data.json]]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]
                 [cljsjs/google-maps "3.18-1"]
                 [cljsjs/moment "2.10.6-0"]
                 [cljsjs/pikaday "1.3.2-0"]
                 [crate "0.2.5"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :plugins [[lein-npm "0.6.1"]]
  :npm {:dependencies [[source-map-support "0.3.2"]]}
  :source-paths ["src" "target/classes"]
  :clean-targets ["out" "release"]
  :target-path "target")
