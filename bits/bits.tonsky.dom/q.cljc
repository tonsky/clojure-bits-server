(defn q
  "Finds a dom element by selector"
  ([sel]
    (js/document.querySelector sel))
  ([el sel]
    (.querySelector el sel)))