(ns clojure-dmx.core
  "Top-level convenience API. Require this namespace in your REPL or examples.

   Quick start:
     (list-ports!)           ; confirm the FTDI device is detected
     (start! 3000)           ; start everything (finds device automatically)
     ; open http://localhost:3000
     (def light (patch uking-14ch 1 \"My Light\"))
     (dimmer! light 200)
     (color!  light :red)
     (stop!)"
  (:require [clojure-dmx.universe   :as u]
            [clojure-dmx.serial     :as serial]
            [clojure-dmx.fixture    :as f]
            [clojure-dmx.color      :as c]
            [clojure-dmx.oscillator :as osc]
            [clojure-dmx.display    :as display]
            [clojure-dmx.server     :as server]))

;; ── System lifecycle ──────────────────────────────────────────────────────────

(defn list-ports!
  "Check whether the FTDI DMX device (VID=0403, PID=6001) is reachable.
   Returns [\"ftdi://0403:6001\"] if found, [] if not.
   The device is found automatically — no COM port needed."
  []
  (let [ports (serial/list-ports)]
    (if (empty? ports)
      (println "FTDI device not found. Is it plugged in? Check Zadig driver setup.")
      (do (println "Device found:" ports)
          ports))))

(defn start!
  "Start the full DMX system:
     • 40Hz DMX output via direct FTDI USB (device auto-detected)
     • Terminal display (4Hz refresh showing active channels)
     • Oscillator engine (~40Hz core.async loop)
     • Web UI at http://localhost:web-port (default 3000)

   web-port: HTTP port for the browser UI (default 3000)

   Examples:
     (start!)        ; port 3000
     (start! 3000)   ; explicit port"
  ([]         (start! 3000))
  ([web-port]
   (serial/start-output!)
   (display/start-display!)
   (osc/start-engine!)
   (server/start-server! web-port)
   :started))

(defn stop!
  "Stop all DMX output, display, oscillators, and web server cleanly."
  []
  (osc/stop-engine!)
  (display/stop-display!)
  (serial/stop-output!)
  (server/stop-server!)
  :stopped)

;; ── Direct channel control ────────────────────────────────────────────────────

(def set-channel!
  "Set DMX channel ch (1-512) to value v (0-255).
   Example: (set-channel! 7 255)"
  u/set-channel!)

(def set-channels!
  "Set multiple channels at once from a {ch val} map.
   Example: (set-channels! {7 255, 8 0, 9 128})"
  u/set-channels!)

(def blackout!
  "Set all 512 channels to 0."
  u/blackout!)

(def get-channel
  "Return the current value of DMX channel ch (1-512)."
  u/get-channel)

;; ── Fixture definitions ────────────────────────────────────────────────────────

(def uking-14ch
  "UKing Mini 7LED moving head — 14-channel mode definition."
  f/uking-14ch)

(def uking-9ch
  "UKing Mini 7LED moving head — 9-channel simplified mode definition."
  f/uking-9ch)

(def patch
  "Patch a fixture at a DMX start address. Returns a patched fixture map.
   Example: (def light (patch uking-14ch 1 \"Stage Left\"))"
  f/patch)

(def channel-of
  "Return the absolute DMX channel number for a fixture's channel key.
   Example: (channel-of light :red) => 7"
  f/channel-of)

;; ── Fixture control ────────────────────────────────────────────────────────────

(defn color!
  "Set the RGB color on a patched fixture.
   color is an [r g b] vector or a keyword (:red :green :blue :white :warm
   :cyan :magenta :yellow :amber :purple :pink :off).
   Example: (color! light :warm)"
  [fixture color]
  (f/set-color! fixture color))

(defn position!
  "Set pan and tilt on a patched fixture.
   Values are 0–255 (use floats for 16-bit precision, e.g. 127.5).
   Example: (position! light 128 64)"
  [fixture pan tilt]
  (f/set-position! fixture pan tilt))

(defn dimmer!
  "Set the dimmer level 0–255 on a patched fixture.
   Input is scaled to the fixture's safe dimmer range automatically.
   For UKing 14CH: 0 = off, 255 = full (maps to raw values 0 and 134).
   Example: (dimmer! light 200)"
  [fixture level]
  (f/set-dimmer! fixture level))

(defn strobe!
  "Set the strobe speed 0–255 on a patched fixture.
   0 = slowest strobe, 255 = fastest. Scaled to fixture's strobe range.
   Call (dimmer! light 200) to return to steady light.
   Example: (strobe! light 128)"
  [fixture speed]
  (f/set-strobe! fixture speed))

(defn speed!
  "Set pan/tilt motor speed 0–255 (0=fastest, 255=slowest).
   Example: (speed! light 200)"
  [fixture spd]
  (f/set-speed! fixture spd))

;; ── Oscillator control ────────────────────────────────────────────────────────

(defn oscillate!
  "Register a channel oscillator. It runs until removed.
   Required keys: :id :channel :type
   Optional: :min (0) :max (255) :frequency (1.0 Hz) :phase (0.0 radians)
   Types: :sine :square :saw :triangle

   Example:
     (oscillate! {:id :red-pulse :channel 7 :type :sine
                  :min 0 :max 255 :frequency 0.5})"
  [osc-map]
  (osc/add-oscillator! osc-map))

(defn stop-oscillate!
  "Remove an oscillator by its :id.
   Example: (stop-oscillate! :red-pulse)"
  [id]
  (osc/remove-oscillator! id))

(defn stop-all-oscillators!
  "Remove all registered oscillators."
  []
  (osc/clear-oscillators!))

;; ── Color helpers ─────────────────────────────────────────────────────────────

(def hsv->rgb
  "Convert HSV to [r g b]. h: 0-360, s: 0.0-1.0, v: 0.0-1.0.
   Example: (hsv->rgb 120 1.0 1.0) => [0 255 0]"
  c/hsv->rgb)

(def mix-colors
  "Interpolate between two colors at t (0.0-1.0). Colors can be keywords.
   Example: (mix-colors :red :blue 0.5) => [128 0 128]"
  c/mix-colors)
