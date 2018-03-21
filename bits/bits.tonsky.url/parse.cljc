(defn parse
  "Parses URL string to components (:location, :scheme, :domain, :port, :path, :query, :fragment)"
  [s]
  (let [[_ location _ query _ fragment] (re-matches #"([^?#]*)(\?([^#]*))?(\#(.*))?" s)
        [_ scheme domain port path]     (re-matches #"([a-zA-Z]+):///?([^/:]+)(?::(\d+))?(/.*)"  location)]
    { :location location
      :scheme   scheme
      :domain   domain
      :port     port
      :path     path
      :query    (when (some? query)
                  (->> (str/split query #"&")
                        (map (fn [s] (let [[k v] (str/split s #"=" 2)]
                                        [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                                        (when-not (str/blank? v) (java.net.URLDecoder/decode v "UTF-8"))])))
                        (reduce (fn [m [k v]]
                                  (if (contains? m k)
                                    (let [old-v (get m k)]
                                      (if (vector? old-v)
                                        (assoc m k (conj old-v v))
                                        (assoc m k [old-v v])))
                                    (assoc m k v))) {})))
      :fragment fragment }))