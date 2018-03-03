(defn find
  "Returns first element that matches `pred`"
  [pred xs]
  (reduce (fn [_ x] (when (pred x) (reduced x))) nil xs))