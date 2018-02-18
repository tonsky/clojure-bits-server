(ns bits.server.core
  (:require
    [clojure.string :as str]))


(def ^:dynamic dev? true) ;; FIXME


(defn now []
  (System/currentTimeMillis))


(defn encode-uri-component [s]
  (-> s
      (java.net.URLEncoder/encode "UTF-8")
      (str/replace #"\+"   "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))


(defn url [path query]
  (->>
    (for [[k v] query
          :when (some? v)]
      (str (name k) "=" (encode-uri-component v)))
    (str/join "&")
    (str path "?")))


(defn spy [res]
  (println res)
  res)


(defn read-cookies [handler]
  (fn [req]
    (let [cookie  (get-in req [:headers "cookie"] "")
          cookies (into {} (for [s     (str/split cookie #";\s*")
                                 :when (not (str/blank? s))
                                 :let  [[k v] (str/split s #"=")]]
                             [k {:value v}]))]
      (handler (assoc req :cookies cookies)))))


(defn set-cookie [resp name value attrs]
  (update resp :headers conj
    ["Set-Cookie" (str name "=" value 
                       ";HttpOnly"
                       (when-not dev? ";Secure")
                       ";SameSite=Lax"
                       ";Path=/"
                       (str/join (for [[k v] attrs] (str ";" k "=" v))))]))