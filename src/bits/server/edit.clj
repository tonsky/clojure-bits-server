(ns bits.server.edit
  (:require
    [clojure.string :as str]
    
    [rum.core :as rum]
    [datascript.core :as ds]
    [ring.util.response :as response]
    
    [bits.server.db :as db]
    [bits.server.core :as core]))


(rum/defc add-bit-page [req]
  [:div "ADD BIT PAGE"])


(defn add-bit [req]
  (let [user (:bits/user req)]
    (if-some [ns (:user/namespace user)]
      ((core/wrap-page add-bit-page) req)
      (response/redirect "/claim-ns"))))


(def ns-reserved? #{"client" "server"})


(rum/defc claim-ns-page [req]
  (let [{:strs [error namespace]} (:query-params req)
        {:bits/keys [session user]} req
        [_ suggest] (re-matches #"([^@]+)@.*" (:user/email user))
        suggest (-> suggest
                    (str/lower-case)
                    (str/replace #"[_\.\+]+" "-")
                    (str/replace #"[^a-z0-9\-]+" ""))
        placeholder (if (and (nil? (ds/entity @db/*db [:user/namespace suggest]))
                             (not (ns-reserved? suggest))
                             (>= (count suggest) 3))
                      (str "namespace, e.g. " suggest)
                      "namespace")]
    [:.page.page_middle
      [:form.claim { :action "/claim-ns" :method "POST" }
        [:h1.claim-title "Claim your root namespace"]
        [:p "Everyone gets their own unique namespace where they could put all their functions and sub-namespaces in."]
        [:p "We recommend using your github username or a nickname."]
        [:p {:style {:margin-bottom "40px"}} "You only have to do this once."]
        (case error
          nil                  nil
          "csrf-token-invalid" [:.claim-message "> Oops. Something went wrong. Please try once more"]
          "taken"              [:.claim-message "> Sorry, “bits." namespace "” is already taken. Try something else"]
          "reserved"           [:.claim-message "> Sorry, “bits." namespace "” is reserved. Try something else"]
          "blank"              [:.claim-message "> Please enter something"]
          "short"              [:.claim-message "> Name’s too short. Please use at least 3 characters"]
          "malformed"          [:.claim-message "> We only allow a-z, 0-9 and -"])
        [:input {:type "hidden" :name "csrf-token" :value (:session/csrf-token session)}]
        [:.input.claim-namespace
          {:on-click "document.querySelector('.claim-namespace-input').focus()"}
          [:.claim-namespace-prefix "bits."]
          [:input.claim-namespace-input {:type "text" :autofocus true :name "namespace" :placeholder placeholder :value namespace}]]
        [:button.button.claim-submit "Claim"]]]))

(defn claim-ns [req]
  (if (some? (:user/namespace (:bits/user req)))
    (response/redirect "/add-bit")
    ((core/wrap-page claim-ns-page) req)))


(defn ns-normalize [s]
  (-> s str/lower-case str/trim))


(defn do-claim-ns [req]
  (let [{:strs [namespace csrf-token]} (:form-params req)
        {:bits/keys [session user]} req
        namespace (ns-normalize namespace)]
    (cond
      ;; already claimed something
      (some? (:user/namespace user))
      (response/redirect "/add-bit")

      ;; csrf
      (not= csrf-token (:session/csrf-token session))
      (response/redirect (core/url "/claim-ns" {:error "csrf-token-invalid", :namespace namespace}))

      ;; taken by someone
      (some? (ds/entity @db/*db [:user/namespace namespace]))
      (response/redirect (core/url "/claim-ns" {:error "taken", :namespace namespace}))

      ;; reserved
      (ns-reserved? namespace)
      (response/redirect (core/url "/claim-ns" {:error "reserved", :namespace namespace}))

      ;; blank
      (str/blank? namespace)
      (response/redirect (core/url "/claim-ns" {:error "blank", :namespace ""}))

      ;; short
      (< (count namespace) 3)
      (response/redirect (core/url "/claim-ns" {:error "short", :namespace namespace}))

      ;; malformed
      (not (re-matches #"[a-z0-9\-]+" namespace))
      (response/redirect (core/url "/claim-ns" {:error "malformed", :namespace namespace}))

      :valid
      (do
        (ds/transact! db/*db [[:db/add (:db/id user) :user/namespace namespace]])
        (response/redirect "/add-bit")))))


(def routes
  ["" {:get  {"/add-bit"  (core/wrap-auth add-bit)
              "/claim-ns" (core/wrap-auth claim-ns)}
       :post {"/add-bit"  (core/wrap-auth add-bit)
              "/claim-ns" (core/wrap-auth do-claim-ns)}}])