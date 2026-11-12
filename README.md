# AppFunction Guard

**Android 16 lets AI agents call your app's functions. Guard is the lock, the doorbell, and the visitor log it forgot to include.**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#status)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2-7F52FF.svg)](https://kotlinlang.org)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-green.svg)](#requirements)
[![build](https://github.com/yangyang2857-rgb/appfunction-guard/actions/workflows/build.yml/badge.svg)](https://github.com/yangyang2857-rgb/appfunction-guard/actions/workflows/build.yml)

---

## The problem

Android 16 ships **AppFunctions** — a platform API that lets an AI agent call functions inside your app directly, without going through your UI. The platform gives you the plumbing:

- a way to register functions
- a way to describe them to the model
- a way to execute them

What it does **not** give you:

| Missing | Consequence |
|---|---|
| Per-function authorization | An agent that can call `getNote` can also call `deleteAllNotes` |
| Argument validation | The model hallucinates `limit: -1` and your code trusts it |
| Rate limiting | A retry loop hits your DB 4,000 times in a minute |
| Audit trail | You cannot answer "what did the assistant do to my data last night?" |
| Consent UX | The user has no idea an agent is about to touch their photos |
| Structured errors | The agent gets `IllegalArgumentException` and retries forever |

These are not hypothetical. Recent Android security research on AppFunctions reports three concrete risk classes: **privacy leakage** through tool outputs, **isolation bypass** through over-broad function exposure, and **LLM integrity attacks** via malicious tool descriptions. Every one of them is a missing-policy problem, not a missing-API problem.

Guard is that policy layer.

---

## What it does

A single interception point between the platform and your business logic:

```
agent
  │
  ▼
AppFunctionService.onExecuteFunction()
  │
  ├─ 1. caller allow-list      who is calling?
  ├─ 2. policy                 is this function allowed at all?
  ├─ 3. rate limit             token bucket, per function × caller
  ├─ 4. argument validation    generated at compile time by KSP
  ├─ 5. user confirmation      only when the policy asks for it
  ├─ 6. execute                your code, unchanged
  ├─ 7. redaction              strip PII before it reaches the model
  └─ 8. audit                  one record per call, never the raw args
```

Everything runs before your business code. Nothing is a decorator you can forget to apply.

---

## Install

```kotlin
dependencies {
    implementation("io.github.yangyang2857-rgb:guard-core:0.1.0-alpha3")
    ksp("io.github.yangyang2857-rgb:guard-ksp:0.1.0-alpha3")

    // optional: ready-made confirmation dialog
    implementation("io.github.yangyang2857-rgb:guard-ui:0.1.0-alpha3")
    // optional: audit records without pulling in Android
    implementation("io.github.yangyang2857-rgb:guard-audit:0.1.0-alpha3")
}
```

Published on Maven Central — `mavenCentral()` in `repositories` is all you need, no JitPack and no `includeBuild`.

---

## Quick start

```kotlin
// 1. Application.onCreate
GuardConfig.init(this)
GuardConfig.confirmationHandler = MyConfirmDialog()   // your UI, 3 lines

// 2. In your AppFunctionService
override fun onExecuteFunction(functionId: String, args: Bundle): Bundle =
    BundleCodec.toBundle(
        AfGuard.intercept(functionId, args) { call ->
            when (call.functionId) {
                "listNotes"  -> repo.recent(call.intOr("limit", 20))
                "deleteNote" -> repo.delete(call.requireString("id"))
                else         -> null
            }
        }
    )

// 3. Declare the policy at the function
@Guard(policy = Policy.CONFIRM_EACH, risk = Risk.HIGH)
@RateLimit(calls = 5, perSeconds = 60)
@AppFunction
fun deleteNote(@TextLimit(max = 64) id: String): Boolean = repo.delete(id)
```

That's it. The annotations are read by KSP at build time; there is no reflection on the hot path.

---

## See it work

`sample/` is a runnable app. Every button in it is one agent call going through `AfGuard.intercept()`:

```bash
gradle :sample:installDebug   # no wrapper in the repo; use local Gradle 8.13, or run `gradle wrapper` first
```

| Button | What you should see |
|---|---|
| `listNotes · ALLOW` | returns immediately, no dialog, one `ALLOWED` audit row |
| `searchNotes("") · validate` | `INVALID_ARGUMENT` before your code runs, `field = keyword` |
| `deleteNote · confirm` | dialog with caller + risk; approval remembered for 7 days |
| `exportNotes · redact` | phone numbers come back as `138****8000` |
| `wipeAllNotes · blocked` | no dialog at all, `FUNCTION_BLOCKED`, `retryable = false` |
| `9x deleteNote` | first calls pass, then `RATE_LIMITED` with `retryAfterMs` |
| `audit` | one row per call, argument *shape* only, never values |

Setup notes, the real-device `AppFunctionService` wiring, and the two ways people get the confirmation dialog wrong: [sample/README.md](sample/README.md)

---

## Modules

| Module | Type | What's in it |
|---|---|---|
| `guard-audit` | pure JVM | `AuditRecord`, `DecisionKind`, `AuditSink` + CSV / JSONL export. No Android dependency, so it runs in CI and in plain JVM tools |
| `guard-core` | Android AAR | interception chain, policy engine, rate limiter, redactor, audit wiring |
| `guard-ksp` | KSP processor | generates argument validators and the function metadata table at build time |
| `guard-ui` | Android AAR | `DialogConfirmationHandler` — lifecycle aware, timeout safe, refuses instead of deadlocking on the main thread |
| `guard-cli` | JVM app | audit export / summary from a JSONL dump, for people who want the log out of the app |

`guard-audit` / `guard-core` / `guard-ksp` / `guard-ui` are published coordinates; `guard-cli` is a runnable app, not a library dependency.

---

## Read the audit log offline

The library only writes *shapes* — decision, function, caller, argument fingerprint. Export it and you get a file you can hand to a reviewer:

```bash
# one line per call, from RingBufferAuditSink.toJsonLines()
gradle :guard-cli:installDist
./guard-cli/build/install/guard-cli/bin/guard-cli summary --in audit.jsonl

# to CSV, for a spreadsheet
./guard-cli/build/install/guard-cli/bin/guard-cli export --in audit.jsonl --out audit.csv --format csv

# or pipe it
cat audit.jsonl | ./guard-cli/build/install/guard-cli/bin/guard-cli summary
```

Unparseable lines are reported, not silently dropped: a truncated audit log is worse than a loud one.

---

## Error protocol

The part most worth stealing even if you never use this library.

```json
{
  "afg_error": {
    "code": "RATE_LIMITED",
    "function": "deleteNote",
    "message": "Rate limit exceeded for 'deleteNote'",
    "retryAfterMs": 12000,
    "retryable": true,
    "hint": "Too many calls. Wait for retryAfterMs, then retry."
  }
}
```

`retryable` is the important field. An agent's default behaviour is *try again*; `retryable: false` is the only signal that reliably stops it.

| code | retryable | meaning |
|---|---|---|
| `INVALID_ARGUMENT` | yes | fix the argument, retry with `expected` |
| `MISSING_ARGUMENT` | yes | supply `field` |
| `RATE_LIMITED` | yes | wait `retryAfterMs` |
| `USER_DENIED` | **no** | the human said no. Stop. |
| `NOT_AUTHORIZED` | **no** | caller not allowed |
| `FUNCTION_BLOCKED` | **no** | permanently disabled |
| `UPSTREAM_ERROR` | **no** | your code broke, don't hammer it |

Full reference: [docs/ERROR_CODES.md](docs/ERROR_CODES.md)

---

## Annotations

| Annotation | Target | Purpose |
|---|---|---|
| `@Guard(policy, risk, redactOutput, idempotent)` | function | authorization policy |
| `@RateLimit(calls, perSeconds, scope, burst)` | function | token bucket |
| `@TextLimit(min, max)` | String param | length bounds |
| `@IntRange(min, max)` | Int/Long param | numeric bounds |
| `@Pattern(regex)` | String param | format check |
| `@OneOfVal(values)` | String param | enum-like whitelist |
| `@Sensitive(kind)` | param | marks PII, feeds the confirm dialog |

Policies:

- `ALWAYS_DENY` — the function is never reachable from an agent
- `CONFIRM_EACH` — user approves every call
- `CONFIRM_ONCE` — approval remembered for `consentTtlMillis` (default 7 days); **does not apply to `Risk.CRITICAL`**
- `ALLOW` — pass through, still rate-limited, still audited

---

## Design decisions

**The library imports nothing from `android.app.appfunctions.*`.**

That API is alpha. It has already removed components between alpha releases. If Guard depended on those types, every platform change would break the library. Instead Guard operates on `String functionId` + `Bundle args` — the two things that are stable across every version. Platform churn is absorbed by *your* service, in one line.

**Audit records never contain argument values.**

Only a SHA-256 digest of the argument *shape*, plus a count of redacted PII. A security library that logs raw personal data is a new vulnerability, not a fix.

**`CONFIRM_ONCE` never applies to `CRITICAL` risk.**

Convenience must not be able to make a destructive action permanent.

**Fail closed on misconfiguration.**

If a function needs confirmation and no `ConfirmationHandler` is installed, the call is denied. Not allowed, not ignored — denied.

**Degrade loudly.**

If the KSP-generated table is missing (misconfigured build), validation is skipped but every audit record is tagged `degraded = true`. Silent failure is worse than a crash.

---

## Field notes

What actually broke during week 1, written down while it still hurt: [给 Android 的 AI 后门装锁：第一周踩坑记录](docs/blog/w1-field-notes.md) (中文)

---

## Requirements

- `compileSdk 36` (AGP 8.13+)
- `minSdk 26` — the library itself is just `Bundle` + `Binder`, it runs anywhere; **AppFunctions itself requires a device on API 36**
- Kotlin 2.x
- KSP for annotation processing

```kotlin
// same coordinates as in Install above
implementation("io.github.yangyang2857-rgb:guard-core:0.1.0-alpha3")
ksp("io.github.yangyang2857-rgb:guard-ksp:0.1.0-alpha3")
```

All four library modules are on Maven Central since 2026-11-12 — resolve them straight from `https://repo1.maven.org/maven2/`, no JitPack, no local build. Release process: [docs/PUBLISHING.md](docs/PUBLISHING.md).

---

## Status

**alpha — not production ready.**

Honest state of things:

- [x] Core interception chain, policy engine, rate limiter, redactor, audit, error protocol
- [x] KSP processor generating validators + metadata
- [x] CI on GitHub Actions — green build, unit tests, sample APK artifact
- [x] `sample/` — runnable demo app covering all four policies, rate limiting, redaction, audit viewer
- [x] `guard-ui` — drop-in confirmation dialog (`DialogConfirmationHandler`), used by `sample` itself
- [x] `guard-cli` — audit export to CSV / JSONL + summary, smoke tested in CI
- [x] Maven Central — `0.1.0-alpha3` of `guard-core` / `guard-ksp` / `guard-audit` / `guard-ui` is published and downloadable from `repo1.maven.org` (GPG signed, sources + javadoc attached). Coordinates use `io.github.yangyang2857-rgb` because Portal only grants `io.github.*` for the account name. Process: [docs/PUBLISHING.md](docs/PUBLISHING.md)
- [ ] **End-to-end verification on a real device** — CI has no device and no Agent host, so the interception chain has never run against a live `AppFunctionService`. Checklist: [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md)

CI: [`build.yml`](.github/workflows/build.yml) runs on every push to `main` — assembles all four library modules plus `sample` / `guard-cli`, runs unit tests, smoke tests the CLI, verifies publication metadata. Details: [docs/CI.md](docs/CI.md)

If you're here early: the interesting part is the *policy surface*, not the code volume. Feedback on the annotation design is more useful than bug reports right now.

---

## Prior art check

As of November 2026 I could not find an existing library that does per-function authorization / validation / rate limiting / audit for AppFunctions. GitHub search for `appfunctions` returns ~72 repos, almost all single-digit stars, mostly samples and one UI-automation tool (`anyfn`) that solves a different problem — it drives the UI, it doesn't guard the API surface. The official sample has a few hundred stars and no policy layer.

If you know of prior art, open an issue and I'll link it here. That's more valuable than pretending to be first.

Background reading on the risk model: the AppFunctions security analysis covering privacy leakage, isolation bypass, and LLM integrity attacks on Android 16.

---

## Roadmap

| Week | Goal | State |
|---|---|---|
| 1 | Run it end-to-end on a device, write up what actually broke | open — checklist ready in [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md) |
| 2 | `guard-ui` confirmation sheet + audit viewer | dialog done; audit viewer still lives in `sample` |
| 3 | `guard-cli` export, policy lint (`afg check`) | export done; `afg check` not started |
| 4 | Maven Central, v0.1.0 | `0.1.0-alpha3` published; `v0.1.0` waits on device verification |

---

## License

MIT. See [LICENSE](LICENSE).

---

*Guard is not affiliated with Google. AppFunctions is an Android platform API; this is an independent community library.*
