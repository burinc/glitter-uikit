(ns hooks.jolt-ffi
  "clj-kondo hook for jolt.ffi/defcfn.

  `defcfn` binds a C symbol to a Clojure var:

      (ffi/defcfn objc-get-class \"objc_getClass\" [:string] :pointer)

  clj-kondo cannot see through the macro, so without this hook every bound
  name is an `Unresolved symbol` inside glitter-uikit.ffi and an `Unresolved
  var: u/…` at each call site in glitter-uikit.appkit / glitter-uikit.widget
  (both require it as `u`) — enough noise to make the linter useless as a
  gate.

  The hook rewrites the form into a `defn` of the same name whose parameter
  count matches the C argument-type vector, and whose body is a literal of
  the declared C return type. That buys three things clj-kondo could not
  otherwise know:

    * the var exists (kills the false positives),
    * its arity — passing the wrong number of arguments to a binding is
      exactly the FFI mistake that otherwise surfaces only as a native
      crash,
    * its return type, so e.g. `(+ 1 (u/objc-msg-send-0i64 …))` type-checks.

  Return-type mapping is deliberately conservative: numeric C types
  (including `:pointer`) become a number, `:string` a string, and
  everything else (`:void`) nil.

  This DEVIATES from b12n-rljlt's original in one deliberate way:
  `:pointer` maps to a number here, not nil. rljlt's raylib pointers are
  opaque handles only ever passed back into other untyped `ffi/*` calls, so
  nil cost nothing. glitter-uikit.ffi's own ns docstring states pointers are
  plain machine addresses (jolt numbers) too — every `objc-msg-send-*`
  binding returning `:pointer` is a raw address, not an opaque handle, so a
  nil-typed stub would trip a spurious `type-mismatch` the moment call-site
  code does arithmetic on one. `:size_t` (underscore, C-style — used by
  `objc-allocate-class-pair`'s `extraBytes` argument) and `:size-t` are both
  recognized below in case a future binding uses either spelling.

  Adapted from b12n-rljlt's `.clj-kondo/hooks/jolt_ffi.clj` (same
  `jolt.ffi/defcfn` macro, same false-positive problem) — see that repo for
  the original raylib-flavored version of this comment and the un-adapted
  `:pointer -> nil` mapping."
  (:require [clj-kondo.hooks-api :as api]))

;; CORRECTION (pre-commit lint run, this task): glitter-gl's set covered its
;; own defcfn vocabulary but omitted :int64 and :char — the two return types
;; glitter-uikit.ffi's objc_msgSend bindings actually declare most (alongside
;; :double/:pointer/:string/:void). Left uncovered, every :int64/:char
;; binding fell to the :else branch below and stubbed as nil, which then
;; false-positived "Expected: number, received: nil" wherever the call
;; result reached arithmetic or a numeric predicate — e.g.
;; glitter-uikit.widget/arranged-index's `(neg? i)` on
;; glitter-uikit.ffi/stack-index-of!'s `:int64`-returning objc_msgSend call.
(def ^:private numeric-ret
  #{:int :uint :long :ulong :short :ushort :byte :ubyte :float :double
    :int64 :char :size_t :size-t :pointer})

;; CORRECTION (Circle Drawer arc): a by-value AGGREGATE return
;; (`[:by-value [:struct ...]]`, used by the CGPoint-returning objc_msgSend
;; bindings) takes an extra FIRST argument at the call site — jolt writes the
;; returned struct into a caller-supplied buffer, the convention its own
;; aggregate test uses: a fn declared with 3 argument types is called with 4.
;; Without this, every such call false-positived as "called with N+1 args but
;; expects N", which is an ERROR not a warning, so it broke the lint gate.
(defn- by-value-return?
  [ret]
  (let [k (when ret (api/sexpr ret))]
    (and (vector? k) (= :by-value (first k)))))

(defn- ret-node
  "A literal whose inferred type matches the declared C return type."
  [ret]
  (let [k (when ret (api/sexpr ret))]
    (cond
      (contains? numeric-ret k) (api/token-node 0)
      (= :string k)             (api/string-node "")
      :else                     (api/token-node nil))))

(defn defcfn
  [{:keys [node]}]
  (let [[_defcfn name-node _c-symbol arg-types ret] (:children node)]
    ;; Only rewrite the shape we understand; anything else falls through to
    ;; the default analysis rather than silently interning a wrong var.
    (if (and name-node arg-types (api/vector-node? arg-types))
      (let [declared (map-indexed (fn [i _] (api/token-node (symbol (str "_arg" i))))
                                  (:children arg-types))
            ;; the implicit output buffer, for an aggregate return only
            params (if (by-value-return? ret)
                     (cons (api/token-node '_out) declared)
                     declared)
            expanded (api/list-node
                      [(api/token-node 'clojure.core/defn)
                       name-node
                       (api/vector-node (vec params))
                       (ret-node ret)])]
        {:node (with-meta expanded (meta node))})
      {:node node})))
