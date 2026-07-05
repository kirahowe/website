(ns site.core
  "Server entry point — the only namespace that touches http-kit."
  (:require [org.httpkit.server :as http]
            [site.app :as app]
            [site.config :as config]
            [site.content :as content]
            [site.sync :as sync]))

(defonce server (atom nil))

(defn stop! []
  (when-let [s @server]
    (s)
    (reset! server nil)))

(defn start!
  "Options (merged over config.edn + config.local.edn):
    :dev?   — rebuild the content index on every request and serve
              drafts at /drafts/<name>; this is what `bb dev` passes
    :block? — park the calling thread (for `bb dev` / `bb run`)"
  ([] (start! {}))
  ([opts]
   (let [cfg (config/load-config (dissoc opts :block?))
         _ (sync/ensure-content! cfg)
         index-atom (atom (content/build-index cfg))
         handler (app/make-app cfg index-atom)]
     (stop!)
     (reset! server (http/run-server handler {:port (:port cfg)}))
     (println (str "Serving content from " (:content-path cfg)
                   " at http://localhost:" (:port cfg)
                   (when (:dev? cfg) " — dev mode: reindexing every request, drafts visible")))
     (when (:content-git-url cfg)
       (sync/start-sync-loop! cfg index-atom))
     (when (:block? opts)
       @(promise))
     @server)))

(defn -main [& _]
  (start! {:block? true}))
