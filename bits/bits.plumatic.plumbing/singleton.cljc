(defn singleton
  "returns (first xs) when xs has only 1 element"
  [xs]
  (when-let [xs (seq xs)]
    (when-not (next xs)
      (first xs))))
