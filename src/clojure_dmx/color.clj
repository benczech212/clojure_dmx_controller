(ns clojure-dmx.color)

;; All functions here are pure — they take values and return values.
;; No atoms, no side effects, no state. Safe to call from anywhere.
;;
;; Colors are represented as [r g b] vectors where each component is 0-255.

(def colors
  "Named color presets as [r g b] vectors."
  {:red     [255   0   0]
   :green   [  0 255   0]
   :blue    [  0   0 255]
   :white   [255 255 255]
   :warm    [255 200 100]
   :cyan    [  0 255 255]
   :magenta [255   0 255]
   :yellow  [255 255   0]
   :amber   [255 160   0]
   :purple  [128   0 255]
   :pink    [255  20 147]
   :off     [  0   0   0]})

(defn color-by-name
  "Look up a named color keyword, returning [r g b] or nil."
  [kw]
  (get colors kw))

(defn hsv->rgb
  "Convert HSV to [r g b] (0-255 each).
   h: hue 0-360 degrees
   s: saturation 0.0-1.0
   v: value/brightness 0.0-1.0

   Examples:
     (hsv->rgb 0   1.0 1.0) => [255 0 0]    ; red
     (hsv->rgb 120 1.0 1.0) => [0 255 0]    ; green
     (hsv->rgb 240 1.0 1.0) => [0 0 255]    ; blue
     (hsv->rgb 60  1.0 1.0) => [255 255 0]  ; yellow
     (hsv->rgb 0   0.0 1.0) => [255 255 255]; white (S=0)"
  [h s v]
  (let [h  (mod (double h) 360)
        c  (* (double v) (double s))
        x  (* c (- 1.0 (Math/abs (- (mod (/ h 60.0) 2.0) 1.0))))
        m  (- (double v) c)
        [r' g' b'] (cond
                     (< h  60) [c x 0.0]
                     (< h 120) [x c 0.0]
                     (< h 180) [0.0 c x]
                     (< h 240) [0.0 x c]
                     (< h 300) [x 0.0 c]
                     :else     [c 0.0 x])]
    [(int (* (+ r' m) 255))
     (int (* (+ g' m) 255))
     (int (* (+ b' m) 255))]))

(defn rgb->hsv
  "Convert [r g b] (0-255 each) to [h s v].
   Returns h (0-360), s (0.0-1.0), v (0.0-1.0)."
  [[r g b]]
  (let [r' (/ (double r) 255.0)
        g' (/ (double g) 255.0)
        b' (/ (double b) 255.0)
        cmax  (max r' g' b')
        cmin  (min r' g' b')
        delta (- cmax cmin)
        h (cond
            (zero? delta)  0.0
            (= cmax r')    (* 60.0 (mod (/ (- g' b') delta) 6.0))
            (= cmax g')    (* 60.0 (+ (/ (- b' r') delta) 2.0))
            :else          (* 60.0 (+ (/ (- r' g') delta) 4.0)))
        s (if (zero? cmax) 0.0 (/ delta cmax))]
    [h s cmax]))

(defn mix-colors
  "Linearly interpolate between two colors. t is 0.0 (c1) to 1.0 (c2).
   Colors can be [r g b] vectors or keywords.
   Example: (mix-colors :red :blue 0.5) => [128 0 128]  ; purple"
  [c1 c2 t]
  (let [[r1 g1 b1] (if (keyword? c1) (color-by-name c1) c1)
        [r2 g2 b2] (if (keyword? c2) (color-by-name c2) c2)
        t          (double t)]
    [(int (+ r1 (* t (- r2 r1))))
     (int (+ g1 (* t (- g2 g1))))
     (int (+ b1 (* t (- b2 b1))))]))

(defn scale-brightness
  "Scale an [r g b] color by a factor 0.0-1.0.
   Example: (scale-brightness [255 128 0] 0.5) => [127 64 0]"
  [[r g b] factor]
  [(int (* r factor))
   (int (* g factor))
   (int (* b factor))])
