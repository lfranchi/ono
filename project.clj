(defproject ono "0.1.0-SNAPSHOT"
  :description "A tomahawk daemon written in Clojure"
  :license {:name "GNU Public License V3"
            :url "http://www.gnu.org/licenses/gpl.html"}
  :dependencies [[org.clojure/clojure "1.3.0"]
        				 [fs "1.3.2"]
        				 [cheshire "4.0.3"]
        				 [korma "0.3.0-beta7"]
        				 [org.clojure/java.jdbc "0.1.1"] 
        				 [org.xerial/sqlite-jdbc "3.7.2"]
        				 [org.jaudiotagger/jaudiotagger "2.0.1"]
                 [aleph "0.3.0-beta2"]
                 [franks42/debug-repl "0.3.1-FS"]]
  :main ono.core)