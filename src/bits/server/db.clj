(ns bits.server.db
  (:require
    [clojure.string :as str]
    [datascript.core :as ds]))


(defonce *db (ds/create-conn {
  :user/email           {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/token   {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/created {#_:db.type/long}
  
  :session/id         {#_:db.type/string :db/unique :db.unique/identity}
  :session/created    {#_:db.type/long}
  :session/csrf-token {#_:db.type/string}
  :session/user       {:db/type :db.type/ref}
}))


#_(ds/listen! *db ::log
  (fn [{:keys [tx-data]}]
    (println "TX" tx-data)))


(defn insert! [*db entity]
  (ds/transact *db [entity])
  entity)
