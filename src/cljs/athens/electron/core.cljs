(ns athens.electron.core
  (:require
    [athens.athens-datoms :as athens-datoms]
    [athens.db :as db]
    [athens.electron.utils :as utils]
    [athens.electron.window]
    [athens.patterns :as patterns]
    [athens.util :as util]
    [cljs.reader :refer [read-string]]
    [datascript.core :as d]
    [datascript.transit :as dt]
    [day8.re-frame.async-flow-fx]
    [goog.functions :refer [debounce]]
    [re-frame.core :as rf]))


;; XXX: most of these operations are effectful. They _should_ be re-written with effects, but feels like too much boilerplate.

(when (util/electron?)

  (def electron (js/require "electron"))
  (def remote (.. electron -remote))


  (def fs (js/require "fs"))
  (def path (js/require "path"))
  (def stream (js/require "stream"))


  ;; Filesystem Dialogs


  ;; Subs

  (rf/reg-sub
    :db/mtime
    (fn [db _]
      (:db/mtime db)))


  (rf/reg-sub
    :db/filepath
    (fn [db _]
      (:db/filepath db)))


  (rf/reg-sub
    :db/filepath-dir
    (fn [db _]
      (.dirname path (:db/filepath db))))


  ;; ------- db -picker related implementation--------------------------------

  ;; Subs

  (rf/reg-sub
    :db-picker/all-dbs
    (fn [db _]
      (:db-picker/all-dbs db)))

  (rf/reg-sub
    :db/is-remote?
    (fn [db _]
      (:db/is-remote? db)))

  ;; helper functions

  (defn get-db-name
    "From a dbpath find out db name.
     e.g : /home/sid597/Desktop/athens db/test db/index.transit --> test db"
    [dbpath]
    (.basename path (.dirname path dbpath)))

  (defn check-duplicate-db
    "Check if a db is already in the list.
     Implementation thoughts
      - Should this be done whenever a new db is added or should we maintain a
        set universally and check that?
      - No performance issues if this is done on every new db addition because
        not many dbs would be made frequently to cause performance issue. What
        about merging 2 large dbs? Not there yet, but in that case also calculating
        the set of all paths is O(n).
      - Also re-frame is in-memory so saving data which can be easily calculated is
        not a good idea I think"
    [db-list check-path]
    (seq (filter #(= check-path (:path %)) db-list)))


  ;; Events

  (rf/reg-event-fx
    :db-picker/add-new-db
    (fn [{:keys [db]} [_ dbpath is-remote?]]
      "Add a new to db picker list.
       Can be invoked when a db is added, opened, moved, selected or removed.
       Update local storage value with the new db list"
      (let [current-db-list (:db-picker/all-dbs db)
            duplicate?       (check-duplicate-db
                               current-db-list
                               dbpath)]
        (if duplicate?
          {:fx [[:dispatch [:alert/js (str "Database already in the list")]]]}
          (let [dbname  (if is-remote?
                          ("Remote db") ;; Do we ask user to provide name also??
                          (get-db-name dbpath))
                newdb   {:path       dbpath
                         :name       dbname
                         :type       (if is-remote? :lan-party :fs) ;; Is this needed?
                         :is-remote  is-remote?}
                all-dbs (conj current-db-list newdb)]
            {:db       (assoc db :db-picker/all-dbs all-dbs)
             :fx       [[:dispatch [:local-storage/set-db-picker-list]]]})))))


  (rf/reg-event-fx
    :local-storage/set-db-picker-list
    (fn [{:keys [db]} _]
      "Save current db-picker list to local storage. Why using `pr-str`? Checkout
       this link for discussion on how to serialize data to store"
      ;; https://stackoverflow.com/questions/67821181/how-to-set-and-get-a-vector-in-local-storage
      (let [current-db-list (:db-picker/all-dbs db)]
        {:fx [[:local-storage/set! ["db-picker/all-dbs" (pr-str current-db-list)]]]})))

  (rf/reg-event-fx
    :local-storage/update-db-picker-list
    (fn [{:keys [db]} _]
      "Check if local storage contains db-picker list.
       If not it means this is the first time opening Athens or local storage was
       cleared, if this is the case we update db-picker list with the current running db.
       This event is dispatched whenever
       - A new db is opened or added
       - Athens is started"

      (let [local-storage-db-list (read-string (js/localStorage.getItem "db-picker/all-dbs"))
            is-remote?            (read-string (js/localStorage.getItem "db/is-remote?"))
            current-db-filepath   (:db/filepath db)]
        (if (nil? local-storage-db-list)
          {:fx [[:dispatch [:db-picker/add-new-db current-db-filepath is-remote?]]]}
          {:db (assoc db :db-picker/all-dbs local-storage-db-list)}))))

  (rf/reg-event-fx
    :db-picker/remove-db-from-list
    (fn [{:keys [db]} [_ db-path is-remote?]]
      "Remove the selected db from db-list. Update local storage value with the new db list"
      (let [current-db-list (:db-picker/all-dbs db)
            new-db-list     (into [] (filter
                                       (fn [db-list-item] (not= db-path (:path db-list-item)))
                                       current-db-list))]
        (if is-remote?
          {:fx [[:dispatch [:remote/client-disconnect!]]]})
        {:db       (assoc db :db-picker/all-dbs new-db-list)
         :fx [[:dispatch [:local-storage/set-db-picker-list]]]})))


  (rf/reg-event-fx
    :db-picker/move-db
    (fn [_ [_ previous-path new-path]]
      "Move db from current location. This is only applicable to local dbs"
      {:fx [[:dispatch-n [[:db-picker/remove-db-from-list previous-path false]
                          [:db-picker/add-new-db new-path false]]]]}))

  (rf/reg-event-fx
    :db-picker/select-new-db
    (fn [{:keys [db]} [_ db-path synced? selected-db-is-remote?]]
      ;; TODO : Handle the case when the current db is a remote db
      "Select a new db from db list.
      If current db is remote db and a new db is selected then need to close the connection
      to current remote db.
      If remote db is selected: Make this active db and start lan party
      General :
      If the selected db is a local db then:
      If the selected db is deleted from disk then show an alert describing the
      situation and remove this db from db list. Prevent selecting a db when sync
      is happening, instead show an alert describing the situation."
      (if (cljs.reader/read-string (js/localStorage.getItem "db/is-remote?"))
        {:fx [[:dispatch [:remote/client-disconnect!]]]})
      (if selected-db-is-remote?
        (cond
          (synced?) {:fx [[:dispatch-n [[:db/update-filepath      db-path true]
                                        [:remote/client-connect!   db-path]]]]}
          :else     {:fx [[:dispatch [:alert/js "Database is saving your changes, if you switch now your changes will not be saved"]]]})
        (let [file-exists? (and db-path (.existsSync fs db-path))]
          (cond
            (and file-exists? synced?)        {:fx   [[:dispatch-n [[:db/update-filepath db-path false]
                                                                    [:boot/desktop]]]]}
            (and file-exists? (not synced?))  {:fx   [[:dispatch [:alert/js "Database is saving your changes, if you switch now your changes will not be saved"]]]}
            :else                             {:fx   [[:dispatch-n [[:alert/js "This database does not exist, removing it from list"]
                                                                    [:db-picker/remove-db-from-list db-path (:is-remote? db)]]]]})))))

  (rf/reg-event-fx
    :db-picker/delete-db
    (fn [{:keys [db]} [_ db-filepath]]
      "Delete selected db.
      Delete the selected db and set the first db in the updated db list as active.
      Another approach could be to keep state of last visited db before the current
      one, and when the current db is deleted switch to previous one. This approach
      is comparatively more complex but would improve UX, we are leaving this one
      out in current scope.

      `select-new-db` event call has 2nd argument (synced) as true because one
      is deleting a db so to them it does not matter if the db is synced or not."

      ;; TODO implement delete db from filesystem not implemented
      ;; so that we can test without accidently deleting real db
      (let [new-list         (:db-picker/all-dbs db)
            next-db-filepath (:path (nth new-list 0))]
        {:fx   [[:dispatch [:db-picker/select-new-db next-db-filepath true]]
                [:local-storage/set! ["db-picker/all-dbs" new-list]]]})))

  ;; ==================== db- picker end ==========================

  (rf/reg-event-fx
    :fs/open-dialog
    (fn [{:keys [db]} _]
      (js/alert (str "No DB found at " (:db/filepath db) "."
                     "\nPlease open or create a new db."))
      {:dispatch-n [[:modal/toggle]]}))


  (rf/reg-event-fx
    :local-storage/get-db-filepath
    [(rf/inject-cofx :local-storage "db/filepath")
     (rf/inject-cofx :local-storage-map {:ls-key "db/is-remote?"
                                         :key    :is-remote})]
    (fn [{:keys [local-storage is-remote?]} _]
      "Dev athens and production athens use different instances of chromium, so they have different localstorages.
       App startup assumes that if there is no filepath in localstorage, it is absolutely the first time athens has
       been run, so it creates a new database at the default location, overwriting the existing database.
       So, at startup, check for an index.transit file at the default location before creating a new database."
      (let [default-db-path (.resolve path utils/documents-athens-dir utils/DB-INDEX)]
        (cond
          is-remote?                             {:fx [[:dispatch-n [[:remote/client-connect! local-storage]
                                                                     [:db/update-filepath     local-storage true]]]]}
          (and (nil? local-storage)
               (.existsSync fs default-db-path)) {:fx [[ :dispatch [:db/update-filepath default-db-path false]]]}
          :else                                  {:fx [[:dispatch [:db/update-filepath local-storage false]]]}))))


  (rf/reg-event-fx
    :local-storage/navigate
    [(rf/inject-cofx :local-storage "current-route/uid")]
    (fn [{:keys [local-storage]} _]
      {:dispatch [:navigate {:page {:id local-storage}}]}))


  (defn create-dir-if-needed!
    [dir]
    (when (not (.existsSync fs dir))
      (.mkdirSync fs dir)))


  (rf/reg-event-fx
    :fs/create-new-db
    (fn []
      "Documents/athens
       ├── images\n
       └── index.transit
       If new local db is created add
       1. This db to all-dbs list
       2. Make this db active "
      (let [db-filepath (.resolve path utils/documents-athens-dir utils/DB-INDEX)
            db-images   (.resolve path utils/documents-athens-dir utils/IMAGES-DIR-NAME)]
        (create-dir-if-needed! utils/documents-athens-dir)
        (create-dir-if-needed! db-images)
        {:fs/write!  [db-filepath (dt/write-transit-str (d/empty-db db/schema))]
         :dispatch-n [[:db/update-filepath   db-filepath false]
                      [:transact             athens-datoms/datoms]
                      [:db-picker/add-new-db db-filepath false]]})))


  (rf/reg-event-fx
    :db/retract-athens-pages
    (fn []
      {:dispatch [:transact (concat (db/retract-page-recursively "Welcome")
                                    (db/retract-page-recursively "Changelog"))]}))


  (rf/reg-event-fx
    :db/transact-athens-pages
    (fn []
      {:dispatch [:transact athens-datoms/datoms]}))

  (declare write-bkp)

  (defn sync-db-from-fs
    "If modified time is newer, update app-db with m-time. Prevents sync happening after db is written from the app."
    [filepath _filename]
    (let [prev-mtime @(rf/subscribe [:db/mtime])
          curr-mtime (.-mtime (.statSync fs filepath))
          newer?     (< prev-mtime curr-mtime)]
      (when newer?
        (let [block-text js/document.activeElement.value
              _          (.. js/navigator -clipboard (writeText block-text))
              _          (write-bkp)
              confirm    (js/window.confirm (str "New file found. Copying your current block to the clipboard, and saving your current db."
                                                 "\n\n"
                                                 "Accept changes?"))]
          (when confirm
            (rf/dispatch [:db/update-mtime curr-mtime])
            (let [read-db (.readFileSync fs filepath)
                  db      (dt/read-transit-str read-db)]
              (rf/dispatch [:reset-conn db])))))))


  (def debounce-sync-db-from-fs
    (debounce sync-db-from-fs 1000))


  ;; Watches directory that db is located in. If db file is updated, sync-db-from-fs.
  ;; Watching db file directly doesn't always work, so watch directory and regex match.
  ;; Debounce because files can be changed multiple times per save.
  (rf/reg-event-fx
    :fs/watch
    (fn [_ [_ filepath]]
      (let [dirpath (.dirname path filepath)]
        (.. fs (watch dirpath (fn [_event filename]
                                ;; when filename matches last part of filepath
                                ;; e.g. "first-db.transit" matches "home/u/Documents/athens/first-db.transit"
                                (when (re-find #"conflict" (or filename ""))
                                  (throw "Conflict file created by Dropbox"))
                                (when (re-find (re-pattern (str "\\b" filename "$")) filepath)
                                  (debounce-sync-db-from-fs filepath filename))))))
      {}))


  (rf/reg-event-db
    :db/update-mtime
    (fn [db [_ mtime1]]
      (let [{:db/keys [filepath]} db
            mtime (or mtime1 (.. fs (statSync filepath) -mtime))]
        (assoc db :db/mtime mtime))))


  ;; if localStorage is empty, assume first open
  ;; create a Documents/athens directory and Documents/athens/db.transit file
  ;; store path in localStorage and re-frame
  ;; if localStorage has filepath, and there is a file
  ;; Open and set db
  ;; else - localStorage has filepath, but no file at filepath
  ;; open or create a new starter db

  ;; Watch filesystem, e.g. in case db is updated via Dropbox sync
  (rf/reg-event-fx
    :boot/desktop
    (fn [_ _]
      {:db         db/rfdb
       :async-flow {:first-dispatch [:local-storage/get-db-filepath]
                    :rules          [{:when        :seen?
                                      :events      :db/update-filepath
                                      :dispatch-fn (fn [[_ filepath]]
                                                     (cond
                                                       ;; No database path found in localStorage. Creating new one
                                                       (nil? filepath) (rf/dispatch [:fs/create-new-db])
                                                       ;; Database found in local storage and filesystem:
                                                       (.existsSync fs filepath) (let [read-db (.readFileSync fs filepath)
                                                                                       db      (dt/read-transit-str read-db)]
                                                                                   (rf/dispatch [:fs/watch filepath])
                                                                                   (rf/dispatch [:reset-conn db])
                                                                                   (rf/dispatch [:local-storage/update-db-picker-list]))
                                                       :else (rf/dispatch [:fs/open-dialog])))}

                                     ;; remote graph
                                     {:when        :seen?
                                      :events      :start-socket}

                                     ;; if first time, go to Daily Pages and open left-sidebar
                                     {:when       :seen?
                                      :events     :fs/create-new-db
                                      :dispatch-n [[:navigate :home]
                                                   [:left-sidebar/toggle]]}

                                     ;; if nth time, remember dark/light theme and last page
                                     {:when       :seen?
                                      :events     :reset-conn
                                      :dispatch-n [[:local-storage/set-theme]
                                                   #_[:local-storage/navigate]]}

                                     ;; whether first or nth time, update athens pages
                                     #_{:when       :seen-any-of?
                                        :events     [:fs/create-new-db :reset-conn]
                                        :dispatch-n [[:db/retract-athens-pages]
                                                     [:db/transact-athens-pages]]}

                                     ;; bind windows toolbar electron buttons
                                     {:when       :seen-any-of?
                                      :events     [:fs/create-new-db :reset-conn]
                                      :dispatch   [:bind-win-listeners]}


                                     {:when        :seen-any-of?
                                      :events      [:fs/create-new-db :reset-conn]
                                      ;; if schema is nil, update to 1 and reparse all block/string's for links
                                      :dispatch-fn (fn [_]
                                                     (let [schemas    (d/q '[:find ?e ?v
                                                                             :where [?e :schema/version ?v]]
                                                                           @db/dsdb)
                                                           schema-cnt (count schemas)]
                                                       (cond
                                                         (= 0 schema-cnt) (let [linked-ref-pattern      (patterns/linked ".*")
                                                                                blocks-with-plain-links (d/q '[:find ?u ?s
                                                                                                               :keys block/uid block/string
                                                                                                               :in $ ?pattern
                                                                                                               :where
                                                                                                               [?e :block/uid ?u]
                                                                                                               [?e :block/string ?s]
                                                                                                               [(re-find ?pattern ?s)]]
                                                                                                             @db/dsdb
                                                                                                             linked-ref-pattern)
                                                                                blocks-orig             (map (fn [{:block/keys [uid string]}]
                                                                                                               {:db/id [:block/uid uid] :block/string string})
                                                                                                             blocks-with-plain-links)
                                                                                blocks-temp             (map (fn [{:block/keys [uid]}]
                                                                                                               {:db/id [:block/uid uid] :block/string ""})
                                                                                                             blocks-with-plain-links)]
                                                                            ;; give all blocks empty string - clears refs
                                                                            ;; give all blocks their original string - adds refs (for the period of time where block/refs were not added to db
                                                                            ;; update schema version, so this doesn't need to happen again
                                                                            (rf/dispatch [:transact blocks-temp])
                                                                            (rf/dispatch [:transact blocks-orig])
                                                                            (rf/dispatch [:transact [[:db/add -1 :schema/version 1]]]))
                                                         (= 1 schema-cnt) (let [schema-version (-> schemas first second)]
                                                                            (case schema-version
                                                                              1 (prn (str "Schema version " schema-version))
                                                                              (js/alert (js/Error (str "No matching case clause for schema version: " schema-version)))))
                                                         (< 1 schema-cnt)
                                                         (js/alert (js/Error (str "Multiple schema versions: " schemas))))

                                                       (rf/dispatch [:loading/unset])))
                                      :halt?       true}]}}))





  ;; Effects

  (defn os-username
    []
    (.. (js/require "os") userInfo -username))


  (defn write-db
    "Tries to create a write stream to {timestamp}-index.transit.bkp. Then tries to copy backup to index.transit.
    If the write operation fails, the backup file is corrupted and no copy is attempted, thus index.transit is assumed to be untouched.
    If the write operation succeeds, a backup is created and index.transit is overwritten.
    User should eventually have MANY backups files. It's their job to manage these backups :)"
    [copy?]
    (let [filepath     @(rf/subscribe [:db/filepath])
          data         (dt/write-transit-str @db/dsdb)
          r            (.. stream -Readable (from data))
          dirname      (.dirname path filepath)
          time         (.. (js/Date.) getTime)
          bkp-filename (str time "-" (os-username) "-" "index.transit.bkp")
          bkp-filepath (.resolve path dirname bkp-filename)
          w            (.createWriteStream fs bkp-filepath)
          error-cb     (fn [err]
                         (when err
                           (js/alert (js/Error. err))
                           (js/console.error (js/Error. err))))]
      (.setEncoding r "utf8")
      (.on r "error" error-cb)
      (.on w "error" error-cb)
      (.on w "finish" (fn []
                        ;; copyFile is not atomic, unlike rename, but is still a short operation and has the nice side effect of creating a backup file
                        ;; If copy fails, by default, node.js deletes the destination file (index.transit): https://nodejs.org/api/fs.html#fs_fs_copyfilesync_src_dest_mode
                        (when copy?
                          (.. fs (copyFileSync bkp-filepath filepath))
                          (let [mtime (.-mtime (.statSync fs filepath))]
                            (rf/dispatch-sync [:db/update-mtime mtime])
                            (rf/dispatch [:db/sync])))))
      (.pipe r w)))


  (def debounce-write-db
    (let [debounce-save-time (js/localStorage.getItem "debounce-save-time")]
      (if (nil? debounce-save-time)
        (let [debounce-save-time 15]
          (js/localStorage.setItem "debounce-save-time" debounce-save-time)
          (debounce write-db (* 1000 debounce-save-time)))

        (let [debounce-save-time (js/Number debounce-save-time)]
          (debounce write-db (* 1000 debounce-save-time))))))


  (defn write-bkp
    []
    (write-db false))


  (rf/reg-fx
    :fs/write!
    (fn []
      (debounce-write-db true))))

