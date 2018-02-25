(ns bits.server.repl
  (:require
    [cider.nrepl :as cider]
    [clojure.tools.reader.edn :as reader.edn]
    [clojure.tools.nrepl.server :as nrepl.server]
    [clojure.tools.namespace.repl :as namespace.repl]
    [bits.server :as server]
    [bits.server.db :as db]
    [datascript.core :as ds]
    [bits.server.core :as core]
    [bits.server.pages.sign-in :as pages.sign-in]))


(defn fixtures! []
  (println "Fixtures:")
  (println "  inserting prokopov@gmail.com")
  (ds/transact! db/*db
    (concat
      [{ :db/id              -1
         :session/id         "ZFcB6peMogWWQ45hq4w5x9xAl0-a4fXE"
         :session/created    (System/currentTimeMillis)
         :session/csrf-token (pages.sign-in/new-token)
         :session/user       -2 }
       { :db/id              -2
         :user/display-email "prokopov@gmail.com"
         :user/display-name  "Nikita Prokopov"
         :user/email         "prokopov@gmail.com"
         :user/namespace     "tonsky" }
       { :db/id              -3
         :user/display-email "james@reeves.com"
         :user/display-name  "James Reeves"
         :user/email         "james@reeves.com"
         :user/namespace     "weavejester" }
       { :db/id              -4
         :user/display-email "prismatic-plumbing@googlegroups.com"
         :user/display-name  "Plumatic"
         :user/email         "prismatic-plumbing@googlegroups.com"
         :user/namespace     "plumatic" }]
    (for [[fqn author] [["bits.tonsky.coll/find" -2]
                        ["bits.tonsky.coll/index-of" -2]
                        ["bits.tonsky.coll/zip" -2]
                        ["bits.tonsky.dom/q" -2]
                        ["bits.tonsky/println*" -2]
                        ["bits.tonsky.string/left-pad" -2]
                        ["bits.weavejester.maps/assoc-some" -3]
                        ["bits.weavejester.maps/dissoc-in"  -3]
                        ["bits.weavejester.maps/map-kv"     -3]
                        ["bits.weavejester/queue?"          -3]
                        ["bits.weavejester/deref-swap!"     -3]
                        ["bits.plumatic.plumbing/frequencies-fast" -4]
                        ["bits.plumatic.plumbing/grouped-map"      -4]
                        ["bits.plumatic.plumbing/millis"           -4]
                        ["bits.plumatic.plumbing/singleton"        -4]]
          :let [_ (println "  inserting" fqn)
                bit (reader.edn/read-string (slurp (str "bits/" (core/fqn->path fqn) ".edn")))]]
      (merge
        { :bit/fqn       fqn
          :bit/author    author }
        (select-keys bit [:bit/namespace :bit/name :bit/docstring :bit/body-clj :bit/body-cljs]))))))


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
