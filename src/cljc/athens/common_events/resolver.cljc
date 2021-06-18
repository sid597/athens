(ns athens.common-events.resolver
  (:require
    [athens.common-db :as common-db]
    [clojure.string :as string]
    #?(:clj  [datahike.api :as d]
       :cljs [datascript.core :as d]))
  #?(:clj
     (:import
       (java.util
         Date
         UUID))))


;; helpers

(defn- now-ts
  []
  #?(:clj  (.getTime (Date.))
     :cljs (.getTime (js/Date.))))


(defn- gen-block-uid
  []
  #?(:clj (subs (.toString (UUID/randomUUID)) 27)
     :cljs (subs (str (random-uuid)) 27)))


;; TODO start using this resolution in handlers
(defmulti resolve-event-to-tx
  "Resolves `:datascript/*` event in context of existing DB into transactions."
  #(:event/type %2))


(defmethod resolve-event-to-tx :datascript/create-page
  [_db {:event/keys [args]}]
  (let [{:keys [uid
                title]} args
        now             (now-ts)
        child-uid       (gen-block-uid)
        child           {:db/id        -2
                         :block/string ""
                         :block/uid    child-uid
                         :block/order  0
                         :block/open   true
                         :create/time  now
                         :edit/time    now}
        page-tx         {:db/id          -1
                         :node/title     title
                         :block/uid      uid
                         :block/children [child]
                         :create/time    now
                         :edit/time      now}]
    [page-tx]))


(defmethod resolve-event-to-tx :datascript/delete-page
  [db {:event/keys [args]}]
  (let [{uid :uid}         args
        ;; NOTE: common DB query? find page title by page uid?
        title              (ffirst
                             (d/q '[:find ?title
                                    :where
                                    [?e :node/title ?title]
                                    [?e :block/uid ?uid]
                                    :in $ ?uid]
                                  db uid))
        retract-blocks     (common-db/retract-uid-recursively-tx db uid)
        delete-linked-refs (common-db/replace-linked-refs-tx db title)
        tx-data            (concat retract-blocks
                                   delete-linked-refs)]
    (println ":datascript/delete-page" uid title)
    tx-data))


(defmethod resolve-event-to-tx :datascript/paste-verbatim
  [_db {:event/keys [args]}]
  (let [{:keys [uid
                text
                start
                value]} args
        block-empty? (string/blank? value)
        block-start? (zero? start)
        new-string   (cond
                       block-empty?       text
                       (and (not block-empty?)
                            block-start?) (str text value)
                       :else              (str (subs value 0 start)
                                               text
                                               (subs value start)))
        tx-data      [{:db/id        [:block/uid uid]
                       :block/string new-string}]]
    tx-data))