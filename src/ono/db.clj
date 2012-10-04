(ns ono.db
    (:use [korma.db])
    (:use [korma.core])
    (:use [fs.core :only (exists?, create, file)]))

(defn addSortName
    "Adds the sort name field to an insert"
    [fields]
    (assoc fields :sortname (:name fields))) ;; For now don't actually calculate the sortname

(defn setupdb
    "Sets up the db connection and relations"
    [dbFile]

    (if-not (exists? dbFile)
        (do (println "No DB found. Please create it with ./setupdb.sh")
                (System/exit 0)))

    (defdb sqlite (sqlite3 {:db dbFile}))

    (defentity artist
        (entity-fields :name :sortname)
        (prepare addSortName))

    (defentity track
        (entity-fields :name :artist :sortname)
        (has-one artist)
        (prepare addSortName))

    (insert track (values {:name "Some Track" :artist "Artist Name"})))
