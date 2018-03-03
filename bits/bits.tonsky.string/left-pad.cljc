(defn left-pad
  "Pads string with characters on the left"
  ([s len]
    (left-pad s len \space))
  ([s len ch]
    (let [c (count s)]
      (if (>= c len)
        s
        #?(:clj  (let [sb (StringBuilder. ^long len)]
                   (dotimes [_ (- len c)]
                     (.append sb ^Character ch))
                   (.append sb ^String s)
                   (str sb))
           :cljs (str (clojure.string/join (repeat (- len c) ch)) s))))))