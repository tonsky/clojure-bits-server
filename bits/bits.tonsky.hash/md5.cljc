(defn md5
  "Calculates MD5 hash of a string"
  [^String str]
  (let [bytes (-> (java.security.MessageDigest/getInstance "MD5")
                  (.digest (.getBytes str)))]
    (-> (areduce bytes i s (StringBuilder. (* 2 (alength bytes)))
          (doto s
            (.append (Integer/toHexString (unsigned-bit-shift-right (bit-and (aget bytes i) 0xF0) 4)))
            (.append (Integer/toHexString (bit-and (aget bytes i) 0x0F)))))
        (.toString))))
