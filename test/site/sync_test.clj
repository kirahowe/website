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

    (let [index-atom (atom (sync/build-indexed config))]
      (testing "nothing new → :unchanged, index untouched"
        (let [before @index-atom]
          (is (= :unchanged (sync/sync-once! config index-atom)))
          (is (identical? before @index-atom))))

      (testing "new commit → :updated, index rebuilt"
        (entry! origin "2026/02/10/second.md" ";;;\n{:type :post :title \"Second\"}\n;;;\nmore")
        (git! origin "add" "-A")
        (git! origin "commit" "-q" "-m" "second")
        (is (= :updated (sync/sync-once! config index-atom)))
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
