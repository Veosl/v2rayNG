# IPREGION PORT SPECIFICATION

## ERRATA (applies to every section below)

These four corrections override the rest of this document wherever they disagree.

1. **“YouTube No Ads” is not a YouTube Music check.** `ipregion.sh` has no YouTube Music
   check at all. Section 2.3 (`check_availability_youtube_no_ads`) is the “No Ads” check
   and the filled example in section 4.2 is mislabelled “YouTube Music”; it is not one.
   The YouTube Music check used by this port (`https://music.youtube.com/`, marker lists
   `blockedMarkers` / `availableMarkers`) is an **experimental, separate** check that is
   not derived from `ipregion.sh`.

2. **curl does not follow redirects unless `-L` is given.** The spec’s own
   `CURL_BASE_ARGS` (section 1.1: `--disable --silent --compressed --retry 1
   --retry-connrefused`) and `build_curl_args()` (section 1.2) never add `-L`, and no
   request in this document documents an explicit `-L`. Therefore **every** check must use
   `followRedirects(false)` and `followSslRedirects(false)`. Section 2.4’s “curl flags:
   Follow redirects (default)” is wrong — following redirects is *not* a curl default.
   The Google captcha check depends on this: the `302` to `/sorry/` and its `Location`
   header must be observed directly instead of being followed. The `followRedirects`
   default of `true` in the section 4.1 JSON schema is superseded by this erratum.

3. **OkHttp's redirect limit is a fixed 20.** Ignore every “`maxRedirects 50`” hint in
   this document (section 5.1). OkHttp has no configurable redirect limit.

4. **The YouTube country JSON prefix is the 4 characters `)]}'` — no backslash.**
   Section 5.2 item 3 spells it `)]}'\n`; the escaped `\n` is not part of the prefix.
   Strip exactly the 4 characters `)]}'` and parse the remainder as JSON.

*Verification note:* `reference/ipregion.sh` is **not present** in this checkout, so the
`-L` usage could not be confirmed from the script itself; the conclusion above is derived
from the curl argument construction documented in sections 1.1 and 1.2 of this file.

---

This document specifies the exact HTTP requests, parsing logic, and behaviors of the `ipregion.sh` script for porting to Kotlin in v2rayNG. All requests must be sent through a local SOCKS5 proxy, one config at a time.

**Source:** `ipregion.sh` (commit from vernette/ipregion)
**License:** MIT License (c) 2024 Nikita Skryabin — see [LICENSE](#license-and-attribution) at the end.

---

## 1. GLOBAL SETTINGS

### 1.1 Default Constants

| Constant | Value | Source Lines |
|----------|-------|--------------|
| `DEFAULT_TIMEOUT` | 5 seconds | 35 |
| `DEFAULT_YOUTUBE_LANG` | `ru` | 37 |
| `USER_AGENT` | `Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0` | 284 |
| `CURL_BASE_ARGS` | `--disable --silent --compressed --retry 1 --retry-connrefused` | 122 |
| `CURL_WRITE_OUT` | `%{http_code}\n%{redirect_url}\n` | 123 |
| `MAX_PARALLEL_JOBS` | 16 | 126 |
| `CACHE_POLL_INTERVAL` | 0.1s | 124 |
| `JOB_POLL_INTERVAL` | 0.1s | 127 |

### 1.2 Curl Flags That Change Behavior

For every request, the script builds curl args via `build_curl_args()` (lines 1822-1836):

```bash
CURL_ARGS=("${CURL_BASE_ARGS[@]}" --max-time "$CURL_TIMEOUT" --retry-max-time "$CURL_TIMEOUT")
```

Then adds conditionally:

| Condition | Flag Added |
|-----------|------------|
| `PROXY_ADDR` set | `--proxy "socks5h://$PROXY_ADDR"` |
| `ip_version` = "4" (no proxy) | `-4` |
| `ip_version` = "6" (no proxy) | `-6` |
| `INTERFACE_NAME` set | `--interface "$INTERFACE_NAME"` |

**Note:** When `--proxy` is used, `-4`/`-6` are NOT added (line 1829-1830). The proxy picks the protocol.

### 1.3 Status Constants (lines 111-120)

| Constant | Symbol | Meaning |
|----------|--------|---------|
| `STATUS_YES` = "yes" | ✓ | Available / country detected |
| `STATUS_NO` = "no" | ✗ | Region blocked / not available |
| `STATUS_IPBLOCK` = "ipblock" | ⊘ | IP blocked / captcha |
| `STATUS_NONE` = "none" | - | No IPv6 route (service skipped for IPv6) |
| `STATUS_UNKNOWN` = "unknown" | ? | Unexpected response / parse failure |

### 1.4 IPv4 vs IPv6 Decision Logic

1. **External IP detection** (lines 1957-1986): Parallel requests to multiple identity URLs:
   - IPv4: `https://ipv4.icanhazip.com`, `https://api.ipify.org`, `https://ipv4.seeip.org`, `https://ipv4.wtfismyip.com/text`
   - IPv6: `https://ipv6.icanhazip.com`, `https://api6.ipify.org`, `https://ipv6.seeip.org`, `https://ipv6.wtfismyip.com/text`
   - First valid response wins; others killed.

2. **Interface routing check** (lines 1142-1193): If `--interface` not set, detects main interface from default route. If IPv6 doesn't go through same interface as IPv4, forces IPv4-only (or IPv6-only).

3. **Proxy overrides** (lines 1020-1028): With `--proxy`, both `-4` and `-6` flags ignored; proxy decides protocol.

4. **Dual-stack flag**: `is_dual_stack()` = both IPv4 and IPv6 detected (line 2374-2376).

### 1.5 Retry Logic

- `--retry 1 --retry-connrefused --retry-max-time "$CURL_TIMEOUT"` (from `CURL_BASE_ARGS` + `build_curl_args`)
- Only retries on connection refused; no retry on HTTP errors, timeouts, or DNS failures.

### 1.6 Request Caching (lines 1815-1888)

- Each unique `(ip_version, url, extra_args)` tuple cached to `$WORK_DIR/http-<cksum>`
- First caller acquires lock (`.lock`), fetches, writes `.body`, `.meta` (http_code, redirect_url, curl_exit), then `.done`
- Other callers wait on `.done` then read cached response
- **Implication for port**: In Kotlin with one config at a time, caching is optional but must respect same deduplication key.

### 1.7 Default Excluded Services

None excluded by default. All groups run unless filtered by `--group` or `--service`:

```
SERVICE_GROUPS=(platforms cdn availability ai geoip)
```

---

## 2. GOOGLE-RELATED CHECKS (DETAILED)

### 2.1 YouTube Country Detection (`check_platforms_youtube`)

**Function:** `check_platforms_youtube` (lines 2647-2654)
**Group:** platforms
**Service ID:** youtube

#### Request
- **URL:** `https://www.youtube.com/sw.js_data`
- **Method:** GET
- **Headers:** Only default UA (`USER_AGENT`)
- **Cookies:** None
- **curl flags:** From `build_curl_args(ip_version)` + base args
- **IP version:** Per IP version (4 or 6), or proxy

#### Response Parsing
```bash
http_request "$ip_version" "$YOUTUBE_URL"
country=$(jq -r '.[0][2][0][0][1] // empty' <<<"${HTTP_BODY#"$YOUTUBE_JSON_PREFIX"}" 2>/dev/null || true)
```
- `YOUTUBE_JSON_PREFIX` = `)]}'` (line 288)
- Strip prefix, then JSON path: `.[0][2][0][0][1]`
- Result: Country code (e.g., "US", "RU") or empty

#### Outcome Mapping
| Parsed Result | Status | Country | Reason |
|---------------|--------|---------|--------|
| Valid 2-letter code | `yes` | code | "" |
| Empty / invalid | `unknown` | "" | `unexpected response` |

#### Notes
- No consent page handling here (that's for YouTube No Ads)
- The `sw.js_data` endpoint returns a JS array-wrapped JSON

---

### 2.2 YouTube Premium (`check_availability_youtube_premium`)

**Function:** `check_availability_youtube_premium` (lines 2830-2835)
**Group:** availability
**Service ID:** youtube_premium

#### Request
- **URL:** `https://www.youtube.com/youtubei/v1/browse`
- **Method:** POST
- **Headers:**
  - `Content-Type: application/json` (implied by `--json`)
  - `User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0`
- **Body (JSON):**
  ```json
  {"context":{"client":{"clientName":"MWEB","clientVersion":"2.20261020.00.00","hl":"en","gl":"US"}},"browseId":"SPunlimited"}
  ```
  (constant `YOUTUBE_PREMIUM_QUERY`, line 333)
- **Cookies:** None
- **curl flags:** `--json "$YOUTUBE_PREMIUM_QUERY"` + base args

#### Response Parsing
```bash
youtube_premium_status() {
  if [[ "$HTTP_CODE" != "$HTTP_OK" ]]; then return; fi
  if [[ "$HTTP_BODY" == *"$YOUTUBE_PREMIUM_PURCHASE_MARKER"* ]]; then
    printf '%s' "$STATUS_YES"; return
  fi
  printf '%s' "$STATUS_NO"
}
```
- `YOUTUBE_PREMIUM_PURCHASE_MARKER` = `premiumPurchaseButtonRenderer` (line 334)
- Checks HTTP 200, then string containment in body

#### Outcome Mapping
| HTTP Code | Body Contains Marker | Status | Reason |
|-----------|---------------------|--------|--------|
| 200 | Yes | `yes` | "" |
| 200 | No | `no` | "" |
| Non-200 | — | `unknown` | http_failure_reason |

---

### 2.3 YouTube No Ads / YouTube Music (`check_availability_youtube_no_ads`)

**Function:** `check_availability_youtube_no_ads` (lines 2851-2870)
**Group:** availability
**Service ID:** `YouTube No Ads <LANG>` (e.g., `YouTube No Ads RU`)

#### Requests (Two Sequential Requests)

**Request 1 (Cookie Collection):**
- **URL:** `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
- **Method:** GET
- **Headers:**
  - `User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0`
  - `Cookie: SOCS=CAI; PREF=hl=<YOUTUBE_LANG>` (line 336: `YOUTUBE_VIEWER_COOKIE_PREFIX$YOUTUBE_LANG`)
  - `Accept-Language: <YOUTUBE_LANG>` (line 337: `ACCEPT_LANGUAGE_PREFIX$YOUTUBE_LANG`)
- **Cookies:** None sent; **Cookie jar** saved to `$WORK_DIR/youtube-cookies-<ip_version>`
- **curl flags:** `--cookie-jar "$cookie_jar"`

**Request 2 (Actual Check):**
- **URL:** Same `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
- **Method:** GET
- **Headers:** Same as Request 1
- **Cookies:** Loaded from `$WORK_DIR/youtube-cookies-<ip_version>` via `--cookie "$cookie_jar"`
- **curl flags:** `--cookie "$cookie_jar"`

#### Response Parsing
```bash
youtube_no_ads_status() {
  if [[ "$HTTP_CODE" != "$HTTP_OK" || "$HTTP_BODY" != *"$YOUTUBE_PLAYER_RESPONSE_MARKER"* ]]; then
    return
  fi
  if [[ "$HTTP_BODY" == *"$YOUTUBE_VIDEO_AD_MARKER"* ]]; then
    printf '%s' "$STATUS_NO"; return
  fi
  printf '%s' "$STATUS_YES"
}
```
- `YOUTUBE_PLAYER_RESPONSE_MARKER` = `ytInitialPlayerResponse` (line 339)
- `YOUTUBE_VIDEO_AD_MARKER` = `SLOT_TYPE_PLAYER_BYTES` (line 340)
- **Logic:**
  1. Must be HTTP 200 AND contain `ytInitialPlayerResponse`
  2. If contains `SLOT_TYPE_PLAYER_BYTES` → ads present → `no`
  3. Otherwise → no ads → `yes`

#### Outcome Mapping
| Condition | Status | Reason |
|-----------|--------|--------|
| HTTP 200 + player response + no ad marker | `yes` | "" |
| HTTP 200 + player response + ad marker | `no` | `video ads` |
| Otherwise | `unknown` | http_failure_reason |

#### Quirks
- **Two-request flow**: First request establishes cookies (consent, preferences), second uses them
- **Language-dependent**: `YOUTUBE_LANG` defaults to `ru`, configurable via `--youtube-lang`
- **Cookie jar**: Persisted per IP version in work dir

---

### 2.4 Google Search Captcha Check (`check_availability_google_search`)

**Function:** `check_availability_google_search` (lines 2940-2951)
**Group:** availability
**Service ID:** Google Search

#### Request
- **URL:** `https://www.google.com/search?q=cats`
- **Method:** GET
- **Headers:**
  - `User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0`
  - `Cookie: SOCS=CAI` (constant `GOOGLE_SOCS_COOKIE`, line 330)
  - `Accept-Language: en-US,en;q=0.9` (constant `ACCEPT_LANGUAGE_ENGLISH`, line 331)
- **Cookies:** `SOCS=CAI`
- **curl flags:** Follow redirects (default), base args

#### Response Parsing
```bash
is_google_search_captcha() {
  [[ "$HTTP_REDIRECT_URL" == *"$GOOGLE_SEARCH_CAPTCHA_PATH"* || "$HTTP_CODE" == "$HTTP_TOO_MANY_REQUESTS" ]]
}

google_search_status() {
  if [[ "$HTTP_CODE" == "$HTTP_OK" && "$HTTP_BODY" == *"$GOOGLE_SEARCH_NO_JS_MARKER"* ]]; then
    printf '%s' "$STATUS_YES"
  fi
}
```
- `GOOGLE_SEARCH_CAPTCHA_PATH` = `/sorry/` (line 346)
- `GOOGLE_SEARCH_NO_JS_MARKER` = `enablejs` (line 347)
- **Logic:**
  1. If redirect URL contains `/sorry/` OR HTTP 429 → **captcha** → `ipblock` with reason `captcha`
  2. Else if HTTP 200 AND body contains `enablejs` → `yes`
  3. Else → `unknown` with http_failure_reason

#### Outcome Mapping
| Condition | Status | Reason |
|-----------|--------|--------|
| Redirect to `/sorry/` or HTTP 429 | `ipblock` | `captcha` |
| HTTP 200 + body contains `enablejs` | `yes` | "" |
| Otherwise | `unknown` | http_failure_reason |

#### Quirks
- Uses `CURL_WRITE_OUT` to capture `%{redirect_url}` for captcha detection
- The `enablejs` marker appears in the "enable JavaScript" fallback page when JS is disabled (which curl is)

---

### 2.5 Google Country Detection (`check_platforms_google`)

**Function:** `check_platforms_google` (lines 2641-2645)
**Group:** platforms
**Service ID:** Google

#### Request
- **URL:** `https://accounts.google.com/v3/signin/identifier?flowName=GlifSetupAndroid`
- **Method:** GET
- **Headers:** `User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0`
- **Cookies:** None
- **curl flags:** Base args

#### Response Parsing
```bash
check_platforms_pattern Google "$ip_version" "$GOOGLE_URL" "$GOOGLE_COUNTRY_PATTERN" --user-agent "$USER_AGENT"
```
- `GOOGLE_COUNTRY_PATTERN` = `name="region" value="([^"]*)"` (line 286)
- Regex extracts value of hidden input `name="region"`

#### Outcome Mapping
| Regex Match | Status | Country |
|-------------|--------|---------|
| Valid 2-letter code | `yes` | code |
| No match / invalid | `unknown` | "" |

---

### 2.6 Gemini Web (`check_ai_gemini_web`)

**Function:** `check_ai_gemini_web` (lines 2990-2994) → `check_ai_gemini` (lines 2961-2988)
**Group:** ai
**Service ID:** Gemini Web

#### Requests (Three Sequential Requests)

**Request 1: Fetch Supported Regions**
- **URL:** `https://support.google.com/gemini/answer/13575153?hl=en`
- **Method:** GET
- **Headers:** Default UA
- **Parsing:**
  ```bash
  regions=$(jq -Rsc "$regions_filter | select(length > 0)" <<<"$HTTP_BODY")
  ```
  - `GEMINI_WEB_REGIONS_FILTER` (line 350):
    ```
    split("Supported countries & territories")[1] // "" | [scan("<li>([^<]+)</li>")[0] | gsub("&[a-z]+;"; " ")]
    ```
  - Extracts `<li>` items from "Supported countries & territories" section, decodes HTML entities

**Request 2: Detect Country via Google Sign-in**
- **URL:** `https://accounts.google.com/v3/signin/identifier?flowName=GlifSetupAndroid`
- **Method:** GET
- **Headers:** Default UA
- **Parsing:** Same `GOOGLE_COUNTRY_PATTERN` regex → country code

**Request 3: Fetch CLDR Territory Names**
- **URL:** `https://cdn.jsdelivr.net/npm/cldr-localenames-full/main/en/territories.json`
- **Method:** GET
- **Headers:** Default UA
- **Usage:** For fuzzy matching in `GEMINI_MATCH_FILTER`

#### Final Matching (gemini_status, lines 2953-2959)
```bash
jq -r --arg code "$country" --argjson regions "$regions" --arg yes "$STATUS_YES" --arg no "$STATUS_NO" \
  "$GEMINI_MATCH_FILTER" <<<"$HTTP_BODY"
```
- `GEMINI_MATCH_FILTER` (lines 355-361):
  ```jq
  def stopwords: ["the", "of", "and", "republic", "island", "islands", "saint", "st"];
  def words: ascii_downcase | [splits("[^\\p{L}]+")] | map(select(. != "" and (IN(stopwords[]) | not))) | unique;
  def names_missing_in_cldr: {CD: ["Democratic Republic of the Congo"], MO: ["Macau"], UM: ["United States Minor Outlying Islands"]}[$code] // [];
  (.main.en.localeDisplayNames.territories | to_entries | map(select(.key | startswith($code)) | .value)) + names_missing_in_cldr
  | map(words)
  | if any(IN($regions[] | words)) then $yes else $no end
  ```
- **Logic:**
  1. Get CLDR territory names for the detected country code (e.g., "US" → "United States")
  2. Normalize both CLDR names and supported regions to word sets (remove stopwords)
  3. If any word set intersects → `yes`, else `no`

#### Outcome Mapping
| Condition | Status |
|-----------|--------|
| Regions list empty | `unknown` |
| Country code not detected | `unknown` |
| Fuzzy match succeeds | `yes` |
| Fuzzy match fails | `no` |

#### Quirks
- **Three HTTP requests** in sequence
- **Fuzzy matching** with stopword removal and CLDR territory names
- **Special cases** for CD, MO, UM in `names_missing_in_cldr`

---

### 2.7 Gemini API (`check_ai_gemini_api`)

**Function:** `check_ai_gemini_api` (lines 2996-3000) → `check_ai_gemini` (lines 2961-2988)
**Group:** ai
**Service ID:** Gemini API

#### Requests (Same three-request flow as Gemini Web)

**Request 1: Fetch Supported Regions**
- **URL:** `https://ai.google.dev/gemini-api/docs/available-regions.md.txt`
- **Method:** GET
- **Parsing:**
  ```bash
  GEMINI_API_REGIONS_FILTER = 'split("\n") | map(select(startswith("- ")) | .[2:])'
  ```
  - Extracts lines starting with `- `, removes first 2 chars

**Requests 2 & 3:** Identical to Gemini Web (Google sign-in + CLDR)

#### Matching Logic
Identical `GEMINI_MATCH_FILTER` as Gemini Web.

---

### 2.8 AI Studio

**Note:** There is **no separate "AI Studio" check** in the script. The Gemini Web and Gemini API checks cover the AI Studio regions (AI Studio uses Gemini API backend). The supported regions page for AI Studio is the same as Gemini API.

---

## 3. OTHER SERVICE CHECKS (BRIEF)

### 3.1 Platforms Group

| Service | Function | URL | Method | Key Parsing | Outcome Logic |
|---------|----------|-----|--------|-------------|---------------|
| **Twitch** | `check_platforms_twitch` (2656-2661) | `https://gql.twitch.tv/gql` | POST (GraphQL) | JSON: `.[0].data.requestInfo.countryCode` | Country → `yes` |
| **ChatGPT** | `check_platforms_chatgpt` (2663-2668) | `https://ab.chatgpt.com/v1/initialize` | POST | Regex: `"derived_fields":\{[^}]*"country":"([^"]*)"` | Country → `yes` |
| **Claude** | `check_platforms_claude` (2670-2675) | `https://claude.ai/api/bootstrap` | GET | JSON: `.statsig.user.country` | Country → `yes` |
| **Netflix** | `check_platforms_netflix` (2677-2681) | `https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=1` | GET | JSON: `.client.location.country` | Country → `yes` |
| **Spotify** | `check_platforms_spotify` (2683-2687) | `https://spclient.wg.spotify.com/signup/public/v1/account/?validate=1&key=142b583129b2df829de3656f9eb484e6` | GET | JSON: `.country` | Country → `yes` |
| **Reddit** | `check_platforms_reddit` (2689-2699) | `https://www.reddit.com/api/v1/not-found` | GET | Regex: `country code: ([A-Z]{2})`; HTTP 403 → `ipblock` | Country → `yes`; 403 → `ipblock` |
| **Disney+** | `check_platforms_disney_plus` (2701-2706) | `https://disney.api.edge.bamgrid.com/graph/v1/device/graphql` | POST (GraphQL) | JSON: `.extensions.sdk.session.location.countryCode` | Country → `yes` |
| **TikTok** | `check_platforms_tiktok` (2708-2712) | `https://www.tiktok.com/api/v1/web-cookie-privacy/config?appId=1988` | GET | JSON: `.body.appProps.region` | Country → `yes` |
| **Apple** | `check_platforms_apple` (2714-2719) | `https://gspe1-ssl.ls.apple.com/pep/gcc` | GET | Body is country code directly | Country → `yes` |
| **Steam** | `check_platforms_steam` (2721-2725) | `https://store.steampowered.com` | HEAD | Regex on headers: `steamCountry=([^%;]*)` | Country → `yes` |
| **Microsoft** | `check_platforms_microsoft` (2727-2731) | `https://login.live.com` | GET | Regex: `"sRequestCountry":"([^"]*)"` | Country → `yes` |
| **Ookla** | `check_platforms_ookla` (2733-2737) | `https://www.speedtest.net/api/js/config-sdk` | GET | JSON: `.location.countryCode` | Country → `yes` |
| **JetBrains** | `check_platforms_jetbrains` (2739-2743) | `https://data.services.jetbrains.com/geo` | GET | JSON: `.code` | Country → `yes` |
| **PlayStation** | `check_platforms_playstation` (2745-2749) | `https://www.playstation.com` | HEAD | Regex on headers: `[Cc]ookie: country=([^;]*)` | Country → `yes` |

### 3.2 Availability Group

| Service | Function | URL | Method | Key Parsing |
|---------|----------|-----|--------|-------------|
| **Spotify Signup** | `check_availability_spotify_signup` (2884-2889) | `https://spclient.wg.spotify.com/signup/public/v1/account/?validate=1&key=142b583129b2df829de3656f9eb484e6` | GET | JSON: `.status` (120/320 → `no`), `.is_country_launched` (true/false → `yes`/`no`) |
| **Disney+** | `check_availability_disney_plus` (2908-2914) | `https://disney.api.edge.bamgrid.com/graph/v1/device/graphql` | POST (GraphQL) | JSON: `.extensions.sdk.session.inSupportedLocation` (true→`yes`, false→`no`); error "Forbidden location" → `no` |
| **Reddit Guest** | `check_availability_reddit_guest` (2923-2928) | `https://www.reddit.com` | GET | HTTP 200 → `yes`; 403 → `ipblock` |

### 3.3 AI Group (API Reachability)

All use `check_ai_api` (lines 3022-3030) or `check_ai_reachability` (lines 3056-3063):

| Service | URL | Region-Blocked Marker | Logic |
|---------|-----|----------------------|-------|
| **OpenAI API** | `https://api.openai.com/v1/models` | `unsupported_country_region_territory` | 401 → `yes`; 403 + marker → `no`; 403 no marker → `ipblock` |
| **Anthropic API** | `https://api.anthropic.com/v1/models` | `Request not allowed` | Same |
| **Grok API** | `https://api.x.ai/v1/models` | `not available in your region` | Same |
| **Qwen API** | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models` | (reachability only) | 401 → `yes` |
| **Qwen CN API** | `https://dashscope.aliyuncs.com/compatible-mode/v1/models` | (reachability only) | 401 → `yes` |
| **Kimi API** | `https://api.moonshot.ai/v1/models` | (reachability only) | 401 → `yes` |
| **Kimi CN API** | `https://api.moonshot.cn/v1/models` | (reachability only) | 401 → `yes` |
| **GLM API** | `https://api.z.ai/api/paas/v4/models` | (reachability only) | 401 → `yes` |
| **GLM CN API** | `https://open.bigmodel.cn/api/paas/v4/models` | (reachability only) | 401 → `yes` |
| **MiniMax API** | `https://api.minimax.io/v1/models` | (reachability only) | 401 → `yes` |
| **MiniMax CN API** | `https://api.minimaxi.com/v1/models` | (reachability only) | 401 → `yes` |
| **Doubao API** | `https://ark.cn-beijing.volces.com/api/v3/models` | (reachability only) | 401 → `yes` |
| **SiliconFlow API** | `https://api.siliconflow.cn/v1/models` | (reachability only) | 401 → `yes` |
| **DeepSeek API** | `https://api.deepseek.com/models` | (reachability only) | 401 → `yes` |

### 3.4 CDN Group

| Service | Function | URL | Key Parsing |
|---------|----------|-----|-------------|
| **Cloudflare** | `check_cdn_cloudflare` (2782-2787) | `https://speed.cloudflare.com/meta` | JSON: `.colo.cca2` (country), `.colo.iata` (airport code) |
| **YouTube** | `check_cdn_youtube` (2789-2801) | `https://redirector.googlevideo.com/report_mapping?di=no` | Regex: `=> ([^ ]+)` → node name; patterns: `GOOGLE_NODE_PATTERN`, `ISP_NODE_PATTERN`, `GGC_NODE_PATTERN` → IATA; airport API → country |
| **Netflix** | `check_cdn_netflix` (2803-2815) | `https://api.fast.com/netflix/speedtest/v2?...` | JSON: `.targets[0].url` → regex `NETFLIX_CDN_URL_PATTERN` → node + IATA; `.targets[0].location.country` |

### 3.5 GeoIP Group

All use `check_geoip_json` (lines 2463-2473) or similar:

| Service | URL | JSON Filter | Extra Headers |
|---------|-----|-------------|---------------|
| **MaxMind** | `https://geoip.maxmind.com/geoip/v2.1/city/<IP>?demo=1` | `.country.iso_code` | `Authorization: Bearer <token>` |
| **RIPE** | `https://rdap.db.ripe.net/ip/<IP>` | `.country` | `--location` (follow redirects) |
| **ipinfo.io** | `https://ipinfo.io/widget/demo/<IP>` (v4) / `https://v6.ipinfo.io/widget/demo/<IP>` (v6) | `.data.country` | — |
| **Cloudflare** | `https://www.cloudflare.com/cdn-cgi/trace` | Regex: `loc=([A-Z]{2})` | — |
| **ipregistry.co** | `https://api.ipregistry.co/<IP>?hostname=true&key=sb69ksjcajfs4c` | `.location.country.code` | `Origin: https://ipregistry.co` |
| **ipapi.co** | `https://ipapi.co/<IP>/json` | `.country` | `Referer: https://ipapi.co/` |
| **ifconfig.co** | `https://ifconfig.co/country-iso?ip=<IP>` | Body is country code | — |
| **ip2location.io** | `https://api.ip2location.io/?ip=<IP>` | `.country_code` | — |
| **country.is** | `https://api.country.is/<IP>` | `.country` | — |
| **geoapify.com** | `https://api.geoapify.com/v1/ipinfo?ip=<IP>&apiKey=b8568cb9afc64fad861a69edbddb2658` | `.country.iso_code` | — |
| **geojs.io** | `https://get.geojs.io/v1/ip/country.json?ip=<IP>` | `.[0].country` | — |
| **ipbase.com** | `https://api.ipbase.com/v2/info?ip=<IP>` | `.data.location.country.alpha2` | — |
| **ipquery.io** | `https://api.ipquery.io/<IP>` | `.location.country_code` | — |
| **ipwho.is** | `https://ipwho.is/<IP>` | `.country_code` | — |
| **ip-api.com** | `https://demo.ip-api.com/json/<IP>?fields=countryCode` | `.countryCode` | `Origin: https://ip-api.com` (forced IPv4 transport) |

---

## 4. KOTLIN-FRIENDLY DATA MODEL

### 4.1 JSON Schema for Check Definitions

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ipregion Check Definition",
  "type": "object",
  "required": ["id", "group", "requests", "outcomeMapping"],
  "properties": {
    "id": { "type": "string", "pattern": "^[a-z0-9_]+$" },
    "group": { "enum": ["platforms", "cdn", "availability", "ai", "geoip"] },
    "serviceName": { "type": "string" },
    "requests": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["url", "method"],
        "properties": {
          "url": { "type": "string", "format": "uri-template" },
          "method": { "enum": ["GET", "POST", "HEAD"] },
          "headers": { "type": "object", "additionalProperties": { "type": "string" } },
          "cookies": { "type": "object", "additionalProperties": { "type": "string" } },
          "body": { "type": "string" },
          "bodyTemplate": { "type": "string" },
          "curlFlags": {
            "type": "object",
            "properties": {
              "followRedirects": { "type": "boolean", "default": true },
              "ipVersion": { "enum": ["4", "6", "any", "proxy"], "default": "any" },
              "timeoutSeconds": { "type": "integer", "default": 5 },
              "retry": { "type": "integer", "default": 1 },
              "retryOn": { "type": "array", "items": { "enum": ["connrefused"] }, "default": ["connrefused"] },
              "compressed": { "type": "boolean", "default": true },
              "userAgent": { "type": "string" },
              "cookieJar": { "type": "boolean", "default": false },
              "cookieFile": { "type": "string" }
            }
          },
          "dependsOn": { "type": "string" }
        }
      }
    },
    "responseParsers": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["type"],
        "properties": {
          "type": { "enum": ["jsonPath", "regex", "statusCode", "redirectUrl", "headerRegex", "bodyContains", "jsonFilter"] },
          "jsonPath": { "type": "string" },
          "pattern": { "type": "string" },
          "filter": { "type": "string" },
          "marker": { "type": "string" },
          "headerName": { "type": "string" },
          "captureGroup": { "type": "integer", "default": 1 }
        }
      }
    },
    "outcomeMapping": {
      "type": "object",
      "required": ["rules"],
      "properties": {
        "rules": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["condition", "status", "country", "reason"],
            "properties": {
              "condition": { "type": "string" },
              "status": { "enum": ["yes", "no", "ipblock", "none", "unknown"] },
              "country": { "type": ["string", "null"] },
              "reason": { "type": ["string", "null"] }
            }
          }
        }
      }
    },
    "specialHandling": {
      "type": "object",
      "properties": {
        "sequentialRequests": { "type": "boolean", "default": false },
        "cookieJarPerIpVersion": { "type": "boolean", "default": false },
        "consentPage": { "type": "boolean", "default": false },
        "fallbackUrls": { "type": "array", "items": { "type": "string" } },
        "fuzzyMatch": { "type": "boolean", "default": false },
        "requiresCountryCode": { "type": "boolean", "default": false }
      }
    }
  }
}
```

### 4.2 Filled Example: YouTube Music (YouTube No Ads)

```json
{
  "id": "youtube_no_ads",
  "group": "availability",
  "serviceName": "YouTube No Ads",
  "requests": [
    {
      "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "method": "GET",
      "headers": {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Cookie": "SOCS=CAI; PREF=hl={{YOUTUBE_LANG}}",
        "Accept-Language": "{{YOUTUBE_LANG}}"
      },
      "cookies": {},
      "curlFlags": {
        "cookieJar": true,
        "cookieFile": "youtube-cookies-{{IP_VERSION}}",
        "followRedirects": true,
        "ipVersion": "{{IP_VERSION}}",
        "timeoutSeconds": 5
      }
    },
    {
      "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "method": "GET",
      "headers": {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Cookie": "SOCS=CAI; PREF=hl={{YOUTUBE_LANG}}",
        "Accept-Language": "{{YOUTUBE_LANG}}"
      },
      "cookies": {},
      "curlFlags": {
        "cookieJar": false,
        "cookieFile": "youtube-cookies-{{IP_VERSION}}",
        "followRedirects": true,
        "ipVersion": "{{IP_VERSION}}",
        "timeoutSeconds": 5
      },
      "dependsOn": "previous"
    }
  ],
  "responseParsers": [
    { "type": "statusCode" },
    { "type": "bodyContains", "marker": "ytInitialPlayerResponse" },
    { "type": "bodyContains", "marker": "SLOT_TYPE_PLAYER_BYTES" }
  ],
  "outcomeMapping": {
    "rules": [
      { "condition": "statusCode == 200 && hasPlayerResponse && !hasAdMarker", "status": "yes", "country": null, "reason": null },
      { "condition": "statusCode == 200 && hasPlayerResponse && hasAdMarker", "status": "no", "country": null, "reason": "video ads" },
      { "condition": "true", "status": "unknown", "country": null, "reason": "http_failure_reason" }
    ]
  },
  "specialHandling": {
    "sequentialRequests": true,
    "cookieJarPerIpVersion": true,
    "consentPage": true
  }
}
```

---

## 5. PITFALLS FOR THE PORT

### 5.1 Curl Behaviors Not Default in Java/Kotlin HTTP Clients

| Behavior | curl Default | Java/Kotlin (OkHttp/HttpClient) Default | Fix Required |
|----------|--------------|-----------------------------------------|--------------|
| **Cookie Jar** | Manual via `--cookie-jar`/`--cookie` | No automatic persistence | Implement `CookieJar` storing per-IP-version cookies |
| **Redirect Limit** | 50 (default) | OkHttp: 20; HttpClient: configurable | Set `followRedirects(true).maxRedirects(50)` |
| **Gzip/Deflate** | `--compressed` (auto) | OkHttp: auto; HttpClient: auto | Usually fine, but verify `Accept-Encoding: gzip` sent |
| **TLS/HTTP/2** | HTTP/2 if available (libcurl) | OkHttp: HTTP/2 default; HttpClient: HTTP/2 default | Ensure ALPN works; may need `ConnectionSpec` for older TLS |
| **SOCKS5h Proxy** | `--proxy socks5h://host:port` (DNS at proxy) | OkHttp: `Proxy.Type.SOCKS` + `InetSocketAddress`; DNS resolution must happen at proxy | Use `socks5h` equivalent: `Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))` |
| **IPv4/IPv6 Binding** | `-4` / `-6` flags | OkHttp: `Dns` override or `Network` binding; HttpClient: `InetAddress` binding | Bind to specific `Network` or use `Dns` that forces IP version |
| **`--disable`** | Disables `.curlrc` | N/A | Not needed |
| **`--retry-connrefused`** | Retries on ECONNREFUSED | OkHttp: `retryOnConnectionFailure(true)` only retries on IOException | Add custom interceptor for connection refused retry |
| **`--max-time` / `--retry-max-time`** | Total time cap including retries | OkHttp: `callTimeout` + `connectTimeout`; separate retry timeout | Set `callTimeout = timeout`, `connectTimeout = timeout`, configure retry with same budget |
| **`CURL_WRITE_OUT`** | Captures `%{http_code}`, `%{redirect_url}` | Response code: `response.code()`; Redirect URL: need to track via `FollowRedirects` interceptor | Implement custom `FollowRedirects` to capture final redirect URL |

### 5.2 Specific Quirks to Replicate

1. **Sequential requests with shared cookie jar** (YouTube No Ads): Must persist cookies between two requests per IP version.

2. **Redirect URL capture for captcha** (Google Search): Need to intercept redirects and capture the `Location` header of the final redirect (or the one matching `/sorry/`).

3. **Body prefix stripping** (YouTube `sw.js_data`): Response starts with `)]}'\n` — must strip before JSON parsing.

4. **GraphQL POST bodies** (Twitch, Disney+): Raw JSON body with `Content-Type: application/json`.

5. **HEAD requests** (Steam, PlayStation): Parse headers only, no body.

6. **IP version forcing without proxy**: When no proxy, bind socket to IPv4-only or IPv6-only. With proxy, skip binding.

7. **Request deduplication key**: `(ip_version, url, sorted_headers, body)` — replicate if caching desired.

8. **MaxMind token flow**: Multi-step: GET demo page → extract CSRF → POST token → use Bearer token. Token expires daily.

### 5.3 Rate-Limit / Captcha Risks

| Service | Risk | Mitigation |
|---------|------|------------|
| **Google Search** | High — `/sorry/` captcha on automated queries | Use realistic UA, `SOCS=CAI` cookie, `Accept-Language: en-US,en;q=0.9`; limit frequency |
| **YouTube** | Medium — consent walls, ad detection varies | Two-request cookie flow; respect `YOUTUBE_LANG` |
| **MaxMind** | High — daily demo limit (403 Payment Required) | Cache token; handle 402 gracefully; fallback to RIPE |
| **ipinfo.io / ipapi.co / others** | Medium — free tier limits | Cache results; use demo endpoints as script does |
| **Netflix (fast.com)** | Low — token is public | Token hardcoded in script |
| **Disney+** | Low — API key public | Key hardcoded |
| **Spotify** | Low — public key | Key hardcoded |
| **Cloudflare/YouTube CDN** | Low | No auth |

**Recommendation:** Add configurable delay between checks (e.g., 500ms–2s), randomize order, respect `Retry-After` headers.

---

## 6. LICENSE AND ATTRIBUTION

```
MIT License

Copyright (c) 2024 Nikita Skryabin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

**Attribution required in v2rayNG:**
- Include the above license text in your app's license screen / about dialog
- Credit: "IP region detection logic based on ipregion.sh by Nikita Skryabin (vernette/ipregion)"
- Link: https://github.com/vernette/ipregion

---

## 7. UNCERTAIN / NEEDS CHECKING

| Item | Uncertainty | Where in Script |
|------|-------------|-----------------|
| **YouTube `sw.js_data` JSON structure** | The exact path `.[0][2][0][0][1]` may change; need to verify with live response | Line 2652 |
| **Gemini Web regions HTML structure** | `split("Supported countries & territories")[1]` depends on exact page text; may break on redesign | Line 350 |
| **Gemini API regions markdown format** | Assumes lines start with `- `; could change | Line 352 |
| **CLDR territories JSON structure** | `GEMINI_MATCH_FILTER` assumes `.main.en.localeDisplayNames.territories`; verify version | Lines 355-361 |
| **YouTube No Ads ad marker** | `SLOT_TYPE_PLAYER_BYTES` may not be the only ad indicator; could miss some ad types | Line 340 |
| **Google Search captcha path** | `/sorry/` is current; Google may change captcha URL pattern | Line 346 |
| **Spotify signup status codes** | `120` and `320` hardcoded; undocumented, may change | Line 342 |
| **Disney+ "Forbidden location" error** | Exact error message string may vary | Line 343 |
| **Netflix CDN URL pattern** | `NETFLIX_CDN_URL_PATTERN` regex assumes specific hostname format | Line 325 |
| **YouTube CDN node patterns** | Three regexes (`GOOGLE_NODE_PATTERN`, `ISP_NODE_PATTERN`, `GGC_NODE_PATTERN`) may not cover all node formats | Lines 322-324 |
| **Airport codes API** | `AIRPORT_CODES_KEY` and endpoint may have rate limits or change | Lines 326-328 |
| **MaxMind demo token lifetime** | Token obtained per run; unclear if reusable across runs | Lines 2023-2041 |
| **RIPE RDAP rate limits** | No auth, but may have limits for bulk checks | Line 2497 |
| **ipregistry.co / ipapi.co demo keys** | Hardcoded keys may be revoked or rate-limited | Lines 268, 271 |
| **Proxy behavior with SOCKS5h** | Script uses `socks5h://` (DNS at proxy); verify Kotlin proxy does same | Line 1828 |
| **IPv6 scope IDs** | Script masks IPv6 for logging; ensure Kotlin handles zone IDs correctly if present | Lines 1994-2015 |
| **Concurrency model** | Script runs up to 16 parallel jobs; port runs one config at a time — verify no shared state issues | Line 126 |

---

## 8. VERIFICATION CHECKLIST (Post-Port)

After implementing, verify each check against the script by running both on same IP/proxy and comparing:

- [ ] YouTube country code matches
- [ ] YouTube Premium yes/no matches
- [ ] YouTube No Ads yes/no matches (same language)
- [ ] Google Search captcha detection matches
- [ ] Google country code matches
- [ ] Gemini Web yes/no matches
- [ ] Gemini API yes/no matches
- [ ] All platform country codes match
- [ ] All availability yes/no/ipblock match
- [ ] All AI API reachability matches
- [ ] CDN node/IATA/country matches
- [ ] GeoIP country codes match (allow MaxMind daily limit variance)
- [ ] Verdict country calculation matches
- [ ] Attention list matches

---

*Generated from `ipregion.sh` analysis. All line numbers refer to the source script at time of writing.*
