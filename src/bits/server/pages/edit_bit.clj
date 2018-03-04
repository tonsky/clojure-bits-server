(ns bits.server.pages.edit-bit
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as spec]
    [clojure.core.specs.alpha :as core.spec]
    [clojure.tools.reader.edn :as reader.edn]
    [clojure.tools.reader.reader-types :as reader.reader-types]
    
    [rum.core :as rum]
    [datascript.core :as ds]
    [ring.util.response :as response]
    
    [bits.core :as bits]
    [bits.server.db :as db]
    [bits.server.core :as core]))


(defn script []
  [:script
    {:language "javascript"
     :dangerouslySetInnerHTML 
       {:__html 
"function taResize(lines) {
  return function(el) {
    el.style.height = '0';
    el.style.height = Math.max(lines*25+46, el.scrollHeight + 18) + 'px';
  }
}

function subscribeToChange(el, cb) {
  var bound_cb = function() { cb(el); },
      delayed_cb = function() { window.setTimeout(bound_cb, 0); };
  el.addEventListener('change',  bound_cb, false);
  el.addEventListener('cut',     delayed_cb, false);
  el.addEventListener('paste',   delayed_cb, false);
  el.addEventListener('drop',    delayed_cb, false);
  el.addEventListener('keydown', delayed_cb, false);
  bound_cb();
}

window.addEventListener('load', function() {
  subscribeToChange(document.getElementById('body'), taResize(4));
});
"}}])


(defn bit-ns [user-ns subns]
  (str "bits." user-ns (when-not (str/blank? subns) (str "." subns))))


(defn fqn 
  ([user-ns name] (str "bits." user-ns "/" name))
  ([user-ns subns name] (str (bit-ns user-ns subns) "/" name)))


(defn normalize-namespace [ns]
  (some-> ns
      (str/replace #"\.{2,}+" ".")
      (str/replace #"^[\s./]+" "")
      (str/replace #"[\s./]+$" "")
      (str/lower-case)))


(defn check-namespace [user-ns subns]
  (bits/cond+
    (not (re-matches #"[a-z0-9\-\.]*" subns))
    "We only allow a-z, 0-9 and -"

    :let [bit-ns (bit-ns user-ns subns)]
    
    (> (count bit-ns) 140)
    "Can’t be longer than 140 characters"))


(defn check-body [old-fqn user-ns subns body]
  (try
    (bits/cond+
      (str/blank? body)
      {:message "Body is definitely required"}

      (> (count body) 10240)
      {:message "Too long, we only accept fns under 10 Kb"}

      :let [fn-clj (bits/parse-defn-form (bits/read-clojure body :clj))]
      
      (contains? fn-clj :message)
      fn-clj

      :let [fn-cljs (bits/parse-defn-form (bits/read-clojure body :cljs))]

      (contains? fn-cljs :message)
      fn-cljs

      (not= (:name fn-clj) (:name fn-cljs))
      { :code    (bits/underline (str (:name fn-clj) " " (:name fn-cljs)))
        :message "Can’t have different names for CLJ and CLJS verisons" }

      :let [name (:name fn-clj)
            fqn  (fqn user-ns subns name)]

      (= fqn old-fqn) ;; no rename
      nil

      (some? (ds/entity @db/*db [:bit/fqn fqn]))
      { :code (bits/underline (str name))
        :message (str "Function `" fqn "` already exists") }

      (> (count (str name)) 140)
      { :code (bits/underline (str name))
        :message "Name can’t be longer than 140 characters" })
    (catch Exception e
      (let [message (or (second (re-matches #"\[[^\]]+\] (.*)" (.getMessage e)))
                        (.getMessage e))
            code    (let [{:keys [line col]} (ex-data e)]
                      (when (some? line)
                        (let [body-line (nth (str/split-lines body) (dec line))]
                          (if (some? col)
                            (bits/underline body-line (- col 2))
                            body-line))))]
        {:code    code
         :message message}))))


(rum/defc edit-bit-page [check? req]
  (let [{:bits/keys [user session]} req
        {:user/keys [namespace]} user
        old-fqn (:fqn (:route-params req))
        edit?   (some? old-fqn)
        old-bit (when edit?
                  (ds/entity @db/*db [:bit/fqn old-fqn]))
        subns   (or (some-> (get (:form-params req) "subns") (normalize-namespace))
                    (when edit?
                      (str/join "." (drop 2 (str/split (:bit/namespace old-bit) #"\.")))))
        body    (or (get (:form-params req) "body")
                    (when edit? (:bit/body old-bit)))]
    [:.page
      (script)

      [:form.bitform
        { :action "" :method "POST" }
        [:input {:type "hidden" :name "csrf-token" :value (:session/csrf-token session)}]

        [:label {:for "subns"} "Sub-namespace (optional)"]
        (when-let [error (and check? (check-namespace namespace subns))]
          [:.bitform-message [:p "> " error]])
        [:.input {:on-click "this.querySelector('input').focus()"}
          [:.input-prefix (str "bits." namespace ".")]
          [:input
            {:type "text"
             :id   "subns"
             :name "subns"
             :placeholder "sub-namespace"
             :max-length (- 140 (count (str "bits." namespace ".")))
             :value subns}]]
        [:.bitform-comment
          [:p "We recommend grouping your fns into semantically named sub-namespaces, e.g. bits.tonsky.coll / .string / .time / .logic etc"]]

        [:label {:for "body"} "Function body"]
        (when-let [{:keys [code message]} (and check? (check-body old-fqn namespace subns body))]
          [:.bitform-message
            (when (some? code) [:pre.code code])
            (when (some? message) [:p (when (nil? code) "> ") message])])
        [:textarea.code
          { :id "body"
            :name "body"
            :max-length 10240
            :placeholder "(defn <name>\n  \"<docstring>\"\n  [args]\n  ...)" }
          body]
        [:.bitform-comment
          [:p "Use (defn <name> <docstring> [args] ...) form"]]

        [:.bitform-submit
          [:button.button (if edit? "Update Bit" "Add Bit")]]
        (when-not edit?
          [:.bitform-comment
            { :style {:margin-top 27}}
            ;; TODO license
            [:p "You’ll have 24 hours to alter & tune bit body, after that it’ll become immutable"]])
        ]]))


(defn get-add-bit [req]
  (let [user (:bits/user req)]
    (if-some [ns (:user/namespace user)]
      ((core/wrap-page #(edit-bit-page false %)) req)
      (response/redirect "/claim-ns"))))


(defn get-edit-bit [req]
  ((core/wrap-page #(edit-bit-page false %)) req))


(defn delete-bit! [fqn]
  (let [path (bits/fqn->path fqn)
        file (io/file (str "bits/" path ".cljc"))]
    (.delete file)
    (ds/transact! db/*db [[:db.fn/retractEntity [:bit/fqn fqn]]])))


(defn add-bit! [bit]
  (let [path (bits/fqn->path (:bit/fqn bit))
        file (io/file (str "bits/" path ".cljc"))]
  (.mkdirs (.getParentFile file))
  (spit file (:bit/body bit))
  (db/insert! db/*db bit)))


(defn post-save-bit [req]
  (let [{:bits/keys [user session]} req
        {:user/keys [namespace]} user
        old-fqn (:fqn (:route-params req))
        edit?   (some? old-fqn)
        {:strs [subns body]} (:form-params req)
        subns (normalize-namespace subns)]    
    (cond
      (nil? namespace)
      {:status 400 :body "User namespace can’t be empty"}

      (some? (check-namespace namespace subns))
      ((core/wrap-page #(edit-bit-page true %)) req)

      (some? (check-body old-fqn namespace subns body))
      ((core/wrap-page #(edit-bit-page true %)) req)

      :else
      (let [{:keys [name docstring]} (bits/parse-defn-form (bits/read-clojure body :clj))
            fqn  (fqn namespace subns name)
            bit  { :bit/fqn       fqn
                   :bit/namespace (bit-ns namespace subns)
                   :bit/name      name
                   :bit/docstring docstring
                   :bit/body      body
                   :bit/author    (:db/id user) }]
        (add-bit! bit)
        (when (not= fqn old-fqn)
          (delete-bit! old-fqn))
        (response/redirect (str "/bits/" (bits/fqn->path fqn)))))))


(defn wrap-author [handler]
  (fn [req]
    (let [old-fqn (:fqn (:route-params req))
          old-bit (ds/entity @db/*db [:bit/fqn old-fqn])
          user (:bits/user req)]
      (if (= (:bit/author old-bit) user)
        (handler req)
        {:status 403 :body "Can only edit your own bits"}))))


(def routes
  {"/add-bit" {:get  (core/wrap-auth #'get-add-bit)
               :post (core/wrap-auth #'post-save-bit)}
   ["/bits/" [#"[a-z0-9\-.]+/[^/]+" :fqn] "/edit"]
   {:get  (core/wrap-auth (wrap-author #'get-edit-bit))
    :post (core/wrap-auth (wrap-author #'post-save-bit))}})