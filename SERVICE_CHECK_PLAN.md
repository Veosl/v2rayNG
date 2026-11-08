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

* [ ] 2.1 Add `MainMoreMenuAction.ServiceCheck` and handle it in **every** `when` over
      `MainMoreMenuAction` (`MainScreen.kt`).
* [ ] 2.2 Add `MainAction.ServiceCheck` and route it to the new activity from
      `MainActivity` (the activity owns the navigation entry).
* [ ] 2.3 New Compose screen `ServiceCheckActivity` (extends `BaseComponentActivity`,
      `AppTopBar`, `exported=false` manifest entry) + `ServiceCheckViewModel`
      (state in a `StateFlow`, work on `Dispatchers.IO`).
* [ ] 2.4 Build the per-profile runtime config with the same function the real-ping worker
      uses (`CoreConfigManager.getV2rayConfig4Speedtest`) and add **one** SOCKS inbound on
      `127.0.0.1` on a random free port, no auth; the profile's proxy outbound stays the
      first outbound.
* [ ] 2.5 Start a temporary core (`CoreNativeManager.newCoreController` + `startLoop`,
      no-op callback), poll the port until it accepts connections (≤ ~3 s), always stop it
      in a `NonCancellable` `finally` block (also on cancel / timeout / error).
* [ ] 2.6 Fresh `OkHttpClient` per profile with
      `Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))`, no custom `Dns`,
      call timeout from the check definition, one retry on `IOException`, no manual
      `Accept-Encoding`.
* [ ] 2.7 Implement checks `exit_ip` and `youtube_premium` only, with the exact URLs,
      headers, cookies and markers from the spec (+ errata).
* [ ] 2.8 List one row per profile (remark, exit IP, Premium status), a progress counter
      (`12/87`) and a **Cancel** button; keep the screen on while running.
* [ ] 2.9 Only profiles of the selected subscription group with `testDelayMillis > 0`;
      if there are none, tell the user to run “Test all real delay” first.
* [ ] 2.10 Concurrency from `SettingsManager.getRealPingConcurrency()`, ~300 ms between the
      checks of one profile.
* [ ] 2.11 Results are in memory only.

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

| check id | source | method | URL | notes |
|---|---|---|---|---|
| `exit_ip` | task | GET | `https://api.ipify.org` | body is the IP; informational only |
| `google_country` | spec 2.5 | GET | `https://accounts.google.com/v3/signin/identifier?flowName=GlifSetupAndroid` | regex `name="region" value="([^"]*)"` |
| `youtube_country` | spec 2.1 | GET | `https://www.youtube.com/sw.js_data` | strip `)]}'`, JSON path `[0][2][0][0][1]`, 2 uppercase letters |
| `youtube_premium` | spec 2.2 | POST | `https://www.youtube.com/youtubei/v1/browse` | 200 + `premiumPurchaseButtonRenderer` → YES |
| `google_search_captcha` | spec 2.4 | GET | `https://www.google.com/search?q=cats` | 429 or `Location` containing `/sorry/` → IPBLOCK |
| `youtube_music_experimental` | task | GET | `https://music.youtube.com/` | marker lists `blockedMarkers` / `availableMarkers`, empty by default |

**Not ported:** “YouTube No Ads” (it is not a YouTube Music check), every other ipregion
service, and the Gemini checks (explicitly deferred).

## Open items / decisions

* `HttpUtil.buildOkHttpClient` is `private` and HTTP-proxy-only, so the service-check
  client is built locally with the same OkHttp builder pattern and a SOCKS `Proxy`.
* `Proxy.Type.SOCKS` has no other usage in this repository; the SOCKS setup follows the
  task instructions verbatim.
* The root `AGENTS.md` asks for JVM tests for new ViewModel logic; the task instruction
  “no unit tests” overrides it for this task.
