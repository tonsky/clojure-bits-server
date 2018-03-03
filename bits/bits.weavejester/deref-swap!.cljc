(defn deref-swap!
  "Atomically swaps the value of the atom to be `(apply f x args)`, where x is
  the current value of the atom, then returns the original value of the atom.
  This function therefore acts like an atomic `deref` then `swap!`."
  {:arglists '([atom f & args])}
  ([atom f]
   #?(:clj  (loop []
              (let [value @atom]
                (if (compare-and-set! atom value (f value))
                  value
                  (recur))))
      :cljs (let [value @atom]
              (reset! atom (f value))
              value)))
  ([atom f & args]
   (deref-swap! atom #(apply f % args))))
