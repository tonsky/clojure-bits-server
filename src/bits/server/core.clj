(ns bits.server.core
  (:require
    [clojure.string :as str]
    [rum.core :as rum]
    [ring.util.response :as response]))


(def ^:dynamic dev? true) ;; FIXME


(defn now []
  (System/currentTimeMillis))


(defmacro one-of? [x & opts]
  (let [xsym (gensym)]
   `(let [~xsym ~x]
      (or ~@(map (fn [o] `(= ~xsym ~o)) opts)))))


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


(defn escape-char [ch]
  (cond
    (<= (int \a) (int ch) (int \z))   ch
    (<= (int \A) (int ch) (int \Z))   ch
    (<= (int \0) (int ch) (int \9))   ch
    (one-of? ch \_ \- \. \+ \! \= \/) ch
    (<= 128 (int ch))                 ch
    :else (str "(" (str/upper-case (Integer/toString (int ch) 16)) ")")))


(defn fqn->path [fqn]
  (str/join (map escape-char fqn)))


(defn path->fqn [path]
  (str/replace path #"\(([0-9A-F]+)\)"
    (fn [[_ hex]]
      (str (char (Integer/parseInt hex 16))))))


(defn md5 [^String str]
  (let [bytes (-> (java.security.MessageDigest/getInstance "MD5")
                  (.digest (.getBytes str)))]
    (-> (areduce bytes i s (StringBuilder. (* 2 (alength bytes)))
          (doto s
            (.append (Integer/toHexString (unsigned-bit-shift-right (bit-and (aget bytes i) 0xF0) 4)))
            (.append (Integer/toHexString (bit-and (aget bytes i) 0x0F)))))
        (.toString))))


(defn spy [& xs]
  (apply prn xs)
  (last xs))


(defmacro cond+ [& clauses]
  (when clauses
    (let [[c1 c2 & cs] clauses]
      (cond
        (< (count clauses) 2) (throw (IllegalArgumentException. "cond requires an even number of forms"))
        (= c1 :let)          `(let ~c2 (cond+ ~@cs))
        (= c1 :do)           `(do ~c2 (cond+ ~@cs))
        :else                `(if ~c1 ~c2 (cond+ ~@cs))))))


(defn reader-some [form]
  (cond
    (map? form)
    `(reduce-kv (fn [m# k# v#] (if (some? v#) (assoc m# k# v#) m#)) {} ~form)

    (vector? form)
    `(reduce (fn [acc# el#] (if (some? el#) (conj acc# el#) acc#)) [] ~form)))


(defn not-blank [s]
  (if (str/blank? s) nil s))


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


(rum/defc header [req]
  (let [{:bits/keys [session user]} req]
    [:.header
      [:.header-inner
        [:.header-left
          [:a.header-title {:href "/"} "Clojure Bits"]]
        [:.header-right
          (if (some? user)
            (list
              [:.header-section.header-section_addbit
                [:a.button_addbit {:href "/add-bit"} "+ Add Function"]]
              [:.header-section.header-section_user 
                [:a {:href "#"} (:user/display-email user)]]
              [:form.header-section.header-section_logout
                {:action "/sign-out" :method "POST"}
                [:input {:type "hidden", :name "csrf-token", :value (:session/csrf-token session)}]
                [:button.button.header-signout "sign out"]])
            (list
              [:.header-section.header-section_signin [:a {:href "/request-sign-in"} "sign in"]]))]]]))


(rum/defc page-skeleton [req content]
  [:html
    [:head
      [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
      [:title "Clojure Bits"]
      [:link { :rel "stylesheet" :type "text/css" :href "/static/style.css" }]]
      [:body
        (header req)
        content]])


(rum/defc avatar-mask []
  [:svg {:height 0 :width 0}
    [:defs
      [:clipPath {:id "avatar-mask"}
        [:path {:d "M 0 25C 0 5.13828 5.13828 0 25 0C 44.8617 0 50 5.13827 50 25C 50 44.8617 44.8617 50 25 50C 5.13827 50 0 44.8617 0 25Z"}]]]])


(defn wrap-page [handler]
  (fn [req]
    { :status  200
      :headers { "Content-Type" "text/html; charset=utf-8" }
      :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup
                                          (page-skeleton req (handler req)))) }))


(defn wrap-auth [handler] ;; TODO remember user’s email address
  (fn [req]
    (if (nil? (:bits/user req))
      (response/redirect (url "/request-sign-in" {:error "session-expired"}))
      (handler req))))