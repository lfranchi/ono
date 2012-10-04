(ns ono.core
    (:use ono.db)
    (:use [cheshire.core :only (parse-string)])
    (:use [fs.core :only (exists?, mkdir, create, walk, file)])
    (:use [korma.db])
    (:gen-class))

; Constants
(def configDir (str (System/getProperty "user.home") "/.ono"))
(def confFile (str configDir "/config"))
(def dbFile (str configDir "/db.sqlite3"))

; Globals
(def config (ref {}))

(defn- setup
    "Loads configuration and database"
    []
    ; Create files if they don't exist yet
    (if (not (exists? configDir))
        (mkdir configDir))
    (if (not (exists? confFile))
        (create (file confFile)))
    (dosync
        (ref-set config (parse-string (slurp confFile)))))
    
    (setupdb dbFile)

;; Scanner
(defn scan
    "Scans a desired folder recursively for any audio files we can recognize.
     Parses the ID3 tags and inserts into the database."
     [folder]
     (println (str "Scanning folder " folder))
     (walk (fn [r dirs files]
       ; (println (str "Walking " r dirs files)))
        (println "OHAI"))
        folder))

(defn handle [input]
    (cond 
        (= input "help")
            (println "Supported commands:

help:                         Show this help message
scan [folder]:                Scan the folder for music
numfiles:                     Return how many files are in the db
search \"track\" \"artist\":      Search for a desired track/artist pair")
        (and (> (count input) 4) (= (subs input 0 4) "scan"))
            (scan (second (clojure.string/split input #" ")))
        :else
            (println "No such command!")))

(defn -main [& args]
    (setup)
    (println "Welcome to Ono.")
    (while true
        (print "> ")
        (flush)
        (let [input (read-line)]
            (handle input))))