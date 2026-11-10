# Service Check — implementation plan

Personal fork of v2rayNG. Adds a **Service check** feature: for every proxy profile the app
starts a throwaway Xray core with one local SOCKS inbound and asks Google / YouTube how the
profile's exit IP is seen.

Source of truth for the HTTP behaviour: [`IPREGION_PORT_SPEC.md`](IPREGION_PORT_SPEC.md)
(including the ERRATA block at the top of that file).

Rules that apply to the whole task:

* No new dependencies (OkHttp, Gson, MMKV, Compose, coroutines are already present).
* No foreground service for the service check. Results live in a manager / MMKV.
* No unit tests (explicit user instruction for this task).
* Never touch `.github/`.
* New strings go into the **default `values/strings.xml` only (English)**. The locale files
  are not touched. Every user-visible text must be a string resource.
* Identify profiles by GUID, never by list index.
* Real-delay results (`ServerAffiliationInfo.testDelayMillis`) must never be deleted or
  overwritten by anything new. Service check data goes in its own storage.
* Before using any API, find an existing usage in this repo and copy the pattern. If there
  is none, say so in the report.
* Commit locally on the current branch with
  `git -c user.name="OpenCode Agent" -c user.email="agent@localhost" commit`. Never push,
  never change the git config, remotes or branches.

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

## Stage A — real-delay list behaviour (scroll lag + live sorting)

### A1. Scroll lag while a real-delay test runs

* [x] A1.1 **Trace** one real-delay result from `CoreTestService` (the `:tasks` process) to
      the screen: where the message arrives in the UI process, what it does (MMKV write?
      full list reload? JSON decoding of every profile?), on which thread, and how often it
      triggers recomposition. Report the trace with file:line.
* [x] A1.2 Check the hypotheses against the code (they are guesses, not facts):
      (a) every result triggers a reload and decode of the whole server list on the main
      thread; (b) every result creates a new list so all rows recompose; (c) rows are not
      keyed by guid or the row model is not stable; (d) results arrive in a burst with no
      batching.
* [x] A1.3 Fix only what can be proved: batch result updates and flush at most every
      400 ms; update only the changed rows; do decoding and MMKV work off the main thread;
      use stable keys (guid) and stable/immutable row models. If there is no concrete
      cause, say so and make **no** speculative change. Do **not** change the concurrency
      setting.

### A2. Live sorting

* [x] A2.1 Find the existing “sort after test” setting and the existing
      `SortByTestResults` logic. **Leave them unchanged.**
* [x] A2.2 New switch setting **“Sort while testing”**, default **ON**, next to the
      “sort after test” setting.
* [x] A2.3 While a real-delay test runs (started from any trigger), re-sort the list by
      test result with the **same** ordering logic, at most once per **1.5 s**, only if at
      least one new valid result (delay > 0) arrived since the last sort, and never while
      the user is touching or scrolling the list (defer until the scroll stops).
* [x] A2.4 Switch OFF: behaviour is exactly as today.
* [x] A2.5 The sort must not run on the main thread when it touches MMKV or does heavy
      work.

### Stage A findings (for the report)

* Trace: `CoreTestService.kt:154` writes the delay to MMKV in the `:tasks` process and
  `CoreTestService.kt:157` broadcasts `MSG_MEASURE_CONFIG_SUCCESS`; the broadcast is
  received by `MainRepository.kt:65-67`, pushed into an unlimited channel
  (`MainRepository.kt:43`) and collected in `MainViewModel.collectServiceEvents`
  (`MainViewModel.kt:130`). No list reload and no profile decoding happens per result.
* (a) **False** — the per-result path never reloads or decodes the server list.
* (b) **Partly true** — a new `List` is created per flush, but only the changed rows get a
  new instance (`applyTestDelayResultsToRows`). The **progress text** however updated
  `_uiState.status` per result, which recomposed the whole `MainScreen`/list per result.
* (c) **True for the model** — the rows were keyed by guid already, but `ServerRowUiModel`
  contained `ProfileItem` (56 `var` properties ⇒ Compose-inferred unstable) and
  `ServerRowActions` was rebuilt on every parent recomposition, so every visible row
  recomposed.
* (d) **Partly true** — the delay values were already batched (500 ms), the progress text
  was not.

### Stage A implementation

* Progress text is now published with the delay flush (`pendingTestProgress`,
  `flushPendingTestResults`), so a burst causes at most one screen update per 500 ms.
* `ServerRowUiModel` no longer holds `ProfileItem`; the row model is now a stable data
  class of immutable values. The profile for the edit/share/more actions is resolved via
  `MainViewModel.profileInGroup(groupId, guid)`.
* `ServerRowActions` is created once per group (`remember(groupId)`) and reads the
  callbacks through `rememberUpdatedState`, so the row composables can be skipped.
* The live sort watcher ticks every 500 ms, checks the conditions on the main thread and
  runs `AngConfigManager.sortByTestResultsForSub` (the existing ordering logic) on the IO
  dispatcher. The new order is applied by re-ordering the existing rows, so the row
  instances survive the sort.
* New setting `AppConfig.PREF_SORT_WHILE_TESTING`, default `true`, switch next to
  “Auto sort after testing” in the subscription update dialog.

## Stage B — service check results on the main page; remove the Service check page

* [x] B1. Remove the Service check page completely: `ServiceCheckActivity` /
      `ServiceCheckViewModel` / `ServiceCheckContract`, its manifest entry, the
      “Service check” menu entry and everything that exists only for it
      (`MainMoreMenuAction.ServiceCheck`, `MainAction.ServiceCheck`, handlers, unused
      strings). Prove with grep that nothing references them any more. **Keep
      `ServiceCheckRunner`.**
* [x] B2. Application-scoped manager. `object ServiceCheckManager` with its own
      `CoroutineScope(SupervisorJob + Dispatchers.Default)` and a `StateFlow` of run state
      (running, done, total, goodCount). It queues configs and runs `ServiceCheckRunner`
      on them with `SERVICE_CHECK_CONCURRENCY = 3`. A run survives the main activity being
      recreated. Expose `start(guids)`, `enqueue(guid)`, `cancel()`, and
      `cancelAndJoin()` (waits until every worker is gone, used before the store is
      emptied).
* [x] B3. Checks. Keep `exit_ip` and `youtube_premium`. Add `google_country`,
      `youtube_country`, `google_search_captcha`, `youtube_music_experimental`. Source of
      truth: `reference/ipregion.sh`, then the spec and its errata. Where the script sends
      no `User-Agent`, send none. All checks use `followRedirects(false)`.
      * Order per config: `youtube_premium` first. If it is not YES, store the result and
        **skip** the rest. If YES, run `google_search_captcha`, `youtube_country`,
        `google_country`, `youtube_music_experimental`, `exit_ip`, about 300 ms apart.
      * “YT OK” = premium YES **and** captcha != IPBLOCK **and** music != NO. One
        function.
      * `youtube_music_experimental`: GET `https://music.youtube.com/` with the SOCS/PREF
        cookie and the same headers as the spec's YouTube requests. `blockedMarkers` and
        `availableMarkers` live in `V2rayNG/app/src/main/assets/ipregion_checks.json`,
        **empty** by default. A blocked match gives NO, an available match gives YES,
        otherwise UNKNOWN. Capture up to 64 KB of its body.
      * `ipregion_checks.json` holds only tunables (URLs, headers, UA, regexes, marker
        lists, timeouts). The logic stays in Kotlin. Use the JSON library already in the
        project.
* [x] B4. Persistence. A new MMKV store following the `MmkvManager` pattern, key = guid,
      value = JSON of a report (timestamp + per-check results: status
      YES/NO/IPBLOCK/UNKNOWN, value, reason, httpCode, locationHeader, bodyPreview up to
      4 KB, plus the full captured music body in a separate field). Delete a profile's
      report wherever its `ServerAffiliationInfo` is removed (delete one, delete
      duplicates, delete invalid, delete all). Add **“Clear service check results”** to the
      more-menu.
      * **Real-delay results must never be deleted or overwritten by service check
        data.** `ServerAffiliationInfo.testDelayMillis` belongs to the real-delay test
        only; the service check writes to its **own** MMKV store (its own key namespace
        and its own JSON payload) and never reads, writes or clears the delay field.
        “Clear service check results” clears the service-check store only.
* [x] B5. Main list row. Append to the existing delay display, without changing the row
      height or removing anything:
      * the 2-letter exit country (YouTube country, else Google country), small text;
      * a “YT” chip: green = YT OK, red = premium NO, orange = captcha/IPBLOCK,
        grey = checked but unknown or error, hidden if never checked;
      * a tiny “M” chip only when the music status is YES (green) or NO (red).
      Chips must handle RTL, long remarks (ellipsis) and dark/light theme (theme colors).
      The report includes an ASCII sketch of the row before and after.
* [x] B6. Details. Add **“Service check details”** to the row's existing overflow menu
      (find it; if none exists, use the existing long-press menu). It opens a dialog or
      bottom sheet showing, per check: status, value, HTTP code, Location header and the
      first 4 KB of the body preview as selectable text, with a **Copy** button per check
      that copies the **full** captured body.
* [x] B7. Add ipregion's MIT license text (spec section 6) and the credit to
      `assets/open_source_licenses.html`, following its structure.
* [x] B8. Run trigger, so the run can be tested before the “YT” button of Stage C
      exists. A more-menu entry **“Check YouTube on tested configs”** collects the guids
      of the current group whose `testDelayMillis` is greater than 0 and calls
      `ServiceCheckManager.start(guids)` — the same API the “YT” button will use. While a
      run is active the same entry cancels it. A Toast shows the number of configs when
      the run starts and another one shows “YouTube check done: X OK of Y” when the run
      finishes (the state of `ServiceCheckManager` is observed).

### Status of the first B part (B1–B4, “B-core”)

Done and committed:

* B1 — the Service check page and everything that only existed for it are gone
  (activity, view model, contract, manifest entry, menu entry, action, strings).
* B2 — `core/ServiceCheckManager.kt`, an application-scoped manager with its own scope
  and a `StateFlow` of the run state. Three workers, an unlimited queue, lazy worker
  start, `pending` bookkeeping, `cancelAndJoin()`.
* B3 — `core/ServiceCheckRunner.kt` (rewritten) and `core/ServiceCheckConfig.kt` with
  the tunable asset `assets/ipregion_checks.json`.
* B4 — the `SERVICE_CHECK` MMKV store in `handler/MmkvManager.kt`, the cleanup hooks and
  the “Clear service check results” entry of the more-menu.

### Status of the second B part (B5–B8, “B-UI”)

Done:

* B5 — the row model got a `serviceCheck` summary, the chips are drawn next to the delay
  of the row (`ui/main/MainServiceCheckUi.kt`, `ui/main/MainServerPager.kt`). The chip
  font is 10 sp, so the height of the row does not change.
* B6 — `ui/main/MainServiceCheckDialog.kt` shows the report of one config. The entry
  **“Service check details”** is in the row's overflow sheet, and the chips of the row
  open the same dialog, because the single column row has no overflow button of its own
  and a long press on the row is the drag handle of the reorderable list.
* B7 — the ipregion credit and its MIT license text are in
  `assets/open_source_licenses.html`.
* B8 — the more-menu entry **“Check YouTube on tested configs”** starts the run of the
  tested configs of the current group, cancels a running run and shows the Toasts.

Still open:

* Stage C is not started: there is no “YT” button and no long-press auto-connect yet.
* The marker lists of `youtube_music_experimental` are empty, so that check always
  answers `UNKNOWN` until the user fills them in.
* Nothing was compiled or run on a device yet: this checkout has no JDK, no Android
  SDK and no Gradle cache.

## Stage C — “YT” button and long-press auto-connect

### Shared rules

* One run at a time. Starting any run cancels the previous one (use the existing cancel
  path for real-delay tests). Cancelling never disconnects the VPN.
* Use the **same** code path as the existing connect button and as tapping another config
  for selecting and (re)starting the VPN, including the VPN permission flow. Find it and
  reuse it; do not write a new start path.
* User override: remember the guid the auto logic selected last. Before any auto switch,
  if the currently selected server differs from it, or the user disconnected, **stop** all
  auto switching for the rest of the run. Testing and marking continue.
* Keep the screen on while a run is active. Show a Toast or Snackbar on every auto
  connect or switch (“Connected: `<remark>`”).
* Put all thresholds as constants in one place.

### C1. “YT” button

* A small FAB with the text **“YT”**, directly left of the connect FAB, vertically
  aligned with it. It must not overlap the last list item (check how the list bottom
  padding handles the connect FAB). Do not change the connect FAB's behaviour or colours.
* Tap: start a run on **all** configs of the current subscription group. Real delay test
  (existing mechanism); every config whose result has delay > 0 is enqueued to
  `ServiceCheckManager` as soon as its result arrives (do not wait for the whole test).
  When the first config is “YT OK”: select it and connect (unless the user override rule
  applies). Keep testing everything; do **not** switch to later ones. Update the main list
  rows as results come in.
* While running: the button shows a progress indicator and a second tap cancels the run.

### C2. Long-press on the connect FAB: auto-connect to the fastest

* Show haptic feedback plus a Toast, and add an `onLongClickLabel`. A plain tap keeps
  its current behaviour. `FloatingActionButton` has no long-click, so find an existing
  pattern in this repo, or use `combinedClickable` / `detectTapGestures` on the same shape.
* Start a real delay test on the current group. **No** service checks in this mode.
* Not connected: connect to the **first** result with delay > 0 immediately. Keep testing.
  Later, switch only when a result is at least **30 % faster** than the connected config's
  delay **and** at least **50 ms** faster, with at least **10 s** since the last switch and
  at most **3** switches per run.
* Connected: the baseline is the connected config's delay **from this run**. Until it
  arrives, remember the best candidate. If the connected config fails (no delay), switch
  to the best candidate. Otherwise apply the 30 % / 50 ms rule.
* Implement the decision logic as a small **pure Kotlin class** (inputs: connected guid,
  its delay, candidate guid and delay, time of the last switch, switch count; output:
  switch or not) with no Android dependencies, used by the run code.

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
