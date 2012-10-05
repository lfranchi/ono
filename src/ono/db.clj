(ns ono.db
    (:use [korma.db]
          [korma.core]
          [fs.core :only (exists?)]))

;; (def testtrack { :title "One",:artist "U2", :album "Joshua Tree" , :year 1992 , :track 3 , :duration 240, :bitrate 256, :mtime 123123123 , :size 0,  :file "/test/mp3", :source 0 })
(def dbworker (agent nil))

(defn with-sort-name
    "Adds the sort name field to an insert"
    [fields]
    (assoc fields :sortname (:name fields))) ;; For now don't actually calculate the sortname

(defn setupdb
    "Sets up the db connection and relations"
    [dbFile]

    ;; I was unable to get korma or java-jdbc to create the initial db/tables
    ;; from scratch, so we use a simple shell script to do it for us
    (if-not (exists? dbFile)
        (do (println "No DB found. Please create it with ./setupdb.sh")
                (System/exit 0)))

    (defdb sqlite (sqlite3 {:db dbFile}))

    (defentity source
        (entity-fields :name :friendlyname :lastop :isonline))

    (defentity artist
        (entity-fields :name :sortname)
        (prepare with-sort-name))

    (defentity album
        (entity-fields :artist_id :name :sortname)
        (prepare with-sort-name))

    (defentity track
        (entity-fields :name :artist_id :sortname)
        (belongs-to artist)
        (prepare with-sort-name))

    (defentity fileT
        (table :file)
        (entity-fields :source_id :url :size :mtime :md5 :mimetype :duration :bitrate)
        (belongs-to source))

    (defentity file_join
        (pk :file_id)
        (entity-fields :file_id :artist_id :track_id :album_id :albumpos :discnumber)
        (belongs-to fileT artist track album)))

(defmacro get-entity-id
  "Get the id for the desired entity, inserting it if it doesn't exist yet"
  [table & {where-clause :where insert-clause :insert}]
  `(if-let [id# (first (select ~table
                       (fields [:id])
                       (where ~where-clause)))]
    (id# :id)
    (first (vals (insert ~table (values ~insert-clause))))))

(defn- get-artist
    "Returns the artist id for a given name, or creates one if it doesn't exist yet"
    [artistName]
    (get-entity-id artist :where {:name [like artistName]} :insert {:name artistName}))

(defn- get-album
    "Returns the album id for a given album name and artist id, or creates one if it doesn't exist yet"
    [albumName, artistId]
    (get-entity-id album :where  {:name [like albumName] :artist_id [= artistId]} 
                         :insert {:name albumName
                                  :artist_id artistId}))

(defn- get-track
    "Returns the track id for the trackname and artist id, or creates one if it doesn't exist yet"
    [trackName, artistId]
    (get-entity-id track :where  {:name [like trackName] :artist_id [= artistId]} 
                         :insert {:name trackName
                                  :artist_id artistId}))

(defn addFiles
    "Adds a list of file maps to the database"
    [files]
    (println (str "Adding number of files: " (count files)))
    (doseq [{:keys [title artist album year track duration
                    bitrate mtime size file source]}
                    files]
        (let [fileId ((insert fileT (values {:source_id source,
                                           :url       file
                                           :size      size
                                           :mtime     mtime
                                           :duration  duration
                                           :bitrate   bitrate})) :last_insert_rowid())
              artistId (get-artist artist)
              albumId  (get-album album, artistId)
              trackId  (get-track title, artistId)]
          (insert file_join (values {:file_id fileId
                                     :artist_id artistId
                                     :track_id trackId
                                     :album_id albumId
                                     :albumpos track}))
          )))

(defn numfiles
    "Returns how many files are in the local collection"
    []
    ((first (select fileT (aggregate (count *) :count))) :count))



