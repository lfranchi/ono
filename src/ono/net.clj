(ns ono.net
    (:import (java.net InetAddress DatagramPacket DatagramSocket)))

;; Namespace for network related operations
;;
;; Listens on UDP port 50210 for broadcasts
;; Handles socket connections to other tomahawk/ono instances

(def port 55555)
(def dgram-size 16384)

(def udp-listener (agent nil))
(def udp-sock (ref nil))

(def running true)

(defn listen
    "UDP listener"
    [_]
    (let [buf (byte-array dgram-size)
          packet (DatagramPacket. buf dgram-size)
          strval (dosync (.receive @udp-sock packet) (String. (.getData packet) 0 (.getLength packet)))]
        ; (println "Received packet:" strval)
        ;; We only support v2 broadcasts
        (let [parts (clojure.string/split strval #":")]
            (if (and (= (count parts) 4) (= (first parts) "TOMAHAWKADVERT"))
                (println "Tomahawk advert from: " (last parts) (nth parts 1)))))
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
