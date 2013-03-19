(defproject ono "0.1.0-SNAPSHOT"
  :description "A tomahawk daemon written in Clojure"
  :license {:name "GNU Public License V3"
            :url "http://www.gnu.org/licenses/gpl.html"}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [fs "1.3.3"] ;; Filesystem/shell tools
                 [cheshire "5.0.2"] ;; JSON lib
                 [korma "0.3.0-RC4"] ;; SQL wrapper
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [org.jaudiotagger/jaudiotagger "2.0.1"] ;; ID3/FLAC tagging library
                 [aleph "0.3.0-beta14"] ;; TCP abstraction layer
                 [log4j/log4j "1.2.16" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jdmk/jmxtools
                                                    com.sun.jmx/jmxri]]] ;; Logging

  :profiles {:dev {:dependencies [[midje "1.5.0"] ;; unit testing
                                  [org.clojure/data.codec "0.1.0"]]
                   :plugins [[lein-midje "3.0.0"]]}
             :user {:plugins [[lein-kibit "0.0.8"]]}}
  :main ono.core)
