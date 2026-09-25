(ns hammer.state
  (:require [hammer.trie :as trie]))

(defonce app-db (atom {}))
(defonce paths (trie/node))
