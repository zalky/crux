(defproject juxt/crux-http-server :derived-from-git
  :description "Crux HTTP Server"
  :url "https://github.com/juxt/crux"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]

                 [juxt/crux-core :derived-from-git]

                 [ring/ring-core "1.7.1"]
                 [ring/ring-jetty-adapter "1.7.1"]
                 [ring/ring-codec "1.1.2"]
                 [ring-cors "0.1.13"]]
  :middleware [leiningen.project-version/middleware])