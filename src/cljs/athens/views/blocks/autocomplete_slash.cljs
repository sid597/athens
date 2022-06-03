(ns athens.views.blocks.autocomplete-slash
  (:require
    ["/components/Block/Autocomplete" :refer [Autocomplete AutocompleteButton]]
    [athens.events.inline-search :as inline-search.events]
    [athens.subs.inline-search :as inline-search.subs]
    [athens.views.blocks.textarea-keydown :as textarea-keydown]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.ratom :as ratom]))


(defn slash-item-click
  [block item]
  (let [id        (str "#editable-uid-" (:block/uid block))
        target    (.. js/document (querySelector id))]
    (textarea-keydown/auto-complete-slash target item)))


(defn slash-menu-el
  [_block last-event]
  (let [inline-search-type (rf/subscribe [::inline-search.subs/type])
        inline-search-index (rf/subscribe [::inline-search.subs/index])
        inline-search-results (rf/subscribe [::inline-search.subs/results])
        open? (ratom/reaction (= @inline-search-type :slash))]
    (fn [block _last-event _state]
      [:> Autocomplete {:event @last-event
                        :isOpen @open?
                        :onClose #(rf/dispatch [::inline-search.events/close!])}
       (when @open?
         (doall
           (for [[i [text icon _expansion kbd _pos :as item]] (map-indexed list @inline-search-results)]
             [:> AutocompleteButton {:key     text
                                     :id      (str "dropdown-item-" i)
                                     :command kbd
                                     :isActive (when (= i @inline-search-index) "isActive")
                                     :onClick (fn [_] (slash-item-click block item))}
              [:<>
               [(r/adapt-react-class icon) {:boxSize 6 :mr 3 :ml 0}]
               text]])))])))
