(ns bits.server.core
  (:require
    [clojure.string :as str]
    [rum.core :as rum]
    [ring.util.response :as response]
    
    [bits.core :as bits]))


(bits/require [bits.tonsky.time :as time :just [now]])


(def ^:dynamic dev? true) ;; FIXME
(def bit-edit-interval (* 24 60 60 1000)) ;; 24 hours


(defn url [path query]
  (->>
    (for [[k v] query
          :when (some? v)]
      (str (name k) "=" (java.net.URLEncoder/encode v "UTF-8")))
    (str/join "&")
    (str path "?")))


(defn editable? [bit]
  (< (- (time/now) (:bit/created bit)) bit-edit-interval))


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
    [:.header.spread
      [:a.header-title {:href "/"} "Clojure Bits"]
      [:.header-links
        (if (some? user)
          (list
            [:a.button.header-addbit {:href "/add-bit"} "Add Bit"]
            [:a.header-link {:href "#"} (or (:user/display-name user) (:user/namespace user) (:user/display-email user))]
            [:form {:action "/sign-out" :method "POST"}
              [:input {:type "hidden", :name "csrf-token", :value (:session/csrf-token session)}]
              [:button.header-signout "sign out"]])
          [:a.header-link {:href "/request-sign-in"} "sign in"])]]))


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