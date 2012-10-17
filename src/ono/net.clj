(ns ono.net
    (:use [clojure.tools.logging]
          [gloss.core]
          [gloss.io])
    (:require [ono.db :as db]
              [ono.utils :as utils]
              [cheshire.core :as json]
              [aleph.tcp :as tcp]
              [lamina.core :as lamina])
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

(defn test-flag
  "Does a not-zero?-bit-and of the arguments"
  [x y]
  (not (zero? (bit-and x y))))

(defprotocol IPeerData
  (set-udp-socket [data port]
    "Sets the udp socket for this network connection
   to listen on the given port")
  (get-udp-socket [data]
    "Returns the UDP socket that is in use")
  (add-connection! [data connection-type peer channel]
    "Adds a connection of the desired type for the given peer, returning the new
   opaque data structure")
  (get-connection [data connection-type peer]
    "Returns a connection channel for the desired type and peer")
  (add-peer-info! [data peerid key value]
    "Adds a key/value pair to the peer data structure, returning the
   modified opaque atom")
  (get-peer-info [data peerid key]
    "Get a piece of metadata associated with the desired peer"))

(defrecord PeerData [udp-socket control-connections
                     dbsync-connections peer-info]
  IPeerData
  (set-udp-socket [data port]
    (assoc data :udp-socket (DatagramSocket. port)))

  (get-udp-socket [data]
    udp-socket)

  (add-connection! [data connection-type peer channel]
    (let [connection-map (connection-type data)]
      (swap! connection-map assoc peer channel))
    data)

  (get-connection [data connection-type peer]
    (if-let [conns (-> data connection-type)]
      (get @conns peer)
      nil))

  (add-peer-info! [data peerid key value]
    (swap! peer-info assoc-in [peerid key] value)
    data)

  (get-peer-info [data peerid key]
    (-> @peer-info
        (get peerid)
        (get key))))

(defn peer-data
  "Return a new PeerData record with default values, unless they are passed in."
  [& {:keys [udp-socket control-connections
             dbsync-connections peer-info] :as pd}]
  (let [defaults {:udp-socket nil
                  :control-connections (atom {})
                  :dbsync-connections (atom {})
                  :peer-info (atom {})}]
    (map->PeerData (merge defaults pd))))

;; Gloss frame definitions
(def inner (compile-frame
               [:ubyte (repeated :byte :prefix :none)]
               (fn [[flag body]] (let [b (if (test-flag flag (flags :COMPRESSED))
                                        body
                                        (.getBytes body (java.nio.charset.Charset/forName "utf-8")))]
                                   [flag b]))
               (fn [[flag body]] (let [b (if (test-flag flag (flags :COMPRESSED))
                                          (byte-array body)
                                          (String. (byte-array body) (java.nio.charset.Charset/forName "utf-8")))]
                                   [flag b]))))

(def frame (finite-frame
              (prefix
               :int32
              inc
              dec)
            inner))

;; Utility functions
(defn agent-error-handler
  "Handles errors and exceptions from agents"
  [ag excp]
  (println "An agent threw an exception:" excp))

(defn source-for-peer
  "Returns the sourceid for a given peer"
  [data peer]
  (get-peer-info data peer :sourceid))

(defn generate-json
  "Generates a vector to be serialized from a map"
  ([msg-data]
    [(flags :JSON) (json/generate-string msg-data)])
  ([msg-data extra-flags]
    [(bit-or (flags :JSON) extra-flags) (json/generate-string msg-data)]))

(defn get-handshake-msg
  "Returns a JSON handshake msg from zeroconf peers"
  [data foreign-dbid connection-type key my-dbid]
  (let [main-msg {:conntype "accept-offer"
                         :key key
                         :port tcp-port}]
          ;; Determine if we need a handshake for a control connection
          ;;  or a secondary connection
          ; (println "ARE WE SENDING FIRST MSG?" foreign-dbid connection-type (get-connection data connection-type foreign-dbid))
          (if (get-connection data connection-type foreign-dbid)
            (generate-json (assoc main-msg :controlid my-dbid)) ;; All subsequent (dbsync and stream connections) require controlid
            (generate-json (assoc main-msg :nodeid my-dbid))))) ;; ControlConnection (first connection) requires nodeid

(defn ping-peers
  "Sends a PING message every 10 minutes to any
   active peer connection"
  [data]
  (let [ping-agent (agent nil)]
    (set-error-handler! ping-agent agent-error-handler)
    (letfn [(ping-fn [_]
              (doseq [ch (vals (deref (get data :control-connections)))]
                (lamina/enqueue ch [(flags :PING) ""]))
              (. Thread (sleep 5000))
              (send-off ping-agent ping-fn))]
      (send-off ping-agent ping-fn))
    ping-agent))

(defn handle-handshake-msg
  "Handles the handshake after an initial SETUP message
   is received"
   [ch peer flag body]
    (when (= body "4") ;; We only support protocol 4
      (lamina/enqueue ch [(flags :SETUP) "ok"])))

;; Forward-declare add-peer as it is required by handle-json-msg
;; but add-peer requires get-tcp-handler (which require handle-json-message)
(declare add-peer-connection!)

(defn send-ops-from
  "Send all ops for the desired source, through the given channel,
   that are later than the given op."
   [ch source lastop]
   (println "Sending ops from" lastop "with source" source "to channel")
   (if-let [ops (ono.db/get-ops-since source lastop)]
     (let [myflags #(bit-or (flags :DBOP) (if (= % (last ops)) 0 (flags :FRAGMENT)))]
       (doseq [cmd ops]
         (println "SENDING DBOP:" (cmd :guid) (cmd :command) (bit-or (flags :JSON) (flags :DBOP) (myflags cmd)) "body:" (cmd :json))
         (lamina/enqueue ch [(bit-or (flags :JSON) (flags :DBOP) (myflags cmd))
                             (cmd :json)]))))
     (lamina/enqueue ch [(flags :DBOP) "ok"])) ;; else if there are no new ops, send OK message
     ; (doseq [cmd ops]
     ;   (println "Sending CMD in fetchops:" (cmd :command)))))

(defn handle-json-msg
  "Handles an incoming JSON message from a peer"
  [data ch peer flag body]
  (let [msg (json/parse-string body (fn [k] (keyword k)))
        cmd (msg :method)
        key (msg :key)]
    ; (println "Handing MSG" cmd (msg :command) key)
    (condp = cmd
      "dbsync-offer" :>>  (fn [_] (let [host (get-peer-info data peer :host)
                                        port (get-peer-info data peer :port)]
                                    (add-peer-connection! data host port peer key :dbsync-connections)))
      "fetchops"     :>>  (fn [_] (send-ops-from ch nil (msg :lastop)))
      (print))
    ;; DBop messages only have a "command" field
    (when (msg :command)
      ;; Add the source field to each msg coming from the network
      (ono.db/dispatch-db-cmd flag (assoc msg :source (source-for-peer data peer))))))

(defn uncompress
  "Uncompresses the tcp request that has been compressed with zlib plus 4-byte big-endian size header.
   Returns the uncompressed bytes inflated into a utf-8 string."
   [bytes]
   (String. (byte-array (utils/inflate (Arrays/copyOfRange bytes 4 (count bytes)))) (java.nio.charset.Charset/forName "utf-8")))

(defn get-tcp-handler
  "Handles the TCP message for a specific peer"
  [data ch peer]
  (fn handle-tcp-request[[flag body]]
    ; (info "Connection msg:" peer flag)
    (if (test-flag flag (flags :COMPRESSED))
      (handle-tcp-request [(bit-and (bit-not (flags :COMPRESSED)) flag) ;; Remove COMPRESSED flag
                           (uncompress body)])                          ;; call ourselves w/ uncompressed body
      (condp test-flag flag
        (flags :SETUP) :>> (fn [_] (handle-handshake-msg ch peer flag body))
        (flags :PING)  :>> (fn [_] (print))  ;; Ignore PING messages for now, TODO if no ping in 10s, disconnect
        (flags :JSON)  :>> (fn [_] (handle-json-msg data ch peer flag body))))))

(defn add-peer-connection!
    "Adds a new peer's connection (main ControlConnection or secondary connection)
     and starts the TCP communication"
    [data, ip, port, foreign-dbid, key, connection-type]
    ;; Attempt to connect to the remote tomahawk
    ; (println "Asked to connect to peer, control or subsequent connection:" ip port foreign-dbid key connection-type)
    (lamina/on-realized (tcp/tcp-client {:host ip :port (Integer/parseInt port) :frame frame})
      (fn [ch]
        ;; Connection suceeded, here's our channel
        (let [handshake-msg (get-handshake-msg data foreign-dbid :control-connections key @db/dbid)]
                                                              ;; Get the handshake message before we add the
                                                              ;; peer to connection-map, because we need to check
                                                              ;; if this is our first connection (and thus controlconnection)
                                                              ;; by testing for existence of this peer in the connection map
          (add-connection! data connection-type foreign-dbid ch)
          (when-not (get-peer-info data foreign-dbid :host)
            (add-peer-info! data foreign-dbid :host ip)
            (add-peer-info! data foreign-dbid :port port))
          (lamina/receive-all ch (get-tcp-handler data ch foreign-dbid))
          (lamina/enqueue ch handshake-msg)))
          ; (if (= ch (get-in @data [:dbsync-connections foreign-dbid])) ;; HACK for development only, force fetch of all dbops
            ; (lamina/enqueue ch (generate-json {:method "fetchops" :lastop ""})))))
        (fn [ch]
          ;; Failed
          (println "Failed to connect to" ip port ch))))


(defn listen
    "Listens on our UDP port and watches for Tomahawk broadcasts. When found, calls addPeer
     with the newly found dbid, host and port."
    [_ data]
    (let [buf (byte-array dgram-size)
          packet (DatagramPacket. buf dgram-size)
          strval (do (.receive (get-udp-socket data) packet) (String. (.getData packet) 0 (.getLength packet)))]
        ; (println "Received packet:" strval)
        ;; We only support v2 broadcasts
        (let [parts        (clojure.string/split strval #":")]
            (if (and (= (count parts) 4) (= (first parts) "TOMAHAWKADVERT"))
              (let [ip           (last parts)
                    port         (nth parts 1)
                    foreign-dbid (nth parts 2)]
                ;; Initial setup in the control-connection uses a magic "whitelist" key
                ;; Make sure we are not already connected
                (when-not (get-connection data :control-connections foreign-dbid)
                  ;; Keep track of each peer by a sourceid. That will be used in the db
                  (let [sourceid (ono.db/get-or-insert-source! foreign-dbid ip)]
                    (add-peer-info! data foreign-dbid :sourceid sourceid)
                    (add-peer-connection! data ip port foreign-dbid "whitelist" :control-connections)))))))
   (send udp-listener listen data))

(defn start-udp
   "Starts the UDP listener and periodically sends
    UDP broacasts"
    []
    ; (reset! udp-sock (DatagramSocket. zeroconf-port))
    (let [opaque-data (set-udp-socket (peer-data) zeroconf-port)]
      ; (println "Beginning to listen on port " zeroconf-port "data:" (type opaque-data))
      (set-error-handler! udp-listener agent-error-handler)
      (send-off udp-listener listen opaque-data)
      (ping-peers opaque-data)
      opaque-data))


(defn stop-udp!
    "Stops all UDP sockets"
    [data]
    (.close (get-udp-socket data)))
