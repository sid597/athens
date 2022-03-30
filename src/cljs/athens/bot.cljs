(ns athens.bot
  (:require [cljs-http.client :as http]
            [re-frame.core :as rf]
            [athens.common-db :as common-db]
            [athens.db :as db]
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))


(rf/reg-event-fx
  :prepare-message
  (fn [_ [_ uid author action action-data]]
    "action-data for:
    Comments: comment string)
    Mention:  mention who, mention message)
    Board:    move state, block content"
    (println "prepare message" uid author action action-data
             (:comment/string action-data))
    (let [full-url                  (.. js/window -location -href)
          base-url                  (first (clojure.string/split full-url "#"))
          block-parent-url          (str base-url "#/page/" uid)
          mention-athens-team       (str "<@&" "858004385215938560]" ">")
          message                   {"message"
                                     (cond
                                       (= action
                                          :comment) (str author " wrote a comment: " "\""
                                                         (:comment/string action-data)
                                                         "\"" " — " block-parent-url)
                                       ; " — " mention-athens-team)
                                       (= action
                                          :mention) (str "mentioned")
                                       (= action
                                          :board)   (str "board movement"))}]
      (println "message" message)
      {:fx [[:dispatch [:notification/send message]]]})))


(rf/reg-event-fx
  :move-ticket
  (fn [{:keys [db]} [_ block-uid target-uid]]
    (println "block uid" block-uid "target-uid" target-uid)
    (let [block-uid-parent (common-db/get-parent @db/dsdb [:block/uid block-uid])]
      (println "block" block-uid-parent))))


(rf/reg-event-fx
  :notification/send
  (fn [_ [_ message]]
    (println "send notification" message)
    {:discord-bot message}))

(rf/reg-fx
  :discord-bot
  (fn [message]
    (println "discord bot" message)
    (go (let [response (<! (http/post "https://3txhfpivzk.execute-api.us-east-2.amazonaws.com/Prod/hello/"
                                      {:query-params message}))]
          (prn  (:body response))) ;; print the response's body in the console
        {})))
