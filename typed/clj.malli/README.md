<!-- DO NOT EDIT! Instead, edit `dev/resources/root-templates/typed/clj.malli/README.md` and run `./script/regen-selmer.sh` -->
# typed.clj.malli

<a href='https://typedclojure.org'><img src='../../doc/images/part-of-typed-clojure-project.png'></a>

<p>
  <a href='https://www.patreon.com/ambrosebs'><img src='../../doc/images/become_a_patron_button.png'></a>
  <a href='https://opencollective.com/typedclojure'><img src='../../doc/images/donate-to-our-collective.png'></a>
</p>

Malli integration.

## Releases and Dependency Information

Latest stable release is 1.0.17.

* [All Released Versions](https://clojars.org/org.typedclojure/typed.clj.malli)

[deps.edn](https://clojure.org/reference/deps_and_cli) JAR dependency information:

```clj
  org.typedclojure/typed.clj.malli {:mvn/version "1.0.17"}
```

[deps.edn](https://clojure.org/reference/deps_and_cli) Git dependency information:

- Note: use `clj -Sresolve` to resolve the `:tag` to a `:sha`

```clj
  org.typedclojure/typed.clj.malli
  {:git/url "https://github.com/typedclojure/typedclojure"
   :deps/root "typed/clj.malli"
   :tag "1.0.17"}
```

[Leiningen](https://github.com/technomancy/leiningen) dependency information:

```clojure
[org.typedclojure/typed.clj.malli "1.0.17"]
```

[Maven](https://maven.apache.org/) dependency information:

```XML
<dependency>
  <groupId>org.typedclojure</groupId>
  <artifactId>typed.clj.malli</artifactId>
  <version>1.0.17</version>
</dependency>
```

## Documentation

[API Reference](https://api.typedclojure.org/latest/typed.clj.malli/index.html)

## Usage

```clojure
(require '[malli.core :as m]
         '[typed.clj.malli :as tm]
         '[clojure.core.typed :as t])
```

### Validation 

[typed.clj.malli/validate](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-validate) is a wrapper for `malli.core/validate`.

See also:
- [typed.clj.malli/validator](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-validator)
- [typed.clj.malli/defvalidator](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-defvalidator)
- [typed.clj.malli/explain](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-explain)

```clojure
;; normally, `validate` takes schemas
(m/validate int? "1")
;=> false

(m/validate int? 1)
;=> true

;; the typed variant (note `tm` namespace) takes Typed Clojure types
(tm/validate t/AnyInteger "1")
;=> false

(tm/validate t/AnyInteger 1)
;=> true

;; it can be used to coerce values in a way the type checker understands
(t/cf (fn [x]
        {:pre [(tm/validate t/AnyInteger x)]}
        (inc x))
      [t/Any :-> Number])
;=> [[t/Any -> Number] {:then tt, :else ff}]

;; tm/explain works oppositely
(t/cf (fn [x]
        {:pre [(not (tm/explain t/AnyInteger x))]}
        (inc x))
      [t/Any :-> Number])
;=> [[t/Any -> Number] {:then tt, :else ff}]
```

### Parsing values

[typed.clj.malli/parse](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-parse) is a wrapper for `malli.core/parse`.

See also:
- [typed.clj.malli/parser](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-parser)
- [typed.clj.malli/defparser](https://api.typedclojure.org/latest/typed.clj.malli/typed.clj.malli.html#var-defparser)

```clojure
;; normally, `parse` takes schemas
(m/parse [:orn
          [:int int?]
          [:bool boolean?]]
         1)
;=> [:int 1]

;; `tm/parse` takes Typed Clojure types and infers the return value.
;; use `::tm/name` to specify `:orn` tags (bare union currently unsupported).
(t/defalias ParsableIntOrBool
  (t/U ^{::tm/name :int} t/AnyInteger
       ^{::tm/name :bool} t/Bool))

(tm/parse ParsableIntOrBool true)
;=> [:bool true] : (t/U
;                    (t/HVec [(t/Val :int) t/AnyInteger])
;                    (t/HVec [(t/Val :bool) t/Bool])
;                    (t/Val :malli.core/invalid))

;; example using `tm/parse` to type check a function
(t/cf
  (t/fn [a :- ParsableIntOrBool] :- t/AnyInteger
    (let [prs (tm/parse ParsableIntOrBool a)]
      (assert (not= ::m/invalid prs))
      (case (first prs)
        :int (second prs)))))
;=> [[ParsableIntOrBool -> t/AnyInteger] {:then tt, :else ff}]
```

## License

Copyright © Ambrose Bonnaire-Sergeant, Rich Hickey & contributors.

Licensed under the EPL (see the file epl-v10.html).