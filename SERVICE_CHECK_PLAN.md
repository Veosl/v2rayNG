# Service Check — implementation plan

Personal fork of v2rayNG. Adds a **Service check** feature: for every proxy profile the app
starts a throwaway Xray core with one local SOCKS inbound and asks Google / YouTube how the
profile's exit IP is seen.

Source of truth for the HTTP behaviour: [`IPREGION_PORT_SPEC.md`](IPREGION_PORT_SPEC.md)
(including the ERRATA block at the top of that file).

Rules that apply to the whole task:

* No new dependencies (OkHttp, Gson, MMKV, Compose, coroutines are already present).
* No foreground service. Results live in the ViewModel (Stage 2) / MMKV (Stage 3).
* No unit tests (explicit user instruction for this task).
* Never touch `.github/`.
* Every visible string lives in `strings.xml` and is localized in every locale listed in
  `androidResources.localeFilters`.
* Identify profiles by GUID, never by list index.

---

## Stage 1 — Baseline audit

* [x] 1.1 Capture raw baseline output: `git status --short`, `git diff --stat`,
      `git log --oneline -5`, `ls .github/workflows`, `ls IPREGION_PORT_SPEC.md
      SERVICE_CHECK_PLAN.md reference`.
* [x] 1.2 Inspect leftover changes from the previous session; keep only what is valid and
      explain each one.
* [x] 1.2a Ensure the `debug` build type has `applicationIdSuffix = ".dev"` and that
      `release` is untouched.
* [x] 1.3 Add `V2rayNG/app/src/debug/res/values/strings.xml` overriding `app_name` with
      `v2rayNG DEV` (only after checking `app_name` is defined in the main strings).
* [x] 1.4 Write this plan file from scratch; re-read it at the start of every stage.
* [x] 1.5 Apply the four spec errata to `IPREGION_PORT_SPEC.md` and verify every hint from
      the task against the code (file path + line number, true / false / different).

## Stage 2 — Vertical slice, reachable in the UI

* [x] 2.1 Add `MainMoreMenuAction.ServiceCheck` and handle it in **every** `when` over
      `MainMoreMenuAction` (`MainScreen.kt`).
* [x] 2.2 Add `MainAction.ServiceCheck` and route it to the new activity from
      `MainActivity` (the activity owns the navigation entry).
* [x] 2.3 New Compose screen `ServiceCheckActivity` (extends `BaseComponentActivity`,
      `AppTopBar`, `exported=false` manifest entry) + `ServiceCheckViewModel`
      (state in a `StateFlow`, work on `Dispatchers.IO`).
* [x] 2.4 Build the per-profile runtime config with the same function the real-ping worker
      uses (`CoreConfigManager.getV2rayConfig4Speedtest`) and add **one** SOCKS inbound on
      `127.0.0.1` on a random free port, no auth; the profile's proxy outbound stays the
      first outbound.
* [x] 2.5 Start a temporary core (`CoreNativeManager.newCoreController` + `startLoop`,
      no-op callback), poll the port until it accepts connections (≤ ~3 s), always stop it
      in a `NonCancellable` `finally` block (also on cancel / timeout / error).
* [x] 2.6 Fresh `OkHttpClient` per profile with
      `Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))`, no custom `Dns`,
      call timeout from the check definition, one retry on `IOException`, no manual
      `Accept-Encoding`.
* [x] 2.7 Implement checks `exit_ip` and `youtube_premium` only, with the exact URLs,
      headers, cookies and markers from the spec (+ errata).
* [x] 2.8 List one row per profile (remark, exit IP, Premium status), a progress counter
      (`12/87`) and a **Cancel** button; keep the screen on while running.
* [x] 2.9 Only profiles of the selected subscription group with `testDelayMillis > 0`;
      if there are none, tell the user to run “Test all real delay” first.
* [x] 2.10 Concurrency from the constant `SERVICE_CHECK_CONCURRENCY = 3`, ~300 ms between
      the checks of one profile.
* [x] 2.11 Results are in memory only.

## Stage 3 — Everything else

* [ ] 3.1 Add `V2rayNG/app/src/main/assets/ipregion_checks.json` with the parameters of
      every check (URLs, UA, headers, cookies, regexes, marker lists, timeouts), loaded
      with Gson. The decision logic stays in Kotlin.
* [ ] 3.2 Add the remaining checks: `google_country`, `youtube_country`,
      `google_search_captcha`, `youtube_music_experimental`.
* [ ] 3.3 Result data per check: `checkId`, `status` (YES/NO/IPBLOCK/UNKNOWN), `value`,
      `reason`, `httpCode`, `locationHeader`, `bodyPreview` (first 2048 chars, always
      captured).
* [ ] 3.4 Persist the per-profile results in a new MMKV store following the `MmkvManager`
      pattern, plus a **Clear results** action.
* [ ] 3.5 Badge row per profile (Google country, YouTube country, Premium, captcha,
      Music) and best-first sort: score = 2 if `youtube_premium` is YES, +1 if
      `google_search_captcha` is YES.
* [ ] 3.6 Detail screen per profile: every check with status, value, HTTP code, Location
      header and body preview as selectable text plus a **Copy** button, and a
      **Use this config** button that makes the profile the active server.
* [ ] 3.7 Add ipregion's MIT license text (spec section 6) and the credit to
      `V2rayNG/app/src/main/assets/open_source_licenses.html`.

---

## Check catalogue (fixed scope)

Verified against `reference/ipregion.sh` (4108 lines) — the script is the source of truth.

| check id | source | method | URL | notes |
|---|---|---|---|---|
| `exit_ip` | task | GET | `https://api.ipify.org` | body is the IP; informational only. Script: `IPV4_IDENTITY_URLS` line 230, fetched at line 1973 without extra headers; the answer is validated against `IPV4_PATTERN` (line 248) / `IPV6_PATTERN` (line 249) |
| `google_country` | spec 2.5 | GET | `https://accounts.google.com/v3/signin/identifier?flowName=GlifSetupAndroid` | regex `name="region" value="([^"]*)"`; script sends the Firefox UA (line 2644) |
| `youtube_country` | spec 2.1 | GET | `https://www.youtube.com/sw.js_data` | strip `)]}'`, JSON path `[0][2][0][0][1]`, 2 uppercase letters; **no explicit UA** (script line 2651) |
| `youtube_premium` | spec 2.2 | POST | `https://www.youtube.com/youtubei/v1/browse` | 200 + `premiumPurchaseButtonRenderer` → YES; `Content-Type`/`Accept: application/json` from curl's `--json`; **no explicit UA** (script line 2833) |
| `google_search_captcha` | spec 2.4 | GET | `https://www.google.com/search?q=cats` | 429 or `Location` containing `/sorry/` → IPBLOCK; script sends UA + `SOCS=CAI` + `Accept-Language` (line 2944) |
| `youtube_music_experimental` | task | GET | `https://music.youtube.com/` | marker lists `blockedMarkers` / `availableMarkers`, empty by default |

**Not ported:** “YouTube No Ads” (it is not a YouTube Music check), every other ipregion
service, and the Gemini checks (explicitly deferred).

## Decision: the runner runs in the UI process

The task asked to investigate where the core environment is initialized and in which
process each piece runs, and to decide where the runner lives. Evidence:

| Fact | Evidence |
|---|---|
| `CoreNativeManager.initCoreEnv` is called only in the daemon and in `:tasks` | `CoreServiceManager.kt:67`, `CoreTestService.kt:56`, `SubscriptionUpdateService.kt:48` — **not** in the UI process |
| The UI process already loads `libv2ray.so` | `AboutActivity.kt:66` and `CheckUpdateActivity.kt:65` call `getLibVersion()` |
| MMKV works in every process | `AngApplication.onCreate` → `MmkvManager.initialize` with `MULTI_PROCESS_MODE` |
| The native API is public and process-local | `CoreNativeManager.initCoreEnv` (line 28) and `newCoreController` (line 92) are public |
| The real-ping worker runs in `:tasks` | `MainViewModel.testAllRealPing` → `TestServiceMessage` → `CoreTestService` → `RealPingWorkerService` |

**Decision: the temporary cores run in the UI process.** `ServiceCheckRunner` calls
`CoreNativeManager.initCoreEnv(context.applicationContext)` itself; the app's own core
keeps running in the daemon process and is not touched.

**No new Service component is needed**, so the “stop and ask” rule did not trigger:
`CoreTestService` would require a foreground service (forbidden by the task) plus new
IPC, while the runner needs neither.

**Known deviation:** `LauncherManager.kt:73-74` deliberately avoids loading the native
libraries in the UI process, so this feature loads `libv2ray.so` there. That is a
deliberate, disclosed difference.

## Open items / decisions

* `HttpUtil.buildOkHttpClient` is `private` and HTTP-proxy-only, so the service-check
  client is built locally with the same OkHttp builder pattern and a SOCKS `Proxy`.
* `Proxy.Type.SOCKS` has no other usage in this repository; the SOCKS setup follows the
  task instructions verbatim.
* Concurrency is the constant `SERVICE_CHECK_CONCURRENCY = 3`, **not**
  `SettingsManager.getRealPingConcurrency()`: every config starts its own native core,
  so the number of parallel cores is kept low and constant.
* The two ported checks send **no explicit `User-Agent`** (erratum 5 of
  `IPREGION_PORT_SPEC.md`); OkHttp sends its own default. The script sends curl's default.
* OkHttp adds `Accept-Encoding: gzip` itself and decodes the answer transparently; the
  task forbids setting that header manually, which is respected.
* The root `AGENTS.md` asks for JVM tests for new ViewModel logic; the task instruction
  “no unit tests” overrides it for this task.
