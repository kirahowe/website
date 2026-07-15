(ns site.livereload
  "Dev-only live reload with code hot-swapping. A background thread polls the
  content vault, the site's CSS/assets, and its own source for changes; on any
  change it re-requires whatever .clj source was edited (so code edits take
  effect without a restart) and tells connected browsers — held on an SSE
  connection — to refresh. Wired in only under `bb dev` (:dev?); the production
  server never loads any of this. Besides site.core, the only namespace that
  touches http-kit, and only for the server-sent-events channel."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

;; Kept in sync with the <script> injected by site.views.layout — a change
;; here means changing that path too.
(def endpoint "/__livereload")

;; Beyond the content vault (added at start-watcher! time), these project roots
;; are watched. Editing anything here refreshes the browser; a changed .clj
;; under src/ is also hot-reloaded into the running server.
(def ^:private watch-roots ["resources/public" "src"])

(defonce ^:private clients (atom #{}))
(defonce ^:private watching (atom false))

(defn- sse-handler
  "Park the request as an SSE stream and register the channel. http-kit keeps
  it open (send! with close?=false); we push a reload event down it later."
  [req]
  (http/as-channel req
    {:on-open  (fn [ch]
                 (http/send! ch {:status  200
                                 :headers {"Content-Type"  "text/event-stream"
                                           "Cache-Control" "no-store"
                                           "Connection"    "keep-alive"}
                                 :body    "retry: 1000\n\n"}
                             false)
                 (swap! clients conj ch))
     :on-close (fn [ch _status] (swap! clients disj ch))}))

(defn wrap
  "Intercept the live-reload endpoint; everything else falls through to the
  real app handler. Applied by site.core only in dev."
  [handler]
  (fn [req]
    (if (= (:uri req) endpoint)
      (sse-handler req)
      (handler req))))

(defn- notify-all!
  "Tell every connected browser to reload. Dead channels are pruned as we go."
  []
  (doseq [ch @clients]
    (when-not (http/send! ch "event: reload\ndata: 1\n\n" false)
      (swap! clients disj ch))))

(defn- snapshot
  "path -> last-modified for every regular file under the given roots. Changes
  when anything is added, edited, or removed."
  [roots]
  (into {}
        (for [root roots
              ^java.io.File f (file-seq (io/file root))
              :when (.isFile f)]
          [(.getPath f) (.lastModified f)])))

(defn- source?
  "A changed project source file (relative 'src/...'), not vault content (whose
  paths are absolute)."
  [path]
  (and (str/starts-with? path "src/")
       (re-find #"\.cljc?$" path)))

(defn- path->ns
  "src/site/views/layout.clj -> site.views.layout. Relies on this repo's
  invariant that a file's namespace matches its path under src/."
  [path]
  (-> path
      (subs 4)                          ; drop "src/"
      (str/replace #"\.cljc?$" "")
      (str/replace \_ \-)
      (str/replace \/ \.)
      symbol))

(defn- hot-swap!
  "Re-require every changed source namespace so the running server picks up the
  new code. A file saved mid-edit (or with a typo) fails to compile — we log it
  and keep serving the last good code, exactly as the terminal would show."
  [changed]
  (doseq [path (filter source? changed)]
    (let [ns-sym (path->ns path)]
      (try
        (require ns-sym :reload)
        (println "reloaded" ns-sym)
        (catch Exception e
          (binding [*out* *err*]
            (println "reload failed:" ns-sym "—" (ex-message e))))))))

(defn start-watcher!
  "Poll the content vault and project roots ~3x/second. On any change, hot-swap
  the source files that changed and tell connected browsers to refresh.
  Idempotent — a second call is a no-op, so repeated start! in a REPL won't
  spawn threads."
  [config]
  (when (compare-and-set! watching false true)
    (let [roots (cons (:content-path config) watch-roots)]
      (doto (Thread.
             (fn []
               (loop [prev (snapshot roots)]
                 (Thread/sleep 350)
                 (let [now (snapshot roots)]
                   (when (not= now prev)
                     (hot-swap! (for [[p m] now :when (not= m (get prev p))] p))
                     (notify-all!))
                   (recur now)))))
        (.setDaemon true)
        (.setName "livereload-watcher")
        (.start)))))
