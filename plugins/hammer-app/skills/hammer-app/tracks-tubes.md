# Tracks: events on db changes

`(:require [hammer.track])`, then dispatch an event whenever the values at
db paths change, without a component (the re-frame "track" pattern):

```clojure
{:hammer.track/register {:id :reload :path [:filters]               ; or :paths [[:a] [:b]]
                         :event-fn (fn [filters] [:load filters])}} ; nil = no event
{:hammer.track/dispose {:id :reload}}
```

`:dispatch-first?` (default true) also fires for the current values. A change
is "not `=`", batched per tick; only tracks whose paths changed run.
Registering an id again replaces it. Don't let a track's event change its own
path (it loops). `reset-app!` disposes all.

# Tubes: events to and from a server

`(:require [hammer.tubes])`: event vectors over a WebSocket as EDN, both ways.

```clojure
{:hammer.tubes/create {:url "ws://host/ws" :params {:token t}
                       :on-connect [:online] :on-disconnect [:offline]}}
{:hammer.tubes/send [:say-hello "x"]}     ; queued while disconnected
{:hammer.tubes/destroy {}}                ; for good, no reconnect
```

Every event the server sends is dispatched (`:on-receive (fn [ev])` replaces
that). Reconnects with backoff. `:id` for several tubes (default `:default`).
The server must send and receive one EDN event vector per text frame. Tests:
`hammer.tubes/set-websocket!` swaps in a fake socket. Costs ~14 KB gzip (EDN
reader) when required.
