(ns ono.db
    (:use [korma.db])
    (:use [korma.core])
    (:use [fs.core :only (exists?)]))

(def testtrack { :title "One",:artist "U2", :album "Joshua Tree" , :year 1992 , :track 3 , :duration 240, :bitrate 256, :mtime 123123123 , :size 0,  :file "/test/mp3", :source 0 })

(defn addSortName
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
        (prepare addSortName))

    (defentity album
        (entity-fields :artist_id :name :sortname)
        (prepare addSortName))

    (defentity track
        (entity-fields :name :artist_id :sortname)
        (belongs-to artist)
        (prepare addSortName))

    (defentity fileT
        (table :file)
        (entity-fields :source_id :url :size :mtime :md5 :mimetype :duration :bitrate)
        (belongs-to source))

    (defentity file_join
        (pk :file_id)
        (entity-fields :file_id :artist_id :track_id :album_id :albumpos :discnumber)
        (belongs-to fileT artist track album)))

;;    (insert track (values {:name "Some Track" :artist "Artist Name"})))

(defn- getArtist
    "Returns the artist id for a given name, or creates one if it doesn't exist yet"
    [artistName]
    (if-let [id (first (select artist
                        (fields [:id])
                        (where {:name [like artistName]})))]
      (id :id) ;; Have an id, return it directly
      (first (vals (insert artist (values {:name artistName})))))) ;; Not here yet, insert it first and return the id

(defn- getAlbum
    "Returns the album id for a given album name and artist id, or creates one if it doesn't exist yet"
    [albumName, artistId]
    (if-let [id (first (select album
                        (fields [:id])
                        (where {:name [like albumName]
                                :artist_id [= artistId]})))]
      (id :id) 
      (first (vals (insert album (values {:name albumName :artist_id artistId}))))))

(defn- getTrack
    "Returns the track id for the trackname and artist id, or creates one if it doesn't exist yet"
    [trackName, artistId]
    (if-let [id (first (select track
                        (fields [:id])
                        (where {:name [like trackName]
                                :artist_id [= artistId]})))]
      (id :id) 
      (first (vals (insert track (values {:name trackName :artist_id artistId}))))))

(defn addFiles
    "Adds a list of file maps to the database"
    [files]
    (println (str "Adding number of files: " (count files)))
    (dorun (map 
        (fn [{:keys [title artist album year track duration bitrate mtime size file source]}]
        (let [fileId ((insert fileT (values {:source_id source,
                                           :url       file
                                           :size      size
                                           :mtime     mtime
                                           :duration  duration
                                           :bitrate   bitrate})) :last_insert_rowid())
              artistId (getArtist artist)
              albumId  (getAlbum album, artistId)
              trackId  (getTrack title, artistId)]
          (insert file_join (values {:file_id fileId
                                     :artist_id artistId
                                     :track_id trackId
                                     :album_id albumId
                                     :albumpos track}))
          ))
    files)))

(defn numfiles
    "Returns how many files are in the local collection"
    []
    ((first (select fileT (aggregate (count *) :count))) :count))



