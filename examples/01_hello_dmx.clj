;; ─────────────────────────────────────────────────────────────────────────────
;; 01 — Hello DMX
;; Your first connection to the ENTTEC Open DMX USB.
;;
;; HOW TO USE THIS FILE:
;;   Open this file in your editor and evaluate each form one at a time.
;;   In VS Code + Calva: place cursor inside a form and press Alt+Enter.
;;   In Emacs + CIDER:   press C-x C-e at the end of a form.
;;
;; WHAT YOU'LL LEARN:
;;   • How the DMX universe atom works
;;   • Setting individual channels directly with set-channel!
;;   • The actual DMX channel positions of the UKing 14CH (every-other pattern)
;;   • The dimmer channel's dual-function value range (important!)
;;   • The 40Hz output loop running in the background
;; ─────────────────────────────────────────────────────────────────────────────

(require '[clojure-dmx.serial   :as serial]
         '[clojure-dmx.universe :as u])

;; ── STEP 1: Check the device is reachable ─────────────────────────────────────

(serial/list-ports)
;; => ["ftdi://0403:6001"]  device found — ready to go
;; => []                    not found — check USB cable

;; ── STEP 2: Start the output loop ─────────────────────────────────────────────

;; This opens the FTDI device and starts a background future sending
;; 40 DMX frames/second. The REPL stays free while this runs.
(serial/start-output!)
;; => [DMX] Output started via FTDI USB (40Hz).

;; ── STEP 3: Look at the universe ──────────────────────────────────────────────

;; The universe is an atom holding a vector of 512 integers (0-255 each).
;; Deref it with @ to see the current state:
@u/universe
;; => [0 0 0 0 0 0 0 0 0 0 ...]  (all zeros = blackout)

(take 30 @u/universe)
;; => (0 0 0 0 0 0 0 0 0 0 ...)

;; ── STEP 4: UKing 14CH actual channel map ─────────────────────────────────────
;;
;; This fixture responds on every other DMX channel.
;; Spec channel N lives at universe channel N*2 from the start address.
;;
;;   DMX Ch 2   — Pan coarse     (0-255, ~540° range)
;;   DMX Ch 4   — Pan fine       (0-255, sub-step)
;;   DMX Ch 6   — Tilt coarse    (0-255)
;;   DMX Ch 8   — Tilt fine      (0-255, sub-step)
;;   DMX Ch 10  — P/T speed      (0=fast, 255=slow)
;;   DMX Ch 12  — Dimmer/Strobe  (DUAL FUNCTION — same channel):
;;                  0-7   = off
;;                  8-134 = dimmer intensity (8=dimmest, 134=full brightness)
;;                  135-239 = strobe (135=slowest, 239=fastest)
;;                  240-255 = auto programs (head moves on its own — avoid!)
;;   DMX Ch 14  — Red    (0-255)
;;   DMX Ch 16  — Green  (0-255)
;;   DMX Ch 18  — Blue   (0-255)
;;   DMX Ch 20  — White  (0-255)
;;   DMX Ch 22  — Color table macro
;;   DMX Ch 26  — Macro/Reset
;;
;; The high-level API (examples 02+) handles all this for you — use it in
;; normal code. Raw set-channel! is useful for learning and debugging only.

;; ── STEP 5: Set the dimmer ────────────────────────────────────────────────────

;; Dimmer is channel 12. Keep values within 8-134 to stay in dimmer mode.
;; Below 8 = off, 135+ = strobe/auto.

(u/set-channel! 12 255)   ; ~mid brightness 

;; ── STEP 6: Set the color channels ───────────────────────────────────────────

;; With the dimmer on, set R/G/B at channels 14, 16, 18:
;; (u/set-channel! 12 100)   ; dimmer on first
(u/set-channel! 14 255)   ; full red
(u/set-channel! 16 0)     ; green off
(u/set-channel! 18 0)     ; blue off
;; Fixture should now show red.

(u/set-channel! 14 0)
(u/set-channel! 18 255)   ; blue

(u/set-channel! 14 255)
(u/set-channel! 18 255)
(u/set-channel! 16 0)     ; magenta (red + blue)

;; ── STEP 7: Set multiple channels atomically ──────────────────────────────────

;; set-channels! applies all changes in a single swap! — no partial flicker.
(u/set-channels! {12 100   ; dimmer at ~75% brightness
                  14 255   ; red full
                  16 0     ; green off
                  18 128}) ; blue at half → purple

;; ── STEP 8: Check a channel value ────────────────────────────────────────────

(u/get-channel 12)   ; => 100  (dimmer)
(u/get-channel 14)   ; => 255  (red)

;; ── STEP 9: Blackout and stop ────────────────────────────────────────────────

(u/blackout!)
;; All 512 channels → 0. Fixture goes dark.

(serial/stop-output!)
;; => [DMX] Output stopped.

;; ── TIP: use the high-level API ──────────────────────────────────────────────
;; The fixture functions in example 02 onwards handle channel addressing and
;; dimmer scaling automatically — (dimmer! light 200) maps to the right range
;; at the right DMX channel regardless of start address or channel stride.
;; Raw set-channel! is useful for learning and debugging, but the high-level
;; API is safer and more readable for normal use.
