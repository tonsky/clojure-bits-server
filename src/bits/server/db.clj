(ns bits.server.db
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    
    [datascript.core :as ds]

    [bits.core :as bits]))


(bits/require [bits.tonsky.time :as time :just [now]])


(defonce *db (ds/create-conn {
  :user/email           {#_:db.type/string :db/unique :db.unique/identity}
  :user/display-email   {#_:db.type/string}
  :user/display-name    {#_:db.type/string}
  :user/namespace       {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/token   {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/created {#_:db.type/long}
  
  :session/id         {#_:db.type/string :db/unique :db.unique/identity}
  :session/created    {#_:db.type/long}
  :session/csrf-token {#_:db.type/string}
  :session/user       {:db/type :db.type/ref}

  :bit/fqn            {#_:db.type/string :db/unique :db.unique/identity}
  :bit/namespace      {#_:db.type/string}
  :bit/name           {#_:db.type/string}
  :bit/docstring      {#_:db.type/string}
  :bit/body           {#_:db.type/string}
  :bit/author         {:db/type :db.type/ref}
  :bit/created        {#_:db.type/long}
}))


#_(ds/listen! *db ::log
  (fn [{:keys [tx-data]}]
    (println "TX" tx-data)))


(defn insert! [*db entity]
  (let [{:keys [db-after tempids]} (ds/transact! *db [(assoc entity :db/id -1)])]
    (ds/entity db-after (get tempids -1))))


(defn wrap-db [handler]
  (fn [req]
    (handler (assoc req :bits/db @*db))))


(defn delete-bit! [fqn]
  (let [path (bits/fqn->path fqn)
        file (io/file (str "bits/" path ".cljc"))]
    (.delete file)
    (ds/transact! *db [[:db.fn/retractEntity [:bit/fqn fqn]]])))


(defn save-bit! [bit]
  (let [path (bits/fqn->path (:bit/fqn bit))
        file (io/file (str "bits/" path ".cljc"))]
  (.mkdirs (.getParentFile file))
  (spit file (:bit/body bit))
  (insert! *db bit)))


(defn add-bit! [bit]
  (save-bit! (assoc bit :bit/created (time/now))))


(defn update-bit! [old-fqn bit]
  (if (not= old-fqn (:bit/fqn bit))
    (let [old-bit (ds/entity @*db [:bit/fqn old-fqn])]
      (save-bit! (merge bit (select-keys old-bit [:bit/created])))
      (delete-bit! old-fqn))
    (save-bit! bit)))
