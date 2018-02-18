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


(rum/defc index-page [_]
  [:div
    [:p "Index page"]
    [:p [:a {:href "/bits/tonsky/coll/find.edn"} "/bits/tonsky/coll/find.edn"]]
    [:p [:a {:href "/bits/tonsky/string/left_pad.edn"} "/bits/tonsky/string/left_pad.edn"]]])


(defn with-headers [handler headers]
  (fn [request]
    (some-> (handler request)
      (update :headers merge headers))))


(def routes
  ["" {:get {"/" (core/page index-page)}}])


(def app
  (some-fn
    (->
      (some-fn
        (bidi.ring/make-handler sign-in/routes)
        (bidi.ring/make-handler routes))
      (sign-in/wrap-session)
      (ring.middleware.params/wrap-params)
      (core/read-cookies)
      (with-headers { "Cache-Control" "no-cache"
                      "Expires"       "-1" })
      (print-errors))
    (->
      (bidi.ring/make-handler
        ["/bits" (bidi.ring/->Files {:dir "bits"})])
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