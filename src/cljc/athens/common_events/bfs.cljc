(ns athens.common-events.bfs
  (:require
    [athens.common-db                     :as common-db]
    [athens.common-events.graph.atomic :as atomic]
    [athens.common-events.graph.composite :as composite]
    [athens.common-events.graph.ops :as graph-ops]
    [clojure.string                       :as string]))


(defn conjoin-to-queue
  [q data-to-be-added]
  (reduce #(conj %1 %2) q data-to-be-added))


(defn get-children-uids
  [children]
  (reduce #(conj %1 (:block/uid %2)) [] children))


(defn get-individual-blocks
  "Given internal representation (basically a tree) get individual blocks from the representation.
   Walk the tree in a bfs manner, extract the individual blocks and the parent-child relationship."
  [tree]
  (println "individual blocks")
  (loop [res                   []
         ;; Why not add the parent in block data? I think it is easier to create the child-parent map
         ;; than to pass the current parent to subsequent recur
         child-parent-map      {}
         q   (conjoin-to-queue #?(:cljs #queue []
                                  :clj  clojure.lang.PersistentQueue/EMPTY)
                               tree)]
    (if (seq q)
      (let [{:block/keys [uid
                          order
                          open
                          string
                          children]
             :node/keys  [title]}      (peek q)
            block                      {:block/uid    uid
                                        :block/order  order
                                        :block/open   open
                                        :block/string string
                                        ;; Used for page
                                        :node/title   title}
            children-uids              (get-children-uids children)
            new-key-value              (zipmap children-uids
                                               (take (count children-uids) (cycle [uid])))
            new-q                      (pop q)]
        (recur (conj res block)
               (merge child-parent-map
                      new-key-value)
               (if (seq children)
                 (conjoin-to-queue new-q children)
                 new-q)))
      [res  child-parent-map])))


(defn internal-repr->atomic-ops
  "Takes the internal representation of a block then,
  For blocks creates `:block/new` and `:block/save` event and for page creates `:page/new` events from it."
  [db block-internal-representation parent-uid]
  (let [{block-uid    :block/uid
         block-string :block/string
         block-order  :block/order
         node-title   :node/title}   block-internal-representation
        new-page-op                  (atomic/make-page-new-op node-title
                                                              block-uid)
        new-block-op                 (atomic/make-block-new-op block-uid
                                                               parent-uid
                                                               block-order)
        block-save-op                (graph-ops/build-block-save-op db
                                                                    block-uid
                                                                    ""
                                                                    block-string)
        all-ops                      (if node-title
                                       [new-page-op]
                                       [new-block-op
                                        block-save-op])]
    all-ops))


(defn paste-ops-from-internal-representation
  "Given an internal representation and default parent uid build atomic ops from the internal
  representation. `paste-block-parent-uid` is nil for the blocks that represent a page."
  [db internal-representation paste-block-parent-uid]
  (println "paste ops")
  (let [[individual-blocks child-parent-map] (get-individual-blocks internal-representation)
        all-atomic-ops                       (map #(let [block-uid (:block/uid %1)
                                                         parent-uid (get child-parent-map block-uid
                                                                         paste-block-parent-uid)
                                                         atomic-ops (internal-repr->atomic-ops db
                                                                                               %
                                                                                               parent-uid)]
                                                     atomic-ops)
                                                  individual-blocks)
        flattened                            (flatten all-atomic-ops)]
    flattened))


(defn build-paste-op-for-blocks
  ([db internal-representation uid]
   (println "build paste for copy paste" internal-representation)
   (let [current-block                        (common-db/get-block db [:block/uid uid])
         current-block-parent-uid             (:block/uid (common-db/get-parent db [:block/uid uid]))
         {:block/keys [order
                       children
                       open
                       string]}               current-block
         ;; The parent of block depends on:
         ;; - if the current block is open and has chidren : if this is the case then we want the blocks to be pasted
         ;;   under the current block as its first children
         ;; - else the parent is the current block's parent
         current-block-parent?                (and children
                                                   open)
         empty-block?                         (and (string/blank? string)
                                                   (empty?        children))
         ;; - If the block is empty then we delete the empty block and add new blocks. So in this case
         ;;   the block order for the new blocks is the same as deleted blocks order.
         ;; - If the block is parent then we want the blocks to be pasted as this blocks first children
         ;; - If the block is not empty then add the new blocks after the current one.
         block-remove-op                      (graph-ops/build-block-remove-op db uid)
         new-block-order                      (cond
                                                empty-block?          order
                                                current-block-parent? 0
                                                :else                 (inc order))
         updated-order                        (map-indexed (fn [idx itm] (assoc itm :block/order (+ idx new-block-order)))
                                                           internal-representation)
         all-atomic-ops                       (paste-ops-from-internal-representation db
                                                                                      updated-order
                                                                                      current-block-parent-uid)
         add-block-remove-op                  (if empty-block?
                                                (conj all-atomic-ops block-remove-op)
                                                all-atomic-ops)]
     (println "blocks for copy paste" add-block-remove-op)
     add-block-remove-op)))

