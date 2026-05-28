;; ─────────────────────────────────────────────────────────────────────────────
;; 04 — Oscillators
;; Animate channels continuously without blocking the REPL.
;;
;; WHAT YOU'LL LEARN:
;;   • Oscillators as plain data maps (no objects, no callbacks)
;;   • Four waveform types: :sine :square :saw :triangle
;;   • The oscillator engine — a core.async go-loop at 40Hz
;;   • Phase offsets to create complementary effects
;;   • Combining multiple oscillators into a scene
;; ─────────────────────────────────────────────────────────────────────────────

(require '[clojure-dmx.serial     :as serial]
         '[clojure-dmx.fixture    :as f]
         '[clojure-dmx.oscillator :as osc]
         '[clojure-dmx.universe   :as u])

(serial/start-output!)
(osc/start-engine!)

(def light (f/patch f/uking-14ch 1 "My Light"))
(f/set-dimmer! light 255)

;; ── Oscillators are data ──────────────────────────────────────────────────────
;;
;; An oscillator is a plain Clojure map with these keys:
;;
;;   :id        — unique keyword to identify and remove it later
;;   :channel   — which DMX channel to update (1-512)
;;   :type      — waveform: :sine :square :saw :triangle
;;   :min       — minimum channel value (default 0)
;;   :max       — maximum channel value (default 255)
;;   :frequency — cycles per second, e.g. 0.5 = once every 2 seconds
;;   :phase     — starting offset in radians (default 0.0)
;;
;; The oscillator engine reads these maps and calls the appropriate
;; waveform function at each 40Hz tick. No objects, no mutation of the
;; oscillator map itself — state lives only in the universe atom.

;; ── Waveform demo ─────────────────────────────────────────────────────────────

;; SINE — smooth, organic pulsing
;; Great for: dimmer breathe, color fade, gentle pan sway
(osc/add-oscillator! {:id :sine-demo :channel (f/channel-of light :red)
                      :type :sine :min 0 :max 255 :frequency 0.5})
(Thread/sleep 5000)
(osc/remove-oscillator! :sine-demo)

;; SQUARE — hard on/off switching at the set frequency
;; Great for: strobe, blinking, beat-sync effects
(osc/add-oscillator! {:id :sq-demo :channel (f/channel-of light :red)
                      :type :square :min 0 :max 255 :frequency 2.0})
(Thread/sleep 5000)
(osc/remove-oscillator! :sq-demo)

;; SAWTOOTH — ramps up then resets instantly
;; Great for: chase effects, sequential color sweeps
(osc/add-oscillator! {:id :saw-demo :channel (f/channel-of light :red)
                      :type :saw :min 0 :max 255 :frequency 0.5})
(Thread/sleep 5000)
(osc/remove-oscillator! :saw-demo)

;; TRIANGLE — ramps up then ramps back down (symmetrical)
;; Great for: smooth back-and-forth pan/tilt, symmetric fade
(osc/add-oscillator! {:id :tri-demo :channel (f/channel-of light :red)
                      :type :triangle :min 0 :max 255 :frequency 0.5})
(Thread/sleep 5000)
(osc/remove-oscillator! :tri-demo)

;; ── Phase offsets: complementary effects ─────────────────────────────────────
;;
;; Math/PI radians = 180° = half a cycle.
;; Two oscillators at the same frequency but PI apart are always opposite:
;; when one is at max, the other is at min.

(osc/add-oscillator! {:id :red-up   :channel (f/channel-of light :red)
                      :type :sine :min 0 :max 255 :frequency 0.5 :phase 0.0})
(osc/add-oscillator! {:id :blue-dn  :channel (f/channel-of light :blue)
                      :type :sine :min 0 :max 255 :frequency 0.5 :phase Math/PI})
;; Result: red fades in as blue fades out, continuously alternating
(Thread/sleep 8000)

(osc/clear-oscillators!)
(u/blackout!)

;; ── Inspect the oscillator registry ──────────────────────────────────────────

;; Oscillators live in an atom — you can inspect it at any time
(osc/add-oscillator! {:id :test :channel (f/channel-of light :red) :type :sine :frequency 1.0})
@osc/oscillators    ; => {:test {:id :test :channel 14 :type :sine ...}}
(osc/remove-oscillator! :test)
@osc/oscillators    ; => {}

;; ── Full scene: three-color pulse ─────────────────────────────────────────────

;; Three oscillators at the same frequency, 120° apart in phase.
;; This creates a continuous rolling color wave.

(f/set-dimmer! light 255)

(osc/add-oscillator! {:id :r :channel (f/channel-of light :red)
                      :type :sine :min 0 :max 255 :frequency 0.3
                      :phase 0.0})
(osc/add-oscillator! {:id :g :channel (f/channel-of light :green)
                      :type :sine :min 0 :max 255 :frequency 0.3
                      :phase (* 2 (/ Math/PI 3))})   ; 120° offset
(osc/add-oscillator! {:id :b :channel (f/channel-of light :blue)
                      :type :sine :min 0 :max 255 :frequency 0.3
                      :phase (* 4 (/ Math/PI 3))})   ; 240° offset

;; The REPL is still free — add more oscillators while this runs
(osc/add-oscillator! {:id :pan-wave :channel (f/channel-of light :pan)
                      :type :triangle :min 60 :max 195 :frequency 0.08})

;; Remove just one at a time
(osc/remove-oscillator! :pan-wave)

;; Override a channel while oscillators are running — the override "wins"
;; until the oscillator overwrites it on the next 40Hz tick
(f/set-color! light [255 255 255])  ; briefly white, then oscillators resume

;; Stop everything
(osc/clear-oscillators!)
(u/blackout!)
(osc/stop-engine!)
(serial/stop-output!)
