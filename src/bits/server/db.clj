(ns bits.server.db
  (:require
    [clojure.string :as str]
    [datascript.core :as ds]))


(defonce *db (ds/create-conn {
  :user/email           {#_:db.type/string :db/unique :db.unique/identity}
  :user/display-email   {#_:db.type/string}
  :user/namespace       {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/token   {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/created {#_:db.type/long}
  
  :session/id         {#_:db.type/string :db/unique :db.unique/identity}
  :session/created    {#_:db.type/long}
  :session/csrf-token {#_:db.type/string}
  :session/user       {:db/type :db.type/ref}

  :bit/fqn            {#_:db.type/string :db/unique :db.unique/identity}
  :bit/name           {#_:db.type/string}
  :bit/namespace      {#_:db.type/string}
  :bit/body-clj       {#_:db.type/string}
  :bit/body-cljs      {#_:db.type/string}
  :bit/docstring      {#_:db.type/string}
  :bit/author         {:db/type :db.type/ref}
}))


#_(ds/listen! *db ::log
  (fn [{:keys [tx-data]}]
    (println "TX" tx-data)))


(defn insert! [*db entity]
  (let [{:keys [db-after tempids]} (ds/transact! *db [(assoc entity :db/id -1)])]
    (ds/entity db-after (get tempids -1))))