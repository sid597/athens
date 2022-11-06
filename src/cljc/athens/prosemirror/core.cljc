(ns athens.prosemirror.core
  (:require
    ["prosemirror-state" :as pm-state       :refer [EditorState]]
    ["prosemirror-view"  :as pm-view        :refer [EditorView]]
    ["prosemirror-model" :as pm-model       :refer [Schema DOMParser]]
    ["prosemirror-schema-basic" :as pm-schema :refer [schema]]
    ["prosemirror-schema-list"   :refer [addListNodes]]
    ["prosemirror-example-setup" :refer [exampleSetup]]))



#_(def state
    (.create EditorState #js {:schema schema}))

#_(def pview
    (EditorView.  js/document.body #js {:state state}))




(def mySchema
  (pm-model/Schema. #js {:nodes (addListNodes (.. schema -spec -nodes)
                                              "paragraph block*"
                                              "block")
                         :marks  (.. schema -spec -marks)}))
(def pview
   (EditorView. (js/document.querySelector "#editor")
                {:state {:schema schema} #_(.create EditorState #js{:doc (.. DOMParser (fromSchema mySchema) (parse (js/document.querySelector "#content")))
                                                                    :plugins (exampleSetup {:schema mySchema})})}))


(println (.. DOMParser (fromSchema mySchema) (parse (js/document.querySelector "#content"))))