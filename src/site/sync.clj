(ns site.sync
  "Keeps the production content checkout fresh. When :content-git-url is
  configured (Fly, any server), the content repo is cloned at boot and
  pulled on a timer; the index rebuilds whenever HEAD moves.

  Local development never needs this — CONTENT_PATH just points at your
  vault directly. Uses clojure.java.shell so it runs under bb and the JVM
  alike."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [site.content :as content]))

(defn- git [dir & args]
  (apply sh/sh "git" "-C" dir args))

(defn- head-rev [dir]
  (str/trim (:out (git dir "rev-parse" "HEAD"))))

(defn ensure-content!
  "Clones the content repo if the content path doesn't exist yet.
  No-op without :content-git-url or when the checkout is already there."
  [{:keys [content-path content-git-url]}]
  (when (and content-git-url (not (.exists (io/file content-path))))
    (println "Cloning content repo into" content-path "...")
    (let [{:keys [exit err]} (sh/sh "git" "clone" "--depth" "1"
                                    content-git-url content-path)]
      (when-not (zero? exit)
        (throw (ex-info (str "git clone of content repo failed: " err) {}))))))

(defn pull!
  "→ :updated | :unchanged | :error"
  [{:keys [content-path]}]
  (let [before (head-rev content-path)
        {:keys [exit err]} (git content-path "pull" "--ff-only" "--quiet")]
    (cond
      (not (zero? exit))
      (do (binding [*out* *err*]
            (println "content sync: git pull failed:" (str/trim (str err))))
          :error)

      (= before (head-rev content-path)) :unchanged
      :else :updated)))

(defn build-indexed
  "build-index, stamped (as metadata) with the git rev it was built from,
  so the sync loop can tell whether the served index matches HEAD."
  [config]
  (let [index (content/build-index config)]
    (with-meta index {:git-rev (head-rev (:content-path config))})))

(defn sync-once!
  "One sync tick: clone if the checkout is missing, otherwise pull; rebuild
  whenever HEAD differs from the rev the served index was built from —
  so a rebuild that failed (broken file pushed) is retried every tick
  until the content is fixed. NEVER throws: any failure is logged and
  the last good index keeps serving. → :updated | :unchanged | :error"
  [config index-atom]
  (try
    (if-not (.exists (io/file (:content-path config)))
      (do (ensure-content! config)
          (reset! index-atom (build-indexed config))
          (println "content sync: cloned and indexed")
          :updated)
      (if (= :error (pull! config))
        :error
        (let [rev (head-rev (:content-path config))]
          (if (= rev (:git-rev (meta @index-atom)))
            :unchanged
            (do (reset! index-atom (build-indexed config))
                (println (str "content sync: reindexed at " (subs rev 0 7)))
                :updated)))))
    (catch Exception e
      (binding [*out* *err*]
        (println "content sync failed — keeping previous content:" (ex-message e)))
      :error)))

(defn start-sync-loop!
  "Background sync every :content-sync-seconds (default 300). sync-once!
  can't throw, but belt-and-braces: nothing escapes this loop."
  [config index-atom]
  (let [seconds (or (:content-sync-seconds config) 300)]
    (future
      (loop []
        (Thread/sleep (* 1000 seconds))
        (try
          (sync-once! config index-atom)
          (catch Throwable t
            (binding [*out* *err*]
              (println "content sync error:" (ex-message t)))))
        (recur)))
    (println (str "Content sync: pulling every " seconds "s"))))
