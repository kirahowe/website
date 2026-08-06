(ns site.sync
  "Keeps the production content checkout fresh. When :content-git-url is
  configured (Fly, any server), the content repo is cloned at boot; the
  index rebuilds whenever HEAD moves. Freshness is driven primarily by
  the /refresh webhook (site.app), fired by a push webhook on the content
  repo — the timed pull here is demoted to a fallback that heals a
  webhook delivery GitHub failed to make (it does not retry those).

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

(defn make-refresh-state
  "The debounce state one server owns: when the last accepted refresh ran
  and whether a trailing refresh is already queued. An atom handed in by
  the caller (not a namespace global) so tests get a fresh one each time."
  []
  (atom {:last 0 :pending? false}))

(defn refresh!
  "Webhook-driven sync. `sha` is the pushed HEAD from the webhook payload
  (nil when unknown — a manual curl, a ping event): when it matches the
  rev the served index was built from there is nothing to fetch, so the
  request is answered without touching git at all.

  Otherwise, debounced to at most one sync per window. The debounce must
  coalesce, not drop: one publish from the admin app arrives as several
  commits (content, images, draft deletion) seconds apart, each firing a
  webhook — so a request landing inside the window schedules exactly one
  trailing sync at the window's end rather than being discarded, and the
  final commit is never left waiting for the fallback loop.

  → :current | :scheduled | :updated | :unchanged | :error"
  [config index-atom refresh-state sha]
  (if (and sha (= sha (:git-rev (meta @index-atom))))
    :current
    (let [window-ms (* 1000 (or (:refresh-debounce-seconds config) 10))
          now (System/currentTimeMillis)
          [old new] (swap-vals! refresh-state
                                (fn [{:keys [last pending?] :as s}]
                                  (cond
                                    pending? s
                                    (>= (- now last) window-ms) {:last now :pending? false}
                                    :else (assoc s :pending? true))))]
      (cond
        ;; A trailing sync is already queued — it will cover this push too.
        (:pending? old) :scheduled
        ;; We claimed the window: sync right now, in-request.
        (and (not (:pending? new)) (> (:last new) (:last old)))
        (sync-once! config index-atom)
        ;; We queued the trailing sync: run it when the window closes.
        :else
        (do (future
              (Thread/sleep (max 0 (- (+ (:last old) window-ms) now)))
              (reset! refresh-state {:last (System/currentTimeMillis) :pending? false})
              (sync-once! config index-atom))
            :scheduled)))))

(defn start-sync-loop!
  "Fallback sync every :content-sync-seconds (default 300) — the webhook
  endpoint is the primary trigger; this loop heals missed deliveries and
  self-hosts the retry-until-fixed behavior. Until the first successful
  index (a failed boot clone serves an empty site) it retries every
  minute instead: a webhook only fires on a push, so it can't heal a
  boot that failed on a quiet day — this loop must. sync-once! can't
  throw, but belt-and-braces: nothing escapes this loop."
  [config index-atom]
  (let [seconds (or (:content-sync-seconds config) 300)]
    (future
      (loop []
        (Thread/sleep (* 1000 (if (:git-rev (meta @index-atom)) seconds 60)))
        (try
          (sync-once! config index-atom)
          (catch Throwable t
            (binding [*out* *err*]
              (println "content sync error:" (ex-message t)))))
        (recur)))
    (println (str "Content sync: fallback pull every " seconds "s"))))
