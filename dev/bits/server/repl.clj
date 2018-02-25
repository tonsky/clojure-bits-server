(ns bits.server.repl
  (:require
    [cider.nrepl :as cider]
    [clojure.tools.nrepl.server :as nrepl.server]
    [clojure.tools.namespace.repl :as namespace.repl]
    [bits.server :as server]
    [bits.server.db :as db]
    [datascript.core :as ds]
    [bits.server.pages.sign-in :as pages.sign-in]))


(defn fixtures! []
  (ds/transact! db/*db
    [{ :db/id -1
       :session/id         "JPas4Y3RqNdldQZMXs9fPClQuUptETck"
       :session/created    (System/currentTimeMillis)
       :session/csrf-token (pages.sign-in/new-token)
       :session/user       -2 }
     { :db/id              -2
       :user/display-email "prokopov@gmail.com"
       :user/email         "prokopov@gmail.com"
       :user/namespace     "tonsky"}]))


(defn -main [& args]
  (nrepl.server/start-server
    :port 8889
    :handler cider/cider-nrepl-handler)
  (println "Started nREPL server at port 8889")
  (bits.server/start! 6001)
  (fixtures!))
  

(defn restart! []
  (do
    (bits.server/stop!)
    (clojure.tools.namespace.repl/refresh)
    ((ns-resolve *ns* 'bits.server/start!) 6001)
    ((ns-resolve *ns* 'bits.server.repl/fixtures!))
    :done)
)