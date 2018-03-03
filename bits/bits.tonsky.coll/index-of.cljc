(defn index-of 
  "Return index of first element for which `(pred element)` returns true, `nil` otherwise"
  [pred xs]
  (loop [tail xs
         idx  0]
    (cond
      (empty? tail) nil
      (pred (first tail)) idx
      :else (recur (next tail) (inc idx)))))