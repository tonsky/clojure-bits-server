(defn frequencies-fast
  "Like clojure.core/frequencies, but faster.
   Uses Java's equal/hash, so may produce incorrect results if
   given values that are = but not .equal"
  [xs]
  (let [res (java.util.HashMap.)]
    (doseq [x xs]
      (.put res x (unchecked-inc (int (or (.get res x) 0)))))
    (into {} res)))