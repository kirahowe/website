(ns site.charm-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.charm :as charm]))

(deftest parse-choice
  (let [parse #'charm/parse-choice]
    (testing "a valid 1-based number maps to a 0-based index"
      (is (= 0 (parse 3 "1")))
      (is (= 2 (parse 3 "3"))))

    (testing "surrounding whitespace is ignored"
      (is (= 1 (parse 3 "  2  "))))

    (testing "blank, nil, and non-numeric answers are nil (cancel)"
      (is (nil? (parse 3 "")))
      (is (nil? (parse 3 "   ")))
      (is (nil? (parse 3 nil)))
      (is (nil? (parse 3 "abc")))
      (is (nil? (parse 3 "2x"))))

    (testing "out-of-range numbers are nil, including zero"
      (is (nil? (parse 3 "0")))
      (is (nil? (parse 3 "4")))
      (is (nil? (parse 3 "99"))))))

(deftest read-key-classification
  (let [read-key #'charm/read-key
        feed (fn [bytes]
               (read-key (java.io.ByteArrayInputStream.
                          (byte-array (map unchecked-byte bytes)))))]
    (testing "arrow escape sequences (ESC [ A/B and app-mode ESC O A/B)"
      (is (= :up (feed [27 91 65])))
      (is (= :down (feed [27 91 66])))
      (is (= :up (feed [27 79 65])))
      (is (= :down (feed [27 79 66]))))

    (testing "a bare Esc — nothing waiting after it — cancels"
      (is (= :esc (feed [27]))))

    (testing "Enter, in either newline flavour"
      (is (= :enter (feed [10])))
      (is (= :enter (feed [13]))))

    (testing "vi keys and q mirror the arrows/quit"
      (is (= :up (feed [(int \k)])))
      (is (= :down (feed [(int \j)])))
      (is (= :quit (feed [(int \q)]))))

    (testing "Ctrl-C, EOF, and unmapped keys"
      (is (= :interrupt (feed [3])))
      (is (= :eof (feed [])))
      (is (= :other (feed [(int \x)]))))))

(deftest choose-edge-cases
  (testing "an empty option list returns nil without prompting"
    ;; Short-circuits before any terminal check, so this holds with or
    ;; without a tty.
    (is (nil? (charm/choose []))))

  ;; Force the numbered path so the test is deterministic whether or not
  ;; the runner has a real terminal (an arrow-key path would block on tty
  ;; input).
  (with-redefs [charm/interactive? (constantly false)]
    (testing "the numbered path returns the element behind a valid number"
      (is (= "b" (with-in-str "2\n" (charm/choose ["a" "b" "c"])))))

    (testing "a blank answer cancels to nil"
      (is (nil? (with-in-str "\n" (charm/choose ["a" "b" "c"])))))

    (testing "a single option is still confirmed, never auto-selected"
      (is (= "only" (with-in-str "1\n" (charm/choose ["only"]))))
      (is (nil? (with-in-str "\n" (charm/choose ["only"])))))))
