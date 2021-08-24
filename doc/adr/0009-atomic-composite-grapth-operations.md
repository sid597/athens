# 9. Atomic/Composite Grapth Operations

Date: 2021-08-18

## Status

Draft

Supercedes [7. Lan-Party Datascript Events](0007-lan-party-datascript-events.md)

- **Consequences**
    - We'll have additional work of Porting Semantic Events to use Atomic Graph Ops and compositions.
    - We have working `:paste` & `:block/save` events, they are broken not ported now.
    - We'll have smaller amount of tests in order to provide correctness guarantees
    - 
    - What becomes easier or more difficult to do and any risks introduced by the change that will need to be mitigated.
- **Additional Resources**
    - ((List of Atomic Graph Operations:))

## Context

We've made an effort to support remote execution of Semantic Events.

These events where direct port of `events.cljs` which where mostly informed by UI concerns.

Result is that we have a lot of different events that are doing same atomic operations over and over,
but are not really reusing these Atomic Ops.

Implementing `:block/save` that sometimes is just updating `:block/string` and other times also needs to `:page/create`.
`:paste` event is another that will be super hard to implement w/o Atomic Graph Operations.


## Decision

We have two kinds of events to modify graph:
- ⚛️ Atomic Graph Ops
    - Not divisible Graph Ops
    - Operations like create new block, create page, save block
- ⎄ Composite Graph Ops
    - Collection of events to be executed on the graph
        - [ ] #[[Open Question]] How to represent multiple events
    - Like `:block/save` when new link is discovered, should produce also `:page/create` event

Atomic events should follow same Common Events model as it's happening now

Composite events are really 2 cases and should be tackled as separate

Use ((1. Change Event Protocol to accept list of events)) & ((2 .Extend common event type by adding consequence events))
- We need both:
  - ((Event type Composite Event, that contains list of Atomic Graph Ops))
  - ((Consequence Event is 2 things))

## Consequences

We'll have additional work of Porting Semantic Events to use Atomic Graph Ops and compositions.

We have working `:paste` & `:block/save` events, they are broken not ported now.

We'll have smaller amount of tests in order to provide correctness guarantees


## Additional Resources

### Catalog of operations

- Page Ops
    - **`:page/create`**
        - ((⚛️ Atomic Graph Ops))
        - *Input*
            - `title` - Page title page to be created
            - `page-uid` - `:block/uid` of page to be created
            - `block-uid` - `:block/uid` of 1st block to be created in page to be created
    - **`:page/rename`**
        - ((⚛️ Atomic Graph Ops))
        - *Input*
            - `page-uid` - `:block/uid` of page to be renamed
            - `new-name` - Page should have this name after operation
            - `old-name` - ^^To Remove^^ This is accidental, and shouldn't be provided
    - **`:page/merge`**
        - ((⚛️ Atomic Graph Ops))
        - *Input*
            - `page-uid` - `:block/uid` of page to be merged into `new-page`
            - `new-page` - page name of a page we'll merge contents of `page-uid` page into
            - `old-name` - ^^To Remove^^ This is accidental, and shouldn't be provided
    - **`:page/delete`**
        - ((⚛️ Atomic Graph Ops))
        - *Input*
            - `page-uid` - `:block/uid` of the page to be deleted
- Block Ops
    - ⚛️**`:block/new`**
        - ((⚛️ Atomic Graph Ops))
        - *Input*
            - `parent-uid` - `:block/uid` of parent block (or page)
            - `block-uid` - `:block/uid` of new block to be created
            - `block-order` - `:block/order` of new block to be created
                - Currently it's only `int
                - We could extend it to allow `int` and 2 keywords `:first` & `:last` (to say that we want this new block to be 1st among the children of `parent-uid` or last)
    - ⚛️**`:block/save`**
        - ((⚛️ Atomic Graph Ops))
        - *Input*
            - `block-uid` - `:block/uid` of block to be saved
            - `new-string` - new value of `:block/string` to be saved
            - `add-time?` - ^^To Remove^^ , we should always update `:edit/time` 
    - {{[[TODO]]}} ⚛️**`:block/open`**
        - ((⚛️ Atomic Graph Ops))
        - 
    - **`:block/add-child`**
        - ((⎄ Composite Graph Ops))
        - It's a special case of ((⚛️**`:block/new`**)) where block is put as 1st child
    - ⎄**`:block/open-block-add-child`**
        - ((⎄ Composite Graph Ops))
        - *Input*
            - `parent-uid` - `:block/uid` of parent to be open
            - `block-uid` - `:block/uid` of child block to be added
        - Composition of:
            - ((⚛️**`:block/new`**))
            - (({{[[TODO]]}} ⚛️**`:block/open`**))
    - {{[[TODO]]}} **`:block/split`**
        - Maybe ((⎄ Composite Graph Ops))
        - *Input*
    - {{[[TODO]]}} **`:block/split-to-children`**
        - Maybe ((⎄ Composite Graph Ops))
        - *Input*
    - {{[[TODO]]}} **`:block/indent`**
        - ((⚛️ Atomic Graph Ops))
    - {{[[TODO]]}} **`:block/indent-multi`**
        - ((⎄ Composite Graph Ops))
    - {{[[TODO]]}} **`:block/unindent`**
        - ((⚛️ Atomic Graph Ops))
    - {{[[TODO]]}} **`:block/unindent-multi`**
        - ((⎄ Composite Graph Ops))
    - {{[[TODO]]}} **`:block/bump-up`**
    - {{[[TODO]]}} **`:paste-verbatim`**
- Drop Ops
    - drop/child
    - drop/child-multi
    - drop/child-link
    - drop/different-parent
    - 
- Shortcut Ops
    - {{[[TODO]]}} **`:shortcut/add`**
    - {{[[TODO]]}} **`:shortcut/remove`**