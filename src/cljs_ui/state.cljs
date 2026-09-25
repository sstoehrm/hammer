(ns cljs-ui.state
  (:require [cljs-ui.trie :as trie]))

(defonce app-db (atom {}))
(defonce paths (trie/node))
