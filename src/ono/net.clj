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
(def peers (ref {}))
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
  [foreign-dbid]
  (generate-json {:conntype "accept-offer"
                         :nodeid db/dbid
                         :key "whitelist"
                         :port tcp-port}))

(defn ping-peers
  "Sends a PING message every 10 minutes to any
   active peer connection"
   [_]
   (doseq [ch (dosync (vals @peers))]
            (lamina/enqueue ch [(flag-value :PING) ""]))
   (. Thread (sleep 5000))
   (send-off ping-agent ping-peers))

(defn handle-handshake-msg
  "Handles the handshake after an initial SETUP message
   is received"
   [peer flag body]
    (when (= body "4") ;; We only support protocol 4
      (let [ch (dosync (peers peer))]
        (lamina/enqueue ch [(flag-value :SETUP) "ok"])

      )))

(defn handle-tcp-msg
  "Handles the TCP message for a specific peer"
  [peer]
  (fn [[flag body]]
    (println "Got TCP message on channel from peer:" peer flag body)
    ((condp = (flags flag)
      :SETUP #(handle-handshake-msg peer flag body)
      :PING #(print))))) ;; Ignore PING messages for now

(defn addPeer
    "Adds a new peer and starts the TCP communication"
    [ip, port, foreign-dbid]
    ;; Attempt to connect to the remote tomahawk
    ; (println "Found broadcast from peer:" ip port dbid)
    (if-not (dosync (peers foreign-dbid)) ;; Ignore if already connected
      (lamina/on-realized (tcp/tcp-client {:host ip :port (Integer/parseInt port) :frame frame})
        (fn [ch]
          ;; Connection suceeded, here's our channel
          (dosync
            (alter peers assoc foreign-dbid ch))
          (lamina/receive-all ch (handle-tcp-msg foreign-dbid))
          (lamina/enqueue ch (get-handshake-msg foreign-dbid))
          (println "Sent initial msg"))
        (fn [ch]
          ;; Failed
          (println "Failed to connect to" ip port ch)))))


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
              (addPeer (last parts) (nth parts 1) (nth parts 2)))))
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
