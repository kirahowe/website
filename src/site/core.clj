(ns site.core
  "Server entry point — the only namespace that touches http-kit."
  (:require [org.httpkit.server :as http]
            [site.app :as app]
            [site.config :as config]
            [site.content :as content]
            [site.livereload :as livereload]
            [site.sync :as sync]))

(defonce server (atom nil))

(defn stop! []
  (when-let [s @server]
    (s)
    (reset! server nil)))

(defn- initial-index
  "The index the server boots with. When content comes from git, boot
  failures (network down, broken file pushed) must not crash-loop the
  machine — serve an empty site and let the sync loop heal it. Local
  content with no git URL fails loudly instead: that's a dev mistake
  you want to see."
  [cfg]
  (if (:content-git-url cfg)
    (try
      (sync/ensure-content! cfg)
      (sync/build-indexed cfg)
      (catch Exception e
        (binding [*out* *err*]
          (println "startup: content unavailable — serving empty site until a sync succeeds:"
                   (ex-message e)))
        content/empty-index))
    (content/build-index cfg)))

(defn start!
  "Options:
    :env    — :dev (config/config.edn + config/dev.edn; reindex every
              request, drafts visible) or :prod (config/config.edn +
              config/prod.edn). Defaults to :prod.
    :block? — park the calling thread (for `bb dev` / `bb prod`)"
  ([] (start! {}))
  ([opts]
   (let [cfg (config/load-config (or (:env opts) :prod))
         index-atom (atom (initial-index cfg))
         ;; In dev, resolve make-app per request (through its var) so edits to
         ;; the request pipeline itself take effect after a hot reload; prod
         ;; builds the handler once.
         handler (if (:dev? cfg)
                   (livereload/wrap (fn [req] ((app/make-app cfg index-atom) req)))
                   (app/make-app cfg index-atom))]
     (stop!)
     (reset! server (http/run-server handler {:port (:port cfg)}))
     (println (str "Serving content from " (:content-path cfg)
                   " at http://localhost:" (:port cfg)
                   (when (:dev? cfg) " — dev mode: reindexing every request, drafts visible")))
     (when (:content-git-url cfg)
       (sync/start-sync-loop! cfg index-atom))
     (when (:dev? cfg)
       (livereload/start-watcher! cfg))
     (when (:block? opts)
       @(promise))
     @server)))

(defn -main [& _]
  (start! {:block? true}))
