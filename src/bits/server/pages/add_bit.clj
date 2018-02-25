(ns bits.server.pages.add-bit
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
    
    [bits.server.db :as db]
    [bits.server.core :as core]))


(defn script []
  [:script
    {:language "javascript"
     :dangerouslySetInnerHTML 
       {:__html 
"function onSubNsChange(el) {
  var v = el.value.replace(/\\.{2,}/g, '.').replace(/^[\\s./]+/, '').replace(/[\\s./]+$/, '').toLowerCase();
  document.getElementById('prefix_subns').innerText=(v === '' ? '' : '.' + v);
}

function taResize(lines) {
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
  subscribeToChange(document.getElementById('subns'),     onSubNsChange);
  subscribeToChange(document.getElementById('body-clj'),  taResize(3));
  subscribeToChange(document.getElementById('body-cljs'), taResize(3));
  subscribeToChange(document.getElementById('docstring'), taResize(1));
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
  (core/cond+
    (not (re-matches #"[a-z0-9\-\.]*" subns))
    "We only allow a-z, 0-9 and -"

    :let [bit-ns (bit-ns user-ns subns)]
    
    (> (count bit-ns) 140)
    "Can’t be longer than 140 characters"))


(defn normalize-name [n]
  (some-> n (str/trim)))


; (def name-re #"(?:/|[^0-9\s`~@#%\^\(\)\[\]\{\}\\:;\"\',/][^\s`~@#%\^\(\)\[\]\{\}\\:;\"\',/]*)")


(defn check-name [user-ns subns name]
  (cond
    (empty? name)
    "Please give it a name"

    (some? (ds/entity @db/*db [:bit/fqn (fqn user-ns subns name)]))
    (str "Function " (fqn user-ns subns name) " already exists")

    (re-matches #"\d" (subs name 0 1))
    "Can’t start with a number"

    (some #(when (some? (str/index-of name %)) %) "`~@#%^()[]{}\\:;\"',/")
    (str "Can’t contain " (some #(when (some? (str/index-of name %)) %) "`~@#%^()[]{}\\:;\"',/"))
    
    (re-find #"\s" name)
    (str "Can’t contain space")

    (> (count name) 140)
    "Can’t be longer than 140 characters"
    
    :else nil))


(defn underline
  ([s]
    (apply str s "\n" (repeat (count s) "^")))
  ([s pos] 
    (str s "\n" (str/join (repeat pos " ")) "^")))


(defn check-fn-body [body]
  (core/cond+
    (not (list? body))
    {:code (underline (pr-str body)) :message "Expected list"}

    :let [[arglist & _] body]

    (not (vector? arglist))
    {:code (underline (pr-str arglist)) :message "Expected arglist vector"}

    (= ::spec/invalid (spec/conform ::core.spec/arg-list arglist))
    {:code (underline (pr-str arglist)) :message "Incorrect binding"}))


(defn check-bodies [& bodies]
  (when (every? str/blank? bodies)
    "Please provide either Clojure or ClojureScript function body"))


(defn check-body [body]
  (try
    (core/cond+
      (str/blank? body)
      nil

      (> (count body) 10240)
      [{:message "Too long, we only accept fns under 10 Kb"}]

      :let [form (reader.edn/read (reader.reader-types/indexing-push-back-reader body))]
    
      (not (list? form))
      [{:code (underline (pr-str form)) :message "Expected list"}]

      :let [fn     (first form)
            sym?   (and (>= (count form) 2)
                        (not (vector? (second form)))
                        (not (list? (second form))))
            sym    (if sym? (second form) ::undef)
            rest   (if sym? (nnext form) (next form))
            bodies (if (vector? (first rest)) [rest] rest)]
      
      :else
      (not-empty
        (concat
          (when (not= 'fn fn)
            [{:code (underline (pr-str fn)) :message "Expected `fn`"}])
          (when sym?
            (when-not (simple-symbol? sym)
              [{:code (underline (pr-str sym)) :message "Expected simple symbol"}]))
          (keep check-fn-body bodies))))
    (catch Exception e
      (let [message (or (second (re-matches #"\[[^\]]+\] (.*)" (.getMessage e)))
                        (.getMessage e))
            code    (let [{:keys [line col]} (ex-data e)]
                      (when (some? line)
                        (let [body-line (nth (str/split-lines body) (dec line))]
                          (if (some? col)
                            (underline body-line (- col 2))
                            body-line))))]
        [{:code    code
          :message message}]))))


(defn normalize-docstring [docstring]
  (some-> docstring (str/trim)))


(defn check-docstring [docstring]
  (cond
    (str/blank? docstring)
    "Docstring is required"
  
    (> (count docstring) 10240)
    "Too long, we only accept docstrings under 10 Kb"))


(rum/defc add-bit-page [check? req]
  (let [{:bits/keys [user session]} req
        {:strs [subns name body-clj body-cljs docstring]} (:form-params req)
        {:user/keys [namespace]} user
        subns     (normalize-namespace subns)
        name      (normalize-name name)
        docstring (normalize-docstring docstring)]
    [:.page
      (script)

      [:form.bitform
        { :action "/add-bit" :method "POST" }
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
        
        [:label {:for "name"} "Function name"]
        (when-let [error (and check? (check-name namespace subns name))]
          [:.bitform-message [:p "> " error]])
        [:.input {:on-click "this.querySelector('input').focus()"}
          [:.input-prefix "bits." namespace [:span#prefix_subns (when-not (str/blank? subns) (str "." subns))] "/"]
          [:input
            {:type "text"
             :id   "name"
             :name "name"
             :placeholder "e.g. iterate"
             :max-length 140
             :value name}]]

        [:label {:for "body-clj"} "Function body (Clojure)"]
        (when-let [error (and check? (check-bodies body-clj body-cljs))]
          [:.bitform-message [:p "> " error]])
        (when-let [errors (and check? (check-body body-clj))]
          (for [{:keys [code message]} errors]
            [:.bitform-message
              (when (some? code) [:pre.code code])
              (when (some? message) [:p (when (nil? code) "> ") message])]))
        [:textarea.code
          { :id "body-clj"
            :name "body-clj"
            :max-length 10240
            :placeholder "(fn [...]\n  ...)" }
          body-clj]
        [:.bitform-comment [:p "Use (fn [args] ...) form"]]

        [:label {:for "body-cljs"} "Function body (ClojureScript)"]
        (when-let [error (and check? (check-bodies body-clj body-cljs))]
          [:.bitform-message [:p "> " error]])
        (when-let [errors (and check? (check-body body-cljs))]
          (for [{:keys [code message]} errors]
            [:.bitform-message
              (when (some? code) [:pre.code code])
              (when (some? message) [:p (when (nil? code) "> ") message])]))
        [:textarea.code
          { :id "body-cljs"
            :name "body-cljs"
            :max-length 10240
            :placeholder "(fn [...]\n  ...)" }
          body-cljs]
        [:.bitform-comment
          ; [:p "Use (fn [args] ...) form"]
          [:p "Provide either clj body, cljs or both"]]

        [:label {:for "docstring"} "Docstring"]
        (when-let [error (and check? (check-docstring docstring))]
          [:.bitform-message [:p "> " error]])
        [:textarea
          { :id "docstring"
            :name "docstring"
            :max-length 10240
            :placeholder "What your bit does" }
          docstring]

        [:.bitform-submit
          [:button.button "Add Bit"]]
        ]]))


(defn get-add-bit [req]
  (let [user (:bits/user req)]
    (if-some [ns (:user/namespace user)]
      ((core/wrap-page #(add-bit-page false %)) req)
      (response/redirect "/claim-ns"))))


(defn post-add-bit [req]
  (let [{:bits/keys [user session]} req
        {:strs [subns name body-clj body-cljs docstring]} (:form-params req)
        {:user/keys [namespace]} user
        subns     (normalize-namespace subns)
        name      (normalize-name name)
        docstring (normalize-docstring docstring)]
    (assert (some? namespace) "User namespace can’t be empty")

    (if (or (check-namespace namespace subns)
            (check-name namespace subns name)
            (check-bodies body-clj body-cljs)
            (check-body body-clj)
            (check-body body-cljs)
            (check-docstring docstring))
      ((core/wrap-page #(add-bit-page true %)) req)
      (let [fqn  (fqn namespace subns name)
            path (core/fqn->path fqn)
            file (io/file (str "bits/" path ".edn"))]
        (.mkdirs (.getParentFile file))
        (spit file (pr-str #some { :bit/namespace   (symbol (bit-ns namespace subns))
                                   :bit/name        (symbol name)
                                   :bit/body-clj    (some-> (core/not-blank body-clj) reader.edn/read-string)
                                   :bit/body-cljs   (some-> (core/not-blank body-cljs) reader.edn/read-string)
                                   :bit/docstring   docstring
                                   :bit.author/name (:user/display-name user) }))
        (db/insert! db/*db { :bit/fqn       fqn
                             :bit/namespace (bit-ns namespace subns)
                             :bit/name      name
                             :bit/body-clj  body-clj
                             :bit/body-cljs body-cljs
                             :bit/docstring docstring
                             :bit/author    (:db/id user) })
        (response/redirect (str "/bits/" path))))))


(def routes
  ["" {:get  {"/add-bit" (core/wrap-auth get-add-bit)}
       :post {"/add-bit" (core/wrap-auth post-add-bit)}}])