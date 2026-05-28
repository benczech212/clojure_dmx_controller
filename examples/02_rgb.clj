;; ─────────────────────────────────────────────────────────────────────────────
;; 02 — RGB Color Control
;; Working with colors: named presets, HSV conversion, color mixing.
;;
;; WHAT YOU'LL LEARN:
;;   • Colors as plain data ([r g b] vectors)
;;   • Named color keywords
;;   • HSV — a more intuitive color model for lighting
;;   • Pure functions: hsv->rgb and mix-colors
;;   • The fixture abstraction — set-color! maps to the right channels
;; ─────────────────────────────────────────────────────────────────────────────

(require '[clojure-dmx.serial  :as serial]
         '[clojure-dmx.fixture :as f]
         '[clojure-dmx.color   :as c]
         '[clojure-dmx.universe :as u])

;; Start output (skip if already running from example 01)
(serial/start-output!)

;; Define a fixture at DMX address 1
(def light (f/patch f/uking-14ch 1 "My Light"))

;; Set the dimmer so the light is on
(f/set-dimmer! light 200)

;; ── Colors as data ────────────────────────────────────────────────────────────

;; In Clojure, a color is just a vector of three numbers.
;; This is the Clojure way: represent data as plain values.
[255 0 0]     ; red
[0 255 0]     ; green
[0 0 255]     ; blue
[255 255 255] ; white

;; ── Named colors ──────────────────────────────────────────────────────────────

;; Look up a color by keyword — returns the [r g b] vector
(c/color-by-name :red)     ; => [255 0 0]
(c/color-by-name :warm)    ; => [255 200 100]
(c/color-by-name :amber)   ; => [255 160 0]

;; All available named colors:
c/colors   ; => {:red [...] :green [...] :blue [...] ...}

;; ── set-color! with keywords ──────────────────────────────────────────────────

(f/set-color! light :red)
(f/set-color! light :green)
(f/set-color! light :blue)
(f/set-color! light :warm)     ; warm white
(f/set-color! light :cyan)     ; turquoise
(f/set-color! light :magenta)  ; pink-red
(f/set-color! light :amber)    ; orange-yellow
(f/set-color! light :white)    ; pure white
(f/set-color! light :off)      ; black / off

;; ── set-color! with [r g b] vectors ──────────────────────────────────────────

(f/set-color! light [255 80 0])     ; deep orange
(f/set-color! light [0 255 200])    ; turquoise-green
(f/set-color! light [128 0 255])    ; violet

;; ── HSV color model ──────────────────────────────────────────────────────────
;;
;; HSV is often more intuitive for lighting:
;;   H (Hue)        0-360   — the color wheel position
;;   S (Saturation) 0.0-1.0 — 0 = white, 1 = full color
;;   V (Value)      0.0-1.0 — 0 = black, 1 = full brightness
;;
;; The color wheel:
;;   0° = Red   60° = Yellow   120° = Green
;;   180° = Cyan  240° = Blue   300° = Magenta   360° = Red again

;; hsv->rgb is a pure function — it just does math and returns [r g b]
(c/hsv->rgb 0   1.0 1.0)   ; => [255 0 0]     red
(c/hsv->rgb 120 1.0 1.0)   ; => [0 255 0]     green
(c/hsv->rgb 240 1.0 1.0)   ; => [0 0 255]     blue
(c/hsv->rgb 60  1.0 1.0)   ; => [255 255 0]   yellow
(c/hsv->rgb 180 1.0 1.0)   ; => [0 255 255]   cyan
(c/hsv->rgb 0   0.0 1.0)   ; => [255 255 255] white (S=0 desaturates)
(c/hsv->rgb 0   1.0 0.5)   ; => [127 0 0]     dark red (V=0.5)

;; Set a color via HSV
(f/set-color! light (c/hsv->rgb 200 0.8 1.0))   ; a nice sky blue

;; ── Sweep the hue ─────────────────────────────────────────────────────────────
;; Evaluate this form to watch the light cycle through all hues.

(doseq [h (range 0 361 5)]
  (f/set-color! light (c/hsv->rgb h 1.0 1.0))
  (Thread/sleep 80))

;; ── Color mixing ──────────────────────────────────────────────────────────────

;; mix-colors linearly interpolates between two colors.
;; t=0.0 gives c1, t=1.0 gives c2, t=0.5 gives the midpoint.

(c/mix-colors [255 0 0] [0 0 255] 0.0)   ; => [255 0 0]   pure red
(c/mix-colors [255 0 0] [0 0 255] 0.5)   ; => [128 0 128] purple
(c/mix-colors [255 0 0] [0 0 255] 1.0)   ; => [0 0 255]   pure blue

;; Works with keyword colors too
(c/mix-colors :amber :blue 0.3)   ; amber leaning blue

;; Fade from red to blue over 2 seconds
(doseq [t (map #(/ % 20.0) (range 21))]
  (f/set-color! light (c/mix-colors :red :blue t))
  (Thread/sleep 100))

;; ── Scale brightness ──────────────────────────────────────────────────────────

;; dim a color without changing its hue
(c/scale-brightness [255 160 0] 0.5)   ; => [127 80 0]  half-bright amber

;; ── Cleanup ───────────────────────────────────────────────────────────────────

(u/blackout!)
(serial/stop-output!)

