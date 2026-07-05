(ns site.sync-test
  "Exercises clone/pull/reindex against real git repos in a temp dir."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [site.content :as content]
            [site.sync :as sync])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def config-base {:entry-types [:post :note :link :quote]})

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
    (entry! origin "2026/01/05/first.md" ";;;\n{:type :note}\n;;;\nfirst entry")
    (git! origin "add" "-A")
    (git! origin "commit" "-q" "-m" "first")

    (testing "ensure-content! clones when the checkout is missing"
      (sync/ensure-content! config)
      (is (.exists (io/file checkout "2026/01/05/first.md"))))

    (testing "ensure-content! is a no-op when already cloned"
      (sync/ensure-content! config))

    (let [index-atom (atom (content/build-index config))]
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
