(ns bits.server.db
  (:require
    [clojure.string :as str]
    [datascript.core :as ds]))


(defn new-token
  ([] (new-token 32))
  ([len] (new-token len "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"))
  ([len alphabet]
    (let [random (java.security.SecureRandom.)
          sb     (StringBuilder. len)]
      (dotimes [_ len]
        (.append sb (.charAt alphabet (.nextInt random (.length alphabet)))))
      (str sb))))


(defonce *db (ds/create-conn {
  :user/email           {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/token   {#_:db.type/string :db/unique :db.unique/identity}
  :user.sign-in/created {#_:db.type/long}
  
  :session/id         {#_:db.type/string :db/unique :db.unique/identity}
  :session/created    {#_:db.type/long}
  :session/accessed   {#_:db.type/long}
  :session/csrf-token {#_:db.type/string}
  :session/user       {:db/type :db.type/ref}
}))


(defn insert! [*db entity]
  (ds/transact *db [entity])
  entity)
