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


(bits/require [bits.tonsky.hash :as hash :just [md5]]
              [bits.tonsky.time :as time :just [now]]
              [bits.tonsky.math :as math :just [ceil]])


(rum/defc lock-icon []
  [:svg.bitview-lock {:width 13 :height 18 :view-box "0 0 26 36" :fill-rule "evenodd" :fill "currentColor"}
    [:path {:d "M 22 14L 24 14C 25.1046 14 26 14.8954 26 16L 26 32C 26 34.2091 24.2091 36 22 36L 4 36C 1.79089 36 0 34.2091 0 32L 0 16C 0 14.8954 0.895386 14 2 14L 4 14L 4 9C 4 4.02942 8.02942 0 13 0C 17.9706 0 22 4.02942 22 9L 22 14ZM 19 14L 19 9C 19 5.68628 16.3137 3 13 3C 9.68628 3 7 5.68628 7 9L 7 14L 19 14ZM 3 32L 3 17L 23 17L 23 32C 23 32.5522 22.5522 33 22 33L 4 33C 3.44775 33 3 32.5522 3 32Z"}]
    [:path {:d "M 14.5 25.5986C 15.3967 25.08 16 24.1105 16 23C 16 21.3431 14.6569 20 13 20C 11.3431 20 10 21.3431 10 23C 10 24.1105 10.6033 25.08 11.5 25.5986L 11.5 30L 14.5 30L 14.5 25.5986Z"}]])


(rum/defc unstable-icon []
  [:svg.bitview-unstable-icon {:width 13 :height 18 :view-box "0 0 26 36" :fill-rule "evenodd" :fill "currentColor"}
    [:path {:d "M 0 1.5C 0 0.671573 0.671573 0 1.5 0L 24.5 0C 25.3284 0 26 0.671573 26 1.5C 26 2.32843 25.3284 3 24.5 3L 23 3L 23 9.5C 23 9.89782 22.842 10.2794 22.5607 10.5607L 15.1213 18L 22.5607 25.4393C 22.842 25.7206 23 26.1022 23 26.5L 23 33L 23.5 33C 24.3284 33 25 33.6716 25 34.5C 25 35.3284 24.3284 36 23.5 36L 1.5 36C 0.671573 36 0 35.3284 0 34.5C 0 33.6716 0.671573 33 1.5 33L 3 33L 3 26.5C 3 26.1022 3.15804 25.7206 3.43934 25.4393L 10.8787 18L 3.43934 10.5607C 3.15804 10.2794 3 9.89782 3 9.5L 3 3L 1.5 3C 0.671573 3 0 2.32843 0 1.5ZM 6 3L 6 8.87868L 13 15.8787L 20 8.87868L 20 3L 6 3ZM 13 20.1213L 6 27.1213L 6 33L 20 33L 20 27.1213L 13 20.1213Z"}]
    [:path {:d "M 5 6L 10 1L 10 0L 0 0L 0 1L 5 6Z" :transform "translate(8 6)"}]
    [:path {:d "M 10 6L 0 6L 0 5L 5 0L 10 5L 10 6Z" :transform "translate(8 25)"}]])


(defn hours [bit]
  (-> (+ (:bit/created bit) core/bit-edit-interval)
      (- (time/now))
      (/ (* 60 60 1000))
      (math/ceil)))


(rum/defc view-bit-page [req]
  (let [{:keys [ns name]} (:route-params req)
        {:bits/keys [user db]} req
        fqn    (bits/path->fqn (str ns "/" name))
        bit    (ds/entity db [:bit/fqn fqn])
        {:bit/keys [namespace name docstring body author]} bit
        user-name (or (:user/display-name author)
                      (:user/namespace author))]
    [:.page
      [:.page_800.column
        [:.bitview-name [:.bitview-namespace namespace " / "] name]
        [:div (str/replace docstring #"(?m)^\s+" " ")]
        [:pre.code.bitview-body body]
        [:.bitview-footer.spread
          [:.bitview-author
            "By"
            [:a
              {:href (str "/user/" (:user/namespace author))}
              [:.bitview-avatar { :style {:background-image (str "url('https://secure.gravatar.com/avatar/" (hash/md5 (:user/email author)) "?s=100&d=404'), url('/static/avatar.png')")}}]
              [:span.link user-name]]]
          (if (= author user)
            (if (core/editable? bit)
              [:a.button.bitview-edit {:href (str "/bits/" ns "/" name "/edit")} "Edit"]
              [:span.bitview-locked
                {:class (when (= "locked" (get-in req [:query-params "error"])) "bitview-locked_error")}
                (lock-icon)
                "This bit can’t be changed anymore"])
            (when (core/editable? bit)
              (let [h (hours bit)]
                [:span.bitview-unstable
                  { :title (str user-name " still has " h " hour" (when (> h 1) "s") " to edit this bit if needed") }
                  (unstable-icon)
                  (str "Potential changes for next " h " hour" (when (> h 1) "s"))])))]
        (core/avatar-mask)]]))


(def routes
  {["/bits/" [#"[a-z0-9\-.]+" :ns] "/" [#"[^/]+" :name]] {:get (core/wrap-page #'view-bit-page)}})
