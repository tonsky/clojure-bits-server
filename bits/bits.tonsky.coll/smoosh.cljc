(defn smoosh
  "Concats all nested collections in `coll` recursively up to the `depth` (1 by default)"
  ([coll] (smoosh 1 coll))
  ([depth coll]
    (if (pos? depth)
      (persistent!
        (reduce #(if (sequential? %2)
                  (reduce conj! %1 (smoosh (dec depth) %2))
                  (conj! %1 %2))
                (transient []) coll))
      coll)))