(defproject ono "0.1.0-SNAPSHOT"
  :description "A tomahawk daemon written in Clojure"
  :license {:name "GNU Public License V3"
            :url "http://www.gnu.org/licenses/gpl.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
        				 [fs "1.3.2"] ;; Filesystem/shell tools
        				 [cheshire "4.0.3"] ;; JSON lib
        				 [korma "0.3.0-beta7"] ;; SQL wrapper
        				 [org.clojure/java.jdbc "0.1.1"] 
        				 [org.xerial/sqlite-jdbc "3.7.2"]
        				 [org.jaudiotagger/jaudiotagger "2.0.1"] ;; ID3/FLAC tagging library
                 [aleph "0.3.0-beta2"] ;; TCP abstraction layer
                 [org.clojure/data.codec "0.1.0"] ;; Base64 decoding
                 [log4j/log4j "1.2.16"]] ;; Logging
  :main ono.core)