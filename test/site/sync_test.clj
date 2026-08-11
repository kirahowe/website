(ns site.sync-test
  "Exercises clone/pull/reindex against real git repos in a temp dir."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [site.content :as content]
            [site.sync :as sync])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def config-base {:entry-types [:post :link :quote]})

(defn- sh! [& args]
  (let [{:keys [exit err] :as res} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " args " — " err) res)))
    res))

(defn- git! [dir & args]
  (apply sh! "git" "-C" dir
         "-c" "user.email=test@test" "-c" "user.name=test"
         args))

(defn- entry! [dir path body]
  (let [f (io/file dir path)]
    (.mkdirs (.getParentFile f))
    (spit f body)))

(defn- temp-dir []
  (str (Files/createTempDirectory "sync-test" (into-array FileAttribute []))))

(deftest clone-pull-reindex
  (let [origin (temp-dir)
        checkout (str (temp-dir) "/content")
        config (assoc config-base
                      :content-path checkout
                      :content-git-url origin)]
    ;; a content repo with one entry
    (sh! "git" "init" "-q" "-b" "main" origin)
    (entry! origin "2026/01/05/first.md" ";;;\n{:type :post}\n;;;\nfirst entry")
    (git! origin "add" "-A")
    (git! origin "commit" "-q" "-m" "first")

    (testing "ensure-content! clones when the checkout is missing"
      (sync/ensure-content! config)
      (is (.exists (io/file checkout "2026/01/05/first.md"))))

    (testing "ensure-content! is a no-op when already cloned"
      (sync/ensure-content! config))

    (let [index-atom (atom (sync/build-indexed config))
          purges (atom [])
          purge (fn [cfg]
                  (swap! purges conj cfg)
                  :failed)]
        (testing "nothing new → :unchanged, index untouched, no purge"
          (let [before @index-atom]
            (is (= :unchanged (sync/sync-once! config index-atom purge)))
            (is (identical? before @index-atom))
            (is (empty? @purges))))

        (testing "new commit → :updated, index rebuilt, purge attempted"
          (entry! origin "2026/02/10/second.md" ";;;\n{:type :post :title \"Second\"}\n;;;\nmore")
          (git! origin "add" "-A")
          (git! origin "commit" "-q" "-m" "second")
          ;; A CDN failure is deliberately irrelevant to the successful sync.
          (is (= :updated (sync/sync-once! config index-atom purge)))
          (is (= [config] @purges))
          (is (= 2 (count (:entries @index-atom))))
          (is (some? (get (:by-path @index-atom) "/2026/feb/10/second")))))))

(deftest sync-failures-never-throw-and-self-heal
  (let [origin (temp-dir)
        checkout (str (temp-dir) "/content")
        config (assoc config-base
                      :content-path checkout
                      :content-git-url origin)]
    (sh! "git" "init" "-q" "-b" "main" origin)
    (entry! origin "2026/01/05/first.md" ";;;\n{:type :post}\n;;;\nfirst")
    (git! origin "add" "-A")
    (git! origin "commit" "-q" "-m" "first")

    (testing "a missing checkout self-heals by cloning"
      (let [index-atom (atom content/empty-index)]
        (is (= :updated (sync/sync-once! config index-atom)))
        (is (= 1 (count (:entries @index-atom))))))

    (let [index-atom (atom (sync/build-indexed config))]
      (testing "a broken push returns :error and keeps the last good index"
        (entry! origin "2026/01/06/bad.md" ";;;\n{:type :oops}\n;;;\nbroken")
        (git! origin "add" "-A")
        (git! origin "commit" "-q" "-m" "bad")
        (is (= :error (sync/sync-once! config index-atom)))
        (is (= 1 (count (:entries @index-atom)))))

      (testing "still broken next tick: retried, still :error, still serving"
        (is (= :error (sync/sync-once! config index-atom)))
        (is (= 1 (count (:entries @index-atom)))))

      (testing "a fix recovers on the next sync"
        (entry! origin "2026/01/06/bad.md" ";;;\n{:type :post}\n;;;\nfixed")
        (git! origin "add" "-A")
        (git! origin "commit" "-q" "-m" "fix")
        (is (= :updated (sync/sync-once! config index-atom)))
        (is (= 2 (count (:entries @index-atom)))))

      (testing "an unreachable origin logs :error without throwing"
        (git! checkout "remote" "set-url" "origin" (str origin "-gone"))
        (is (= :error (sync/sync-once! config index-atom)))
        (is (= 2 (count (:entries @index-atom))))))))

(deftest refresh-sha-matches-served-index-skips-pull
  (let [origin (temp-dir)
        checkout (str (temp-dir) "/content")
        config (assoc config-base
                      :content-path checkout
                      :content-git-url origin
                      :refresh-debounce-seconds 0)]
    (sh! "git" "init" "-q" "-b" "main" origin)
    (entry! origin "2026/01/05/first.md" ";;;\n{:type :post}\n;;;\nfirst")
    (git! origin "add" "-A")
    (git! origin "commit" "-q" "-m" "first")
    (sync/ensure-content! config)
    (let [index-atom (atom (sync/build-indexed config))
          rev (:git-rev (meta @index-atom))
          state (sync/make-refresh-state)]
      ;; Break the remote so any pull attempt would surface as :error —
      ;; proving the sha match answered without touching git at all.
      (git! checkout "remote" "set-url" "origin" (str origin "-gone"))
      (is (= :current (sync/refresh! config index-atom state rev))))))

(deftest refresh-with-differing-sha-syncs-immediately
  (let [origin (temp-dir)
        checkout (str (temp-dir) "/content")
        config (assoc config-base
                      :content-path checkout
                      :content-git-url origin
                      :refresh-debounce-seconds 0)]
    (sh! "git" "init" "-q" "-b" "main" origin)
    (entry! origin "2026/01/05/first.md" ";;;\n{:type :post}\n;;;\nfirst")
    (git! origin "add" "-A")
    (git! origin "commit" "-q" "-m" "first")
    (sync/ensure-content! config)
    (let [index-atom (atom (sync/build-indexed config))
          state (sync/make-refresh-state)]
      (entry! origin "2026/02/10/second.md" ";;;\n{:type :post :title \"Second\"}\n;;;\nmore")
      (git! origin "add" "-A")
      (git! origin "commit" "-q" "-m" "second")

      (testing "a sha the index doesn't recognize syncs right now, in-request"
        (is (= :updated (sync/refresh! config index-atom state
                                       "0000000000000000000000000000000000000000")))
        (is (some? (get (:by-path @index-atom) "/2026/feb/10/second"))))

      (testing "nothing new — a nil sha still syncs, and finds nothing changed"
        (is (= :unchanged (sync/refresh! config index-atom state nil)))))))

(deftest refresh-debounce-coalesces-into-one-trailing-sync
  (let [origin (temp-dir)
        checkout (str (temp-dir) "/content")
        config (assoc config-base
                      :content-path checkout
                      :content-git-url origin
                      :refresh-debounce-seconds 1)]
    (sh! "git" "init" "-q" "-b" "main" origin)
    (entry! origin "2026/01/05/first.md" ";;;\n{:type :post}\n;;;\nfirst")
    (git! origin "add" "-A")
    (git! origin "commit" "-q" "-m" "first")
    (sync/ensure-content! config)
    (let [index-atom (atom (sync/build-indexed config))
          state (sync/make-refresh-state)]
      (testing "the first request in a window claims it and runs in-request"
        (is (= :unchanged (sync/refresh! config index-atom state nil))))

      (entry! origin "2026/03/20/third.md" ";;;\n{:type :post :title \"Third\"}\n;;;\nnewer")
      (git! origin "add" "-A")
      (git! origin "commit" "-q" "-m" "third")

      (testing "a request landing inside the window schedules a trailing sync"
        (is (= :scheduled (sync/refresh! config index-atom state nil))))

      (testing "another request inside the window coalesces onto that same trailing sync"
        (is (= :scheduled (sync/refresh! config index-atom state nil))))

      (Thread/sleep 1600)
      (testing "the trailing sync ran on its own — no further request needed"
        (is (some? (get (:by-path @index-atom) "/2026/mar/20/third")))))))
