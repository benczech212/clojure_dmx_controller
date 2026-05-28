(ns clojure-dmx.fixture
  (:require [clojure-dmx.universe :as u]
            [clojure-dmx.color    :as c]))

;; Fixtures are plain Clojure maps — data, not objects.
;; A fixture definition describes channel layout relative to address 1.
;; A patched fixture adds a :start-address, placing it in the 512-ch universe.

;; ── Fixture definitions ──────────────────────────────────────────────────────

(def uking-14ch
  "UKing Mini 7LED moving head — 14-channel mode.

   Spec channel layout (1-14):
     Ch 1  Pan           (0-255, coarse)
     Ch 2  Pan Fine      (0-255, sub-step)
     Ch 3  Tilt          (0-255, coarse)
     Ch 4  Tilt Fine     (0-255, sub-step)
     Ch 5  P/T Speed     (0=fast, 255=slow)
     Ch 6  Dimmer/Strobe (DUAL FUNCTION — same channel):
             8-134  = dimmer intensity (8=min, 134=max)
             135-239 = strobe (135=slow, 239=fast)
             0-7     = off
             240-255 = sound-active / auto programs (avoid!)
     Ch 7  Red     (0-255)
     Ch 8  Green   (0-255)
     Ch 9  Blue    (0-255)
     Ch 10 White   (0-255)
     Ch 11-14 Macro / Reset

   Note: :channel-stride 2 — this unit responds on every other DMX channel.
   Spec ch N maps to actual DMX channel (start-address - 1) + N*2.
   Root cause TBD; remove :channel-stride once resolved."
  {:name           "UKing Mini 7LED (14CH)"
   :channels       14
   :channel-stride 2
   :pan            1
   :pan-fine       2
   :tilt           3
   :tilt-fine      4
   :speed          5
   :dimmer         6
   :dimmer-min     8    ; anything below 8 = off
   :dimmer-max     134  ; 134 = full brightness
   :strobe-min     135  ; 135 = slowest strobe
   :strobe-max     239  ; 239 = fastest strobe
   :red            7
   :green          8
   :blue           9
   :white          10
   :macro1         11
   :macro2         12
   :macro3         13
   :macro4         14})

(def uking-9ch
  "UKing Mini 7LED moving head — 9-channel simplified mode.
   Channel layout:
     1 Pan    2 Tilt   3 Speed  4 Dimmer  5 Red  6 Green  7 Blue  8 White  9 Strobe
   Note: :channel-stride 2 assumed — unverified, matches 14CH hardware behaviour."
  {:name           "UKing Mini 7LED (9CH)"
   :channels       9
   :channel-stride 2
   :pan            1
   :tilt           2
   :speed          3
   :dimmer         4
   :dimmer-min     8
   :dimmer-max     134
   :strobe-min     135
   :strobe-max     239
   :red            5
   :green          6
   :blue           7
   :white          8
   :strobe         9})

;; ── Patching ─────────────────────────────────────────────────────────────────

(defn patch
  "Place a fixture at a DMX start address in the universe.
   Returns a new map with :start-address and :label added.

   Example:
     (def left  (patch uking-14ch  1 \"Stage Left\"))   ; channels 1-14
     (def right (patch uking-14ch 15 \"Stage Right\"))  ; channels 15-28"
  [fixture-def start-address label]
  (assoc fixture-def
         :start-address start-address
         :label label))

(defn channel-of
  "Return the absolute DMX channel number for a fixture's channel key, or nil.
   Applies :channel-stride if set (default 1).
   Formula: (start-address - 1) + (offset * stride)
   Example: (channel-of stage-left :red) => 14  (with stride 2, start 1)"
  [fixture ch-key]
  (when-let [offset (get fixture ch-key)]
    (let [stride (get fixture :channel-stride 1)]
      (+ (dec (:start-address fixture)) (* offset stride)))))

;; ── Internal helpers ──────────────────────────────────────────────────────────

(defn- scale-range
  "Map input 0-255 linearly onto the range [out-min out-max].
   Input 0 returns 0 (fixture off), input 1-255 maps to out-min..out-max."
  [out-min out-max input]
  (if (zero? input)
    0
    (int (+ out-min (* (/ (dec input) 254.0) (- out-max out-min))))))

;; ── High-level control ────────────────────────────────────────────────────────

(defn set-color!
  "Set the RGB color on a patched fixture.
   color can be an [r g b] vector or a keyword like :red, :blue, :warm.

   Examples:
     (set-color! stage-left [255 0 128])
     (set-color! stage-left :cyan)"
  [fixture color]
  (let [[r g b] (if (keyword? color) (c/color-by-name color) color)]
    (u/set-channels!
     (cond-> {}
       (channel-of fixture :red)   (assoc (channel-of fixture :red)   r)
       (channel-of fixture :green) (assoc (channel-of fixture :green) g)
       (channel-of fixture :blue)  (assoc (channel-of fixture :blue)  b)))))

(defn set-position!
  "Set pan and tilt on a patched fixture with 16-bit precision.
   pan and tilt are floats in the range 0–255.
   The integer part maps to the coarse channel; the fractional part × 256 to the fine channel.

   Examples:
     (set-position! stage-left 128 64)      ; 8-bit
     (set-position! stage-left 128.5 64.25) ; 16-bit (smoother)"
  [fixture pan tilt]
  (let [pan  (double pan)
        tilt (double tilt)
        pc   (int pan)
        pf   (int (* (mod pan  1.0) 256))
        tc   (int tilt)
        tf   (int (* (mod tilt 1.0) 256))]
    (u/set-channels!
     (cond-> {}
       (channel-of fixture :pan)       (assoc (channel-of fixture :pan)       pc)
       (channel-of fixture :pan-fine)  (assoc (channel-of fixture :pan-fine)  pf)
       (channel-of fixture :tilt)      (assoc (channel-of fixture :tilt)      tc)
       (channel-of fixture :tilt-fine) (assoc (channel-of fixture :tilt-fine) tf)))))

(defn set-dimmer!
  "Set the dimmer level on a patched fixture.
   Input 0-255 is scaled to the fixture's actual dimmer range.
   Input 0 = completely off. Input 255 = maximum brightness.

   For the UKing 14CH, the dimmer range is 8-134.
   Sending values > 134 raw would trigger the strobe/auto program and
   cause the head to move — this function prevents that by scaling correctly."
  [fixture level]
  (when-let [ch (channel-of fixture :dimmer)]
    (let [dmin (get fixture :dimmer-min 0)
          dmax (get fixture :dimmer-max 255)]
      (u/set-channel! ch (scale-range dmin dmax level)))))

(defn set-strobe!
  "Set the strobe speed on a patched fixture.
   Input 0 = slowest strobe, 255 = fastest strobe.
   The fixture's strobe range (e.g. 135-239 for UKing) is scaled automatically.
   Use set-dimmer! to return to steady light."
  [fixture speed]
  (when-let [ch (channel-of fixture :dimmer)]
    (when (get fixture :strobe-min)
      (let [smin (get fixture :strobe-min)
            smax (get fixture :strobe-max)]
        (u/set-channel! ch (int (+ smin (* (/ speed 255.0) (- smax smin)))))))))

(defn set-white!
  "Set the white channel 0–255 on a patched fixture (if it has one)."
  [fixture level]
  (when-let [ch (channel-of fixture :white)]
    (u/set-channel! ch level)))

(defn set-speed!
  "Set pan/tilt motor speed 0–255.
   0 = fastest movement, 255 = slowest (most smooth) movement."
  [fixture speed]
  (when-let [ch (channel-of fixture :speed)]
    (u/set-channel! ch speed)))
