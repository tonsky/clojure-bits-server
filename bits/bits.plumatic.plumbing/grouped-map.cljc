(defn grouped-map
  "Like group-by, but accepts a map-fn that is applied to values before
   collected."
  [key-fn map-fn coll]
  (persistent!
   (reduce
    (fn [ret x]
      (let [k (key-fn x)]
        (assoc! ret k (conj (get ret k []) (map-fn x)))))
    (transient {}) coll)))