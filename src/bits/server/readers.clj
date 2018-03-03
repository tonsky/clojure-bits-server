(ns bits.server.readers
  (:refer-clojure :exclude [some]))


(defn trace [form]
 `(let [res# ~form]
    (println "[ TRACE ]" (pr-str res#))
    res#))


(defn some [form]
  (cond
    (map? form)
    `(reduce-kv (fn [m# k# v#] (if (some? v#) (assoc m# k# v#) m#)) {} ~form)

    (vector? form)
    `(reduce (fn [acc# el#] (if (some? el#) (conj acc# el#) acc#)) [] ~form)))
