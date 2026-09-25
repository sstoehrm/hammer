(ns hammer.test-env
  "Required first by every test namespace that needs browser globals."
  (:require ["jsdom" :refer [JSDOM]]))

(defonce env
  (let [d (JSDOM. "<!DOCTYPE html><html><body></body></html>" #js {:url "http://localhost/"})
        w (.-window d)]
    (set! js/globalThis.window w)
    (set! js/globalThis.document (.-document w))
    (set! js/globalThis.localStorage (.-localStorage w))
    (set! js/globalThis.location (.-location w))
    (set! js/globalThis.requestAnimationFrame (fn [f] (js/setTimeout f 16)))
    d))
