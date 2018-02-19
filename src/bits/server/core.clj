(ns bits.server.core
  (:require
    [clojure.string :as str]
    [rum.core :as rum]
    [ring.util.response :as response]))


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