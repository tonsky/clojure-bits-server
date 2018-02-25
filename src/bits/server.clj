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
    [bits.server.pages.sign-in :as pages.sign-in]
    [bits.server.pages.claim-ns :as pages.claim-ns]
    [bits.server.pages.add-bit :as pages.add-bit]
    [bits.server.pages.view-bit :as pages.view-bit]
    ))


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


(rum/defc index-page [req]
  (let [db (:bits/db req)]
    [:.page.page_center
      (for [d (ds/datoms db :avet :bit/fqn)
            :let [bit (ds/entity db (:e d))
                  fqn (:bit/fqn bit)]]
        [:p [:a {:href (str "/bits/" (core/fqn->path fqn))} fqn]])]))


(defn with-headers [handler headers]
  (fn [request]
    (some-> (handler request)
      (update :headers merge headers))))


(def routes
  {"/" {:get (core/wrap-page index-page)}})


(def app
  (some-fn
    (->
      (bidi.ring/make-handler
        ["" (merge routes
                   pages.sign-in/routes
                   pages.claim-ns/routes
                   pages.add-bit/routes
                   pages.view-bit/routes)])
      (pages.sign-in/wrap-session)
      (db/wrap-db)
      (ring.middleware.params/wrap-params)
      (core/read-cookies)
      (with-headers { "Cache-Control" "no-cache"
                      "Expires"       "-1" })
      (print-errors))
    (->
      (bidi.ring/make-handler
        ["/bits-edn" (bidi.ring/->Files {:dir "bits"})])
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