(ns clojure-dmx.server
  (:require [ring.adapter.jetty      :as jetty]
            [ring.middleware.params  :as rmp]
            [hiccup.core             :refer [html]]
            [cheshire.core           :as json]
            [clojure-dmx.universe    :as u]
            [clojure-dmx.serial      :as serial]))

;; Browser UI served at http://localhost:3000
;; Routes:
;;   GET /              — full HTML page with live display
;;   GET /api/universe  — JSON snapshot: {:universe [...512 ints...] :connected bool}
;;
;; The page polls /api/universe every 150ms via JavaScript fetch() and updates:
;;   • Color swatch (RGB of channels 14/16/18 — 0-indexed 13/15/17)
;;   • Pan/Tilt indicator (SVG dot from channels 2/4 coarse/fine, 6/8 tilt)
;;   • Active channel table (all non-zero channels with bar charts)
;;
;; Teaching note: this is a complete Ring web server in ~100 lines.
;; Ring is just a function: handler(request) -> {:status :headers :body}

(def ^:private server-atom (atom nil))

;; ── CSS ──────────────────────────────────────────────────────────────────────

(def ^:private page-css
  "* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  background: #0d0d0d; color: #ccc;
  font-family: 'Courier New', monospace; padding: 20px;
}
h1 { color: #fff; font-size: 1.3em; margin-bottom: 6px; }
h2 { font-size: 0.75em; text-transform: uppercase; letter-spacing: 2px;
     color: #555; margin-bottom: 12px; }
#status { font-size: 0.8em; margin-bottom: 20px; }
.grid { display: flex; gap: 16px; flex-wrap: wrap; align-items: flex-start; }
.card {
  background: #181818; border: 1px solid #2a2a2a; border-radius: 6px; padding: 16px;
}
#swatch {
  width: 180px; height: 180px; border-radius: 4px;
  border: 1px solid #2a2a2a; background: #000; transition: background 0.1s;
}
#rgb-vals { margin-top: 8px; font-size: 0.8em; color: #777; }
#pt-vals  { margin-top: 8px; font-size: 0.8em; color: #777; }
.card.wide { min-width: 380px; flex: 1; max-height: 600px; overflow-y: auto; }
table { width: 100%; border-collapse: collapse; font-size: 0.8em; }
th { text-align: left; padding: 4px 8px; color: #555;
     border-bottom: 1px solid #2a2a2a; position: sticky; top: 0; background: #181818; }
td { padding: 3px 8px; vertical-align: middle; }
tr:hover { background: #202020; }
td:first-child { color: #888; width: 50px; }
td:nth-child(2) { text-align: right; width: 40px; }
.bar-cell { width: 200px; }
.bar { height: 10px; background: #2af; border-radius: 2px; min-width: 2px; max-width: 200px; }
.none-msg { color: #444; padding: 8px; }")

;; ── JavaScript ────────────────────────────────────────────────────────────────
;; Uses ES5 syntax (no template literals) for maximum compatibility.
;; Channel indices are 0-based (u[0] = DMX channel 1).

(def ^:private app-js
  "function updateUI(data) {
  var u = data.universe;
  var connected = data.connected;

  // Status indicator
  var statusEl = document.getElementById('status');
  if (connected) {
    statusEl.innerHTML = '<span style=\"color:#4f4\">&#9679; Hardware connected</span>';
  } else {
    statusEl.innerHTML = '<span style=\"color:#fa0\">&#9675; Hardware disconnected</span>';
  }

  // Color swatch — DMX ch 14,16,18 = R,G,B (0-indexed 13,15,17)
  var r = u[13], g = u[15], b = u[17];
  document.getElementById('swatch').style.background =
    'rgb(' + r + ',' + g + ',' + b + ')';
  document.getElementById('rgb-vals').textContent =
    'R: ' + r + '   G: ' + g + '   B: ' + b;

  // Pan/Tilt dot — DMX ch 2,4 = pan coarse/fine; ch 6,8 = tilt coarse/fine
  var pan16  = u[1] * 256 + u[3];
  var tilt16 = u[5] * 256 + u[7];
  var dotX   = Math.round(pan16  / 65535 * 176) + 2;
  var dotY   = Math.round(tilt16 / 65535 * 176) + 2;
  document.getElementById('pt-dot').setAttribute('cx', dotX);
  document.getElementById('pt-dot').setAttribute('cy', dotY);
  document.getElementById('pt-vals').textContent =
    'Pan: '  + (pan16  / 65535 * 100).toFixed(1) + '%' +
    '   Tilt: ' + (tilt16 / 65535 * 100).toFixed(1) + '%';

  // Active channel table
  var rows = '';
  for (var i = 0; i < u.length; i++) {
    if (u[i] > 0) {
      var pct = Math.round(u[i] / 255 * 100);
      rows += '<tr>' +
        '<td>' + (i + 1) + '</td>' +
        '<td>' + u[i] + '</td>' +
        '<td class=\"bar-cell\"><div class=\"bar\" style=\"width:' + pct + '%\"></div></td>' +
        '</tr>';
    }
  }
  document.getElementById('ch-body').innerHTML =
    rows || '<tr><td colspan=\"3\" class=\"none-msg\">All channels at 0 &mdash; blackout</td></tr>';
}

// Poll the API every 150ms and update the page
setInterval(function() {
  fetch('/api/universe')
    .then(function(r) { return r.json(); })
    .then(updateUI)
    .catch(function() {
      document.getElementById('status').innerHTML =
        '<span style=\"color:#f44\">&#10007; Server unreachable</span>';
    });
}, 150);")

;; ── HTML page ────────────────────────────────────────────────────────────────
;; CSS and JS are injected as raw strings (not via Hiccup) to avoid HTML
;; entity escaping of quotes and other special characters inside the blocks.

(defn- page-html []
  (str
    "<!DOCTYPE html><html lang=\"en\"><head>"
    "<meta charset=\"utf-8\">"
    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    "<title>Clojure DMX Controller</title>"
    "<style>" page-css "</style>"
    "</head><body>"
    (html
      [:h1 "Clojure DMX Controller"]
      [:div#status "Connecting..."]
      [:div.grid
       ;; Color swatch
       [:div.card
        [:h2 "Color"]
        [:div#swatch]
        [:div#rgb-vals "R: 0   G: 0   B: 0"]]
       ;; Pan/Tilt SVG indicator
       [:div.card
        [:h2 "Position"]
        [:svg {:id "pt-svg" :width "180" :height "180"
               :xmlns "http://www.w3.org/2000/svg"
               :style "display:block;border-radius:4px"}
         [:rect {:width "180" :height "180" :fill "#0a0a0a" :rx "3"}]
         [:line {:x1 "90" :y1 "0" :x2 "90" :y2 "180" :stroke "#1f1f1f" :stroke-width "1"}]
         [:line {:x1 "0" :y1 "90" :x2 "180" :y2 "90" :stroke "#1f1f1f" :stroke-width "1"}]
         [:rect {:x "1" :y "1" :width "178" :height "178" :fill "none"
                 :stroke "#2a2a2a" :stroke-width "1" :rx "2"}]
         [:circle {:id "pt-dot" :cx "90" :cy "90" :r "7"
                   :fill "#0f8" :opacity "0.9"}]
         [:text {:x "4"  :y "174" :fill "#333" :font-size "9"} "PAN →"]
         [:text {:x "4"  :y "10"  :fill "#333" :font-size "9"} "↑ TILT"]]
        [:div#pt-vals "Pan: 0%   Tilt: 0%"]]
       ;; Channel table
       [:div.card.wide
        [:h2 "Active Channels"]
        [:table
         [:thead [:tr [:th "Ch"] [:th "Val"] [:th ""]]]
         [:tbody#ch-body
          [:tr [:td {:colspan "3" :class "none-msg"}
                "All channels at 0 — blackout"]]]]]])
    "<script>" app-js "</script>"
    "</body></html>"))

;; ── Ring handlers ─────────────────────────────────────────────────────────────

(defn- index-handler [_req]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (page-html)})

(defn- universe-handler [_req]
  {:status  200
   :headers {"Content-Type"                 "application/json"
             "Cache-Control"                "no-store"
             "Access-Control-Allow-Origin"  "*"}
   :body    (json/generate-string
             {:universe  @u/universe
              :connected (serial/connected?)})})

(defn handler [req]
  (case (:uri req)
    "/"             (index-handler req)
    "/api/universe" (universe-handler req)
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not found"}))

(def app (-> #'handler rmp/wrap-params))

;; ── Server lifecycle ──────────────────────────────────────────────────────────

(defn start-server!
  "Start the Ring/Jetty web server. Defaults to port 3000.
   Open http://localhost:3000 in your browser after calling this."
  ([]      (start-server! 3000))
  ([port]
   (if @server-atom
     (println "[Server] Already running.")
     (do
       (reset! server-atom
               (jetty/run-jetty app {:port port :join? false}))
       (println (str "[Server] Web UI running at http://localhost:" port))))))

(defn stop-server!
  "Stop the web server."
  []
  (when-let [s @server-atom]
    (.stop s)
    (reset! server-atom nil)
    (println "[Server] Stopped.")))
