CREATE TABLE IF NOT EXISTS source (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                    name TEXT NOT NULL,
                                    friendlyname TEXT,
                                    lastop TEXT NOT NULL DEFAULT "",       -- guid of last op we've successfully applied
                                    isonline BOOLEAN NOT NULL DEFAULT false);
CREATE UNIQUE INDEX source_name ON source(name);

CREATE TABLE IF NOT EXISTS artist (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                   name TEXT NOT NULL,
                                   sortname TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS album (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                   artist_id INTEGER NOT NULL REFERENCES artist(id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
                                   name TEXT NOT NULL,
                                   sortname TEXT NOT NULL);
CREATE UNIQUE INDEX album_artist_sortname ON album(artist_id,sortname);

CREATE TABLE IF NOT EXISTS track (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                  artist_id INTEGER NOT NULL REFERENCES artist(id)  
                                                 ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE 
                                                 INITIALLY DEFERRED,
                                  name TEXT NOT NULL,
                                  sortname TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS file ( id INTEGER PRIMARY KEY AUTOINCREMENT,
                                  source_id INTEGER REFERENCES source(id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
                                  url TEXT NOT NULL,
                                  size INTEGER NOT NULL, 
                                  mtime INTEGER NOT NULL,              -- file mtime, so we know to rescan
                                  md5 TEXT,                            -- useful when comparing stuff p2p
                                  mimetype TEXT,                       -- "audio/mpeg"
                                  duration INTEGER NOT NULL DEFAULT 0, -- seconds
                                  bitrate INTEGER NOT NULL DEFAULT 0);   -- kbps (or equiv)Ï
                                
CREATE UNIQUE INDEX file_url_src_uniq ON file(source_id, url);
CREATE INDEX file_source ON file(source_id);
CREATE INDEX file_mtime ON file(mtime);

CREATE TABLE IF NOT EXISTS file_join (
    file_id INTEGER PRIMARY KEY REFERENCES file(id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
    artist_id INTEGER NOT NULL REFERENCES artist(id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
    track_id INTEGER NOT NULL REFERENCES track(id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
    album_id INTEGER REFERENCES album(id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
    albumpos INTEGER,
    discnumber INTEGER
);
CREATE INDEX file_join_track  ON file_join(track_id);
CREATE INDEX file_join_artist ON file_join(artist_id);
CREATE INDEX file_join_album  ON file_join(album_id);
