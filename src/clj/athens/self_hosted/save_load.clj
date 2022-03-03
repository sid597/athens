(ns athens.self-hosted.save-load
  (:gen-class)
  (:require
    [athens.common.logging :as log]
    [athens.self-hosted.components.fluree :as fluree-comp]
    [athens.self-hosted.event-log :as event-log]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [clojure.tools.cli :refer [parse-opts]]
    [fluree.db.api :as fdb]))


(defn exit
  [status msg]
  (println msg)
  (System/exit status))

(defn save-log
  [args]
  (let [{:keys [fluree-address
                filename]} args
        comp               (fluree-comp/create-fluree-comp fluree-address)
        events             (event-log/events comp)]
    ;; Save the ledger on file
    ;; TODO : Who should discover the name for file to save?
    (spit filename
          (pr-str (doall events)))
    (-> comp :conn-atom deref fdb/close)))


(defn recover-log
  [args]
  (let [{:keys [fluree-address
                filename]} args
        comp               (fluree-comp/create-fluree-comp fluree-address)
        events             (event-log/recovered-events comp)]
    (spit filename
          (pr-str (doall events)))
    (-> comp :conn-atom deref fdb/close)))


(defn load-log
  [args]
  (let [{:keys [fluree-address
                filename]}     args
        comp                   (fluree-comp/create-fluree-comp fluree-address)
        conn                    (-> comp
                                    :conn-atom
                                    deref)
        previous-events        (edn/read-string (slurp filename))
        ledger-exists?         (seq  @(fdb/ledger-info conn event-log/ledger))
        progress               (atom 0)]

    ;; Delete the current ledger
    (if ledger-exists?
      (do
        @(fdb/delete-ledger conn
                            event-log/ledger)
        (log/warn "Please restart the fluree docker."))
      ;; Create the ledger again
      (do
        (event-log/ensure-ledger! comp [])
        (doseq [[id data] previous-events]
          (swap! progress inc)
          (log/info "Processing" (str "#" @progress) id)
          (event-log/add-event! comp id data 5000 10000)
          (if (= 0 (rem @progress 1000))
            (do (log/info "Pausing for 15s after 1000 events")
                (async/<!! (async/timeout 15000)))
            (async/<!! (async/timeout 50))))))))


(defn get-log-with-multiple-of-x
  [args]
  (let [{:keys [fluree-address
                filename
                x-events
                save-location
                stop-after]}    args
        comp                    (fluree-comp/create-fluree-comp fluree-address)
        conn                    (-> comp
                                    :conn-atom
                                    deref)
        previous-events         (edn/read-string (slurp filename))
        ledger-exists?          (seq  @(fdb/ledger-info conn event-log/ledger))
        progress                (atom 0)]
    (println x-events save-location)

    ;; Delete the current ledger
    (if ledger-exists?
      (do
        @(fdb/delete-ledger conn
                            event-log/ledger)
        (log/warn "Please restart the fluree docker."))
      ;; Create the ledger again
      (do
        (event-log/ensure-ledger! comp [])
        (doseq [[id data] previous-events]
          (swap! progress inc)
          (log/info "Processing" (str "#" @progress) id)
          (event-log/add-event! comp id data 5000 10000)
          (if (= 0 (rem @progress x-events))
            (do (log/info "Pausing for 15s after " x-events " events")
                (async/<!! (async/timeout 15000))
                (log/info "Saving the current log ====")
                (save-log {:fluree-address fluree-address
                           :filename       (str save-location @progress)})
                (log/info "Current log saved ===="))
            (async/<!! (async/timeout 50)))
          (if (= @progress stop-after)
              (exit 1 (str "Processed " stop-after " so stopping the load"))))))))


(def cli-options
  ;; An option with a required argument
  [["-a" "--fluree-address ADDRESS" "Fluree address"
    :default "http://localhost:8090"]
   ["-f" "--filename FILENAME" "Name of the file to be saved or loaded"
    :default []]
   ["-sl" "--save-location SAVELOCATION" "Location where to save the files for `get-log-with-multiple-of-x`"
    :default "/tmp/athens/"]
   ["-x" "--x-events XEVENTS" "Timeout/save log after loading `x` events"
    :default 1000
    :parse-fn #(Integer/parseInt %)]
   ["-s" "--stop-after STOPAFTER" "Stop loading events after ..."
    :default 1000
    :parse-fn #(Integer/parseInt %)]
   ["-h" "--help"]])


(defn usage
  [options-summary]
  (->> ["Save or load a ledger"
        ""
        "Usage: program-name [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  save     Save the current ledger"
        "  load     Load the passed ledger"
        "  recover  Recover failed transactions from the current ledger"
        ""
        "Please refer to the manual page for more information."]
       (string/join \newline)))


(defn error-msg
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))


(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      ;; help => exit OK with usage summary
      (:help options)           {:exit-message (usage summary) :ok? true}
      ;; errors => exit with description of errors
      errors                    {:exit-message (error-msg errors)}
      ;; custom validation on arguments
      (and (= 1 (count arguments))
           (#{"save" "load" "recover" "get-x-events-log"}
            (first arguments))) {:action (first arguments) :options options}
      ;; failed custom validation => exit with usage summary
      :else                     {:exit-message (usage summary)})))


(defn -main
  [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do
        (case action
          "save"    (save-log options)
          "load"    (load-log options)
          "recover" (recover-log options)
          "get-x-events-log" (get-log-with-multiple-of-x options))
        (System/exit 0)))))
