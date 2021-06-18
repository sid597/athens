(ns athens.common-events
  "Event as Verbs executed on Knowledge Graph"
  (:require
    [clojure.string :as string]))


;; helpers

(defn- gen-event-id
  []
  (str (gensym "eid-")))


;; building events

;; - confirmation events

(defn build-event-accepted
  "Builds ACK Event Response with `:accepted/tx-id` transaction id
  that accepted this event."
  [id tx-id]
  {:event/id       id
   :event/status   :accepted
   :accepted/tx-id tx-id})


(defn build-event-rejected
  "Builds Rejection Event Response with `:reject/reason & :reject/data`."
  [id message data]
  {:event/id      id
   :event/status  :rejected
   :reject/reason message
   :reject/data   data})


;; - datascript events

(defn build-db-dump-event
  "Builds `:datascript/db-dump` events with `datoms`."
  [last-tx datoms]
  (let [event-id (gen-event-id)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :datascript/db-dump
     :event/args    {:datoms datoms}}))


(defn build-tx-log-event
  "Builds `:datascript/tx-log` event from transaction report."
  [tx-report]
  (let [event-id          (gen-event-id)
        {:keys [tx-data
                tempids]} tx-report
        last-tx           (:db/current-tx tempids)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :datascript/tx-log
     :event/args    {:tx-data tx-data
                     :tempids tempids}}))


(defn build-page-create-event
  "Builds `:datascript/create-page` event with `uid` and `title` of page."
  [last-tx uid title]
  (let [event-id (gen-event-id)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :datascript/create-page
     :event/args    {:uid   uid
                     :title title}}))


;; TODO: Do we need `value` here? can't we discover it during event resolution?
(defn build-paste-verbatim-event
  "Builds `:datascript/paste-verbatim` evnt with:
  - `uid`: of block that events applies to,
  - `text`: string that was pasted,
  - `start`: cursor position in block,
  - `value`: previous value (?) of block"
  [last-tx uid text start value]
  (let [event-id (gen-event-id)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :datascript/paste-verbatim
     :event/args    {:uid   uid
                     :text  text
                     :start start
                     :value value}}))


(defn build-page-delete-event
  "Builds `:datascript/page-delete` event with:
  - `uid`: of page to be deleted."
  [last-tx uid]
  (let [event-id (gen-event-id)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :datascript/delete-page
     :event/args    {:uid uid}}))


;; - presence events

(defn build-presence-hello-event
  "Builds `:presence/hello` event with `username`"
  [last-tx username]
  (let [event-id (gen-event-id)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :presence/hello
     :event/args    {:username username}}))


(defn build-presence-online-event
  "Builds `:presence/online` event with `username` that went online."
  [last-tx username]
  (let [event-id (gen-event-id)]
    {:event/id      event-id
     :event/last-tx last-tx
     :event/type    :presence/online
     :event/args    {:username username}}))