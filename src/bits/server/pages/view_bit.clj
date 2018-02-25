(ns bits.server.pages.view-bit
  (:require
    [clojure.string :as str]
    [clojure.tools.reader.edn :as reader.edn]
    
    [rum.core :as rum]
    [datascript.core :as ds]
    [ring.util.response :as response]
    
    [bits.server.db :as db]
    [bits.server.core :as core]))



(rum/defc view-bit-page [req]
  (let [{:keys [ns name]} (:route-params req)
        {:bits/keys [user db]} req
        fqn (core/path->fqn (str ns "/" name))
        bit (ds/entity db [:bit/fqn fqn])
        author (:bit/author bit)]
    [:.page.page_center
      [:.bitview-name      [:.bitview-namespace (:bit/namespace bit) " / "] (:bit/name bit)]
      [:.bitview-docstring (:bit/docstring bit)]
      (if (= (reader.edn/read-string (:bit/body-clj bit))
             (reader.edn/read-string (:bit/body-cljs bit)))
        [:pre.code.bitview-body [:.bitview-body-lang "cljc"] (:bit/body-clj bit)]
        (list
          (when-some [body (:bit/body-clj bit)]
            [:pre.code.bitview-body [:.bitview-body-lang "clj"] body])
          (when-some [body (:bit/body-cljs bit)]
            [:pre.code.bitview-body [:.bitview-body-lang "cljs"] body])))
      [:.bitview-footer
        [:.bitview-author
          "By"
          [:a
            {:href (str "/user/" (:user/namespace author))}
            [:.bitview-avatar { :style {:background-image (str "url('https://secure.gravatar.com/avatar/" (core/md5 (:user/email author)) "?s=100&d=404'), url('/static/avatar.png')")}}]
            [:span.link (or (:user/display-name author)
                            (:user/namespace author))]]]
        (when (= author user)
          [:a.button.bitview-edit {:href (str "/bits/" ns "/" name "/edit")} "Edit"])]]))


(def routes
  {["/bits/" [#"[a-z0-9\-.]+" :ns] "/" [#"[^/]+" :name]] {:get (core/wrap-page #'view-bit-page)}})
