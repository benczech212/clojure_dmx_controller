;; ─────────────────────────────────────────────────────────────────────────────
;; 03 — Pan and Tilt Position Control
;; Moving head positioning with 8-bit and 16-bit precision.
;;
;; WHAT YOU'LL LEARN:
;;   • How pan/tilt channel pairs work (coarse + fine = 16-bit precision)
;;   • 256 steps vs 65536 steps — why 16-bit matters for smooth movement
;;   • Sweeping through positions in a loop
;;   • Motor speed control
;; ─────────────────────────────────────────────────────────────────────────────

(require '[clojure-dmx.serial   :as serial]
         '[clojure-dmx.fixture  :as f]
         '[clojure-dmx.universe :as u])

(serial/start-output!)

(def light (f/patch f/uking-14ch 1 "My Light"))

;; White light at medium brightness so we can see position changes
(f/set-dimmer! light 180)
(f/set-color!  light :white)

;; ── How pan/tilt channels work ────────────────────────────────────────────────
;;
;; The UKing 14CH fixture uses two DMX channels per axis:
;;
;;   Channel 2  — Pan Coarse  (MSB): 0-255  → big steps
;;   Channel 4  — Pan Fine    (LSB): 0-255  → tiny sub-steps
;;   Channel 6  — Tilt Coarse (MSB): 0-255  → big steps
;;   Channel 8  — Tilt Fine   (LSB): 0-255  → tiny sub-steps
;;
;; (Fixture responds on every other channel — see example 01 for details.)
;; Together they give 16-bit resolution: 256 × 256 = 65,536 positions.
;; This is the difference between jerky and silky-smooth movement.

;; ── 8-bit positioning (integer pan/tilt) ─────────────────────────────────────

;; When you pass integers, set-position! only sets the coarse channel.
;; 256 discrete steps across the pan range.

(f/set-position! light 0   0)    ; extreme: full left, full up
(f/set-position! light 255 255)  ; extreme: full right, full down
(f/set-position! light 128 128)  ; center

;; ── 16-bit positioning (float pan/tilt) ──────────────────────────────────────

;; When you pass floats, set-position! uses both coarse and fine channels.
;; The integer part → coarse channel
;; fractional part × 256 → fine channel
;;
;; Example: pan = 128.5
;;   coarse = 128
;;   fine   = (0.5 × 256) = 128
;;
;; This gives 65,536 steps — you can move the head 0.0015° at a time.

(f/set-position! light 128.5   64.0)
(f/set-position! light 128.25  64.75)  ; sub-step precision

;; Verify what got written to the DMX universe
(u/get-channel 2)   ; pan coarse
(u/get-channel 4)   ; pan fine
(u/get-channel 6)   ; tilt coarse
(u/get-channel 8)   ; tilt fine

;; ── Motor speed ───────────────────────────────────────────────────────────────

;; Channel 10 = Pan/Tilt Speed  (spec ch 5, stride 2 → DMX ch 10)
;;   0   = fastest (instant snap)
;;   255 = slowest (very gradual)
;; Setting a slow speed makes large position jumps appear as smooth sweeps.

(f/set-speed! light 200)    ; slow, smooth movement
(f/set-position! light 0 0)
(Thread/sleep 3000)          ; wait for the head to arrive
(f/set-position! light 255 255)
(Thread/sleep 3000)

(f/set-speed! light 0)      ; back to instant movement
(f/set-position! light 128 128)

;; ── Sweeping across pan ───────────────────────────────────────────────────────

;; A smooth 16-bit pan sweep using fractional steps.
;; Each step moves 0.5 DMX units = half of one coarse step.
(doseq [pan (map #(/ % 2.0) (range 0 512))]
  (f/set-position! light pan 128)
  (Thread/sleep 15))

;; ── Circular motion ───────────────────────────────────────────────────────────

;; Use trig to trace a circular path in pan/tilt space
(doseq [angle (map #(* % (/ Math/PI 30)) (range 60))]
  (let [pan  (+ 128 (* 60 (Math/cos angle)))
        tilt (+ 90  (* 40 (Math/sin angle)))]
    (f/set-position! light pan tilt)
    (Thread/sleep 50)))

;; ── Figure-8 pattern ──────────────────────────────────────────────────────────

(doseq [t (map #(/ % 10.0) (range 120))]
  (let [pan  (+ 128 (* 70 (Math/sin t)))
        tilt (+ 90  (* 35 (Math/sin (* 2 t))))]
    (f/set-position! light pan tilt)
    (Thread/sleep 40)))

;; Return to center
(f/set-position! light 128 128)

;; ── Cleanup ───────────────────────────────────────────────────────────────────

(u/blackout!)
(serial/stop-output!)
