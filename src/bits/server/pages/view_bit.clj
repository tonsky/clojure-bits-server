(ns bits.server.pages.view-bit
  (:require
    [clojure.string :as str]
    [clojure.tools.reader.edn :as reader.edn]
    
    [rum.core :as rum]
    [datascript.core :as ds]
    [ring.util.response :as response]
    
    [bits.core :as bits]
    [bits.server.db :as db]
    [bits.server.core :as core]))


(bits/require [bits.tonsky.hash :as hash :just [md5]])


(rum/defc view-bit-page [req]
  (let [{:keys [ns name]} (:route-params req)
        {:bits/keys [user db]} req
        fqn    (bits/path->fqn (str ns "/" name))
        bit    (ds/entity db [:bit/fqn fqn])
        {:bit/keys [namespace name docstring body author]} bit]
    [:.page.page_center
      [:.bitview-name [:.bitview-namespace namespace " / "] name]
      [:.bitview-docstring (str/replace docstring #"(?m)^\s+" " ")]
      [:pre.code.bitview-body body]
      [:.bitview-footer
        [:.bitview-author
          "By"
          [:a
            {:href (str "/user/" (:user/namespace author))}
            [:.bitview-avatar { :style {:background-image (str "url('https://secure.gravatar.com/avatar/" (hash/md5 (:user/email author)) "?s=100&d=404'), url('/static/avatar.png')")}}]
            [:span.link (or (:user/display-name author)
                            (:user/namespace author))]]]
        (when (= author user)
          [:a.button.bitview-edit {:href (str "/bits/" ns "/" name "/edit")} "Edit"])]
      (core/avatar-mask)]))


(def routes
  {["/bits/" [#"[a-z0-9\-.]+" :ns] "/" [#"[^/]+" :name]] {:get (core/wrap-page #'view-bit-page)}})
