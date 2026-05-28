;; ─────────────────────────────────────────────────────────────────────────────
;; 05 — Combined Demo
;; Everything together: full system start, multiple fixtures, oscillators,
;; the browser UI, and REPL-driven live control.
;;
;; WHAT YOU'LL LEARN:
;;   • Starting the complete system with one call
;;   • Multiple fixtures at different addresses
;;   • Mixing manual control with oscillators
;;   • Building scenes with named functions
;;   • Real-time feedback in both the terminal and browser
;; ─────────────────────────────────────────────────────────────────────────────

(require '[clojure-dmx.core       :as dmx]
         '[clojure-dmx.fixture    :as f]
         '[clojure-dmx.color      :as c]
         '[clojure-dmx.oscillator :as osc]
         '[clojure-dmx.universe   :as u])

;; ── STEP 1: Check the device is connected ────────────────────────────────────

(dmx/list-ports!)
;; => ["ftdi://0403:6001"]  if found
;; => []                    if not found (check USB connection / Zadig driver)

;; ── STEP 2: Start everything ──────────────────────────────────────────────────

;; start! brings up:
;;   • 40Hz DMX output via direct FTDI USB (no COM port needed)
;;   • Terminal display refreshing at 4Hz
;;   • Oscillator engine (core.async go-loop)
;;   • Ring/Jetty web server at http://localhost:3000
;;
;; After this call, open http://localhost:3000 in your browser.
(dmx/start! 3000)

;; ── STEP 3: Define fixtures ───────────────────────────────────────────────────

;; Two fixtures. With :channel-stride 2 each fixture occupies 28 DMX slots.
;; Fixture 1 at address 1 (pan → ch 2). Fixture 2 at address 29 (pan → ch 30).
;; Note: if your second fixture's DIP switch is set differently, update 29 to match.
(def left  (f/patch f/uking-14ch  1 "Stage Left"))
(def right (f/patch f/uking-14ch 29 "Stage Right"))

;; Initialize both: dimmer on, slow motor speed
(doseq [fixture [left right]]
  (dmx/dimmer! fixture 200)
  (dmx/speed!  fixture 200))   ; slow, smooth movement

;; ── STEP 4: Build a scene ─────────────────────────────────────────────────────

;; Set initial colors and positions
(dmx/color!    left  :amber)
(dmx/color!    right :blue)
(dmx/position! left   80 140)
(dmx/position! right 175 140)

;; ── STEP 5: Add oscillators for movement ─────────────────────────────────────

;; Left fixture: slow pan sway
(dmx/oscillate! {:id :left-pan  :channel (f/channel-of left :pan)
                 :type :triangle :min 50 :max 110 :frequency 0.06})

;; Right fixture: mirrored pan sway (opposite phase)
(dmx/oscillate! {:id :right-pan :channel (f/channel-of right :pan)
                 :type :triangle :min 145 :max 205 :frequency 0.06
                 :phase Math/PI})

;; Both fixtures: gentle dimmer breathe, slightly offset
(dmx/oscillate! {:id :left-dim  :channel (f/channel-of left :dimmer)
                 :type :sine :min 120 :max 220 :frequency 0.2})
(dmx/oscillate! {:id :right-dim :channel (f/channel-of right :dimmer)
                 :type :sine :min 120 :max 220 :frequency 0.2
                 :phase (/ Math/PI 3)})   ; 60° offset

;; ── STEP 6: Live REPL control while oscillators run ─────────────────────────

;; The REPL is free while oscillators animate in the background.
;; Try these one at a time:

;; Change color on the fly
(dmx/color! left :cyan)

;; Add a color oscillator on top
(dmx/oscillate! {:id :right-hue :channel (f/channel-of right :red)
                 :type :saw :min 0 :max 255 :frequency 0.15})

;; Kill just the pan oscillators — dimmer breathe continues
(dmx/stop-oscillate! :left-pan)
(dmx/stop-oscillate! :right-pan)

;; Manual position while breathe still runs
(dmx/position! left  60 160)
(dmx/position! right 195 160)

;; ── STEP 7: Named scenes ──────────────────────────────────────────────────────

;; Wrap scenes in functions for easy recall during a performance
(defn scene-sunset! []
  (dmx/stop-all-oscillators!)
  (dmx/color!    left  [255  80   0])
  (dmx/color!    right [255  40   0])
  (dmx/position! left   80 150)
  (dmx/position! right 175 150)
  (dmx/dimmer!   left  200)
  (dmx/dimmer!   right 200))

(defn scene-deep-blue! []
  (dmx/stop-all-oscillators!)
  (dmx/color!    left  [0 60 255])
  (dmx/color!    right [0 30 200])
  (dmx/position! left  128 90)
  (dmx/position! right 128 90)
  (dmx/oscillate! {:id :b-breathe-l :channel (f/channel-of left  :dimmer)
                   :type :sine :min 80 :max 200 :frequency 0.15})
  (dmx/oscillate! {:id :b-breathe-r :channel (f/channel-of right :dimmer)
                   :type :sine :min 80 :max 200 :frequency 0.15 :phase Math/PI}))

(defn scene-strobe! []
  (dmx/stop-all-oscillators!)
  (doseq [fix [left right]]
    (dmx/color! fix :white))
  (dmx/oscillate! {:id :strobe-l :channel (f/channel-of left  :dimmer)
                   :type :square :min 0 :max 255 :frequency 4.0})
  (dmx/oscillate! {:id :strobe-r :channel (f/channel-of right :dimmer)
                   :type :square :min 0 :max 255 :frequency 4.0
                   :phase Math/PI}))  ; alternate flashing

;; Call scenes by name:
(scene-sunset!)
(Thread/sleep 5000)
(scene-deep-blue!)
(Thread/sleep 5000)
(scene-strobe!)
(Thread/sleep 3000)
(scene-sunset!)

;; ── STEP 8: Hue sweep across both fixtures ───────────────────────────────────

(dmx/stop-all-oscillators!)
(doseq [h (range 0 721 6)]   ; two full rotations
  (let [h1 (mod h 360)
        h2 (mod (+ h 180) 360)]   ; complementary hue
    (dmx/color! left  (c/hsv->rgb h1 1.0 1.0))
    (dmx/color! right (c/hsv->rgb h2 1.0 1.0))
    (Thread/sleep 60)))

;; ── STEP 9: Shutdown ──────────────────────────────────────────────────────────

(u/blackout!)
(dmx/stop!)
;; => :stopped
