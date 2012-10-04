(ns ono.core
    (:require [ono.db :as db])
    (:require [cheshire.core :as json])
    (:require [fs.core :as fs])
    (:import [org.jaudiotagger.audio])
    (:import [org.jaudiotagger.tag])
    (:gen-class))

;; Constants
(def configDir (str (System/getProperty "user.home") "/.ono"))
(def confFile (str configDir "/config"))
(def dbFile (str configDir "/db.sqlite3"))
(def supportedSuffixes #{".mp3" ".flac" ".ogg" ".mp4"})

;; Globals
(def config (ref {}))

(defn- setup
    "Loads configuration and database"
    []
    ;; Create files if they don't exist yet
    (if (not (fs/exists? configDir))
        (fs/mkdir configDir))
    (if (not (fs/exists? confFile))
        (fs/create (fs/file confFile)))
    (dosync
        (ref-set config (json/parse-string (slurp confFile)))))
    
    (db/setupdb dbFile)

(defn- extractID3
    "Extracts basic ID3 info from a file"
    [f]
    (if (contains? supportedSuffixes (last (fs/split-ext f)))
        (let [fd (fs/file f)
              audio (org.jaudiotagger.audio.AudioFileIO/read fd)
              tag (.getTag audio)
              header (.getAudioHeader audio)]
            {:title  (.getFirst tag org.jaudiotagger.tag.FieldKey/TITLE)
              :artist (.getFirst tag org.jaudiotagger.tag.FieldKey/ARTIST)
              :album  (.getFirst tag org.jaudiotagger.tag.FieldKey/ALBUM)
              :year   (.getFirst tag org.jaudiotagger.tag.FieldKey/YEAR)
              :track  (.getFirst tag org.jaudiotagger.tag.FieldKey/TRACK)
              :duration (.getTrackLength header)
              :bitrate (.getSampleRateAsNumber header)
              :mtime  (fs/mod-time fd)
              :size   (fs/size fd)
              :file   f
              :source nil})))

;; Scanner
(defn- scan
    "Scans a desired folder recursively for any audio files we can recognize.
     Parses the ID3 tags and inserts into the database."
     [folder]
     (println (str "Scanning folder " folder))
     ;; jaudiotagger is super verbose on stderr
     (let [os System/err] 
        (System/setErr (new java.io.PrintStream (new java.io.FileOutputStream "/dev/null")))
        (dorun (fs/walk (fn [r dirs files]
                    (db/addFiles (filter #(not (empty? %)) ;; Remove files with no tags
                                   (map (fn [f]
                                     (extractID3 (fs/file r f)))
                                      files))))
        folder))
     (System/setErr os)))

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