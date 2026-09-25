(ns cljs-ui.test-env
  "Required first by every test namespace that needs browser globals.")

(defonce raf
  (set! js/globalThis.requestAnimationFrame (fn [f] (js/setTimeout f 16))))
