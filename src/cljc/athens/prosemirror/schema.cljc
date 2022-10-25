(ns athens.prosemirror.schema
  (:require
    ["prosemirror-model" :refer [Schema, NodeSpec, MarkSpec, DOMOutputSpec]]))

;; Nodes
;; For nodes that aren't text or top-level nodes,
;; it is necessary to provide toDOM methods, so that the editor can render them, and parseDOM values, so that they can be parsed.

;; Every schema must at least define a top-level node type (which defaults to the name "doc",
;; but you can configure that), and a "text" type for text content.

;; To be able to easily refer to both our inline nodes, they are tagged as a group (also called "inline").
;; Text is unmarked text/plain text without any markings. E.g this is text: "Heya bitches" and this is not "Heya *bitches*"

;; Marks
;; By default, nodes with inline content allow all marks defined in the schema to be applied to their children.
;; Any style that applies to all the inline nodes goes into the marks otherwise we move them to inline node.
;; Whether this mark should be active when the cursor is positioned at its end.
;; Mark specs can include additional properties that can be inspected through MarkType.spec when working with the mark.

;; Parse
;; You'll also often need to parse a document from DOM data, for example when the user pastes or drags something into the editor.




;; What we need in Athens
;; ==== Inline nodes

;; - Inline basic text input
(def text-node
  {:text {:group "inline"}})

;; - Hard break block
(def hard-break-node
  {:hard-break {:inline     true
                :selectable false
                :group      "inline"
                :toDOM      #(["br"])
                :parseDOM   [{:tag "br"}]}})

;; ==== Block nodes
(def doc-node
  ;; block+ means all the nodes in the group "block"
  ;; blocks are then made of inline nodes, therefore the doc contains all the nodes.
  {:doc {:content "block+"}})
;; - Paragraph
;; A paragraph is made of inline nodes like plain text, em, italics
(def paragraph-node
  ;; only inline not inline+ because inline+ can have breaks in it which would not make the resulting structure a paragraph.
  ;; A paragraph has line breaks before and after the text.
  {:paragraph {:content  "inline"
               :group    "block"
               :toDOM    #(["p" 0])
               :parseDOM [{:tag "p"}]}})
;; - Heading h1-h6
(def heading-node
  ;; 0 or more inline nodes
  {:heading {:attrs    {:level {:default 1}}
             :content  "inline*"
             :group    "block"
             :toDOM    (fn [node]
                         [(str "h"
                               (-> node
                                   :attrs
                                   :level))
                          0])
             :parseDOM [{:tag "h1", :attrs {:level 1}}
                        {:tag "h2", :attrs {:level 2}}
                        {:tag "h3", :attrs {:level 3}}
                        {:tag "h4", :attrs {:level 4}}
                        {:tag "h5", :attrs {:level 5}}
                        {:tag "h6", :attrs {:level 6}}]}})
;; - Code block
(def code-block-node
  ;; We don't allow marks that come with the inline nodes, therefore we use text*
  {:code-block {:content  "text*"
                :group    "block"
                :marks    ""
                :code     true
                :defining true
                :toDOM    #(["pre" ["code" 0]])
                :parseDOM [:tag                "pre"
                           :preserveWhitespace "full"]}})
;; - Block quote
(def block-quote-node
  ;; Block quote is more than 1 block
  {:block-quote {:content  "block+"
                 :group    "block"
                 :defining true
                 :toDOM    #(["blockquote"])
                 :parseDOM [{:tag "blockquote"}]}})
;; - Latex
;; TODO later
(def latex-node
  {:latex {:content  "inline"
           :group    "block"
           :toDOM    #()
           :parseDOM #()}})

;; - Bullet list?
;; - Ordered list?
;; ==== Marks

;; Inclusive
;; Whether this mark should be active when the cursor is positioned at its end (or at its start when that is also the
;; start of the parent node). Defaults to true.


;; - Emphasis
(def emphasis-mark
  {:emphasis {:toDOM     #(["em" 0])
              :parseDOM  [{:tag "em"}
                          {:tag "i"}
                          {:style "font-style=italic"}]}})
;; - Link
(def link-mark
  {:link {:attrs     {:href {}
                      :title {:default nil}}
          :toDOM     (fn [node]
                       (let [{href :href
                              title :title} node]
                         ["a" {href title} 0]))

          :parseDOM   [{:tag      "a[href]"
                        :getAttrs (fn [dom]
                                    (.getAttribute dom))}]}})
;; - Highlight
(def highlight-mark
  {:hightlight {:toDOM     #()
                :parseDOM  #()}})

;; - Underline
(def underline-mark
  {:underline {:toDOM     #(["u" 0])
               :parseDOM  [{:tag "u"}]}})
;; - Strikethrough
(def strikethrough-mark
  {:strikethrough {:toDOM     #(["s" 0])
                   :parseDOM  [{:tag "s"}]}})
;; - code
(def code-mark
  {:code {:toDOM     #(["code" 0])
          :parseDOM  [{:tag "code"}]}})

;; - strong
(def strong-mark
  {:strong {:toDOM #(["strong" 0])
            :parseDOM [{:tag "strong"
                        :getAttrs (fn [node]
                                    (not= (-> node
                                              :style
                                              :fontWeight)
                                          (and "normal"
                                               nil)))}]}})


(defn basic-editor-schema
  []
  (Schema {:nodes (conj text-node
                        hard-break-node
                        doc-node
                        paragraph-node
                        heading-node
                        code-block-node
                        block-quote-node)
           :marks (conj emphasis-mark
                        highlight-mark
                        underline-mark
                        strikethrough-mark
                        code-mark
                        strong-mark)}))