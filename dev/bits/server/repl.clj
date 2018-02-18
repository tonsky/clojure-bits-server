(ns bits.server.repl
  (:require
    [cider.nrepl :as cider]
    [clojure.tools.nrepl.server :as nrepl.server]
    [clojure.tools.namespace.repl :as namespace.repl]
    [bits.server :as server]))


(defn -main [& args]
  (nrepl.server/start-server
    :port 8889
    :handler cider/cider-nrepl-handler)
  (println "Started nREPL server at port 8889")
  (bits.server/start! 6001))


(defn restart! []
  (do
    (bits.server/stop!)
    (clojure.tools.namespace.repl/refresh)
    ((ns-resolve *ns* 'bits.server/start!) 6001)
    ))