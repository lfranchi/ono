(ns ono.net
    (:use [clojure.tools.logging])
    (:require [ono.db :as db]
              [ono.utils :as utils]
              [cheshire.core :as json]
              [gloss.io :as gio]
              [gloss.core :as gloss]
              [aleph.tcp :as tcp]
              [lamina.core :as lamina]
              [clojure.data.codec.base64 :as b64])
    (:import (java.net InetAddress DatagramPacket DatagramSocket)
             (java.util Arrays)))

;; Namespace for network related operations
;;
;; Listens on UDP port 50210 for broadcasts
;; Handles socket connections to other tomahawk/ono instances

;; Zeroconf discovery handling
(def zeroconf-port 55555)
(def dgram-size 16384)
(def udp-listener (agent nil))
(def udp-sock (ref nil))

;; TCP protocol
(def tcp-port 55555)
(def flags {:RAW        1
            :JSON       2
            :FRAGMENT   4
            :COMPRESSED 8
            :DBOP       16
            :PING       32
            :RESERVED   64
            :SETUP      128})
(defn has-value
  "Returns if the desired map has the given value, returning
   the associated key."
   [m v]
   (some #(if (= (val %) v) (key %)) m))

(defn flag-from-value
  [value]
  (has-value flags value))


;; Ono<->Tomahawk connections
;; Keyed by dbid
(def control-connections (ref {}))
(def dbsync-connections (ref {}))

;; Bookkeeping: dbid<--> {:host :port :sourceid}
(def known-peers (ref {}))

(def ping-agent (agent nil))

(def running true)

;; Network protocol
; (def frame (gloss/finite-frame
;  (gloss/prefix 
;     :int32
;     inc
;     dec)
;  [:ubyte (gloss/string :utf-8)]))

(def frame (gloss/finite-frame
  (gloss/prefix :int32 inc dec)
  [:ubyte (gloss/repeated :byte :prefix :none)]))

;; Utility functions
(defn agent-error-handler
  "Handles errors and exceptions from agents"
  [ag excp]
  (println "An agent threw an exception:" excp))

(defn is-dbsync-connection?
  "Returns true if the given connection is a dbsyncconnection"
  [ch]
  (dosync (has-value @dbsync-connections ch)))

(defn source-for-peer
  "Returns the sourceid for a given peer"
  [peer]
  (dosync (known-peers :sourceid)))

(defn generate-json
  "Generates a vector to be serialized from a map"
  ([msg-data]
    [(flags :JSON) (utils/str-bytes (json/generate-string msg-data))])
  ([msg-data extra-flags]
    [(bit-or (flags :JSON) extra-flags) (utils/str-bytes (json/generate-string msg-data))]))

(defn get-handshake-msg
  "Returns a JSON handshake msg from zeroconf peers"
  [foreign-dbid, connection-map, key]
  (let [main-msg {:conntype "accept-offer"
                         :key key
                         :port tcp-port}]
          (if (dosync (connection-map foreign-dbid))
            (generate-json (assoc main-msg :controlid db/dbid)) ;; All subsequent (dbsync and stream connections) require controlid
            (generate-json (assoc main-msg :nodeid db/dbid))))) ;; ControlConnection (first connection) requires nodeid

(defn ping-peers
  "Sends a PING message every 10 minutes to any
   active peer connection"
   [_]
   (doseq [ch (dosync (vals @control-connections))]
            (lamina/enqueue ch [(flags :PING) ""]))
   (. Thread (sleep 5000))
   (send-off ping-agent ping-peers))

(defn handle-handshake-msg
  "Handles the handshake after an initial SETUP message
   is received"
   [ch peer flag body]
    (when (= body "4") ;; We only support protocol 4
      (lamina/enqueue ch [(flags :SETUP) "ok"])))

;; Forward-declare add-peer as it is required by handle-json-msg
;; but add-peer requires get-tcp-handler (which require handle-json-message)
(declare add-peer-connection)

(defn test-flag
  "Does a not-zero?-bit-and of the arguments"
  [x y]
  (not (zero? (bit-and x y))))

(defn send-ops-from
  "Send all ops for the desired source, through the given channel,
   that are later than the given op."
   [ch source lastop]
   (println "Sending ops from" lastop "with source" source "to channel")
   (if-let [ops (ono.db/get-ops-since source lastop)]
       (let [flags #(bit-or (flags :DBOP) (if (= % (last ops)) 0 (flags :FRAGMENT)))]
         (doseq [cmd ops]
           (println "SENDING DBOP:" (cmd :guid) (cmd :command) (flags cmd) "body:" (cmd :json))
           (lamina/enqueue ch [(bit-or (flags :JSON) (flags :DBOP))
                               (utils/str-bytes (cmd :json))]))))
     (lamina/enqueue ch [(flags :DBOP) (utils/str-bytes "ok")])) ;; else if there are no new ops, send OK message
     ; (doseq [cmd ops]
     ;   (println "Sending CMD in fetchops:" (cmd :command)))))

(defn handle-json-msg
  "Handles an incoming JSON message from a peer"
  [ch peer flag body]
  (let [msg (json/parse-string body (fn [k] (keyword k)))
        cmd (msg :method)
        key (msg :key)]
    ; (print "Handing MSG" cmd key)
    (condp = cmd
      "dbsync-offer" :>>  (fn [_] (let [host (dosync ((known-peers peer) :host))
                                       port (dosync ((known-peers peer) :port))]
                                    (add-peer-connection host port peer key dbsync-connections)))
      "fetchops"     :>>  (fn [_] (send-ops-from ch nil (msg :lastop)))
      (print))
    ;; DBop messages only have a "command" field
    (when (msg :command)
      ;; Add the source field to each msg coming from the network
      (ono.db/dispatch-db-cmd flag (assoc msg :source (source-for-peer peer))))))

(defn uncompress
  "Uncompresses the tcp request that has been compressed with zlib plus 4-byte big-endian size header"
   [bytes]
   (utils/inflate (Arrays/copyOfRange (byte-array bytes) 4 (count bytes))))

(defn get-tcp-handler
  "Handles the TCP message for a specific peer"
  [ch peer]
  (fn handle-tcp-request[[flag body-bytes]]
    ; (info "Connection msg:" `peer flag)
    (if (test-flag flag (flags :COMPRESSED))
      (handle-tcp-request [(bit-and (bit-not (flags :COMPRESSED)) flag) ;; Remove COMPRESSED flag
                           (uncompress body-bytes)]) ;; call ourselves w/ uncompressed body
      (let [body-str (String. (byte-array body-bytes) (java.nio.charset.Charset/forName "utf-8"))]
        (condp test-flag flag
          (flags :SETUP) :>> (fn [_] (handle-handshake-msg ch peer flag body-str))
          (flags :PING)  :>> (fn [_] (print))  ;; Ignore PING messages for now, TODO if no ping in 10s, disconnect
          (flags :JSON)  :>> (fn [_] (handle-json-msg ch peer flag body-str)))))))

(defn add-peer-connection
    "Adds a new peer's connection (main ControlConnection or secondary connection)
     and starts the TCP communication"
    [ip, port, foreign-dbid, key, connection-map]
    ;; Attempt to connect to the remote tomahawk
    ; (println "Asked to connect to peer, control or subsequent connection:" ip port foreign-dbid key connection-map)
    (lamina/on-realized (tcp/tcp-client {:host ip :port (Integer/parseInt port) :frame frame})
      (fn [ch]
        ;; Connection suceeded, here's our channel
        (let [handshake-msg (get-handshake-msg foreign-dbid control-connections key)] ;; Get the handshake message before we add the
                                                              ;; peer to connection-map, because we need to check
                                                              ;; if this is our first connection (and thus controlconnection)
                                                              ;; by testing for existence of this peer in the connection map
          (dosync
            (alter connection-map assoc foreign-dbid ch)
            (if-not (known-peers foreign-dbid) 
              (alter known-peers assoc foreign-dbid {:host ip :port port})))
          (lamina/receive-all ch (get-tcp-handler ch foreign-dbid))
          (lamina/enqueue ch handshake-msg)))
          ; (if (is-dbsync-connection? ch) ;; HACK for development only, force fetch of all dbops
            ; (lamina/enqueue ch (generate-json {:method "fetchops" :lastop ""})))))
        (fn [ch]
          ;; Failed
          (println "Failed to connect to" ip port ch))))


(defn listen
    "Listens on our UDP port and watches for Tomahawk broadcasts. When found, calls addPeer
     with the newly found dbid, host and port."
    [_]
    (let [buf (byte-array dgram-size)
          packet (DatagramPacket. buf dgram-size)
          strval (dosync (.receive @udp-sock packet) (String. (.getData packet) 0 (.getLength packet)))]
        ; (println "Received packet:" strval)
        ;; We only support v2 broadcasts
        (let [parts        (clojure.string/split strval #":")]
            (if (and (= (count parts) 4) (= (first parts) "TOMAHAWKADVERT"))
              (let [ip           (last parts)
                    port         (nth parts 1)
                    foreign-dbid (nth parts 2)]
                ;; Initial setup in the control-connection uses a magic "whitelist" key
                ;; Make sure we are not already connected
                (when-not (dosync (control-connections foreign-dbid)) ;; Keep track of each peer by a sourceid. That will be used in the db
                  (let [sourceid (ono.db/get-or-insert-source! foreign-dbid ip)]
                    (dosync (alter known-peers assoc :sourceid sourceid))
                    (add-peer-connection ip port foreign-dbid "whitelist" control-connections)))))))
   (send udp-listener listen))

(defn start-udp
   "Starts the UDP listener and periodically sends
    UDP broacasts"
    []
    (dosync (ref-set udp-sock (DatagramSocket. zeroconf-port)))
    (println "Beginning to listen on port " zeroconf-port)
    (set-error-handler! udp-listener agent-error-handler)
    (set-error-handler! ping-agent agent-error-handler)
    (send-off udp-listener listen)
    (send-off ping-agent ping-peers))


(defn stop-udp
    "Stops all UDP sockets"
    []
    (dosync 
        (.close @udp-sock)))
