(ns bits.server.pages.profile
  (:require
    [clojure.string :as str]
    
    [rum.core :as rum]
    [datascript.core :as ds]
    [ring.util.response :as response]
    
    [bits.core :as bits]
    [bits.server.db :as db]
    [bits.server.core :as core]))


(bits/require [bits.tonsky.url :as url :just [parse]])


(rum/defc get-profile-page [req]
  (let [{:bits/keys [user session]} req
        display-name (or (get-in req [:query-params "display-name"])
                         (:user/display-name user))
        return-to    (or (get-in req [:query-params "return-to"])
                         (let [referer (url/parse (get-in req [:headers "referer"]))]
                           (if (= (:domain referer) (:server-name req))
                             (:path referer)
                             "/")))]
    [:.page
      [:form.page_500.column {:action "" :method "POST"}
        [:h2 "Your profile"]
        [:input {:type "hidden" :name "csrf-token" :value (:session/csrf-token session)}]
        [:input {:type "hidden" :name "return-to" :value return-to}]
        [:label {:for "display-name"} "Display name"]
        (case (get-in req [:query-params "error"])
          nil                  nil
          "csrf-token-invalid" [:.error "> Oops. Something went wrong. Please try once more"]
          "too-long"           [:.error "> Can’t be longer that 140 characters"])
        [:input#display-name
          {:type "text"
           :autofocus true
           :name "display-name"
           :placeholder "Choose wisely..."
           :max-length 150
           :value display-name}]
        [:div [:button.button "Update"]]
        ]]))


(defn post-profile [req]
  (let [{:strs [display-name return-to csrf-token]} (:form-params req)
        {:bits/keys [session user]} req
        display-name (str/trim display-name)]
    (cond
      (not= csrf-token (:session/csrf-token session))
      (response/redirect-after-post (core/url "/profile" {:error "csrf-token-invalid"
                                                          :display-name display-name
                                                          :return-to return-to}))

      (> (count display-name) 140)
      (response/redirect-after-post (core/url "/profile" {:error "too-long"
                                                          :display-name (subs display-name 0 140)
                                                          :return-to return-to}))
      
      (str/blank? display-name)
      (do
        (ds/transact! db/*db [[:db.fn/retractAttribute (:db/id user) :user/display-name]])
        (response/redirect-after-post return-to))

      :else
      (do
        (ds/transact! db/*db [[:db/add (:db/id user) :user/display-name display-name]])
        (response/redirect-after-post return-to)))))


(def routes
  {"/profile" {:get  (core/wrap-auth (core/wrap-page #'get-profile-page))
               :post (core/wrap-auth #'post-profile)}})
