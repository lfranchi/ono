(ns ono.test.core
  (:use [ono.core]
        [midje.sweet])
  (:require [fs.core :as fs]))

(fact (filetype-supported? ".mp3")  => truthy)
(fact (filetype-supported? ".ogg")  => truthy)
(fact (filetype-supported? ".mp4")  => truthy)
(fact (filetype-supported? ".flac") => truthy)
(fact (filetype-supported? ".wav")  => falsey)
(fact (filetype-supported? ""    )  => falsey)

(defn mute-stderr
  "Quiet stderr when calling the desired functions"
  [f]
  (fn [& args]
    (let [old-err (System/err)
          _       (System/setErr (new java.io.PrintStream (new java.io.FileOutputStream "/dev/null")))
          retval (apply f args)]
      (System/setErr old-err)
      retval)))

(defn keyvals= [baseline-map]
  (fn [m]
    (every? #(= (% m) (% baseline-map)) (keys baseline-map))))

(fact
 (let [testfile (fs/file "resources/test_data/test_file.mp3")]
   ((mute-stderr #'ono.core/extractID3) testfile) => (keyvals= {:duration 280, :artist "Marc Reeves", :year "", :size 7127061, :bitrate 44100, :album "Perfectly Fine (remastered)", :albumpos "1", :track "Handshakes", :source nil})))



