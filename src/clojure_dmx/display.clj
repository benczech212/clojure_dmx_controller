(ns clojure-dmx.display
  (:require [clojure-dmx.universe :as u]))

;; Terminal display — renders a live snapshot of the DMX universe to stdout.
;;
;; A background thread polls @universe every 250ms and clears/redraws the terminal.
;; Only non-zero channels are shown, keeping the output readable.
;;
;; ANSI escape codes: works in Windows Terminal, VS Code terminal, and most modern
;; terminals. Does not work in the classic Windows cmd.exe without ANSI mode enabled.
;;
;; Teaching note: this uses a simple polling thread rather than add-watch.
;; add-watch fires on every swap! (40+ times/second from the output loop),
;; which would flood the terminal. Polling every 250ms is the right tool here.

(def ^:private running?    (atom false))
(def ^:private disp-thread (atom nil))

(defn- bar
  "Render value v (0-255) as a 16-character ASCII progress bar."
  [v]
  (let [filled (int (* (/ (double v) 255.0) 16))
        empty  (- 16 filled)]
    (str (apply str (repeat filled "█"))
         (apply str (repeat empty  "░")))))

(defn- render []
  (let [snap   @u/universe
        active (filter #(pos? (second %)) (map-indexed vector snap))]
    ;; ANSI: clear screen + move cursor to top-left
    (print "[2J[H")
    (println "╔══ Clojure DMX Universe ═══════════════════════════╗")
    (if (empty? active)
      (println "║  (all channels at 0 — blackout)                    ║")
      (doseq [[idx v] active]
        (println (format "║  CH %3d  %3d  %s  ║" (inc idx) v (bar v)))))
    (println "╚════════════════════════════════════════════════════╝")
    (flush)))

(defn start-display!
  "Start a background thread that renders the DMX universe to the terminal at 4Hz.
   Works best in Windows Terminal or VS Code's integrated terminal."
  []
  (if @running?
    (println "[Display] Already running.")
    (do
      (reset! running? true)
      (reset! disp-thread
              (future
                (loop []
                  (when @running?
                    (try
                      (render)
                      (catch Exception _))
                    (Thread/sleep 250)
                    (recur)))))
      (println "[Display] Terminal display started (4Hz). Press Ctrl+C or call stop-display! to stop."))))

(defn stop-display!
  "Stop the terminal display thread."
  []
  (reset! running? false)
  (when-let [t @disp-thread]
    (future-cancel t)
    (reset! disp-thread nil))
  (println "[Display] Stopped."))
