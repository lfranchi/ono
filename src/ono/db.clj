(ns ono.db
    (:use [korma.db]
          [korma.core]
          [clojure.tools.logging])
    (:require [fs.core :as fs]
              [ono.utils :as utils]
              [cheshire.core :as json]))

;; TODO properly generate dbid UUID when initalizing a new
;; database
(def dbid "55bd135d-113f-481a-977e-999991111114")

(def testtrack { :title "One",:artist "U2", :album "Joshua Tree" , :year 1992 , :track 3 , :duration 240, :bitrate 256, :mtime 123123123 , :size 0,  :url "/test/mp3", :source 0 })
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
    (if-not (fs/exists? dbFile)
      (fs/with-mutable-cwd
        (do
          (fs/chdir (str fs/*cwd* "/resources"))
          (fs/exec (str "./setupdb.sh"))))
      )

    (set-error-handler! dbworker (fn [a e] (println "AGENT ERROR:" a e)))

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
        (belongs-to fileT artist track album))

    (defentity oplog
      (entity-fields :source_id :guid :command :singleton :compressed :json)))

;; Utility functions for DB operations
(defmacro get-or-insert-id!
  "Get the id for the desired entity, inserting it if it doesn't exist yet"
  [table & {where-clause :where insert-clause :insert}]
  `(if-let [id# (first (select ~table
                       (fields [:id])
                       (where ~where-clause)))]
    (id# :id)
    (first (vals (insert ~table (values ~insert-clause))))))

(defn- get-or-insert-artist!
    "Returns the artist id for a given name, or creates one if it doesn't exist yet"
    [artistName]
    (get-or-insert-id! artist :where {:name [like artistName]}
                              :insert {:name artistName}))

(defn- get-or-insert-album!
    "Returns the album id for a given album name and artist id, or creates one if it doesn't exist yet"
    [albumName, artistId]
    (get-or-insert-id! album :where  {:name [like albumName] :artist_id [= artistId]}
                             :insert {:name albumName
                                      :artist_id artistId}))

(defn- get-or-insert-track!
    "Returns the track id for the trackname and artist id, or creates one if it doesn't exist yet"
    [trackName, artistId]
    (get-or-insert-id! track :where  {:name [like trackName] :artist_id [= artistId]}
                             :insert {:name trackName
                                      :artist_id artistId}))

(defn get-or-insert-source!
  "Returns the source id for the given source's dbid. If it doesn't exist yet, creates it first.
   Friendlyname is optional, and will only be used when inserting a new source"
  [dbid friendlyname]
  (get-or-insert-id! source :where {:name dbid} :insert {:name dbid :friendlyname friendlyname}))

(defn- do-add-files
  "Internal add-files. Returns a list of files that have the id added"
  [msg, source, files]
  ; (info "GOT FILES" files)
  (assoc msg :files (for [{:keys [track artist album year albumpos duration
                    bitrate mtime size url] :as file}
                    files]
        (let [fileId (get (insert fileT (values {:source_id source,
                                             :url       url
                                             :size      size
                                             :mtime     mtime
                                             :duration  duration
                                             :bitrate   bitrate})) (keyword "last_insert_rowid()"))
              artistId (get-or-insert-artist! artist)
              albumId  (get-or-insert-album! album, artistId)
              trackId  (get-or-insert-track! track, artistId)]
          (insert file_join (values {:file_id fileId
                                     :artist_id artistId
                                     :track_id trackId
                                     :album_id albumId
                                     :albumpos albumpos}))
          (assoc file :id fileId)))))

(defn get-ops-since
  "Load ops for this source since the desired op. If the op is nil, this
   will return all ops for this source"
   [source since]
   (await dbworker)
   (select oplog (fields :guid :command :singleton :compressed :json)
                 (where {:source_id [= source]
                         :id [> (sqlfn coalesce (subselect oplog (fields [:id]) (where {:guid [like since]})) 0)]})))
     ; (println "Found ops:" ops)))

;; Dispatch central for db operations

(defn clean-for-oplog
  "Cleans a database command for insertion into the oplog. Some commands need to be tweaked
   before being sent to peers, for example the 'url' field of the 'addfiles' command needs
   to be replaced with the file id."
   [msg]
   (condp = (msg :command)
      "addfiles"     :>> (fn [_] (assoc msg :files (for [file (msg :files)]
                                                    (assoc file :url (file :id)))))

      msg))

(defn dispatch-db-cmd
  "Dispatches the given db command from a peer. The dbcmd is a map either from json or native
   client that describes the operation

   If the source is null (meaning this is a local dbcmd), it will be logged to the oplog
   and replicated to peers."
   [flags msg]
   (let [newmsg (condp = (msg :command)
                  "addfiles"                  :>> (fn [_]
                                                    (do-add-files msg (msg :source) (msg :files)))
                  "deletefiles"               :>> (fn [_] msg)
                  "createplaylist"            :>> (fn [_] msg)
                  "renameplaylist"            :>> (fn [_] msg)
                  "setplaylistrevision"       :>> (fn [_] msg)
                  "logplayback"               :>> (fn [_] msg)
                  "socialaction"              :>> (fn [_] msg)
                  "deleteplaylist"            :>> (fn [_] msg)
                  "setcollectionattributes"   :>> (fn [_] msg)
                  "setcollectionattributes"   :>> (fn [_] msg)

                  (do (println "Unknown command:" (msg :command)) msg))]
     ;; Update lastop when applying
     (let [msg-with-guid (if-not (newmsg :guid) (assoc newmsg :guid (utils/uuid)) newmsg)]
       (when (msg-with-guid :source)
         (update source
           (set-fields {:lastop (msg-with-guid :guid)})
           (where {:id [like (msg-with-guid :source)]})))
       ;; Serialize all commands to oplog
       (insert oplog (values {:source_id  (msg-with-guid :source)
                              :guid       (msg-with-guid :guid)
                              :command    (msg-with-guid :command)
                              :singleton  0 ;; We don't support any singleton commands yet
                              :compressed 0 ;; We don't compress on our end
                              :json       (json/generate-string (clean-for-oplog msg-with-guid))})))))

(defn add-files
    "Adds a list of file maps to the database, from the local user"
    [files]
    ; (println (str "Adding number of files: " (count files)))
    (send-off dbworker #(dispatch-db-cmd 0 {:command "addfiles" :files %2}) files))

(defn numfiles
    "Returns how many files are in the local collection"
    []
    (await dbworker)
    (:count (first (select fileT (aggregate (count *) :count)))))
