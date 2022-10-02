(ns athens.views.right-sidebar.core
  (:require
    ["/components/Block/Taskbox"    :refer [Taskbox]]
    ["/components/Empty/Empty" :refer [Empty EmptyIcon EmptyMessage]]
    ["/components/Empty/Empty"      :refer [Empty
                                            EmptyIcon
                                            EmptyTitle
                                            EmptyMessage]]
    ["/components/Icons/Icons" :refer [RightSidebarAddIcon  CheckboxIcon]]
    ["@chakra-ui/react" :refer [ButtonGroup Button Text Link Flex VStack]]
    ["framer-motion" :refer [AnimatePresence motion]]
    ["/components/Layout/List" :refer [List]]
    ["/components/Layout/RightSidebar" :refer [RightSidebar
                                               SidebarItemBody
                                               SidebarItemBody
                                               SidebarItemContainer
                                               SidebarItemHeader
                                               SidebarItemHeaderToggle
                                               SidebarItemTitle]]
    [athens.parse-renderer          :as parse-renderer]
    [athens.views.right-sidebar.events]
    [athens.types.query.shared :as query-shared]
    [athens.types.tasks.shared      :as tasks-shared]
    [athens.types.tasks.handlers    :as task-handlers]
    [athens.router                  :as router]
    [athens.views.right-sidebar.shared :as shared]
    [athens.reactive :as reactive]
    [athens.db                           :as db]
    [athens.common-db                    :as common-db]
    [athens.views.right-sidebar.subs]
    [reagent.core :as r]
    [re-frame.core :as rf]))


(def name-from-route
  {:home "Daily Notes"
   :graph "Graph"})


(def selected-tab (r/atom :properties))
(def details-open (r/atom true))



(defn sidebar-task-el
  [task]
  (let [task-uid      (get task ":block/uid")
        task-title    (get task ":task/title")
        status-uid    (get task ":task/status")
        status-options (->> (tasks-shared/find-allowed-statuses)
                            (map (fn [{:block/keys [string]}]
                                   string)))
        status-block  (reactive/get-reactive-block-document [:block/uid status-uid])
        status-string (:block/string status-block)]
    [:> Flex {:display "inline-flex"
              :as (.-div motion)
              :initial {:opacity 0
                        :height 0}
              :animate {:opacity 1
                        :height "auto"}
              :exit {:opacity 0
                     :height 0}
              :align   "baseline"
              :gap     1}
     [:> Taskbox {:position "relative"
                  :top      "3px"
                  :options status-options
                  :onChange #(task-handlers/update-task-status task-uid %)
                  :status   status-string}]
     [:> Link {:fontSize  "sm"
               :py 1
               :noOfLines 1
               ;; TODO: clicking on refs might take you to ref instead of task
               :onClick   #(router/navigate-uid task-uid %)}
      [parse-renderer/parse-and-render task-title task-uid]]]))


(defn tasks-list []
    (fn []
    (let [all-tasks          (->> (reactive/get-reactive-instances-of-key-value ":entity/type" "[[athens/task]]")
                                  (map query-shared/block-to-flat-map)
                                  (map query-shared/get-root-page))
          this-page          @(rf/subscribe [:current-route/page-title])
          fn-this-page       (fn [task]
                               (= this-page
                                  (get task ":task/page")))
          tasks-on-this-page (filterv fn-this-page all-tasks)]
      [:div
       (if (empty? tasks-on-this-page)
         [:> Empty {:size "sm" :pl 0 :py 2}
          [:> EmptyIcon {:Icon CheckboxIcon}]
          [:> EmptyTitle "All done"]
          [:> EmptyMessage "Tasks assigned to you will appear here."]]
         [:> VStack {:align "stretch" :spacing 1 :py 2}
          [:> AnimatePresence {:initial false}
           (for [task tasks-on-this-page]
             ^{:key (get task ":block/uid")}
             [sidebar-task-el task])]])])))


(defn current-item-details
  []
  (let [current-route-uid (rf/subscribe [:current-route/uid])
        route-uid              (rf/subscribe [:current-route/uid])
        route-name             (rf/subscribe [:current-route/name])
        current-page-title (rf/subscribe [:current-route/page-title])]
    (fn []
      (let [title (or @current-page-title
                      (common-db/get-block-string @db/dsdb @route-uid)
                      (name-from-route @route-name))
            current-type (if @current-route-uid :block :page)]
        [:> SidebarItemContainer {:key "test"}
         [:> SidebarItemHeader
          [:> SidebarItemHeaderToggle {:isOpen @details-open :onToggle #(reset! details-open (not @details-open))}
           [:> SidebarItemTitle "Details – " title]]]
         [:> SidebarItemBody {:isOpen @details-open}
          [:> ButtonGroup {:size "xs" :isAttached true :display "grid" :gridTemplateColumns "1fr 1fr 1fr"}
           [:> Button {:onClick #(reset! selected-tab :properties) :isActive (= @selected-tab :properties)} "Properties"]
           [:> Button {:onClick #(reset! selected-tab :tasks) :isActive (= @selected-tab :tasks)} "Tasks"]
           [:> Button {:onClick #(reset! selected-tab :refs) :isActive (= @selected-tab :refs)} "References"]]
           (case @selected-tab
             :properties [:div "Properties of " title]
             :refs [:div "References to " title]
             :tasks [tasks-list])]]))))


;; Components

(defn right-sidebar-el
  "Resizable: use local atom for width, but dispatch value to re-frame on mouse up. Instantiate local value with re-frame width too."
  [open? items on-resize rf-width]
  [:> RightSidebar
   {:isOpen open?
    :onResize on-resize
    :rightSidebarWidth rf-width}
   (if (empty? items)
     [:> Empty {:size "lg" :mt 4}
      [:> EmptyIcon {:Icon RightSidebarAddIcon}]
      [:> EmptyMessage "Hold " [:kbd "shift"] " when clicking a page link to view the page in the sidebar."]]
     
     [:<>
      [current-item-details]
      [:> List {:items              (shared/create-sidebar-list items)
                :onUpdateItemsOrder (fn [source-uid target-uid old-index new-index]
                                      (rf/dispatch [:right-sidebar/reorder source-uid target-uid old-index new-index]))}]])])





(defn right-sidebar
  []
  (let [open? (shared/get-open?)
        items (shared/get-items)
        on-resize #(rf/dispatch [:right-sidebar/set-width %])
        width @(rf/subscribe [:right-sidebar/width])]
    [right-sidebar-el open? items on-resize width]))
