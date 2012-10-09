(ns ono.net
    (:require [cheshire.core :as json]
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
(def port 55555)
(def dgram-size 16384)
(def udp-listener (agent nil))
(def udp-sock (ref nil))

;; Ono<->Tomahawk connections
(def peers (ref (list)))

(def running true)

;; Network protocol
(def frame (gloss/finite-frame
 (gloss/prefix 
    [:int32 :byte]
    first
    (fn [x] [x 2]))
 (gloss/string :utf-8)))


(defn addPeer
    "Adds a new peer and starts the TCP communication"
    [ip, port, dbid]
    ;; Attempt to connect to the remote tomahawk
    
    )


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
    (dosync (ref-set udp-sock (DatagramSocket. port)))
    (println "Beginning to listen on port " port)
    (send-off udp-listener listen))


(defn stop-udp
    "Stops all UDP sockets"
    []
    (dosync 
        (.close @udp-sock)))
