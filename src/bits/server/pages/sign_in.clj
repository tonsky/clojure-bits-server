(ns bits.server.pages.sign-in
  (:require
    [clojure.string :as str]
    
    [rum.core :as rum]
    [datascript.core :as ds]
    [ring.util.response :as response]

    [bits.core :as bits]    
    [bits.server.db :as db]
    [bits.server.core :as core]))


(bits/require [bits.tonsky.time :as time :just [now]])


(def session-ttl-ms (* 14 24 60 60 1000)) ;; 2 weeks
(def sign-in-ttl-ms (* 15 60 1000)) ;; 15 minutes


(defn new-token
  ([] (new-token 32))
  ([len] (new-token len "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"))
  ([len alphabet]
    (let [random (java.security.SecureRandom.)
          sb     (StringBuilder. len)]
      (dotimes [_ len]
        (.append sb (.charAt alphabet (.nextInt random (.length alphabet)))))
      (str sb))))


(rum/defc request-sign-in-page [req]
  (let [{:strs [error email]} (:query-params req)]
    [:.page.page_centered
      [:.page_500.column
        [:h2 "Signing In"]
        (case error
          nil                     nil
          "session-expired"       [:.error "> Sorry, your session has expired. Please sign in again"]
          "malformed-address"     [:.error "> Malformed email"]
          "email-failure"         [:.error "> Oops. Can’t sent mail right now. Please try again later"]
          "csrf-token-invalid"    [:.error "> Oops. Something went wrong. Please try once more"]
          "sign-in-token-expired" [:.error "> This sign-in link has expired. Please request a new one"]
          "sign-in-token-invalid" [:.error "> Sorry, this link doesn’t work anymore. Please request a new one"])
        [:form.row { :action "/request-sign-in" :method "POST" }
          [:input {:type "hidden" :name "csrf-token" :value (:session/csrf-token (:bits/session req))}]
          [:input.row-stretch {:type "text" :autofocus true :name "email" :placeholder "Email" :value (or email "prokopov@gmail.com")}] ;; FIXME
          [:button.button.signin-submit "Sign In"]]]]))


(defn send-sign-in! [user email display-email]
  (let [token (new-token)]
    (ds/transact! db/*db
      [{ :db/id                (:db/id user)
         :user.sign-in/token   token
         :user.sign-in/created (time/now) }])
    (if true ;; TODO sent email
      (response/redirect
        (core/url "/sign-in-sent"
          (cond-> {:message "sent"
                   :email   display-email}
            core/dev? (assoc :sign-in-token token))))
      (response/redirect
        (core/url "/request-sign-in"
          {:error "email-failure"
           :email display-email})))))


(defn normalize-email [display-email]
  (str/lower-case display-email))


(defn api-request-sign-in [req]
  (let [{:strs [csrf-token email]} (:form-params req)
        display-email (str/trim email)
        email         (normalize-email display-email)]
    (cond
      (some? (:bits/user req))
      (response/redirect-after-post "/")

      (not= csrf-token (:session/csrf-token (:bits/session req)))
      (response/redirect-after-post (core/url "/request-sign-in" {:error "csrf-token-invalid"
                                                                  :email display-email}))

      (not (re-matches #"\s*[^@\s]+@[^@.\s]+\.[^@\s]+\s*" email))
      (response/redirect-after-post (core/url "/request-sign-in" {:error "malformed-address"
                                                                  :email display-email}))

      :else
      (let [db   @db/*db
            user (ds/entity db [:user/email email])]
        (cond
          ;; no user
          (nil? user)
          (let [new-user (db/insert! db/*db {:user/email email
                                             :user/display-email display-email})]
            (send-sign-in! new-user email display-email))

          ;; user, no token
          (nil? (:user.sign-in/token user))
          (send-sign-in! user email display-email)

          ;; user, token, not expired yet
          (<= (- (time/now) (:user.sign-in/created user)) sign-in-ttl-ms)
          (response/redirect-after-post 
            (core/url "/sign-in-sent"
              {:message       "already-sent"
               :email         display-email
               :sign-in-token (when core/dev? (:user.sign-in/token user))}))
          
          ;; user, token, expired
          :else
          (do
            (ds/transact! db/*db
              [[:db.fn/retractAttribute (:db/id user) :user.sign-in/token]
               [:db.fn/retractAttribute (:db/id user) :user.sign-in/created]])
            (send-sign-in! user email display-email)))))))


(rum/defc sign-in-sent-page [req]
  (let [{:strs [message email sign-in-token]} (:query-params req)]
    [:.page.page_centered
      [:.message.column
        [:h2 "Check your inbox"]
        (case message
          "sent"         [:p "Magic sign in link has been sent to " [:em email]]
          "already-sent" [:p "The link we’ve sent to " [:em email] " is still valid"])
        (when (some? sign-in-token)
          [:p
            [:a
              {:href (core/url "/sign-in" {:sign-in-token sign-in-token
                                          :email email})}
              "Psst... Sign in here"]])]]))


(defn sign-in [req]
  (let [{:strs [email sign-in-token]} (:query-params req)
        db   @db/*db
        user (ds/entity db [:user.sign-in/token sign-in-token])]
    (cond
      (some? (:bits/user req))
      (response/redirect "/")

      (nil? user)
      (response/redirect (core/url "/request-sign-in" {:error "sign-in-token-invalid", :email email}))

      (> (- (time/now) (:user.sign-in/created user)) sign-in-ttl-ms)
      (response/redirect (core/url "/request-sign-in" {:error "sign-in-token-expired", :email email}))

      :else
      (do
        (ds/transact! db/*db
          [[:db.fn/retractAttribute (:db/id user) :user.sign-in/token]
           [:db.fn/retractAttribute (:db/id user) :user.sign-in/created]
           [:db/add (:db/id (:bits/session req)) :session/user (:db/id user)]])
        (response/redirect "/")))))


(defn sign-out [req]
  (let [{:strs [csrf-token]} (:form-params req)
        session (:bits/session req)]
    (cond
      (not= csrf-token (:session/csrf-token session))
      (response/redirect-after-post "/")
      
      (nil? (:bits/user req))
      (response/redirect-after-post "/")

      :else
      (do
        (ds/transact! db/*db [[:db.fn/retractEntity (:db/id session)]])
        (response/redirect-after-post "/")))))


(defn wrap-session [handler]
  (fn [req]
    (let [session-id (:value (get-in req [:cookies "bits_session_id"]))
          session    (ds/entity @db/*db [:session/id session-id])
          session'   (or session
                         (db/insert! db/*db
                           { :session/id         (new-token)
                             :session/created    (time/now)
                             :session/csrf-token (new-token) }))
          req'       (assoc req
                       :bits/session session'
                       :bits/user (:session/user session))]
      (some->
        (handler req')
        (cond-> (nil? session)
          (core/set-cookie "bits_session_id" (:session/id session') {"Max-Age" (/ session-ttl-ms 1000)}))))))


(def routes
  {"/request-sign-in" {:get  (core/wrap-page #'request-sign-in-page)
                       :post #'api-request-sign-in}
   "/sign-in-sent"    {:get (core/wrap-page #'sign-in-sent-page)}
   "/sign-in"         {:get #'sign-in}
   "/sign-out"        {:post #'sign-out}})