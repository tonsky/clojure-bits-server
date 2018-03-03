(defn queue
  "Creates an empty persistent queue, or one populated with a collection."
  ([] #?(:clj  clojure.lang.PersistentQueue/EMPTY
         :cljs cljs.core.PersistentQueue.EMPTY))
  ([coll] (into (queue) coll)))
