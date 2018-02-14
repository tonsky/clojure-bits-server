(ns bits.server
  (:require
    [clojure.string :as str]
    [clojure.stacktrace :as stacktrace]
    
    [rum.core :as rum]
    [bidi.bidi :as bidi]
    [datascript.core :as ds]
    [ring.middleware.params]
    [bidi.ring :as bidi.ring]
    [ring.util.response :as response]
    [org.httpkit.server :as http-kit]
    
    [bits.server.db :as db]))


(def ^:dynamic dev? true)
(def session-ttl-ms (* 14 24 60 60 1000)) ;; 2 weeks
(def sign-in-ttl-ms (* 15 60 1000)) ;; 15 minutes
(declare page-routes api-routes index-page sign-in-page email-sent-page api-request-sign-in api-sign-in api-sign-out)


(defn encode-uri-component [s]
  (-> s
      (java.net.URLEncoder/encode "UTF-8")
      (str/replace #"\+"   "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))


(defn path-for
  ([page]
    (or (bidi/path-for page-routes page)
        (bidi/path-for api-routes page)))
  ([page params]
    (str (path-for page) 
      "?" (str/join "&" (for [[k v] params :when (some? v)] (str (name k) "=" (encode-uri-component v)))))))


(defn spy [res]
  (println res)
  res)


(defn now []
  (System/currentTimeMillis))


(defn print-errors [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (.printStackTrace e)
        { :status 500
          :headers { "Content-type" "text/plain; charset=utf-8" }
          :body (with-out-str
                  (stacktrace/print-stack-trace (stacktrace/root-cause e))) }))))


(defn check-session [req]
  (when (nil? (:request/user req))
    { :status 401
      :body   (str "Unauthorized, session_id: " (get-in req [:cookies "bits_session_id"])) }))


(def ^:dynamic *session* nil)
(def ^:dynamic *user* nil)


(rum/defc header []
  [:.header
    [:.header-inner
      [:.header-left
        [:a.header-title {:href (path-for index-page)} "Clojure Bits"]]
      [:.header-right
        (if (some? *user*)
          (list
            [:.header-section.header-section_user (:user/email *user*)]
            [:.header-section.header-section_logout
              [:form {:action (path-for api-sign-out) :method "POST"}
                [:input {:type "hidden", :name "csrf-token", :value (:session/csrf-token *session*)}]
                [:button.header-signout "sign out"]]])
          (list
            [:.header-section.header-section_signin [:a {:href "/sign-in"} "sign in"]]))]]])


(rum/defc page [& children]
  [:html
    [:head
      [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
      [:title "Clojure Bits"]
      [:link { :rel "stylesheet" :type "text/css" :href "/static/style.css" }]]
      [:body
        (header)
        children]])


(rum/defc index-page [_]
  [:div
    [:p "Index page"]
    [:p [:a {:href "/api/bits/tonsky/coll/find.edn"} "/bits/tonsky/coll/find.edn"]]
    [:p [:a {:href "/api/bits/tonsky/string/left_pad.edn"} "/bits/tonsky/string/left_pad.edn"]]])


(rum/defc sign-in-page [req]
  (let [{:strs [error email]} (:query-params req)]
    [:.page.page_middle
      [:form.signin { :action (path-for api-request-sign-in) :method "POST" }
        (case error
          nil                     nil
          "malformed-address"     [:.signin-message "Malformed email"]
          "email-failure"         [:.signin-message "Oops. Can’t sent mail right now. Please try again later"]
          "csrf-token-invalid"    [:.signin-message "Oops. Something went wrong. Please try once more"]
          "sign-in-token-expired" [:.signin-message "This sign-in link has expired. Please request a new one"]
          "sign-in-token-invalid" [:.signin-message "Sorry, this link doesn’t work anymore. Please request a new one"])
        [:input {:type "hidden" :name "csrf-token" :value (:session/csrf-token *session*)}]
        [:input.signin-email {:type "text" :name "email" :placeholder "Email" :value (or email "prokopov@gmail.com")}] ;; FIXME
        [:button.button.signin-submit "Sign In"]]]))


(defn send-sign-in! [email]
  (let [user (db/insert! db/*db
               { :user/email           email
                 :user.sign-in/token   (db/new-token)
                 :user.sign-in/created (now) })]
    (if true ;; TODO sent email
      (response/redirect
        (path-for email-sent-page
          {:message       "sent"
           :email         email
           :sign-in-token (:user.sign-in/token user)}))
      (response/redirect
        (path-for sign-in-page
          {:error "email-failure"
           :email email})))))


(defn normalize-email [email]
  (let [[user domain] (-> email (str/trim) (str/split #"@"))]
    (str user "@" (str/lower-case domain))))


(defn api-request-sign-in [req]
  (let [{:strs [csrf-token email]} (:form-params req)]
    (cond
      (some? *user*)
      (response/redirect (path-for index-page))

      (not= csrf-token (:session/csrf-token *session*))
      (response/redirect (path-for sign-in-page {:error "csrf-token-invalid"
                                                 :email email}))

      (not (re-matches #"\s*[^@\s]+@[^@.\s]+\.[^@\s]+\s*" email))
      (response/redirect (path-for sign-in-page {:error "malformed-address"
                                                 :email email}))

      :else
      (let [email (normalize-email email)
            db    @db/*db
            user  (ds/entity db [:user/email email])]
        (cond
          ;; no user
          (nil? user)
          (do
            (db/insert! db/*db {:user/email email})
            (send-sign-in! email))

          ;; user, no token
          (nil? (:user.sign-in/token user))
          (send-sign-in! email)

          ;; user, token, not expired yet
          (<= (- (now) (:user.sign-in/created user)) sign-in-ttl-ms)
          (response/redirect 
            (path-for email-sent-page
              {:message       "already-sent"
               :email         email
               :sign-in-token (when dev? (:user.sign-in/token user))}))
          
          ;; user, token, expired
          :else
          (do
            (ds/transact! db/*db
              [[:db.fn/retractAttribute (:db/id user) :user.sign-in/token]
               [:db.fn/retractAttribute (:db/id user) :user.sign-in/created]])
            (send-sign-in! email)))))))


(rum/defc email-sent-page [req]
  (let [{:strs [message email sign-in-token]} (:query-params req)]
    [:.page.page_middle
      [:.message
        [:h1 "Check your inbox"]
        (case message
          "sent"         [:p "Magic sign in link has been sent to " [:em email]]
          "already-sent" [:p "The link we’ve sent to " [:em email] " is still valid"])
        (when (some? sign-in-token)
          [:p
            [:a.button
              {:href (path-for api-sign-in {:sign-in-token sign-in-token
                                            :email email})}
              "Psst... Sign in here"]])]]))


(defn api-sign-in [req]
  (let [{:strs [email sign-in-token]} (:query-params req)
        db   @db/*db
        user (ds/entity db [:user.sign-in/token sign-in-token])]
    (cond
      (some? *user*)
      (response/redirect (path-for index-page))

      (nil? user)
      (response/redirect (path-for sign-in-page {:error "sign-in-token-invalid", :email email}))

      (> (- (now) (:user.sign-in/created user)) sign-in-ttl-ms)
      (response/redirect (path-for sign-in-page {:error "sign-in-token-expired", :email email}))

      :else
      (do
        (ds/transact! db/*db
          [[:db.fn/retractAttribute (:db/id user) :user.sign-in/token]
           [:db.fn/retractAttribute (:db/id user) :user.sign-in/created]
           [:db/add (:db/id *session*) :session/user (:db/id user)]])
        (response/redirect (path-for index-page))))))


(defn api-sign-out [req]
  (let [{:strs [csrf-token]} (:form-params req)]
    (cond
      (not= csrf-token (:session/csrf-token *session*))
      (response/redirect (path-for index-page))
      
      (nil? *user*)
      (response/redirect (path-for index-page))

      :else
      (do
        (ds/transact! db/*db [[:db.fn/retractEntity (:db/id *session*)]])
        (response/redirect (path-for index-page))))))


(defn html-response [component]
  { :status  200
    :headers { "Content-Type" "text/html; charset=utf-8" }
    :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component)) })


(defn with-headers [handler headers]
  (fn [request]
    (some-> (handler request)
      (update :headers merge headers))))


(defn read-cookies [handler]
  (fn [req]
    (let [cookie  (get-in req [:headers "cookie"] "")
          cookies (into {} (for [s     (str/split cookie #";\s*")
                                 :when (not (str/blank? s))
                                 :let  [[k v] (str/split s #"=")]]
                             [k {:value v}]))]
      (handler (assoc req :cookies cookies)))))


(defn set-cookie [resp name value attrs]
  (update resp :headers conj ["Set-Cookie" 
    (str name "=" value ";HttpOnly;" (when-not dev? "Secure;") "SameSite=Lax;Path=/;" (str/join ";" (for [[k v] attrs] (str k "=" v))))]))


(defn wrap-session [handler]
  (fn [req]
    (let [session-id (:value (get-in req [:cookies "bits_session_id"]))
          session    (ds/entity @db/*db [:session/id session-id])
          session'   (or session
                         (db/insert! db/*db
                           { :session/id         (db/new-token)
                             :session/created    (now)
                             :session/accessed   (now)
                             :session/csrf-token (db/new-token) }))]
      (binding [*session* session'
                *user*    (:session/user session)]
        (some->
          (handler (assoc req :session session'))
          (cond-> (nil? session)
            (set-cookie "bits_session_id" (:session/id session') {"Max-Age" (/ session-ttl-ms 1000)})))))))


(defn page-middleware [handler]
  (fn [req]
    (some-> (handler req) (page) (html-response))))


(def page-routes
  ["" {:get
        {"/"           index-page
         "/sign-in"    sign-in-page
         "/email-sent" email-sent-page}}])


(def api-routes
  ["" {:get
         {"/api/sign-in" api-sign-in}
       :post
         {"/api/request-sign-in" api-request-sign-in
          "/api/sign-out"        api-sign-out}}])


(def app
  (some-fn
    (->
      (some-fn
        (-> (bidi.ring/make-handler page-routes)
            (page-middleware))
        (bidi.ring/make-handler api-routes))
      (wrap-session)
      (ring.middleware.params/wrap-params)
      (read-cookies)
      (with-headers { "Cache-Control" "no-cache"
                      "Expires"       "-1" })
      (print-errors))
    (->
      (bidi.ring/make-handler
        ["/api/bits" (bidi.ring/->Files {:dir "bits"})])
      (with-headers { "Cache-Control" "no-cache"
                      "Expires"       "-1" }))
    (->
      (bidi.ring/make-handler
        ["/static" (bidi.ring/->Resources {:prefix "bits/static"})])
      (with-headers (if dev?
                      { "Cache-Control" "no-cache"
                        "Expires"       "-1" }
                      { "Cache-Control" "max-age=315360000" })))
    (fn [req]
      { :status 404
        :body "404 Not found" })))


(defonce *server (atom nil))


(defn start! [port]
  (when (nil? @*server)
    (println "Starting web server on port" port)
    (reset! *server (http-kit/run-server #'app { :port port }))))


(defn stop! []
  (when-some [stop-fn @*server]
    (println "Stopping web server")
    (stop-fn)
    (reset! *server nil)))


(defn -main [& args]
  (let [args-map (apply array-map args)
        port-str (or (get args-map "-p")
                     (get args-map "--port")
                     "6001")]
    (start! (Integer/parseInt port-str))))


(comment
  (bits.server.repl/restart!)
)