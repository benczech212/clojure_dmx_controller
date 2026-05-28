(ns clojure-dmx.universe)

;; The universe is an atom holding a vector of 512 integers (one per DMX channel).
;; Channel values are 0-255. This is the single source of truth for all output.
;;
;; Teaching note: atoms hold a value and allow safe concurrent updates.
;; (swap! atom f) applies f to the current value and stores the result.
;; (reset! atom v) replaces the current value with v directly.

(def universe (atom (vec (repeat 512 0))))

(defn- clamp
  "Clamp v to the valid DMX range 0-255."
  [v]
  (max 0 (min 255 (int v))))

(defn set-channel!
  "Set DMX channel ch (1-512) to value v (0-255).
   Channel 1 maps to index 0 in the universe vector."
  [ch v]
  (swap! universe assoc (dec ch) (clamp v)))

(defn set-channels!
  "Set multiple channels at once from a map of {channel value} pairs.
   All updates are applied atomically in a single swap!.
   Example: (set-channels! {7 255, 8 0, 9 128})"
  [ch-map]
  (swap! universe
         (fn [u]
           (reduce (fn [acc [ch v]]
                     (assoc acc (dec ch) (clamp v)))
                   u
                   ch-map))))

(defn get-channel
  "Return the current value of channel ch (1-512)."
  [ch]
  (get @universe (dec ch)))

(defn blackout!
  "Set all 512 channels to 0."
  []
  (reset! universe (vec (repeat 512 0))))

(defn snapshot
  "Return the current universe vector (a snapshot, not live)."
  []
  @universe)
