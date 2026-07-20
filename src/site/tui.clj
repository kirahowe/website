(ns site.tui
  "Small interactive terminal (TUI) helpers for the babashka authoring
  tasks — a single-pick menu (`choose`), a multi-select (`choose-many`), a
  line prompt (`input`), and a `spin`ner for slow work. Pure babashka: no
  gum, no fzf, no dependencies, matching the project's zero-dep ethos. On a
  real terminal the pickers draw an arrow-key menu (↑/↓ or j/k, Enter to
  pick, q/Esc to cancel); with no tty (a pipe, CI, a redirect) they fall
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
      (= b 32) :space
      (= b (int \+)) :add
      (#{(int \k) (int \K)} b) :up
      (#{(int \j) (int \J)} b) :down
      (#{(int \a) (int \A)} b) :all
      (#{(int \q) (int \Q)} b) :quit
      (#{(int \n) (int \N)} b) :none
      :else :other)))

(defn- read-raw-line
  "Read a line from the raw tty stream `in`, echoing as we type (cbreak
  mode is no-echo) and honoring backspace. Returns the typed string, or nil
  if cancelled with Esc/Ctrl-C. Byte-oriented — meant for short ASCII input
  like a tag; non-ASCII may echo as mojibake but the caller normalizes it."
  [in]
  (loop [sb (StringBuilder.)]
    (let [b (.read in)]
      (cond
        (or (neg? b) (= b 3) (= b 27)) nil
        (or (= b 10) (= b 13)) (do (print "\n") (flush) (str sb))
        (or (= b 127) (= b 8)) (do (when (pos? (.length sb))
                                     (.deleteCharAt sb (dec (.length sb)))
                                     (print "\b \b")
                                     (flush))
                                   (recur sb))
        (>= b 32) (do (.append sb (char b))
                      (print (char b))
                      (flush)
                      (recur sb))
        :else (recur sb)))))

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

;; --- spinner ------------------------------------------------------------

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn spin
  "Run thunk `f` while animating a spinner labelled `msg` on the terminal,
  returning f's value. With no terminal (a pipe, CI) it prints `msg` once
  and just runs f — no animation. The spinner line is cleared before
  returning so the caller's own output starts on a clean line.

  Deliberately does not hide the cursor: callers reach for `spin` around
  work that may `System/exit` on failure, which skips `finally`, and a
  stranded hidden cursor is worse than a visible one on the spinner line."
  [msg f]
  (if-not (interactive?)
    (do (println msg) (f))
    (let [running (atom true)
          animation (future
                      (loop [i 0]
                        (when @running
                          (print (str "\r" (nth spinner-frames (mod i (count spinner-frames))) " " msg))
                          (flush)
                          (Thread/sleep 90)
                          (recur (inc i)))))]
      (try
        (f)
        (finally
          (reset! running false)
          @animation
          (print (str "\r" clear-line))
          (flush))))))

;; --- multi-select -------------------------------------------------------

(defn- multi-lines [label render add? options idx selected]
  (-> [label]
      (into (map-indexed
             (fn [i o]
               (let [box (if (selected i) "◉" "◯")
                     text (str box " " (render o))]
                 (if (= i idx)
                   (str reverse-video " ❯ " text " " reset)
                   (str "   " text))))
             options))
      (conj (str "   ␣ toggle · " (when add? "+ add · ") "↵ confirm · a all · n none · esc cancel"))))

(defn- draw-block!
  "Redraw a block of `lines` in place, moving up over the `prev` lines drawn
  last time and clearing each. Clears any surplus when the block shrank (an
  added-tag prompt that came to nothing), leaving the cursor just below the
  block. Returns the new line count to pass back as `prev`."
  [lines prev]
  (when (pos? prev)
    (print (cursor-up prev)))
  (doseq [ln lines]
    (print (str clear-line ln "\n")))
  (let [extra (max 0 (- prev (count lines)))]
    (dotimes [_ extra]
      (print (str clear-line "\n")))
    (when (pos? extra)
      (print (cursor-up extra))))
  (flush)
  (count lines))

(defn- choose-many-arrows [options label render add preselected]
  (with-open [in (io/input-stream "/dev/tty")]
    (with-cbreak
      (fn []
        (print hide-cursor)
        (flush)
        (loop [options options idx 0 selected preselected prev 0]
          (let [n (draw-block! (multi-lines label render add options idx selected) prev)]
            (case (read-key in)
              :up (recur options (mod (dec idx) (max 1 (count options))) selected n)
              :down (recur options (mod (inc idx) (max 1 (count options))) selected n)
              :space (recur options idx
                            (if (< idx (count options))
                              (if (selected idx) (disj selected idx) (conj selected idx))
                              selected)
                            n)
              :all (recur options idx (set (range (count options))) n)
              :none (recur options idx #{} n)
              :add (if-not add
                     (recur options idx selected n)
                     (do
                       (print "  + add tag: ")
                       (flush)
                       (let [val (some-> (read-raw-line in) add)
                             at (when val (first (keep-indexed #(when (= %2 val) %1) options)))]
                         (cond
                           (nil? val) (recur options idx selected (inc n))
                           at (recur options at (conj selected at) (inc n))
                           :else (let [options' (conj options val)
                                       i (dec (count options'))]
                                   (recur options' i (conj selected i) (inc n)))))))
              :enter (mapv options (sort selected))
              (:quit :esc :interrupt :eof) nil
              (recur options idx selected n))))))))

(defn- parse-choices
  "Parse a numbered multi-select reply into a set of 0-based indices in
  [0, n). Accepts space- or comma-separated numbers; a blank line selects
  everything (matching the arrow menu's all-preselected default); a lone
  `-` or `none` selects nothing."
  [n s]
  (let [t (some-> s str/trim)]
    (cond
      (str/blank? t) (set (range n))
      (#{"-" "none"} t) #{}
      :else (into #{}
                  (comp (map parse-long)
                        (remove nil?)
                        (map dec)
                        (filter #(and (nat-int? %) (< % n))))
                  (str/split t #"[,\s]+")))))

(defn- choose-many-numbered [options label render]
  (println label)
  (doseq [[i o] (map-indexed vector options)]
    (println (format "  %2d. %s" (inc i) (render o))))
  (print "Numbers (space-separated; Enter = all, - = none): ")
  (flush)
  (mapv options (sort (parse-choices (count options) (read-line)))))

(defn choose-many
  "Multi-select `options`, returning a vector of the chosen elements (which
  may be empty), or nil if the author cancels. Options map:
    :label     heading printed above the list (default \"Select:\")
    :render    element → display string (default `str`)
    :preselect :all (default) or :none — the initial selection
    :add       optional (String -> element-or-nil) fn; when given, `+`
               prompts for a line, and the fn's non-nil result is appended
               (or re-selected, if already present) and selected
  Draws a checkbox menu on a real terminal (␣ toggles, + adds, a/n select
  all/none, Enter confirms, q/Esc cancels) and falls back to numbered entry
  otherwise. An empty `options` with no `:add` returns an empty vector
  without prompting."
  [options & {:keys [label render preselect add] :or {label "Select:" render str preselect :all}}]
  (let [options (vec options)
        preselected (if (= preselect :none) #{} (set (range (count options))))]
    (cond
      (and (empty? options) (not (and add (interactive?)))) []
      (interactive?) (choose-many-arrows options label render add preselected)
      :else (choose-many-numbered options label render))))
