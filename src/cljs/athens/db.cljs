(ns athens.db
  (:require
    [athens.common-db :as common-db]
    [athens.patterns :as patterns]
    [athens.util :refer [escape-str]]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [datascript.core :as d]
    [posh.reagent :refer [posh! pull q]]
    [re-frame.core :refer [dispatch]]))


;; -- Example Roam DBs ---------------------------------------------------

(def help-url   "https://raw.githubusercontent.com/athensresearch/athens/master/data/help.datoms")
(def ego-url    "https://raw.githubusercontent.com/athensresearch/athens/master/data/ego.datoms")


;; -- seed data -----------------------------------------------------------


(def default-graph-conf
  {:hlt-link-levels  1
   :link-distance    50
   :charge-strength  -15
   :local-depth      1
   :root-links-only? false
   :orphans?         true
   :daily-notes?     true})


(def greek-pantheon
  #{"Zeus"
    "Hera"
    "Poseidon"
    "Demeter"
    "Athena"
    "Apollo"
    "Artemis"
    "Ares"
    "Aphrodite"
    "Hephaestus"
    "Hermes"
    "Dionysus"
    ;; Technically not part of the Olympians, but a cool guy nonetheless.
    "Hades"
    ;; Son of either Zeus and Persephone or Hades and persephone.
    ;; Nothing to do with a recent video game at all.
    "Zagreus"})


(def default-settings
  {:email       nil
   :username    (rand-nth (vec greek-pantheon))
   :monitoring  true
   :backup-time 15})


(def default-athens-persist
  {:persist/version 1
   :theme/dark      false
   :graph-conf      default-graph-conf
   :settings        default-settings})


;; -- update -------------------------------------------------------------

(defn update-legacy-to-latest
  "Add settings in the legacy format to the latest persist format."
  [latest]
  ;; This value was not saved in local storage proper, saving it there
  ;; to enable use of update-when-found.
  (js/localStorage.setItem "monitoring" (not (try (.. js/window -posthog has_opted_out_capturing)
                                                  (catch :default _ true))))
  (let [update-when-found (fn [x keyseq k xf]
                            (if-some [v (js/localStorage.getItem k)]
                              (assoc-in x keyseq (xf v))
                              x))
        str->boolean (fn [x] (= x "true"))]
    (-> latest
        (update-when-found [:settings :email] "auth/email" identity)
        (update-when-found [:settings :username] "user/name" identity)
        (update-when-found [:settings :backup-time] "debounce-save-time" js/Number)
        (update-when-found [:settings :monitoring] "monitoring" str->boolean)
        (update-when-found [:theme/dark] "theme/dark" str->boolean)
        (update-when-found [:graph-conf] "graph-conf" edn/read-string))))


(defn update-persisted
  "Updates persisted to the latest format."
  [{:keys [:persist/version] :as persisted}]
  ;; Anything saved under the :athens/persist key will be automatically
  ;; persisted and loaded between sessions.
  {:athens/persist
   (if-not version
     (update-legacy-to-latest default-athens-persist)
     ;; Ignore the clj-kondo warning for v<, it'll go away once we have updates.
     #_:clj-kondo/ignore
     (let [v< #(< version %)]
       (cond-> persisted
               ;; Update persisted by applying each update fn incrementally.
               ;; (v< 2) update-v1-to-v2
               ;; (v< 3) update-v2-to-v3
               )))})


;; -- re-frame -----------------------------------------------------------

(defonce rfdb {:db/synced           true
               :db/mtime            nil
               :current-route       nil
               :loading?            true
               :modal               false
               :alert               nil
               :win-maximized?      false
               :win-fullscreen?     false
               :win-focused?        true
               :athena/open         false
               :athena/recent-items '()
               :devtool/open        false
               :left-sidebar/open   false
               :right-sidebar/open  false
               :right-sidebar/items {}
               :right-sidebar/width 32
               :mouse-down          false
               :daily-notes/items   []
               :selection           {:items []}
               :theme/dark          false
               :zoom-level          0
               :fs/watcher          nil
               :presence            {}
               :connection-status   :disconnected})


(defn init-app-db
  [persisted]
  (merge rfdb (update-persisted persisted)))


;; -- JSON Parsing ----------------------------------------------------

(def str-kw-mappings
  "Maps attributes from \"Export All as JSON\" to original datascript attributes."
  {"children" :block/children
   "create-email" :create/email
   "create-time" :create/time
   "edit-email" :edit/email
   "edit-time" :edit/time
   "email" :user/email
   "emoji" :ent/emoji
   "emojis" :ent/emojis
   "props" :block/props
   "string" :block/string
   "text-align" :block/text-align
   "time" nil
   "title" :node/title
   "uid" :block/uid
   "users" nil
   "heading" :block/heading})


(defn convert-key
  [k]
  (get str-kw-mappings k k))


(defn parse-hms
  "Parses JSON retrieved from Roam's \"Export all as JSON\". Not fully functional."
  [hms]
  (if (not (coll? hms))
    hms
    (map #(reduce (fn [acc [k v]]
                    (assoc acc (convert-key k) (parse-hms v)))
                  {}
                  %)
         hms)))


(defn parse-tuples
  "Parse tuples exported via method specified in https://roamresearch.com/#/app/ego/page/eJ14YtH2G."
  [tuples]
  (->> tuples
       (partition 3)            ; chunk into 3-tuples
       rest                     ; drop first tuple which is (?e ?a ?v)
       (map #(map edn/read-string %))
       (map #(cons :db/add %))))


(defn json-str-to-edn
  "Convert a JSON str to EDN. May receive JSON through an HTTP request or file upload."
  [json-str]
  (->> json-str
       (js/JSON.parse)
       (js->clj)))


(defn str-to-db-tx
  "Deserializes a JSON string into EDN and then Datoms."
  [json-str]
  (let [edn-data (json-str-to-edn json-str)]
    (if (coll? (first edn-data))
      (parse-hms edn-data)
      (parse-tuples edn-data))))


;; -- Datascript and Posh ------------------------------------------------

(defonce dsdb (d/create-conn common-db/schema))
(defonce dsdb-snapshot (atom nil))


;; todo: turn into an effect
(posh! dsdb)


(defn e-by-av
  [a v]
  (-> (d/datoms @dsdb :avet a v) first :e))


(defn v-by-ea
  [e a]
  (-> (d/datoms @dsdb :eavt e a) first :v))


(def rules
  '[[(after ?p ?at ?ch ?o)
     [?p :block/children ?ch]
     [?ch :block/order ?o]
     [(> ?o ?at)]]
    [(between ?p ?lower-bound ?upper-bound ?ch ?o)
     [?p :block/children ?ch]
     [?ch :block/order ?o]
     [(> ?o ?lower-bound)]
     [(< ?o ?upper-bound)]]
    [(inc-after ?p ?at ?ch ?new-o)
     (after ?p ?at ?ch ?o)
     [(inc ?o) ?new-o]]
    [(dec-after ?p ?at ?ch ?new-o)
     (after ?p ?at ?ch ?o)
     [(dec ?o) ?new-o]]
    [(plus-after ?p ?at ?ch ?new-o ?x)
     (after ?p ?at ?ch ?o)
     [(+ ?o ?x) ?new-o]]
    [(minus-after ?p ?at ?ch ?new-o ?x)
     (after ?p ?at ?ch ?o)
     [(- ?o ?x) ?new-o]]
    [(siblings ?uid ?sib-e)
     [?e :block/uid ?uid]
     [?p :block/children ?e]
     [?p :block/children ?sib-e]]])


(defn inc-after
  [eid order]
  (->> (d/q '[:find ?ch ?new-o
              :keys db/id block/order
              :in $ % ?p ?at
              :where (inc-after ?p ?at ?ch ?new-o)]
            @dsdb rules eid order)))


(defn uid-and-embed-id
  [uid]
  (or (some->> uid
               (re-find #"^(.+)-embed-(.+)")
               rest vec)
      [uid nil]))


(defn sort-block-children
  [block]
  (if-let [children (seq (:block/children block))]
    (assoc block :block/children
           (vec (sort-by :block/order (map sort-block-children children))))
    block))


(def block-document-pull-vector
  '[:db/id :block/uid :block/string :block/open :block/order :block/header {:block/children ...} :block/refs :block/_refs])


(def node-document-pull-vector
  (-> block-document-pull-vector
      (conj :node/title :page/sidebar)))


(def roam-node-document-pull-vector
  '[:node/title :block/uid :block/string :block/open :block/order {:block/children ...}])


(defn get-block-document
  [id]
  (->> @(pull dsdb block-document-pull-vector id)
       sort-block-children))


(defn get-node-document
  ([id]
   (->> @(pull dsdb node-document-pull-vector id)
        sort-block-children))
  ([id db]
   (->> (d/pull db node-document-pull-vector id)
        sort-block-children)))


(defn get-roam-node-document
  [id db]
  (->> (d/pull db roam-node-document-pull-vector id)
       sort-block-children))


(defn get-athens-datoms
  "Copy REPL output to athens-datoms.cljs"
  [id]
  (->> @(pull dsdb (filter #(not (or (= % :db/id) (= % :block/_refs))) node-document-pull-vector) id)
       sort-block-children))


(defn shape-parent-query
  "Normalize path from deeply nested block to root node."
  [pull-results]
  (->> (loop [b   pull-results
              res []]
         (if (:node/title b)
           (conj res b)
           (recur (first (:block/_children b))
                  (conj res (dissoc b :block/_children)))))
       (rest)
       (reverse)
       vec))


(defn get-parents-recursively
  [id]
  (->> @(pull dsdb '[:db/id :node/title :block/uid :block/string :edit/time {:block/_children ...}] id)
       shape-parent-query))


(defn get-root-parent-page
  "Returns the root parent page or returns the block because this block is a page."
  [uid]
  ;; make sure block first exists
  (when-let [block (d/entity @dsdb [:block/uid uid])]
    (let [opt1 (first (get-parents-recursively [:block/uid uid]))]
      (or opt1 block))))


(defn get-block
  [id]
  @(pull dsdb '[:db/id :node/title :block/uid :block/order :block/string {:block/children [:block/uid :block/order]} :block/open] id))


(defn get-parent
  [id]
  (-> (d/entity @dsdb id)
      :block/_children
      first
      :db/id
      get-block))


(defn same-parent?
  "Given a coll of uids, determine if uids are all direct children of the same parent."
  [uids]
  (let [parents (->> uids
                     (mapv (comp first uid-and-embed-id))
                     (d/q '[:find ?parents
                            :in $ [?uids ...]
                            :where
                            [?e :block/uid ?uids]
                            [?parents :block/children ?e]]
                          @dsdb))]
    (= (count parents) 1)))


(defn deepest-child-block
  [id]
  (let [document (->> (d/pull @dsdb '[:block/order :block/uid {:block/children ...}] id)
                      sort-block-children)]
    (loop [block document]
      (let [{:block/keys [children]} block
            n (count children)]
        (if (zero? n)
          block
          (recur (get children (dec n))))))))


(defn get-children-recursively
  "Get list of children UIDs for given block ID (including the root block's UID)"
  [uid]
  (when-let [eid (e-by-av :block/uid uid)]
    (->> eid
         (d/pull @dsdb '[:block/order :block/uid {:block/children ...}])
         (tree-seq :block/children :block/children)
         (map :block/uid))))


(defn retract-page-recursively
  "Retract all blocks of a page, excluding the page. Used to reset athens/Welcome page.
  Page is excluded because block/uid will be generated by walk-string if [[athens/Welcome]] doesn't already exist."
  [title]
  (let [eid (e-by-av :node/title title)
        uid (v-by-ea eid :block/uid)]
    (->> (get-children-recursively uid)
         (mapv (fn [uid] [:db/retractEntity [:block/uid uid]]))
         next)))


(defn re-case-insensitive
  "More options here https://clojuredocs.org/clojure.core/re-pattern"
  [query]
  (re-pattern (str "(?i)" (escape-str query))))


(defn search-exact-node-title
  [query]
  (d/entity @dsdb [:node/title query]))


(defn search-in-node-title
  ([query] (search-in-node-title query 20 false))
  ([query n] (search-in-node-title query n false))
  ([query n exclude-exact-match?]
   (if (string/blank? query)
     (vector)
     (let [exact-match            (when exclude-exact-match? query)
           case-insensitive-query (re-case-insensitive query)]
       (sequence
         (comp
           (filter (every-pred
                     #(re-find case-insensitive-query (:v %))
                     #(not= exact-match (:v %))))
           (take n)
           (map #(d/entity @dsdb (:e %))))
         (d/datoms @dsdb :aevt :node/title))))))


(defn get-root-parent-node-from-block
  [block]
  (loop [b block]
    (cond
      (:node/title b)       (assoc block :block/parent b)
      (:block/_children b)  (recur (first (:block/_children b)))
      ;; protect against orphaned nodes
      :else                 nil)))


(defn search-in-block-content
  ([query] (search-in-block-content query 20))
  ([query n]
   (if (string/blank? query)
     (vector)
     (let [case-insensitive-query (re-case-insensitive query)]
       (->>
         (d/datoms @dsdb :aevt :block/string)
         (sequence
           (comp
             (filter #(re-find case-insensitive-query (:v %)))
             (take n)
             (map #(:e %))))
         (d/pull-many @dsdb '[:db/id :block/uid :block/string :node/title {:block/_children ...}])
         (sequence
           (comp
             (keep get-root-parent-node-from-block)
             (map #(dissoc % :block/_children)))))))))


(defn nth-sibling
  "Find sibling that has order+n of current block.
  Negative n means previous sibling.
  Positive n means next sibling."
  [uid n]
  (let [block      (get-block [:block/uid uid])
        {:block/keys [order]} block
        find-order (+ n order)]
    (d/q '[:find (pull ?sibs [*]) .
           :in $ % ?curr-uid ?find-order
           :where
           (siblings ?curr-uid ?sibs)
           [?sibs :block/order ?find-order]]
         @dsdb rules uid find-order)))


(defn prev-block-uid
  "If order 0, go to parent.
   If order n but block is closed, go to prev sibling.
   If order n and block is OPEN, go to prev sibling's deepest child."
  [uid]
  (let [[uid embed-id]                (uid-and-embed-id uid)
        block                         (get-block [:block/uid uid])
        parent                        (get-parent [:block/uid uid])
        prev-sibling                  (nth-sibling uid -1)
        {:block/keys      [open]
         prev-sibling-uid :block/uid} prev-sibling
        prev-block                    (cond
                                        (zero? (:block/order block)) parent
                                        (false? open)                prev-sibling
                                        (true? open)                 (deepest-child-block [:block/uid prev-sibling-uid]))]
    (cond-> (:block/uid prev-block)
      embed-id (str "-embed-" embed-id))))


(defn next-sibling-recursively
  "Search for next sibling. If not there (i.e. is last child), find sibling of parent.
  If parent is root, go to next sibling."
  [uid]
  (loop [uid uid]
    (let [sib    (nth-sibling uid +1)
          parent (get-parent [:block/uid uid])
          {node :node/title}   (get-block [:block/uid uid])]
      (if (or sib (:node/title parent) node)
        sib
        (recur (:block/uid parent))))))


(defn next-block-uid
  "1-arity:
    if open and children, go to child 0
    else recursively find next sibling of parent
  2-arity:
    used for multi-block-selection; ignores child blocks"
  ([uid]
   (let [[uid embed-id]       (uid-and-embed-id uid)
         block                (->> (get-block [:block/uid uid])
                                   sort-block-children)
         {:block/keys [children open] node :node/title} block
         next-block-recursive (next-sibling-recursively uid)
         next-block           (cond
                                (and (or open node) children) (first children)
                                next-block-recursive          next-block-recursive)]
     (cond-> (:block/uid next-block)

       ;; only go to next block if it's part of current embed scheme
       (and embed-id (js/document.querySelector (str "#editable-uid-" (:block/uid next-block) "-embed-" embed-id)))
       (str "-embed-" embed-id))))
  ([uid selection?]
   (if selection?
     (let [[o-uid embed-id]     (uid-and-embed-id uid)
           next-block-recursive (next-sibling-recursively o-uid)]
       (cond-> (:block/uid next-block-recursive)

         ;; only go to next block if it's part of current embed scheme
         (and embed-id (js/document.querySelector (str "#editable-uid-" (:block/uid next-block-recursive) "-embed-" embed-id)))
         (str "-embed-" embed-id)))
     (next-block-uid uid))))


(defn get-first-child-uid
  [uid db]
  (when uid
    (try
      (->> (d/pull db [{:block/children [:block/uid :block/order]}] [:block/uid uid])
           sort-block-children
           :block/children
           first
           :block/uid)
      (catch :default _))))


;; history

(defonce history (atom '()))
#_(def ^:const history-limit 10)


;; this gives us customization options
;; now if there is a pattern for a tx then the datoms can be
;; easily modified(mind the order of datoms) to add a custom undo/redo strategy
;; Not seeing a use case now, but there is an option to do it
(d/listen! dsdb :history
           (fn [tx-report]
             (when-not (or (->> tx-report :tx-data (some (fn [datom]
                                                           (= (nth datom 1)
                                                              :from-undo-redo))))
                           (->> tx-report :tx-data empty?))
               (swap! history (fn [buff]
                                (->> buff (remove (fn [[_ applied? _]]
                                                    (not applied?)))
                                     doall)))
               (swap! history (fn [cur-his]
                                (cons [(-> tx-report :tx-data first vec (nth 3))
                                       true
                                       (:tx-data tx-report)]
                                      cur-his))))))


;; -- Linked & Unlinked References ----------

(defn get-ref-ids
  [pattern]
  @(q '[:find [?e ...]
        :in $ ?regex
        :where
        [?e :block/string ?s]
        [(re-find ?regex ?s)]]
      dsdb
      pattern))


(defn merge-parents-and-block
  [ref-ids]
  (let [parents (reduce-kv (fn [m _ v] (assoc m v (get-parents-recursively v)))
                           {}
                           ref-ids)
        blocks (map (fn [id] (get-block-document id)) ref-ids)]
    (mapv
      (fn [block]
        (merge block {:block/parents (get parents (:db/id block))}))
      blocks)))


(defn group-by-parent
  [blocks]
  (group-by (fn [x]
              (let [parent (-> x
                               :block/parents
                               first)]
                [(:node/title parent) (:edit/time parent 0)]))
            blocks))


(defn get-data
  [pattern]
  (-> pattern get-ref-ids merge-parents-and-block group-by-parent seq))


(defn get-linked-references
  "For node-page references UI."
  [title]
  (->> @(pull dsdb '[* :block/_refs] [:node/title title])
       :block/_refs
       (mapv :db/id)
       merge-parents-and-block
       group-by-parent
       (sort-by #(-> % first second))
       (map #(vector (ffirst %) (second %)))
       vec
       rseq))


(defn get-linked-block-references
  "For block-page references UI."
  [block]
  (->> (:block/_refs block)
       (mapv :db/id)
       merge-parents-and-block
       group-by-parent
       (sort-by #(-> % first second))
       (map #(vector (ffirst %) (second %)))
       vec))


(defn get-unlinked-references
  "For node-page references UI."
  [title]
  (-> title patterns/unlinked get-data))


;; -- save ------------------------------------------------------------


(defn transact-state-for-uid
  "uid -> Current block
   state -> Look at state atom in block-el"
  [uid state]
  (let [{:string/keys [local
                       previous]} @state
        callback                  #(swap! state assoc :string/previous local)]
    (dispatch [:block/save {:uid        uid
                            :old-string previous
                            :new-string local
                            :callback   callback}])))
