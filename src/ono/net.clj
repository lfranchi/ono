(ns ono.net
    (:require [ono.db :as db]
              [cheshire.core :as json]
              [gloss.io :as gio]
              [gloss.core :as gloss]
              [aleph.tcp :as tcp]
              [lamina.core :as lamina])
    (:import (java.net InetAddress DatagramPacket DatagramSocket)))

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
(def flags {1   :RAW
            2   :JSON
            4   :FRAGMENT
            8   :COMPRESSED
            16  :DBOP
            32  :PING
            64  :RESERVED
            128 :SETUP})
(defn flag-value
  [value]
  (some #(if (= (val %) value) (key %)) flags))

;; Ono<->Tomahawk connections
;; Keyed by dbid
(def control-connections (ref {}))
(def dbsync-connections (ref {}))

;; Bookkeeping: dbid<--> {:host :port}
(def known-peers (ref {}))

(def ping-agent (agent nil))

(def running true)

;; Network protocol
(def frame (gloss/finite-frame
 (gloss/prefix 
    :int32
    inc
    dec)
 [:ubyte (gloss/string :utf-8)]))

;; Utility functions
(defn agent-error-handler
  "Handles errors and exceptions from agents"
  [ag excp]
  (println "An agent threw an exception:" excp))

(defn generate-json
  "Generates a vector to be serialized from a map"
  [msg-data]
  [(flag-value :JSON) (json/generate-string msg-data)])

(defn get-handshake-msg
  "Returns a JSON handshake msg from zeroconf peers"
  [foreign-dbid, connection-map, key]
  (let [main-msg {:conntype "accept-offer"
                         :key key
                         :port tcp-port}]
          (if (dosync (connection-map foreign-dbid))
            (generate-json (assoc main-msg :controlid db/dbid)) ;; All subsequent (dbsync and stream connections) require controlid
            (generate-json (assoc main-msg :nodeid db/dbid))))) ;; ControlConnection (first connection) requires nodeid

; (defn get-dbconn-handler
;   "Returns a handler function for dbsync connections
;    that is bound do this particular peer"
;    [peer]
;    (fn [[flag body]]
;     (println "DBSyncConnection msg:" flag body)))

(defn ping-peers
  "Sends a PING message every 10 minutes to any
   active peer connection"
   [_]
   (doseq [ch (dosync (vals @control-connections))]
            (lamina/enqueue ch [(flag-value :PING) ""]))
   (. Thread (sleep 5000))
   (send-off ping-agent ping-peers))

(defn handle-handshake-msg
  "Handles the handshake after an initial SETUP message
   is received"
   [ch peer flag body]
   (println "Doing handshake!")
    (when (= body "4") ;; We only support protocol 4
      (lamina/enqueue ch [(flag-value :SETUP) "ok"])))

;; Forward-declare add-peer as it is required by handle-json-msg
;; but add-peer requires get-tcp-handler (which require handle-json-message)
(declare add-peer-connection)

(defn handle-json-msg
  "Handles an incoming JSON message from a peer"
  [ch peer body]
  (println "Got JSON message from:" peer body)
  (let [msg (json/parse-string body)
        cmd (msg "method")
        key (msg "key")]
    (condp = cmd
      "dbsync-offer" :>> (fn [_] (let [host (dosync ((known-peers peer) :host))
                                       port (dosync ((known-peers peer) :port))]
                                    (add-peer-connection host port peer key dbsync-connections))))))

(defn test-flag
  "Does a not-zero?-bit-and of the arguments"
  [x y]
  (not (zero? (bit-and x y))))

(defn get-tcp-handler
  "Handles the TCP message for a specific peer"
  [ch peer]
  (fn [[flag body]]
    (println "Connection msg:" peer flag body)
    (condp test-flag flag
      (flag-value :SETUP) :>> (fn [_] (handle-handshake-msg ch peer flag body))
      (flag-value :PING)  :>> (fn [_] (print))  ;; Ignore PING messages for now, TODO if no ping in 10s, disconnect
      (flag-value :JSON)  :>> (fn [_] (handle-json-msg ch peer body)))))

(defn add-peer-connection
    "Adds a new peer's connection (main ControlConnection or secondary connection)
     and starts the TCP communication"
    [ip, port, foreign-dbid, key, connection-map]
    ;; Attempt to connect to the remote tomahawk
    (println "Asked to connect to peer, control or subsequent connection:" ip port foreign-dbid key connection-map)
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
          (println "Sending connection message" handshake-msg)
          (lamina/enqueue ch handshake-msg)))
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
        (let [parts (clojure.string/split strval #":")]
            (if (and (= (count parts) 4) (= (first parts) "TOMAHAWKADVERT"))
              ;; Initial setup in the control-connection uses a magic "whitelist" key
              ;; Make sure we are not already connected
              (if-not (dosync (control-connections (nth parts 2)))
                (add-peer-connection (last parts) (nth parts 1) (nth parts 2) "whitelist" control-connections)))))
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
