(ns hammer.canvas-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas :as fake]
            [hammer.core :as core :refer [defc]]
            [hammer.canvas :as cv :refer [defdraw]]
            ;; #19's macro-expansion probe below calls hammer.gl/defloop
            ;; fully-qualified; without this require, whether it's already
            ;; compiled depends on load order across test namespaces.
            [hammer.gl]
            [hammer.draw :as draw]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.test-util :refer [capture-errors]]
            [hammer.testing :as t])
  (:require-macros [hammer.macro-probe :refer [expand-error]]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (reset! fake/log []))
                     ;; the last test installs a counting rAF on the shared
                     ;; draw/clock and never uninstalls it; restore it here so
                     ;; it doesn't leak into a test namespace loaded after this one.
                     :after (fn [] (draw/set-raf! nil))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defn- div [] (js/document.createElement "div"))
(defn- fills [id] (filterv #(= :fillRect (first %)) (fake/ops id)))

(defdraw bars [color id]
  [n [:n]]
  {:size [100 50] :attrs {:id id}}
  (fn [ctx {:keys [h]}]
    (set! (.-fillStyle ctx) color)
    (.fillRect ctx 0 0 n h)))

(deftest draws-once-per-frame-and-only-on-change
  (reset! state/app-db {:n 1})
  (cv/mount! [bars "red" "b"] (div))
  (is (empty? @fake/log) "nothing before the frame")
  (t/frame! 16)
  (is (= [[:setTransform 1 0 0 1 0 0] [:fillRect 0 0 1 50]] (fake/ops "b")))
  (reset! fake/log [])
  (events/dispatch [::set :n 5])
  (events/dispatch [::set :n 7])
  (t/frame! 32)
  (is (= [[:fillRect 0 0 7 50]] (fills "b")) "two events, one draw, latest value")
  (reset! fake/log [])
  (t/frame! 48)
  (is (empty? @fake/log) "no change, no redraw"))

(deftest backing-store-follows-size-and-dpr
  (set! (.-devicePixelRatio js/globalThis) 2)
  (try
    (reset! state/app-db {:n 1})
    (let [host (div)]
      (cv/mount! [bars "red" "b"] host)
      (t/frame! 16)
      (let [c (.-firstChild host)]
        (is (= "CANVAS" (.-tagName c)))
        (is (= [200 100] [(.-width c) (.-height c)]))
        (is (= ["100px" "50px"] [(.. c -style -width) (.. c -style -height)]))
        (is (= [:setTransform 2 0 0 2 0 0] (first (fake/ops "b"))))))
    (finally (js-delete js/globalThis "devicePixelRatio"))))

(deftest two-instances-are-independent
  (reset! state/app-db {:n 3})
  (let [a (div) b (div)]
    (cv/mount! [bars "red" "a"] a)
    (cv/mount! [bars "blue" "b2"] b)
    (t/frame! 16)
    (is (= [[:fillRect 0 0 3 50]] (fills "a") (fills "b2")))
    (is (= "red" (.-fillStyle (.getContext (.-firstChild a) "2d"))))
    (is (= "blue" (.-fillStyle (.getContext (.-firstChild b) "2d"))))))

(deftest unmount-before-frame-draws-nothing
  (reset! state/app-db {:n 1})
  (let [host (div)]
    (cv/mount! [bars "red" "u"] host)
    (t/frame! 16)
    (reset! fake/log [])
    (events/dispatch [::set :n 9])
    (t/flush!)
    (t/reset-app!)
    (t/frame! 32)
    (is (empty? @fake/log))))

(def calls (atom []))

(defdraw boom [] [n [:n]] {:size [10 10] :attrs {:id "boom"}}
  (fn [_ _] (swap! calls conj n) (when (= n 2) (throw (js/Error. "boom")))))

(deftest a-throwing-draw-is-logged-and-retried
  (reset! state/app-db {:n 1})
  (reset! calls [])
  (let [logs (capture-errors
              (fn [_]
                (cv/mount! [boom] (div))
                (t/frame! 16)
                (events/dispatch [::set :n 2]) (t/frame! 32)
                (events/dispatch [::set :n 3]) (t/frame! 48)))]
    (is (= [1 2 3] @calls))
    (is (= 1 (count logs)))))

(def res-log (atom []))

(defdraw with-res [] [n [:n]]
  {:size [10 10]
   :init (fn [_ctx {:keys [w]}] (swap! res-log conj [:init w]) {:r n})
   :dispose (fn [r] (swap! res-log conj [:dispose r]))}
  (fn [_ctx _info res] (swap! res-log conj [:draw res n])))

(deftest init-runs-once-dispose-on-remount
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [with-res] c)
    (t/frame! 16)
    (events/dispatch [::set :n 2]) (t/frame! 32)
    (is (= [[:init 10] [:draw {:r 1} 1] [:draw {:r 1} 2]] @res-log))
    (reset! res-log [])
    (cv/mount! [with-res] c)                ; same canvas again (hot reload)
    (t/frame! 48)
    (is (= [[:dispose {:r 1}] [:init 10] [:draw {:r 2} 2]] @res-log))))

(defdraw dot [id] [on? (cv/is? [:sel] id)] {:size [10 10] :attrs {:id (str "d" id)}}
  (fn [ctx _] (set! (.-fillStyle ctx) (if on? "red" "gray")) (.fillRect ctx 0 0 10 10)))

(defc dots [] [ids [:ids]] [:div (for [id ids] ^{:key id} [dot id])])

(deftest embedded-in-defc-keyed-and-is?
  (reset! state/app-db {:ids [1 2 3] :sel nil})
  (let [host (div)]
    (core/mount! [dots] host)
    (t/frame! 16)
    (is (= 3 (.-length (.querySelectorAll host "canvas"))))
    (reset! fake/log [])
    (events/dispatch [::set :sel 2])
    (t/frame! 32)
    (is (= #{"d2"} (set (map second @fake/log))) "only the dot whose is? flipped redraws")
    (let [[c1 _ c3] (js/Array.from (.querySelectorAll host "canvas"))]
      (events/dispatch [::set :ids [3 1]])
      (t/frame! 48)
      (is (= [c3 c1] (vec (js/Array.from (.querySelectorAll host "canvas"))))))))

;; ---- fix round 1: a failed create must not leak a subscription or a state

(def ghost
  "Built directly via hammer.draw/component (bypassing defdraw) so its :kind
  is never registered: create-host's backend lookup always throws."
  (draw/component "ghost" 0
                   [{:kind :path :deps [] :f (fn [] [:ghost])}]
                   [0]
                   (fn [_g] (cljs.core/array {} (fn [_ _] nil)))
                   :no-such-backend false))

(deftest mount-with-unregistered-backend-throws-and-unsubscribes
  (reset! state/app-db {:ghost 1})
  (is (thrown? js/Error (cv/mount! [ghost] (div)))
      "no backend registered for :no-such-backend")
  (let [logs (capture-errors (fn [_] (events/dispatch-sync [::set :ghost 2])))]
    (is (zero? (count logs)) "the failed instance's path subscription must not have leaked")))

(draw/register-backend!
 :boom-backend
 (draw/Backend. (fn [_st _render] (throw (js/Error. "setup boom")))
                (fn [_st] nil)
                (fn [_st] nil)
                (fn [_st] nil)))

(def boom-comp
  "A defloop-shaped component (loop? true) on a backend whose setup! always
  throws, to check create-host unwinds all/queued/loops before rethrowing."
  (draw/component "boom-comp" 0
                   [{:kind :path :deps [] :f (fn [] [:n])}]
                   [0]
                   (fn [_n] (cljs.core/array {} (fn [_ _] nil)))
                   :boom-backend true))

(deftest mount-with-throwing-setup-cleans-up
  (reset! state/app-db {:n 1})
  (is (thrown? js/Error (cv/mount! [boom-comp] (div))))
  (is (empty? (draw/states :boom-backend)) "no orphaned state after setup! throws")
  (reset! fake/log [])
  (t/frame! 16)
  (is (empty? @fake/log) "nothing left queued or looping to draw")
  ;; boom-comp is loop? true with default :run? true, so a leaked State left
  ;; in the loops set would keep frame! requesting another frame forever.
  (let [asked (atom 0)]
    (draw/set-raf! (fn [_] (swap! asked inc)))
    (draw/frame! 32)
    (is (zero? @asked) "no orphaned loop keeps requesting frames")))

;; ---- fix round 1: dropped :style keys must be cleared, not left stale

(defdraw styled [] [mode [:mode]]
  {:size [10 10]
   :attrs (cond-> {:id "styled"}
            (= mode :full) (assoc :style {:opacity "1" :color "red"})
            (= mode :partial) (assoc :style {:opacity "0.5"}))}
  ;; :none has no :style key at all -- exercises the "removed entirely" path,
  ;; distinct from :partial's "still present, fewer keys" path.
  (fn [_ctx _info]))

(deftest style-attrs-follow-binding-and-clear
  (reset! state/app-db {:mode :full})
  (let [host (div)]
    (cv/mount! [styled] host)
    (t/frame! 16)
    (let [c (.-firstChild host)]
      (is (= "1" (.. c -style -opacity)))
      (is (= "red" (.. c -style -color)))
      (events/dispatch [::set :mode :partial]) (t/frame! 32)
      (is (= "0.5" (.. c -style -opacity)))
      (is (= "" (.. c -style -color)) "a style key dropped from the map is removed, not left stale")
      (events/dispatch [::set :mode :none]) (t/frame! 48)
      (is (= "" (.. c -style -opacity)) "removing :style entirely clears every property it had set")
      (is (= "" (.. c -style -color))))))

;; ---- fix round 1: switching auto size -> :size -> auto restores the box

(defdraw resizy [] [mode [:mode]]
  {:size (when (= mode :fixed) [40 20]) :attrs {:id "resizy"}}
  (fn [_ctx _info]))

(deftest auto-size-restored-after-fixed-size-cleared
  ;; auto -> fixed -> auto: the observer created for the first auto pass
  ;; must not stop apply-size! from restoring the auto CSS on the way back.
  (reset! state/app-db {:mode :auto})
  (let [host (div)]
    (cv/mount! [resizy] host)
    (let [c (.-firstChild host)
          st (first (filter #(identical? c (.-canvas %)) (draw/states :canvas)))]
      (is (= ["100%" "100%"] [(.. c -style -width) (.. c -style -height)]) "starts auto-sized")
      (events/dispatch [::set :mode :fixed])
      (t/flush!)
      (is (= ["40px" "20px"] [(.. c -style -width) (.. c -style -height)]))
      (is (= [40 20] [(.-w st) (.-h st)]))
      (events/dispatch [::set :mode :auto])
      (t/flush!)
      (is (= ["100%" "100%"] [(.. c -style -width) (.. c -style -height)])
          "auto-size CSS restored when switching back from a fixed size")
      (is (= [(.-clientWidth c) (.-clientHeight c)] [(.-w st) (.-h st)])
          "w/h re-measured from the live box, not left at the stale fixed value"))))

;; ---- fix round 2: final whole-branch review

(deftest draw-def-rejects-a-bare-map-as-draw-fn
  (is (= "defdraw: missing draw-fn after opts"
         (expand-error (hammer.canvas/defdraw bad [] [] {:size [1 1]})))
      "a lone map literal must not be silently accepted as the draw fn")
  (is (nil? (expand-error (hammer.canvas/defdraw ok [] [] (fn [_ _])))))
  (is (nil? (expand-error (hammer.canvas/defdraw ok2 [] [] {:size [1 1]} (fn [_ _]))))))

(defdraw bad-size [] [] {:size 5} (fn [_ _]))

(deftest throwing-opts-during-mount-cleans-up-draw-state
  (reset! state/app-db {})
  (is (empty? (draw/states :canvas)) "sanity: nothing left over from an earlier test")
  (is (thrown? js/Error (cv/mount! [bad-size] (div)))
      "rerender! throws while destructuring the bad :size value")
  (is (empty? (draw/states :canvas))
      "a throwing rerender! inside create-host must still unwind! the state"))

(defdraw watched [] [] {:size [10 10] :on-unsupported (fn [_])}
  (fn [_ _]))

(deftest on-unsupported-is-not-registered-as-a-dom-listener
  (reset! state/app-db {})
  (let [host (div)]
    (cv/mount! [watched] host)
    (t/frame! 16)
    (let [c (.-firstChild host)
          st (first (filter #(identical? c (.-canvas %)) (draw/states :canvas)))]
      (is (zero? (.-size (.-listeners st)))
          ":on-unsupported is gl-only bookkeeping, not a DOM event to listen for"))))

(deftest null-2d-context-is-logged-once
  (reset! state/app-db {})
  (let [c (js/document.createElement "canvas")]
    (set! (.-getContext c) (fn [_] nil)) ; simulates a canvas already used for another context type
    (let [logs (capture-errors
                (fn [logs-atom]
                  (cv/mount! [bars "red" "null2d"] c)
                  (is (= 1 (count @logs-atom)) "a null 2d context is logged once at setup")
                  (t/frame! 16)))]
      (is (= 1 (count logs)) "no ctx: draw-arg returns nil, so the frame draws nothing and logs nothing more"))))

(defc show-wrap [] [show? [:show?]]
  [:div (when show? [with-res])])

(deftest dispose-runs-when-a-draw-component-unmounts-from-inside-a-defc
  (reset! state/app-db {:n 1 :show? true})
  (reset! res-log [])
  (let [host (div)]
    (core/mount! [show-wrap] host)
    (t/frame! 16)
    (is (some #(= :init (first %)) @res-log) "mounted and initialized")
    (reset! res-log [])
    (events/dispatch [::set :show? false])
    (t/flush!)
    (is (= [[:dispose {:r 1}]] @res-log)
        "unmounting the embedded draw component (show? -> false) runs :dispose")))

;; ---- #19: is? arity errors name the calling macro

(deftest is?-arity-error-names-the-draw-macro
  (is (= "defdraw: is? takes a path and a value"
         (expand-error (hammer.canvas/defdraw bad [] [a (is? [:x])] (fn [_ _])))))
  (is (= "defloop: is? takes a path and a value"
         (expand-error (hammer.gl/defloop bad [] [a (is? [:x])] (fn [_ _]))))))

;; ---- #11: a failed render or :init keeps the canvas's last content

(def opt-draws (atom []))

(defdraw opt-throws [] [x [:x]]
  {:size [10 10] :attrs {:id (if (= x :bad) (throw (js/Error. "bad opts")) "ot")}}
  (fn [_ _] (swap! opt-draws conj x)))

(deftest throwing-opts-keep-last-content-until-a-good-render
  (reset! state/app-db {:x 1})
  (reset! opt-draws [])
  (let [host (div)]
    (try
      (capture-errors
       (fn [errs]
         (cv/mount! [opt-throws] host)
         (t/frame! 16)
         (let [c (.-firstChild host)]
           (events/dispatch [::set :x :bad])
           (t/frame! 32)
           (is (= [1] @opt-draws) "no redraw with the previous draw fn after a failed render")
           (is (= 1 (count @errs)) "the failed render is logged")
           (set! (.-devicePixelRatio js/globalThis) 2)
           (.dispatchEvent js/window (new (.-Event js/window) "resize"))
           (t/frame! 48)
           (is (= [10 10] [(.-width c) (.-height c)]) "backing store untouched (not cleared) while the render is failed")
           (is (= [1] @opt-draws))
           (events/dispatch [::set :x 3])
           (t/frame! 64)
           (is (= [1 3] @opt-draws) "the next good render draws again")
           (is (= [20 20] [(.-width c) (.-height c)])))))
      (finally (js-delete js/globalThis "devicePixelRatio")))))

(def failed-init-draws (atom 0))

(defdraw init-fails [] [w [:w]]
  {:size [w 10] :init (fn [_ _] (throw (js/Error. "init boom")))}
  (fn [_ _ _] (swap! failed-init-draws inc)))

(deftest failed-init-leaves-the-backing-store-alone-on-resize
  (reset! state/app-db {:w 10})
  (reset! failed-init-draws 0)
  (let [c (js/document.createElement "canvas")]
    (capture-errors
     (fn [_]
       (cv/mount! [init-fails] c)
       (t/frame! 16)
       (is (= [10 10] [(.-width c) (.-height c)]))
       (events/dispatch [::set :w 30])
       (t/frame! 32)
       (is (= [10 10] [(.-width c) (.-height c)]) "nothing will draw, so the canvas is not resized (cleared)")
       (is (zero? @failed-init-draws))))))

;; ---- #11 ruling: a defdraw doesn't redraw after a failed render (see
;; throwing-opts-keep-last-content-until-a-good-render above), but a running
;; defloop keeps animating with the previous opts/f, and its clock (:n) keeps
;; advancing -- "a loop keeps running".

(def loop-draws (atom []))

(cv/defloop loop-opt-throws [] [x [:x]]
  {:size [10 10] :attrs {:id (if (= x :bad) (throw (js/Error. "bad opts")) "lot")}}
  (fn [_ctx {:keys [n]}] (swap! loop-draws conj [x n])))

(deftest running-defloop-keeps-drawing-and-advances-its-clock-through-a-failed-render
  (reset! state/app-db {:x 1})
  (reset! loop-draws [])
  (let [host (div)]
    (capture-errors
     (fn [errs]
       (cv/mount! [loop-opt-throws] host)
       (t/frame! 16)
       (is (= [[1 1]] @loop-draws) "first frame draws with x=1, n=1")
       (events/dispatch [::set :x :bad])
       (t/frame! 32)
       (is (= 1 (count @errs)) "the failed render is logged once")
       (is (= [[1 1] [1 2]] @loop-draws)
           "still running: drew again with the previous f (x=1 captured), n advanced to 2")
       (t/frame! 48)
       (is (= [[1 1] [1 2] [1 3]] @loop-draws)
           "keeps animating every frame while broken, n keeps advancing")
       (events/dispatch [::set :x 5])
       (t/frame! 64)
       (is (= 1 (count @errs)) "no new failure logged for the good render")
       (is (= [[1 1] [1 2] [1 3] [5 4]] @loop-draws)
           "the next good render replaces f, and the clock kept advancing throughout")))))

;; ---- #13 / #14 / #15: standalone roots

(def teardowns (atom 0))

(draw/register-backend!
 :counting
 (draw/Backend. (fn [st _render] (.-canvas ^draw/State st))
                (fn [_st] nil)
                (fn [_st] nil)
                (fn [_st] (swap! teardowns inc))))

(def counted
  "A draw component on a backend that counts teardowns."
  (draw/component "counted" 0 [] [] (fn [] (cljs.core/array {:size [10 10]} (fn [_ _] nil)))
                  :counting false))

(deftest failed-replacement-mount-does-not-destroy-the-old-root-twice
  (reset! state/app-db {:n 1})
  (reset! teardowns 0)
  (let [el (div)]
    (cv/mount! [counted] el)
    (is (thrown? js/Error (cv/mount! [boom-comp] el)) "the replacement's setup! throws")
    (is (= 1 @teardowns) "the old root was destroyed by the replacement")
    (t/reset-app!)
    (is (= 1 @teardowns) "unmount-all! does not destroy it a second time")))

(deftest unmount-all-removes-created-canvases-and-keeps-adopted-ones
  (reset! state/app-db {:n 1})
  (let [host (div)
        wrap (div)
        c (js/document.createElement "canvas")]
    (.appendChild wrap c)
    (cv/mount! [bars "red" "made"] host)
    (cv/mount! [bars "red" "adopted"] c)
    (is (= 1 (.. host -childNodes -length)))
    (t/reset-app!)
    (is (zero? (.. host -childNodes -length)) "the canvas mount! created is removed from its container")
    (is (identical? wrap (.-parentNode c)) "an adopted canvas stays where it was")))

(defc label [] [x [:x]] [:p (str x)])

(def spins (atom 0))

(cv/defloop spin [] [] {:size [10 10]} (fn [_ _] (swap! spins inc)))

(deftest canvas-mount-replaces-a-dom-root-on-the-same-element
  (reset! state/app-db {:x 1 :n 1})
  (let [host (div)]
    (core/mount! [label] host)
    (cv/mount! [bars "red" "over-dom"] host)
    (is (= "CANVAS" (.. host -firstChild -tagName)))
    (t/reset-renders! label)
    (events/dispatch [::set :x 2])
    (t/flush!)
    (is (zero? (t/renders label)) "the dom root was unmounted: its [:x] subscription is gone")))

(deftest dom-mount-replaces-a-canvas-root-on-the-same-element
  (reset! state/app-db {:x 1})
  (reset! spins 0)
  (let [host (div)
        asked (atom 0)]
    (cv/mount! [spin] host)
    (t/frame! 16)
    (is (= 1 @spins))
    (core/mount! [label] host)
    (is (= "<p>1</p>" (.-innerHTML host)))
    (is (empty? (draw/states :canvas)) "the canvas root was unmounted")
    (draw/set-raf! (fn [_] (swap! asked inc)))
    (t/frame! 32)
    (is (= 1 @spins) "no loop keeps drawing on the detached canvas")
    (is (zero? @asked) "and none keeps requesting frames")))

;; ---- destroy!: a throwing backend teardown! must not skip cells/destroy!
;; or leave the standalone root's canvas mounted, or escape the next mount!

(draw/register-backend!
 :boom-teardown
 (draw/Backend. (fn [st _render] (.-canvas ^draw/State st))
                (fn [_st] nil)
                (fn [_st] nil)
                (fn [_st] (throw (js/Error. "teardown boom")))))

(def teardown-boom
  "A draw component with a path binding on a backend whose teardown! always
  throws."
  (draw/component "teardown-boom" 0
                   [{:kind :path :deps [] :f (fn [] [:tb])}]
                   [0]
                   (fn [_tb] (cljs.core/array {:size [10 10]} (fn [_ _] nil)))
                   :boom-teardown false))

(deftest throwing-teardown-does-not-block-cleanup-or-the-next-mount
  (reset! state/app-db {:tb 1})
  (let [el (div)
        before (.-refs state/paths)]
    (cv/mount! [teardown-boom] el)
    (is (= (inc before) (.-refs state/paths)) "the [:tb] path subscription is registered")
    (let [logs (capture-errors (fn [_] (cv/mount! [teardown-boom] el)))]
      (is (= "hammer: teardown failed in teardown-boom" (first (first logs)))
          "logged with the component name, not left to crash the unmount")
      (is (= 1 (count logs)))
      (is (= (inc before) (.-refs state/paths))
          "the old instance's subscription is gone; the new one's replaces it 1:1")
      (is (= 1 (.. el -childNodes -length)) "the throwing unmount still removed the old canvas before the new mount! added its own")
      ;; clean up: this root's teardown! always throws, so its own unmount
      ;; must be captured too, and done explicitly (this test's assertions
      ;; already ran) so it doesn't linger into another namespace's test of
      ;; hammer.cells/unmount-roots! sharing the same global registry.
      (capture-errors (fn [_] (t/reset-app!))))))
