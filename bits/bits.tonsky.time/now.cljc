(defn now
  "Current time in milliseconds, since UNIX epoch"
  ^long []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))
