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
    
    [bits.server.db :as db]
    [bits.server.core :as core]
    [bits.server.sign-in :as sign-in]))


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


(rum/defc header [req]
  (let [{:bits/keys [session user]} req]
    [:.header
      [:.header-inner
        [:.header-left
          [:a.header-title {:href "/"} "Clojure Bits"]]
        [:.header-right
          (if (some? user)
            (list
              [:.header-section.header-section_user 
                [:a {:href "#"} (:user/email user)]]
              [:.header-section.header-section_logout
                [:form {:action "/api/sign-out" :method "POST"}
                  [:input {:type "hidden", :name "csrf-token", :value (:session/csrf-token session)}]
                  [:button.header-signout "sign out"]]])
            (list
              [:.header-section.header-section_signin [:a {:href "/request-sign-in"} "sign in"]]))]]]))


(rum/defc page [req & children]
  [:html
    [:head
      [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
      [:title "Clojure Bits"]
      [:link { :rel "stylesheet" :type "text/css" :href "/static/style.css" }]]
      [:body
        (header req)
        children]])


(defn html-response [component]
  { :status  200
    :headers { "Content-Type" "text/html; charset=utf-8" }
    :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component)) })


(defn page-middleware [handler]
  (fn [req]
    (when-some [resp (handler req)]
      (html-response (page req resp)))))


(rum/defc index-page [_]
  [:div
    [:p "Index page"]
    [:p [:a {:href "/api/bits/tonsky/coll/find.edn"} "/bits/tonsky/coll/find.edn"]]
    [:p [:a {:href "/api/bits/tonsky/string/left_pad.edn"} "/bits/tonsky/string/left_pad.edn"]]])


(defn with-headers [handler headers]
  (fn [request]
    (some-> (handler request)
      (update :headers merge headers))))


(def page-routes
  ["" {:get {"/"                index-page
             "/request-sign-in" sign-in/request-sign-in-page
             "/sign-in-sent"    sign-in/sign-in-sent-page}}])


(def api-routes
  ["" {:get  {"/api/sign-in"         sign-in/api-sign-in}
       :post {"/api/request-sign-in" sign-in/api-request-sign-in
              "/api/sign-out"        sign-in/api-sign-out}}])


(def app
  (some-fn
    (->
      (some-fn
        (page-middleware
          (bidi.ring/make-handler page-routes))
        (bidi.ring/make-handler api-routes))
      (sign-in/wrap-session)
      (ring.middleware.params/wrap-params)
      (core/read-cookies)
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
      (with-headers (if core/dev?
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