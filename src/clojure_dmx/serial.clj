(ns clojure-dmx.serial
  (:require [clojure-dmx.universe :as u])
  (:import [org.usb4java LibUsb Context DeviceHandle]
           [java.nio ByteBuffer IntBuffer]))

;; DMX512 output via direct FTDI USB access (usb4java / libusb).
;;
;; This approach matches how the Python pyftdi project works:
;;   - No VCP (COM port) driver needed
;;   - Requires WinUSB or libusbK driver on the FTDI device
;;   - If the Python project already works, this will work too
;;
;; FTDI FT232R USB identifiers
;;   VID: 0x0403  (FTDI Ltd)
;;   PID: 0x6001  (FT232R)
;;
;; FTDI USB control transfer protocol:
;;   bmRequestType = 0x40  (vendor, device, host→device)
;;   bRequest      = command byte (see SIO-* constants below)
;;   wValue        = command parameter
;;   wIndex        = FTDI interface index (1 for channel A on FT232R)
;;
;; DMX512 frame structure:
;;   1. Assert BREAK (≥92µs, we use 2ms) — via SIO_SET_DATA with bit 14 set
;;   2. Clear BREAK (mark state)         — via SIO_SET_DATA with bit 14 clear
;;   3. Bulk-write [0x00, ch1..ch512]   — to endpoint 0x02
;;
;; Baud rate 250,000:
;;   FT232R base clock = 3,000,000 Hz
;;   divisor = 3,000,000 / 250,000 = 12  (exact, no fractional needed)
;;
;; Data format 8N2 (8 data bits, no parity, 2 stop bits):
;;   wValue = data_bits | (stop_bits << 10) = 8 | (2 << 10) = 0x0808

(def ^:private FTDI-VID (short 0x0403))
(def ^:private FTDI-PID (short 0x6001))

(def ^:private CTRL-OUT     (byte 0x40))   ; bmRequestType: vendor, device, H→D
(def ^:private SIO-RESET    (byte 0x00))   ; reset chip
(def ^:private SIO-SET-BAUD (byte 0x03))   ; set baud rate divisor
(def ^:private SIO-SET-DATA (byte 0x04))   ; set line encoding + break control

;; wIndex = 1 for interface A (FTDI convention for single-channel FT232R)
(def ^:private IFACE-A       (short 0x0001))
;; For the baud rate command, wIndex also encodes high divisor bits:
;; wIndex = (high_bits_of_divisor & 0xFF) | (interface_index << 8)
;; For divisor 12 (fits in 14 bits): high_bits = 0, so wIndex = (1 << 8) = 0x0100
(def ^:private IFACE-A-BAUD  (short 0x0100))

(def ^:private BAUD-250K  (short 12))    ; divisor for 250,000 baud
(def ^:private DATA-8N2   (short 0x0808)); 8 data bits | (2 stop bits << 10)
(def ^:private BREAK-ON   (short 0x4808)); DATA-8N2 | (1 << 14) — asserts break
(def ^:private BREAK-OFF  DATA-8N2)     ; clears break, back to normal

(def ^:private BULK-OUT (byte 0x02))    ; FT232R bulk-out endpoint

(def ^:private ctx-atom    (atom nil))
(def ^:private handle-atom (atom nil))
(def ^:private running?    (atom false))
(def ^:private thread-atom (atom nil))

;; Pre-allocated buffers — avoids per-frame off-heap allocation at 40Hz
(def ^:private ^ByteBuffer pkt-buf (ByteBuffer/allocateDirect 513))
(def ^:private ^IntBuffer  xfr-buf (IntBuffer/allocate 1))

;; ── Low-level helpers ─────────────────────────────────────────────────────────

(defn- ctrl!
  "Send an FTDI vendor control transfer (no data phase)."
  [^DeviceHandle h cmd value index]
  (LibUsb/controlTransfer h CTRL-OUT cmd value index
                          (ByteBuffer/allocateDirect 0) (long 500)))

(defn- init-ftdi!
  "Reset the chip and configure it for DMX512 output."
  [^DeviceHandle h]
  (ctrl! h SIO-RESET    (short 0)    IFACE-A)      ; reset
  (Thread/sleep 10)
  (ctrl! h SIO-SET-BAUD BAUD-250K    IFACE-A-BAUD) ; 250,000 baud
  (ctrl! h SIO-SET-DATA DATA-8N2     IFACE-A))     ; 8N2 line encoding

;; ── Device open / close ───────────────────────────────────────────────────────

(defn- open-device! []
  (let [ctx (Context.)
        r   (LibUsb/init ctx)]
    (when (not= r LibUsb/SUCCESS)
      (throw (ex-info "libusb init failed" {:code r})))
    (reset! ctx-atom ctx)
    (let [h (LibUsb/openDeviceWithVidPid ctx FTDI-VID FTDI-PID)]
      (when (nil? h)
        (LibUsb/exit ctx)
        (reset! ctx-atom nil)
        (throw (ex-info (str "FTDI device not found (VID=0403 PID=6001). "
                             "Is it plugged in? Does it have the WinUSB/libusbK driver?")
                        {})))
      (LibUsb/claimInterface h (int 0))
      (reset! handle-atom h)
      (init-ftdi! h)
      h)))

(defn- close-device! []
  (when-let [h ^DeviceHandle @handle-atom]
    (try (LibUsb/releaseInterface h (int 0)) (catch Exception _))
    (LibUsb/close h)
    (reset! handle-atom nil))
  (when-let [ctx ^Context @ctx-atom]
    (LibUsb/exit ctx)
    (reset! ctx-atom nil)))

;; ── DMX frame output ──────────────────────────────────────────────────────────

(defn- send-dmx-frame!
  "Send one complete DMX512 frame:
   BREAK (2ms) → MARK → start-code (0x00) → 512 channel bytes."
  [^DeviceHandle h channel-vec]
  ;; Assert break: line held low for 2ms
  (ctrl! h SIO-SET-DATA BREAK-ON IFACE-A)
  (Thread/sleep 2)
  ;; Clear break: line returns to mark (idle high)
  (ctrl! h SIO-SET-DATA BREAK-OFF IFACE-A)
  ;; Build 513-byte packet in the pre-allocated direct buffer
  (.clear pkt-buf)
  (.put pkt-buf (byte 0))                             ; start code
  (dotimes [i 512]
    (.put pkt-buf (unchecked-byte (nth channel-vec i 0))))
  (.flip pkt-buf)
  ;; Bulk-write to endpoint 0x02
  (.clear xfr-buf)
  (LibUsb/bulkTransfer h BULK-OUT pkt-buf xfr-buf (long 500)))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn list-ports
  "Check whether the FTDI DMX device is reachable.
   Returns [\"ftdi\"] if the device is found, [] if not.

   Unlike the old COM-port approach, no port name is needed — the device
   is located automatically by its USB VID/PID (0403:6001)."
  []
  (let [ctx (Context.)]
    (if (not= (LibUsb/init ctx) LibUsb/SUCCESS)
      []
      (let [h (LibUsb/openDeviceWithVidPid ctx FTDI-VID FTDI-PID)]
        (when h (LibUsb/close h))
        (LibUsb/exit ctx)
        (if h ["ftdi://0403:6001"] [])))))

(defn start-output!
  "Open the FTDI device and start the 40Hz DMX output loop.
   The device is found automatically — no port name is required.
   An optional argument is accepted for API compatibility but is ignored.

   Requires the WinUSB or libusbK driver on the FTDI device.
   If the Python pyftdi project already works, this will work too."
  ([]          (start-output! nil))
  ([_ignored]
   (if @running?
     (do (println "[DMX] Already running.") true)
     (try
       (open-device!)
       (reset! running? true)
       (reset! thread-atom
               (future
                 (loop []
                   (when @running?
                     (let [t0 (System/currentTimeMillis)]
                       (try
                         (send-dmx-frame! @handle-atom @u/universe)
                         (catch Exception e
                           (println "[DMX] Frame error:" (.getMessage e))))
                       (let [elapsed   (- (System/currentTimeMillis) t0)
                             remaining (- 25 elapsed)]
                         (when (pos? remaining)
                           (Thread/sleep remaining))))
                     (recur)))))
       (println "[DMX] Output started via FTDI USB (40Hz).")
       true
       (catch Exception e
         (println "[DMX] Failed to start:" (.getMessage e))
         false)))))

(defn stop-output!
  "Stop the DMX output loop and release the USB device."
  []
  (reset! running? false)
  (when-let [t @thread-atom]
    (future-cancel t)
    (reset! thread-atom nil))
  (Thread/sleep 30)
  (close-device!)
  (println "[DMX] Output stopped."))

(defn connected?
  "Return true if the FTDI device is open and the output loop is running."
  []
  (boolean (and @running? (some? @handle-atom))))
