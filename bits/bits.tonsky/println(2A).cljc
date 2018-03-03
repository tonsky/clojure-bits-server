(defn println*
  "Prints elements of collection each on a new line"
  [coll]
  (doseq [el coll]
    (println el))
  (println "Count:" (count coll)))