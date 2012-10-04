CREATE TABLE IF NOT EXISTS artist (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                   name TEXT NOT NULL,
                                   sortname TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS track (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                  artist INTEGER NOT NULL REFERENCES artist(id)  
                                                 ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE 
                                                 INITIALLY DEFERRED,
                                  name TEXT NOT NULL,
                                  sortname TEXT NOT NULL);