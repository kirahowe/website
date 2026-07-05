(ns site.core
  "Server entry point — the only namespace that touches http-kit."
  (:require [org.httpkit.server :as http]
            [site.app :as app]
            [site.config :as config]
            [site.content :as content]))

(defonce server (atom nil))

(defn stop! []
  (when-let [s @server]
    (s)
    (reset! server nil)))

(defn start!
  "Options (merged over config.edn + env):
    :reload? — rebuild the content index on every request (dev mode)
    :block?  — park the calling thread (for `bb dev` / `bb run`)"
  ([] (start! {}))
  ([opts]
   (let [cfg (config/load-config (dissoc opts :block?))
         index-atom (atom (content/build-index cfg))
         handler (app/make-app cfg index-atom)]
     (stop!)
     (reset! server (http/run-server handler {:port (:port cfg)}))
     (println (str "Serving content from " (:content-path cfg)
                   " at http://localhost:" (:port cfg)
                   (when (:reload? cfg) " — dev mode, reindexing every request")))
     (when-not (:admin-token cfg)
       (println "Note: ADMIN_TOKEN not set — draft previews and /admin/reindex are disabled."))
     (when (:block? opts)
       @(promise))
     @server)))

(defn -main [& _]
  (start! {:block? true}))
