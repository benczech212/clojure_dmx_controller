# Clojure DMX Controller

An educational Clojure project for controlling DMX lighting fixtures via the
**ENTTEC Open DMX USB** interface. Designed to be explored live in a REPL — evaluate
one form at a time and watch your lights respond in real time.

## Features

- **Real-time DMX output** — 40Hz serial loop via jSerialComm (no native drivers needed)
- **RGB / HSV color control** with named presets and color mixing
- **Pan/Tilt positioning** with 16-bit precision (65,536 steps)
- **Oscillators** — animate channels continuously with `:sine` `:square` `:saw` `:triangle` waves
- **Terminal display** — live channel view in the REPL terminal (4Hz refresh)
- **Browser UI** — live color swatch + pan/tilt indicator at `http://localhost:3000`
- **REPL-first design** — every example is a loadable script, evaluated form by form

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 11+ | `java -version` to check |
| Clojure CLI (`clj`) | [install guide](https://clojure.org/guides/install_clojure) |
| FTDI VCP driver | Usually auto-installed on Windows with the ENTTEC device |

## Quick Start

```bash
# Start an nREPL server (connect from VS Code/Calva, Emacs/CIDER, IntelliJ/Cursive)
clj -M:dev
```

Then in your REPL:

```clojure
(require '[clojure-dmx.core :as dmx])

;; 1. Find your COM port
(dmx/list-ports!)        ; => ["COM3"]

;; 2. Start everything: serial + terminal display + oscillator engine + web UI
(dmx/start! "COM3")
;; Open http://localhost:3000 in your browser

;; 3. Define a fixture at DMX address 1
(def light (dmx/patch dmx/uking-14ch 1 "My Light"))

;; 4. Control it
(dmx/dimmer! light 200)
(dmx/color!  light :warm)
(dmx/position! light 128 90)

;; 5. Add an oscillator
(dmx/oscillate! {:id :breathe :channel 6 :type :sine
                 :min 80 :max 220 :frequency 0.3})

;; 6. Stop
(dmx/stop!)
```

## Project Structure

```
deps.edn                     ← dependencies (clj run with clj -M:dev)
src/
  clojure_dmx/
    core.clj                 ← top-level convenience API (start! stop! color! ...)
    universe.clj             ← DMX universe atom: 512-channel state
    serial.clj               ← ENTTEC Open DMX USB serial output (jSerialComm)
    fixture.clj              ← fixture definitions + high-level control fns
    color.clj                ← HSV↔RGB, named colors, color mixing
    oscillator.clj           ← time-based waveform engine (core.async)
    display.clj              ← terminal live display (4Hz polling thread)
    server.clj               ← browser UI (Ring/Jetty + Hiccup, port 3000)
examples/
  01_hello_dmx.clj           ← connect, set channels, blackout
  02_rgb.clj                 ← color keywords, HSV, color mixing
  03_position.clj            ← pan/tilt, 16-bit precision, sweep patterns
  04_oscillators.clj         ← waveform types, phase offsets, engine lifecycle
  05_combined.clj            ← full scene with multiple fixtures + live control
```

## Working Through the Examples

Each example in `examples/` is a plain Clojure script designed to be evaluated
**one form at a time** in your REPL. This is the Clojure development model —
you change one thing and immediately see the effect on your hardware.

```clojure
;; Load an example file (evaluates all forms top to bottom)
(load-file "examples/01_hello_dmx.clj")

;; Or evaluate individual forms by placing your cursor and pressing the
;; eval-form keybinding in your editor.
```

## Fixture: UKing Mini 7LED (14CH)

The library includes a definition for the UKing Mini 7LED moving head in 14-channel mode:

| Ch | Name | Range |
|---|---|---|
| 1 | Pan (coarse) | 0-255 |
| 2 | Pan Fine | 0-255 |
| 3 | Tilt (coarse) | 0-255 |
| 4 | Tilt Fine | 0-255 |
| 5 | Pan/Tilt Speed | 0=fast, 255=slow |
| 6 | Dimmer | 0=off, 255=full |
| 7 | Red | 0-255 |
| 8 | Green | 0-255 |
| 9 | Blue | 0-255 |
| 10 | White | 0-255 |
| 11-14 | Macro/Reset | — |

## Adding Your Own Fixture

Fixtures are plain Clojure maps. Add one to `fixture.clj` or define it inline:

```clojure
(def my-par
  {:name    "Generic RGB PAR"
   :channels 3
   :red     1
   :green   2
   :blue    3})

(def par-light (f/patch my-par 20 "FOH Par"))
(f/set-color! par-light :blue)
```

## Browser UI

Start the web server as part of `(dmx/start! ...)` or standalone:

```clojure
(require '[clojure-dmx.server :as srv])
(srv/start-server! 3000)
;; => [Server] Web UI running at http://localhost:3000
```

The page auto-refreshes every 150ms showing:
- **Color swatch** — live RGB from channels 7/8/9
- **Pan/Tilt dot** — position indicator from channels 1-4
- **Channel table** — all non-zero channels with value bars

## Oscillator Waveforms

```
:sine      ╭──╮     ╭──╮     smooth, organic
           ╯  ╰─────╯  ╰─────

:square    ┌──┐     ┌──┐     hard on/off (strobe, blink)
           ┘  └─────┘  └─────

:saw       ╱│  ╱│  ╱│        ramp up, instant reset
           ╱ │╱  │╱  │

:triangle  ╱╲   ╱╲   ╱╲      ramp up and down (pan sweep)
```

```clojure
;; Add an oscillator
(dmx/oscillate! {:id :my-osc :channel 7 :type :sine
                 :min 0 :max 255 :frequency 0.5})

;; Remove it
(dmx/stop-oscillate! :my-osc)

;; Remove all
(dmx/stop-all-oscillators!)
```

## Clojure Patterns Demonstrated

| Pattern | Where |
|---|---|
| `atom` for mutable state | `universe.clj`, `oscillator.clj`, `serial.clj` |
| Pure functions | `color.clj`, `oscillator.clj` (all waveform fns) |
| `future` for background threads | `serial.clj`, `display.clj` |
| `core.async go-loop` | `oscillator.clj` |
| Maps as domain objects | `fixture.clj` (fixtures as data) |
| `cond->` for conditional map building | `fixture.clj` (set-color!, set-position!) |
| REPL-driven development | all `examples/` files |
