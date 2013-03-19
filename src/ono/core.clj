(ns ono.core
    (:use [clojure.tools.logging])
    (:require [ono.db :as db]
              [ono.net :as net]
              [cheshire.core :as json]
              [fs.core :as fs])
    (:import  [org.jaudiotagger.audio]
              [org.jaudiotagger.tag])
    (:gen-class))

;; Constants

(defn constants-map
  "Map of constants"
  []
  (let [confDir (str (System/getProperty "user.home") "/.ono")]
    {:confDir   confDir
     :confFile  (str confDir "/config.json")
     :dbFile    (str confDir "/db.sqlite3")}))

(defn filetype-supported?
  "Returns whether or not the desired extension is supported"
  [extension]
  (contains? #{".mp3" ".flac" ".ogg" ".mp4"} extension))

(defn setup!
    "Loads configuration and database from defaults

     Expects :confFile, :confDir and :dbFile."
    [{:keys [confFile confDir dbFile]}]
    (org.apache.log4j.BasicConfigurator/configure)

    ;; Create files if they don't exist yet
    (if-not (fs/exists? confDir)
        (fs/mkdir confDir))
    (if-not (fs/exists? confFile)
        (fs/create (fs/file confFile)))

    ;; jaudiotagger is super verbose on stderr
    (System/setErr (new java.io.PrintStream (new java.io.FileOutputStream "/dev/null")))

    (db/setupdb dbFile)
    (net/start-udp))

(defn load-config
  "Loads the configuration from the config file"
  [file]
  (json/parse-string (slurp file)))

(defn- extractID3
    "Extracts basic ID3 info from a file"
    [f]
    (when (filetype-supported? (last (fs/split-ext f)))
        (let [fd (fs/file f)
              audio (org.jaudiotagger.audio.AudioFileIO/read fd)
              tag (.getTag audio)
              header (.getAudioHeader audio)]
            { :track      (.getFirst tag org.jaudiotagger.tag.FieldKey/TITLE)
              :artist     (.getFirst tag org.jaudiotagger.tag.FieldKey/ARTIST)
              :album      (.getFirst tag org.jaudiotagger.tag.FieldKey/ALBUM)
              :year       (.getFirst tag org.jaudiotagger.tag.FieldKey/YEAR)
              :albumpos   (.getFirst tag org.jaudiotagger.tag.FieldKey/TRACK)
              :duration   (.getTrackLength header)
              :bitrate    (.getSampleRateAsNumber header)
              :mtime      (fs/mod-time fd)
              :size       (fs/size fd)
              :url        (.getAbsolutePath f)
              :source     nil})))

(defn- starts-with?
  [input match]
  (and (>= (count input) (count match)) (= (subs input 0 (count match)) match)))

(defmacro parse-args
  "Argument parsing macro. Takes an input string to parse, and a :match-list like
   { \"cmd\", fn }. It will execute the associated function for the matching
   command.

   Also takes a default :default function if no command is matched

   The associated function must take a list of arguments."
  [input & {matches :match-list default :default}]
  `(if-let [matching-keys# (seq
                            (for [k# (keys ~matches) :when (starts-with? ~input k#)] k#))]
    ((first (vals (select-keys ~matches matching-keys#)))
      (rest (clojure.string/split ~input #" ")))
    (~default ~input)))

;; Scanner
(defn- scan!
    "Scans a desired folder recursively for any audio files we can recognize.
     Parses the ID3 tags and inserts into the database."
     [folder]
     (println (str "Scanning folder " folder))
      (dorun (fs/walk (fn [r dirs files]
                  (db/add-files (filter #(not (empty? %)) ;; Remove files with no tags
                                 (map (fn [f]
                                   (extractID3 (fs/file r f)))
                                    files))))
      folder)))

(defn handle
  "Handles a line of user input from the REPL"
  [input]
  (parse-args input
              :match-list {
                                 "help" (fn [_] (println
"Supported commands:

help:                         Show this help message
scan [folder]:                Scan the folder for music
numfiles:                     Return how many files are in the db
search \"track\" \"artist\":      Search for a desired track/artist pair"))

                                 "scan"     (fn [args] (scan! (first args)))
                                 "numfiles" (fn [_] (println (db/numfiles)))
                         }
              :default   #(when-not (= "" %)
                            (println "No such command!"))))

(defn -main [& args]
    (setup! (constants-map))
    (println "Welcome to Ono.")
    (while true
        (print "> ")
        (flush)
        (let [input (read-line)]
            (handle input))))