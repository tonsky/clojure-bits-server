(defn not-blank
  "Returns nil if `s` is blank, `s` otherwise"
  [s]
  (if (clojure.string/blank? s) nil s))
