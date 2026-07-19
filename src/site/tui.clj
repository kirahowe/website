(ns site.tui
  "Small interactive terminal (TUI) helpers for the babashka authoring
  tasks — a picker (`choose`) and a line prompt (`input`). Pure babashka:
  no gum, no fzf, no dependencies, matching the project's zero-dep ethos.
  On a real terminal `choose` draws an arrow-key menu (↑/↓ or j/k, Enter
  to pick, q/Esc to cancel); with no tty (a pipe, CI, a redirect) it falls
  back to numbered entry, so the same call works everywhere."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- ANSI ---------------------------------------------------------------

(def ^:private esc (str (char 27)))
(def ^:private hide-cursor (str esc "[?25l"))
(def ^:private show-cursor (str esc "[?25h"))
(def ^:private clear-line (str esc "[2K"))
(def ^:private reset (str esc "[0m"))
(def ^:private reverse-video (str esc "[7m"))
(defn- cursor-up [n] (str esc "[" n "A"))

;; --- terminal detection -------------------------------------------------

(defn interactive?
  "True when there's a real terminal to draw on — the choice between the
  arrow-key menu and numbered entry keys off this. False under a pipe, a
  redirect, or CI, where `choose` falls back to reading a line."
  []
  (and (some? (System/console))
       (.exists (io/file "/dev/tty"))))

;; --- raw-mode plumbing --------------------------------------------------

(defn- stty
  "Run stty against the controlling terminal. stty reads and modifies the
  tty on its stdin, so we point that at /dev/tty explicitly — that way it
  still works if our own stdin has been redirected."
  [& args]
  (apply p/sh {:in (io/file "/dev/tty")} "stty" args))

(defn- with-cbreak
  "Run f with the terminal in cbreak mode — characters available to read
  immediately, no echo — restoring the saved settings (and the cursor) no
  matter how f exits. Signals (Ctrl-C) still work; only line buffering and
  echo are off."
  [f]
  (let [saved (str/trim (:out (stty "-g")))]
    (try
      (stty "-echo" "-icanon" "min" "1" "time" "0")
      (f)
      (finally
        (stty saved)
        (print show-cursor)
        (flush)))))

(defn- read-key
  "Block until the next keypress and classify it. Arrow keys arrive as an
  ESC [ A/B escape sequence (ESC O A in some terminals' application mode);
  a bare Esc — no bytes waiting after it — reads as :esc."
  [in]
  (let [b (.read in)]
    (cond
      (neg? b) :eof
      (= b 3) :interrupt
      (or (= b 10) (= b 13)) :enter
      (= b 27) (if (pos? (.available in))
                 (let [b1 (.read in) b2 (.read in)]
                   (if (#{91 79} b1)
                     (case (long b2) 65 :up, 66 :down, :other)
                     :other))
                 :esc)
      (#{(int \k) (int \K)} b) :up
      (#{(int \j) (int \J)} b) :down
      (#{(int \q) (int \Q)} b) :quit
      :else :other)))

;; --- rendering ----------------------------------------------------------

(defn- menu-lines [label render options idx]
  (into [label]
        (map-indexed
         (fn [i o]
           (if (= i idx)
             (str reverse-video " ❯ " (render o) " " reset)
             (str "   " (render o))))
         options)))

(defn- render-menu!
  "Draw (or redraw) the menu in place. After the first draw the cursor is
  parked below the list, so redraws move it back up over the old lines and
  clear each before reprinting — no flicker, no scrolling."
  [label render options idx first?]
  (let [lines (menu-lines label render options idx)]
    (when-not first?
      (print (cursor-up (count lines))))
    (doseq [ln lines]
      (print (str clear-line ln "\n")))
    (flush)))

;; --- the two front-ends -------------------------------------------------

(defn- choose-arrows [options label render]
  (with-open [in (io/input-stream "/dev/tty")]
    (with-cbreak
      (fn []
        (print hide-cursor)
        (flush)
        (loop [idx 0 first? true]
          (render-menu! label render options idx first?)
          (case (read-key in)
            :up (recur (mod (dec idx) (count options)) false)
            :down (recur (mod (inc idx) (count options)) false)
            :enter (nth options idx)
            (:quit :esc :interrupt :eof) nil
            (recur idx false)))))))

(defn- parse-choice
  "Parse a numbered-menu reply into a 0-based index in [0, n), or nil for
  a blank, non-numeric, or out-of-range answer."
  [n s]
  (when-let [t (some-> s str/trim not-empty)]
    (when (re-matches #"\d+" t)
      (let [i (dec (parse-long t))]
        (when (and (nat-int? i) (< i n)) i)))))

(defn- choose-numbered [options label render]
  (println label)
  (doseq [[i o] (map-indexed vector options)]
    (println (format "  %2d. %s" (inc i) (render o))))
  (print "Number (blank to cancel): ")
  (flush)
  (some->> (read-line) (parse-choice (count options)) (nth options)))

;; --- public API ---------------------------------------------------------

(defn choose
  "Show `options` and return the chosen element, or nil if the author
  cancels. Options map:
    :label   heading printed above the list (default \"Choose:\")
    :render  element → its display string (default `str`)
  Draws an arrow-key menu on a real terminal and falls back to numbered
  entry otherwise. An empty `options` returns nil without prompting."
  [options & {:keys [label render] :or {label "Choose:" render str}}]
  (let [options (vec options)]
    (cond
      (empty? options) nil
      (interactive?) (choose-arrows options label render)
      :else (choose-numbered options label render))))

(defn input
  "Prompt for a line of text, returning it trimmed, or nil if the author
  enters nothing. Plain line editing — no raw mode."
  [prompt]
  (print prompt)
  (flush)
  (some-> (read-line) str/trim not-empty))
