(defproject dashboard-cljs "0.2.0"
  :description "A dashboard written in cljs for Purple"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [weasel "0.7.0" :exclusions [org.clojure/clojurescript]]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]
                 [cljsjs/google-maps "3.18-1"]
                 [cljsjs/moment "2.10.6-0"]
                 [cljsjs/pikaday "1.3.2-0"]
                 [crate "0.2.5"]
                 [sablono "0.3.4"]
                 [org.omcljs/om "0.9.0"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :plugins [[lein-npm "0.6.1"]]
  :npm {:dependencies [[source-map-support "0.3.2"]]}
  :source-paths ["src" "target/classes"]
  :clean-targets ["out" "release"]
  :target-path "target")
