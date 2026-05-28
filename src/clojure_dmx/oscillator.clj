(ns clojure-dmx.oscillator
  (:require [clojure-dmx.universe :as u]
            [clojure.core.async   :as async]))

;; Oscillators animate DMX channels over time without blocking the REPL.
;;
;; An oscillator is a plain Clojure map:
;;   {:id        :my-osc        ; unique keyword identifier
;;    :channel   7              ; absolute DMX channel (1-512)
;;    :type      :sine          ; :sine :square :saw :triangle
;;    :min       0              ; minimum output value (0-255)
;;    :max       255            ; maximum output value (0-255)
;;    :frequency 0.5            ; cycles per second (Hz)
;;    :phase     0.0}           ; phase offset in radians
;;
;; Teaching notes:
;;   - Oscillators as data is the Clojure way. No OOP, no callbacks.
;;   - The engine is a core.async go-loop running ~40Hz in the background.
;;   - Pure value functions make oscillators easy to test and reason about.

(def oscillators
  "Registry of active oscillators. A map of id -> oscillator-map."
  (atom {}))

(def ^:private engine-chan (atom nil))
(def ^:private start-time  (atom nil))

;; ── Pure waveform functions ────────────────────────────────────────────────
;; These are pure functions of time t (seconds since engine start).
;; They return an integer 0-255.

(defn sine-value
  "Sine wave oscillating between min-v and max-v at frequency Hz."
  [min-v max-v frequency phase t]
  (let [angle (+ (* 2.0 Math/PI frequency t) phase)
        range (- (double max-v) (double min-v))]
    (int (+ min-v (* (/ range 2.0) (+ 1.0 (Math/sin angle)))))))

(defn square-value
  "Square wave alternating between min-v and max-v at frequency Hz."
  [min-v max-v frequency phase t]
  (let [period (/ 1.0 frequency)
        offset (/ phase (* 2.0 Math/PI frequency))
        pos    (mod (+ t offset) period)]
    (if (< pos (/ period 2.0)) max-v min-v)))

(defn saw-value
  "Sawtooth wave rising from min-v to max-v over each cycle."
  [min-v max-v frequency phase t]
  (let [period (/ 1.0 frequency)
        offset (/ phase (* 2.0 Math/PI frequency))
        pos    (mod (+ t offset) period)]
    (int (+ min-v (* (- (double max-v) (double min-v)) (/ pos period))))))

(defn triangle-value
  "Triangle wave rising then falling between min-v and max-v."
  [min-v max-v frequency phase t]
  (let [period (/ 1.0 frequency)
        offset (/ phase (* 2.0 Math/PI frequency))
        pos    (mod (+ t offset) period)
        half   (/ period 2.0)
        range  (- (double max-v) (double min-v))]
    (if (< pos half)
      (int (+ min-v (* range (/ pos half))))
      (int (+ min-v (* range (/ (- period pos) half)))))))

(defn- osc-value
  "Compute the current channel value for oscillator osc at time t (seconds)."
  [{:keys [type min max frequency phase]
    :or   {min 0 max 255 frequency 1.0 phase 0.0}} t]
  (case type
    :sine     (sine-value     min max frequency phase t)
    :square   (square-value   min max frequency phase t)
    :saw      (saw-value      min max frequency phase t)
    :triangle (triangle-value min max frequency phase t)
    0))

;; ── Oscillator registry ────────────────────────────────────────────────────

(defn add-oscillator!
  "Register an oscillator. The engine will update its channel on every tick.
   Required keys: :id :channel :type
   Optional keys: :min (0) :max (255) :frequency (1.0) :phase (0.0)

   Examples:
     (add-oscillator! {:id :red-pulse :channel 7 :type :sine
                       :min 0 :max 255 :frequency 0.5})

     (add-oscillator! {:id :pan-sweep :channel 1 :type :triangle
                       :min 50 :max 200 :frequency 0.1})"
  [osc]
  (swap! oscillators assoc (:id osc) osc)
  osc)

(defn remove-oscillator!
  "Stop and remove a registered oscillator by its :id key."
  [id]
  (swap! oscillators dissoc id))

(defn clear-oscillators!
  "Remove all registered oscillators."
  []
  (reset! oscillators {}))

;; ── Engine lifecycle ────────────────────────────────────────────────────────

(defn start-engine!
  "Start the oscillator engine. It runs a go-loop at ~40Hz using core.async,
   updating each registered oscillator's channel on every tick.
   Safe to call multiple times — will not start a second engine."
  []
  (when-not @engine-chan
    (reset! start-time (/ (System/currentTimeMillis) 1000.0))
    (let [ch (async/chan)]
      (reset! engine-chan ch)
      (async/go-loop []
        ;; alts! returns [value selected-channel].
        ;; When the timeout fires, selected-channel is the timeout channel → keep going.
        ;; When ch is closed via stop-engine!, selected-channel is ch → stop.
        (let [[_ selected] (async/alts! [(async/timeout 25) ch])]
          (when (not= selected ch)
            (let [t (- (/ (System/currentTimeMillis) 1000.0) @start-time)]
              (doseq [[_id osc] @oscillators]
                (u/set-channel! (:channel osc) (osc-value osc t))))
            (recur)))))
    (println "[Oscillator] Engine started.")))

(defn stop-engine!
  "Stop the oscillator engine."
  []
  (when-let [ch @engine-chan]
    (async/close! ch)
    (reset! engine-chan nil)
    (println "[Oscillator] Engine stopped.")))
