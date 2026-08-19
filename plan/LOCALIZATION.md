# Localization

Flow Lens ships English and Japanese. `FlowLensBundleParityTest` enforces that
both bundles hold the same keys; this document covers the half a test cannot
check — that the same idea is called the same thing everywhere.

Fixed during the v1.0 Japanese pass, after four terminology drifts were found in
one reading of the bundle. Each had a cause worth remembering, so the reasons
are kept beside the rulings.

---

## 1. Glossary

| Internal / English | 日本語 |
|---|---|
| call | 呼び出し |
| **call site** | **呼び出し箇所** |
| **caller** | **呼び出し元** (reserved — see §2) |
| target / declaration | 呼び出し先 / 宣言 |
| expand | 展開 |
| collapse | 折りたたみ |
| analyzed | 解析済み |
| **not entered** | **未解析** (never 未展開 — see §3) |
| callable body | 関数・メソッド・ラムダなどの本体 |
| callback | コールバック |
| unresolved | 解決できませんでした / 未解決 |
| unknown timing | 実行タイミング不明 |
| frame | フレーム |
| node | ノード |
| entry point | エントリポイント |
| dispatch | ディスパッチ |
| ambiguous | 曖昧 |

---

## 2. 呼び出し元 is reserved for `caller`

In static analysis, `caller` and `call site` are different things:

- **呼び出し元** — the callable that performs the call.
- **呼び出し箇所** — the place in the source where `foo()` is written.

Flow Lens navigates to the second. Until v1.0 the Japanese bundle used
呼び出し元 for the button and 呼び出し箇所 in that button's own description,
so one feature had two names.

Two reasons the ruling matters beyond tidiness. IntelliJ's own Japanese pack
uses 呼び出し元 for Call Hierarchy's callers, so the word already means
something else in the same window. And reverse analysis — which callables reach
*this* one — is a v1.x candidate (`PLAN.md` §17). If it ships, it needs
呼び出し元, and it will need the word to still be free.

---

## 3. 展開 belongs to the canvas, not to the analysis

The canvas has an expand/collapse control, and the analyzer has a depth limit.
They are unrelated:

- a **collapsed** card was analyzed and is not being shown;
- a **depth-limited** card was never analyzed at all.

English keeps them apart on its own — "Expand / Collapse" against "Not entered".
Japanese does not, if the second is written 未展開: a reader sees 展開 in both
and concludes one is the undo of the other. It is 未解析.

**Rule: 展開 and 折りたたみ appear only in strings about the display control.**
Anything about what the analyzer did or did not do uses 解析 / 未解析.

---

## 4. Not every callable is a method

Flow Lens analyzes Java methods, Kotlin functions and lambdas, and Go functions
and closures. Strings that said メソッド were describing the v0.1 product.

Translating `callable` literally — 呼び出し可能要素 — reads as machine output.
Name the cases instead: **関数・メソッド・ラムダ本体**, or the subset a given
string is actually about. A pin, for instance, cannot mark a lambda, so
`flow.action.toggle.pin.description` says 関数・メソッド and stops there.

The English side had the same staleness (`One method body could not be
analyzed`) and was corrected with it. Fixing only the translation would have
left the original wrong.

---

## 5. A count includes callbacks

Since v0.5 a callback body hits the depth limit like a call does, so
「深さ上限で入らなかった呼び出し」 is too narrow. The status line says 項目 —
abstract, but it holds both without claiming either.

Anywhere a count could span calls *and* callbacks, do not name one of them.

---

## 6. When adding a string

1. Add it to both bundles in the same commit. The parity test fails otherwise,
   which is the point.
2. Check this glossary before inventing a word. If the concept is not here and
   is not obvious, add a row rather than deciding twice.
3. Read the Japanese where it lands, not in the properties file. Length and
   tone are properties of the card, not of the string.

   Do **not** get there by passing `-Duser.language=ja` to `runIde`. That
   triggers the platform's language-plugin detection, which offers to install a
   language pack and then restarts the IDE — and a Gradle-launched sandbox
   cannot restart itself, so the run dies with exit code 2. The locale has to
   come from the machine, or from a language pack installed into the sandbox.
