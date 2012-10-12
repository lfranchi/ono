(ns ono.utils
    (:import java.util.zip.Inflater))

;; Following inflate code taken from GNUnet-in-clojure
;;  https://github.com/amatus/GNUnet-in-Clojure/blob/master/src/main/clojure/org/gnu/clojure/gnunet/zip.clj
(defn- inflate!
  [inflater byte-seq]
  (if (.needsInput inflater)
    (when-not (empty? byte-seq)
      (let [input (take 256 byte-seq)
            byte-seq (drop 256 byte-seq)
            _ (.setInput inflater (byte-array input))
            output (byte-array 256)
            output-length (.inflate inflater output)]
        (lazy-seq (concat (take output-length output)
                          (inflate! inflater byte-seq)))))
    (let [output (byte-array 256)
          output-length (.inflate inflater output)]
      (lazy-seq (concat (take output-length output)
                        (inflate! inflater byte-seq))))))

(defn inflate
  [byte-seq]
  (let [inflater (Inflater.)]
    (inflate! inflater byte-seq)))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))