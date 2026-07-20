(ns site.tui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.tui :as tui]))

(deftest parse-choice
  (let [parse #'tui/parse-choice]
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
  (let [read-key #'tui/read-key
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

    (testing "multi-select keys"
      (is (= :space (feed [32])))
      (is (= :add (feed [(int \+)])))
      (is (= :all (feed [(int \a)])))
      (is (= :none (feed [(int \n)]))))

    (testing "Ctrl-C, EOF, and unmapped keys"
      (is (= :interrupt (feed [3])))
      (is (= :eof (feed [])))
      (is (= :other (feed [(int \x)]))))))

(deftest read-raw-line
  (let [read-raw-line #'tui/read-raw-line
        feed (fn [bytes]
               (with-out-str
                 (read-raw-line (java.io.ByteArrayInputStream.
                                 (byte-array (map unchecked-byte bytes))))))
        result (fn [bytes]
                 (read-raw-line (java.io.ByteArrayInputStream.
                                 (byte-array (map unchecked-byte bytes)))))]
    (testing "printable characters up to Enter form the line"
      (is (= "hi" (result [(int \h) (int \i) 10])))
      (is (= "hi" (result [(int \h) (int \i) 13]))))

    (testing "backspace (DEL or BS) erases the last character"
      (is (= "axc" (result [(int \a) (int \b) 127 (int \x) (int \c) 10])))
      (is (= "a" (result [(int \a) (int \b) 8 10])))
      (testing "backspace on an empty buffer is harmless"
        (is (= "z" (result [127 (int \z) 10])))))

    (testing "Esc and Ctrl-C cancel to nil"
      (is (nil? (result [(int \h) (int \i) 27])))
      (is (nil? (result [(int \h) 3]))))

    (testing "typed characters are echoed"
      (is (= "hi" (str/trim (feed [(int \h) (int \i) 10])))))))

(deftest draw-block!
  (let [draw-block! #'tui/draw-block!
        clr (str (char 27) "[2K")]
    (testing "returns the new line count and prints one cleared line each"
      (let [out (with-out-str (is (= 2 (draw-block! ["a" "b"] 0))))]
        (is (= 2 (count (re-seq (re-pattern (java.util.regex.Pattern/quote clr)) out))))
        (is (not (re-find #"\x1b\[\d+A" out)))))

    (testing "a redraw moves up over the previous block first"
      (let [out (with-out-str (draw-block! ["a" "b" "c"] 3))]
        (is (re-find #"\x1b\[3A" out))))

    (testing "a shrunk block clears the surplus lines and steps back up"
      ;; e.g. an add prompt (prev=3) that came to nothing, redrawn as 2 lines:
      ;; 2 real + 1 surplus cleared, then up 1 to sit just below the block.
      (let [out (with-out-str (is (= 2 (draw-block! ["a" "b"] 3))))]
        (is (re-find #"\x1b\[3A" out))          ; up over the taller previous block
        (is (re-find #"\x1b\[1A" out))          ; step back after clearing 1 surplus line
        (is (= 3 (count (re-seq (re-pattern (java.util.regex.Pattern/quote clr)) out))))))))

(deftest choose-edge-cases
  (testing "an empty option list returns nil without prompting"
    ;; Short-circuits before any terminal check, so this holds with or
    ;; without a tty.
    (is (nil? (tui/choose []))))

  ;; Force the numbered path so the test is deterministic whether or not
  ;; the runner has a real terminal (an arrow-key path would block on tty
  ;; input).
  (with-redefs [tui/interactive? (constantly false)]
    (testing "the numbered path returns the element behind a valid number"
      (is (= "b" (with-in-str "2\n" (tui/choose ["a" "b" "c"])))))

    (testing "a blank answer cancels to nil"
      (is (nil? (with-in-str "\n" (tui/choose ["a" "b" "c"])))))

    (testing "a single option is still confirmed, never auto-selected"
      (is (= "only" (with-in-str "1\n" (tui/choose ["only"]))))
      (is (nil? (with-in-str "\n" (tui/choose ["only"])))))))

(deftest multi-lines-hint
  (let [multi-lines #'tui/multi-lines
        hint (fn [add?] (last (multi-lines "L" identity add? ["a"] 0 #{})))]
    (testing "the footer advertises + add only when adding is enabled"
      (is (str/includes? (hint true) "+ add"))
      (is (not (str/includes? (hint false) "+ add"))))))

(deftest menu-lines-hint
  (let [menu-lines #'tui/menu-lines]
    (testing "a footer with the + hint is present only when adding is enabled"
      (let [with-add (menu-lines "L" identity true ["a"] 0)
            no-add (menu-lines "L" identity false ["a"] 0)]
        (is (str/includes? (last with-add) "type your own"))
        (is (= (inc (count no-add)) (count with-add)))   ; the hint is the extra line
        (is (not (some #(str/includes? % "type your own") no-add)))))))

(deftest choose-many-numbered
  ;; Force the numbered path so it's deterministic without a tty.
  (with-redefs [tui/interactive? (constantly false)]
    (testing "space/comma separated numbers pick those elements, in order"
      (is (= ["a" "c"] (with-in-str "1 3\n" (tui/choose-many ["a" "b" "c"]))))
      (is (= ["b" "c"] (with-in-str "2,3\n" (tui/choose-many ["a" "b" "c"])))))

    (testing "a blank line takes all (matching the arrow menu's default)"
      (is (= ["a" "b" "c"] (with-in-str "\n" (tui/choose-many ["a" "b" "c"])))))

    (testing "a lone - selects nothing"
      (is (= [] (with-in-str "-\n" (tui/choose-many ["a" "b" "c"])))))

    (testing "an empty option list (with no :add) is an empty vector, no prompt"
      (is (= [] (tui/choose-many []))))))
