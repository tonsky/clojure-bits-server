(ns bits.server.repl
  (:require
    [clojure.string :as str]
    [clojure.tools.nrepl.server :as nrepl.server]
    [clojure.tools.namespace.repl :as namespace.repl]

    [cider.nrepl :as cider]
    [datascript.core :as ds]
    
    [bits.core :as bits]
    [bits.server :as server]
    [bits.server.db :as db]
    [bits.server.pages.sign-in :as pages.sign-in]))


(set! *warn-on-reflection* true)


(bits/require [bits.tonsky.time :as time :just [now]])


(defn fixtures! []
  (println "Inserting fixtures...")
  (ds/transact! db/*db
    [{ :db/id              -1
       :session/id         "tonsky"
       :session/created    (time/now)
       :session/csrf-token (pages.sign-in/new-token)
       :session/user       -2 }
     { :db/id              -2
       :user/display-email "prokopov@gmail.com"
       :user/display-name  "Nikita Prokopov"
       :user/email         "prokopov@gmail.com"
       :user/namespace     "tonsky" }
     { :user/display-email "james@booleanknot.com"
       :user/display-name  "James Reeves"
       :user/email         "james@booleanknot.com"
       :user/namespace     "weavejester" }
     { :user/display-email "prismatic-plumbing@googlegroups.com"
       :user/display-name  "Plumatic"
       :user/email         "prismatic-plumbing@googlegroups.com"
       :user/namespace     "plumatic" }])
  (ds/transact! db/*db
    (for [^java.io.File file  (file-seq (clojure.java.io/file "bits"))
          :when (.isFile file)
          :when (str/ends-with? (.getName file) ".cljc")
          :let  [path      (str (.getName (.getParentFile file))
                                "/"
                                (subs (.getName file) 0 (- (count (.getName file)) (count ".cljc"))))
                 fqn       (bits/path->fqn path)
                 body      (slurp file)
                 [namespace name] (str/split fqn #"/")
                 user-ns (second (str/split namespace #"\."))
                 parsed  (bits/parse-defn-form (bits/read-clojure body))]]
      { :bit/fqn       fqn
        :bit/namespace namespace
        :bit/name      name
        :bit/docstring (:docstring parsed)
        :bit/body      body
        :bit/author    (:db/id (ds/entity @db/*db [:user/namespace user-ns]))
        :bit/created   (.getTime #inst "2018-03-04T16:55:00.000") })))


(defn -main [& args]
  (nrepl.server/start-server
    :port 8888
    :handler cider/cider-nrepl-handler)
  (println "Started nREPL server at port 8888")
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
