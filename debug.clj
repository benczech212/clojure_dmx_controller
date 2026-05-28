
(require '[clojure-dmx.universe :as u]
         '[clojure-dmx.serial :as serial])

;; Make sure output is running
(serial/start-output!)

;; ── Sweep helpers ─────────────────────────────────────────────────────────────

(defn sweep! [ch]
  (u/blackout!)
  (Thread/sleep 500)
  (println "Sweeping channel" ch "...")
  (dotimes [_ 2]
    (doseq [v (concat (range 0 256 3) (range 255 -1 -3))]
      (u/set-channel! ch v)
      (Thread/sleep 20)))
  (u/set-channel! ch 0)
  (println "Done. Channel" ch "is now 0."))

(defn sweep-fast! [ch]
  (u/blackout!)
  (Thread/sleep 200)
  (println "Sweeping channel" ch "fast...")
  (dotimes [_ 3]
    (doseq [v (concat (range 0 256 5) (range 255 -1 -5))]
      (u/set-channel! ch v)
      (Thread/sleep 5)))
  (u/set-channel! ch 0)
  (println "Done. Channel" ch "= 0"))

(defn sweep-slow! [ch]
  (u/blackout!)
  (Thread/sleep 1000)
  (println "Sweeping channel" ch "slowly...")
  (doseq [v (range 0 256 5)]
    (u/set-channel! ch v)
    (Thread/sleep 50))
  (doseq [v (range 255 -1 -5)]
    (u/set-channel! ch v)
    (Thread/sleep 50))
  (u/set-channel! ch 0)
  (println "Done. Channel" ch "= 0"))

;; ── Confirmed channel map (start address 1) ───────────────────────────────────
;; Ch 1:  unknown
;; Ch 2:  Pan coarse
;; Ch 3:  unknown
;; Ch 4:  Pan fine
;; Ch 5:  unknown
;; Ch 6:  Tilt coarse
;; Ch 7:  unknown
;; Ch 8:  Tilt fine
;; Ch 9-14: unknown — color/dimmer (need each other to produce visible output)

;; ── Color/dimmer diagnostic ───────────────────────────────────────────────────
;; Color channels only produce visible light when the dimmer is ALSO active.
;; Sweeping them one at a time looks like "nothing" due to this dependency.
;;
;; try-light! sets every unidentified channel to 100 simultaneously.
;; If light appears, we know dimmer+color candidates are in this group.

(defn try-light!
  "Set all unidentified channels to 100. Does any light appear?
   Skips confirmed motion channels (2 4 6 8) to avoid moving the head."
  []
  (u/blackout!)
  (Thread/sleep 300)
  (doseq [ch [1 3 5 7 9 10 11 12 13 14]]
    (u/set-channel! ch 100))
  (println "Channels 1 3 5 7 9-14 set to 100 — do you see light?"))

(defn sweep-with!
  "Sweep ch 0→255→0 while holding hold-ch at hold-val.
   Use to sweep color channels with a dimmer candidate held at 100.
   Example: (sweep-with! 9 12 100) — sweep ch 9 while ch 12 = 100."
  [ch hold-ch hold-val]
  (u/blackout!)
  (u/set-channel! hold-ch hold-val)
  (Thread/sleep 300)
  (println "Sweeping ch" ch "while ch" hold-ch "=" hold-val "...")
  (doseq [v (concat (range 0 256 3) (range 255 -1 -3))]
    (u/set-channel! ch v)
    (Thread/sleep 20))
  (u/set-channel! ch 0)
  (println "Done."))

;; ── STEP 1: Does light appear when all unidentified channels = 100? ───────────
(try-light!)

;; ── STEP 2: If yes, zero one channel at a time to find the dimmer.
;;           When you zero the dimmer, the light should go out.
;; (do (u/blackout!) (doseq [ch [1 3 5 7 9 10 11 12 13 14]] (u/set-channel! ch 100)) (u/set-channel! 9  0))
;; (do (u/blackout!) (doseq [ch [1 3 5 7 9 10 11 12 13 14]] (u/set-channel! ch 100)) (u/set-channel! 10 0))
;; (do (u/blackout!) (doseq [ch [1 3 5 7 9 10 11 12 13 14]] (u/set-channel! ch 100)) (u/set-channel! 11 0))
;; (do (u/blackout!) (doseq [ch [1 3 5 7 9 10 11 12 13 14]] (u/set-channel! ch 100)) (u/set-channel! 12 0))
;; (do (u/blackout!) (doseq [ch [1 3 5 7 9 10 11 12 13 14]] (u/set-channel! ch 100)) (u/set-channel! 13 0))
;; (do (u/blackout!) (doseq [ch [1 3 5 7 9 10 11 12 13 14]] (u/set-channel! ch 100)) (u/set-channel! 14 0))

;; ── STEP 3: Once dimmer is known, sweep each remaining channel with dimmer held.
;;           Whichever channel changes the color is that color.
;; (sweep-with! 9  <dimmer-ch> 100)
;; (sweep-with! 10 <dimmer-ch> 100)
;; etc.
