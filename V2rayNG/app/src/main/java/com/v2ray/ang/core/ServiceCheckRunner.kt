package com.v2ray.ang.core

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ServiceCheckReport
import com.v2ray.ang.dto.entities.ServiceCheckResult
import com.v2ray.ang.dto.entities.ServiceCheckStatus
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Protocols that never carry the traffic of a profile. */
private val SERVICE_CHECK_NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns")

/** ipregion.sh constant IPV4_PATTERN. */
private val SERVICE_CHECK_IPV4_PATTERN = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")

/** ipregion.sh constant IPV6_PATTERN. */
private val SERVICE_CHECK_IPV6_PATTERN = Regex("^[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){2,7}$")

/** ipregion.sh constant COUNTRY_CODE_PATTERN. */
private val SERVICE_CHECK_COUNTRY_PATTERN = Regex("^[A-Z]{2}$")

/**
 * Runs the service checks of one config through a throwaway native core.
 *
 * Every config gets its own core instance with one SOCKS inbound on the loopback
 * interface. The core lives in the process that owns this runner, which is the UI
 * process; the app's own core keeps running in the daemon process and is not touched.
 */
object ServiceCheckRunner {

    const val CHECK_EXIT_IP = "exit_ip"
    const val CHECK_YOUTUBE_PREMIUM = "youtube_premium"
    const val CHECK_YOUTUBE_COUNTRY = "youtube_country"
    const val CHECK_GOOGLE_COUNTRY = "google_country"
    const val CHECK_GOOGLE_SEARCH_CAPTCHA = "google_search_captcha"
    const val CHECK_YOUTUBE_MUSIC = "youtube_music_experimental"

    /** The JSON path of the country code in the answer of the YouTube country check. */
    private val SERVICE_CHECK_YOUTUBE_COUNTRY_PATH = listOf(0, 2, 0, 0, 1)

    /**
     * Runs every check of one config and returns its report.
     *
     * The YouTube Premium check runs first. When it is not `YES` the other checks are
     * skipped, their result would be meaningless without Premium. The temporary core is
     * always stopped, also when the caller is cancelled.
     */
    suspend fun check(context: Context, guid: String): ServiceCheckReport = withContext(Dispatchers.IO) {
        val tunables = ServiceCheckConfig.load(context)
        val timestamp = System.currentTimeMillis()
        val port = try {
            Utils.findRandomFreePort()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Service check: no free port for $guid", e)
            return@withContext ServiceCheckReport(
                guid = guid,
                timestamp = timestamp,
                error = e.message ?: e.javaClass.simpleName,
            )
        }

        val configJson = try {
            buildConfig(context, guid, port)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service check: failed to build the config for $guid", e)
            return@withContext ServiceCheckReport(
                guid = guid,
                timestamp = timestamp,
                error = e.message ?: e.javaClass.simpleName,
            )
        }

        var controller: CoreController? = null
        try {
            CoreNativeManager.initCoreEnv(context.applicationContext)
            controller = startCore(configJson)

            if (!waitForSocksPort(port, tunables.portTimeoutMs)) {
                return@withContext ServiceCheckReport(
                    guid = guid,
                    timestamp = timestamp,
                    error = "SOCKS inbound did not open",
                )
            }

            val client = buildClient(port, tunables.timeoutSeconds)
            val results = mutableListOf<ServiceCheckResult>()
            var musicBody: String? = null

            val premium = checkYoutubePremium(client, tunables)
            results += premium
            if (premium.status == ServiceCheckStatus.YES) {
                delay(tunables.checkIntervalMs)
                results += checkGoogleSearchCaptcha(client, tunables)

                delay(tunables.checkIntervalMs)
                results += checkYoutubeCountry(client, tunables)

                delay(tunables.checkIntervalMs)
                results += checkGoogleCountry(client, tunables)

                delay(tunables.checkIntervalMs)
                val music = checkYoutubeMusic(client, tunables)
                results += music.result
                musicBody = music.body

                delay(tunables.checkIntervalMs)
                results += checkExitIp(client, tunables)
            }

            ServiceCheckReport(
                guid = guid,
                timestamp = timestamp,
                results = results,
                musicBody = musicBody,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service check: failed for $guid", e)
            ServiceCheckReport(
                guid = guid,
                timestamp = timestamp,
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            withContext(NonCancellable) {
                stopCore(controller, guid)
            }
        }
    }

    /**
     * Tells whether the config is usable for YouTube.
     *
     * YouTube works when Premium is offered, when the search is not blocked by a
     * captcha and when YouTube Music is not blocked.
     */
    fun isYtOk(report: ServiceCheckReport): Boolean {
        if (report.result(CHECK_YOUTUBE_PREMIUM)?.status != ServiceCheckStatus.YES) return false
        if (report.result(CHECK_GOOGLE_SEARCH_CAPTCHA)?.status == ServiceCheckStatus.IPBLOCK) return false
        if (report.result(CHECK_YOUTUBE_MUSIC)?.status == ServiceCheckStatus.NO) return false
        return true
    }

    /**
     * Builds the runtime config of one profile with a single SOCKS inbound.
     *
     * The config is the one the real-ping worker uses, only the inbound is replaced so
     * the temporary core listens on a free loopback port without authentication.
     */
    private fun buildConfig(context: Context, guid: String, port: Int): String {
        val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }
        val config = JsonUtil.fromJson(result.content, V2rayConfig::class.java)
            ?: error("Failed to parse V2Ray config")
        config.inbounds.clear()
        config.inbounds.add(buildSocksInbound(port))
        ensureProxyOutboundFirst(config)
        return JsonUtil.toJson(config)
    }

    /**
     * Builds the one SOCKS inbound of the temporary core.
     */
    private fun buildSocksInbound(port: Int): V2rayConfig.InboundBean = V2rayConfig.InboundBean(
        tag = "service-check",
        port = port,
        protocol = "socks",
        listen = AppConfig.LOOPBACK,
        settings = V2rayConfig.InboundBean.InSettingsBean(
            auth = "noauth",
            udp = false,
            userLevel = null,
            accounts = null,
        ),
        sniffing = null,
    )

    /**
     * Keeps the proxy outbound of the profile in the first position.
     *
     * The speed test config has no routing rule, so the core sends every connection to
     * the first outbound. A custom profile brings its own outbound order, so the first
     * outbound that is not freedom, blackhole or dns is moved to the front.
     */
    private fun ensureProxyOutboundFirst(config: V2rayConfig) {
        val outbounds = config.outbounds
        if (outbounds.isEmpty()) return
        val proxyIndex = outbounds.indexOfFirst { it.protocol !in SERVICE_CHECK_NON_PROXY_PROTOCOLS }
        if (proxyIndex > 0) {
            outbounds.add(0, outbounds.removeAt(proxyIndex))
        }
    }

    /**
     * Starts one throwaway core, the caller has to stop it.
     */
    private fun startCore(configJson: String): CoreController {
        val controller = CoreNativeManager.newCoreController(ServiceCheckCoreCallback())
        controller.startLoop(configJson, 0)
        return controller
    }

    /**
     * Stops the throwaway core, also when it never started.
     */
    private fun stopCore(controller: CoreController?, guid: String) {
        if (controller == null) return
        try {
            controller.stopLoop()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service check: failed to stop the core for $guid", e)
        }
    }

    /**
     * Waits until the SOCKS inbound accepts connections.
     */
    private suspend fun waitForSocksPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val connected = try {
                Socket().use { it.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 200) }
                true
            } catch (_: Exception) {
                false
            }
            if (connected) return true
            delay(100)
        }
        return false
    }

    /**
     * Builds the client that sends every request through the temporary SOCKS inbound.
     *
     * The hostname is resolved by the proxy, so no custom Dns is set. Redirects are not
     * followed, the same way curl does it in ipregion.sh.
     */
    private fun buildClient(port: Int, timeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port)))
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * The answer of one HTTP request.
     */
    private data class HttpOutcome(
        val code: Int,
        val location: String?,
        val body: String,
    )

    /**
     * Runs one request and returns its status code, its Location header and its body,
     * null when it failed.
     *
     * A connection error is retried once, the same way curl retries with --retry 1. The
     * body is read up to [maxBodyBytes], a bigger body is not kept.
     */
    private fun performRequest(
        client: OkHttpClient,
        request: Request,
        maxBodyBytes: Long,
    ): HttpOutcome? {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                client.newCall(request).execute().use { response ->
                    return HttpOutcome(
                        code = response.code,
                        location = response.header("Location"),
                        body = readBody(response, maxBodyBytes),
                    )
                }
            } catch (e: IOException) {
                LogUtil.w(AppConfig.TAG, "Service check: request failed, attempt $attempt", e)
            }
        }
        return null
    }

    /**
     * Reads at most [maxBodyBytes] bytes of the answer body.
     *
     * The body is read into a buffer, the same way the browser dialer reads a streamed
     * answer, so a big answer does not fill the memory of the app. The source is closed
     * by the response, it is not closed here.
     */
    private fun readBody(response: Response, maxBodyBytes: Long): String {
        val source = response.body.source()
        val buffer = Buffer()
        var remaining = maxBodyBytes
        while (remaining > 0) {
            val read = source.read(buffer, remaining)
            if (read <= 0L) break
            remaining -= read
        }
        return buffer.readUtf8()
    }

    /**
     * Shortens a body to at most [limitBytes] bytes.
     *
     * The limit counts bytes, not characters, so a body of many multi byte characters
     * is shortened as well.
     */
    private fun previewOf(body: String, limitBytes: Long): String {
        if (limitBytes <= 0L) return ""
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limitBytes) return body
        return String(bytes, 0, limitBytes.toInt(), Charsets.UTF_8)
    }

    /**
     * Adds the headers of a check to a request.
     *
     * A check that sends no user agent gets the default of the HTTP client, the same way
     * curl sends its own default.
     */
    private fun applyHeaders(
        builder: Request.Builder,
        definition: ServiceCheckDefinition,
        tunables: ServiceCheckTunables,
    ): Request.Builder {
        if (definition.useUserAgent && tunables.userAgent.isNotBlank()) {
            builder.header("User-Agent", tunables.userAgent)
        }
        definition.headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    /**
     * The reason of an HTTP answer that ipregion.sh treats as a failure.
     */
    private fun httpFailureReason(code: Int): String? = when {
        code == 403 -> "access denied ($code)"
        code == 429 -> "rate limited ($code)"
        code in 500..599 -> "server error ($code)"
        else -> null
    }

    /**
     * The result of a check that could not run because it is not configured.
     */
    private fun notConfigured(checkId: String): ServiceCheckResult =
        ServiceCheckResult(checkId, ServiceCheckStatus.UNKNOWN, reason = "check is not configured")

    /**
     * Reads the exit IP of the config, the body of the page is the address itself.
     */
    private fun checkExitIp(
        client: OkHttpClient,
        tunables: ServiceCheckTunables,
    ): ServiceCheckResult {
        val definition = tunables.definition(CHECK_EXIT_IP) ?: return notConfigured(CHECK_EXIT_IP)
        val request = applyHeaders(Request.Builder().url(definition.url).get(), definition, tunables).build()
        val outcome = performRequest(client, request, tunables.bodyPreviewLimitBytes)
            ?: return ServiceCheckResult(
                CHECK_EXIT_IP,
                ServiceCheckStatus.UNKNOWN,
                reason = "request failed",
            )
        val ip = outcome.body.trim()
        if (outcome.code == 200 && isIpAddress(ip)) {
            return ServiceCheckResult(
                CHECK_EXIT_IP,
                ServiceCheckStatus.YES,
                value = ip,
                httpCode = outcome.code,
                bodyPreview = outcome.body,
            )
        }
        return ServiceCheckResult(
            CHECK_EXIT_IP,
            ServiceCheckStatus.UNKNOWN,
            reason = "unexpected response",
            httpCode = outcome.code,
            bodyPreview = outcome.body,
        )
    }

    /**
     * Checks the answer of the identity service, the same patterns as ipregion.sh.
     */
    private fun isIpAddress(value: String): Boolean =
        SERVICE_CHECK_IPV4_PATTERN.matches(value) || SERVICE_CHECK_IPV6_PATTERN.matches(value)

    /**
     * Asks the YouTube InnerTube API whether Premium is offered for the exit IP.
     */
    private fun checkYoutubePremium(
        client: OkHttpClient,
        tunables: ServiceCheckTunables,
    ): ServiceCheckResult {
        val definition = tunables.definition(CHECK_YOUTUBE_PREMIUM)
            ?: return notConfigured(CHECK_YOUTUBE_PREMIUM)
        val body = definition.body.orEmpty().toByteArray()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = applyHeaders(
            Request.Builder().url(definition.url),
            definition,
            tunables,
        ).post(body).build()
        val outcome = performRequest(client, request, tunables.bodyPreviewLimitBytes)
            ?: return ServiceCheckResult(
                CHECK_YOUTUBE_PREMIUM,
                ServiceCheckStatus.UNKNOWN,
                reason = "request failed",
            )
        if (outcome.code != 200) {
            return ServiceCheckResult(
                CHECK_YOUTUBE_PREMIUM,
                ServiceCheckStatus.UNKNOWN,
                reason = httpFailureReason(outcome.code) ?: "unexpected response",
                httpCode = outcome.code,
                bodyPreview = outcome.body,
            )
        }
        val marker = definition.marker.orEmpty()
        val status = if (marker.isNotEmpty() && outcome.body.contains(marker)) {
            ServiceCheckStatus.YES
        } else {
            ServiceCheckStatus.NO
        }
        return ServiceCheckResult(
            CHECK_YOUTUBE_PREMIUM,
            status,
            httpCode = outcome.code,
            bodyPreview = outcome.body,
        )
    }

    /**
     * Reads the country code of the exit IP from the YouTube country answer.
     */
    private fun checkYoutubeCountry(
        client: OkHttpClient,
        tunables: ServiceCheckTunables,
    ): ServiceCheckResult {
        val definition = tunables.definition(CHECK_YOUTUBE_COUNTRY)
            ?: return notConfigured(CHECK_YOUTUBE_COUNTRY)
        val request = applyHeaders(Request.Builder().url(definition.url).get(), definition, tunables).build()
        val outcome = performRequest(client, request, tunables.bodyPreviewLimitBytes)
            ?: return ServiceCheckResult(
                CHECK_YOUTUBE_COUNTRY,
                ServiceCheckStatus.UNKNOWN,
                reason = "request failed",
            )
        val failure = httpFailureReason(outcome.code)
        if (failure != null) {
            return ServiceCheckResult(
                CHECK_YOUTUBE_COUNTRY,
                ServiceCheckStatus.UNKNOWN,
                reason = failure,
                httpCode = outcome.code,
                locationHeader = outcome.location,
                bodyPreview = outcome.body,
            )
        }
        val prefix = definition.jsonPrefix.orEmpty()
        val json = if (prefix.isNotEmpty() && outcome.body.startsWith(prefix)) {
            outcome.body.substring(prefix.length)
        } else {
            outcome.body
        }
        val country = extractYoutubeCountry(json)
        if (outcome.code == 200 && country != null && SERVICE_CHECK_COUNTRY_PATTERN.matches(country)) {
            return ServiceCheckResult(
                CHECK_YOUTUBE_COUNTRY,
                ServiceCheckStatus.YES,
                value = country,
                httpCode = outcome.code,
                bodyPreview = outcome.body,
            )
        }
        return ServiceCheckResult(
            CHECK_YOUTUBE_COUNTRY,
            ServiceCheckStatus.UNKNOWN,
            reason = "unexpected response",
            httpCode = outcome.code,
            bodyPreview = outcome.body,
        )
    }

    /**
     * Reads the country code of the exit IP from the YouTube country answer.
     *
     * The answer is a JSON array, the country code is at the path [0][2][0][0][1].
     */
    private fun extractYoutubeCountry(json: String): String? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        var current: JsonElement = root
        for (index in SERVICE_CHECK_YOUTUBE_COUNTRY_PATH) {
            val array = current as? JsonArray ?: return null
            if (index >= array.size()) return null
            current = array.get(index)
        }
        if (!current.isJsonPrimitive) return null
        return runCatching { current.asString }.getOrNull()
    }

    /**
     * Reads the country code of the exit IP from the Google sign-in answer.
     */
    private fun checkGoogleCountry(
        client: OkHttpClient,
        tunables: ServiceCheckTunables,
    ): ServiceCheckResult {
        val definition = tunables.definition(CHECK_GOOGLE_COUNTRY)
            ?: return notConfigured(CHECK_GOOGLE_COUNTRY)
        val pattern = definition.countryPattern?.let { runCatching { Regex(it) }.getOrNull() }
            ?: return notConfigured(CHECK_GOOGLE_COUNTRY)
        val request = applyHeaders(Request.Builder().url(definition.url).get(), definition, tunables).build()
        val outcome = performRequest(client, request, tunables.bodyPreviewLimitBytes)
            ?: return ServiceCheckResult(
                CHECK_GOOGLE_COUNTRY,
                ServiceCheckStatus.UNKNOWN,
                reason = "request failed",
            )
        val failure = httpFailureReason(outcome.code)
        if (failure != null) {
            return ServiceCheckResult(
                CHECK_GOOGLE_COUNTRY,
                ServiceCheckStatus.UNKNOWN,
                reason = failure,
                httpCode = outcome.code,
                locationHeader = outcome.location,
                bodyPreview = outcome.body,
            )
        }
        val country = pattern.find(outcome.body)?.groupValues?.getOrNull(1)
        if (outcome.code == 200 && country != null && SERVICE_CHECK_COUNTRY_PATTERN.matches(country)) {
            return ServiceCheckResult(
                CHECK_GOOGLE_COUNTRY,
                ServiceCheckStatus.YES,
                value = country,
                httpCode = outcome.code,
                bodyPreview = outcome.body,
            )
        }
        return ServiceCheckResult(
            CHECK_GOOGLE_COUNTRY,
            ServiceCheckStatus.UNKNOWN,
            reason = "unexpected response",
            httpCode = outcome.code,
            bodyPreview = outcome.body,
        )
    }

    /**
     * Checks whether Google shows a captcha for the exit IP.
     */
    private fun checkGoogleSearchCaptcha(
        client: OkHttpClient,
        tunables: ServiceCheckTunables,
    ): ServiceCheckResult {
        val definition = tunables.definition(CHECK_GOOGLE_SEARCH_CAPTCHA)
            ?: return notConfigured(CHECK_GOOGLE_SEARCH_CAPTCHA)
        val request = applyHeaders(Request.Builder().url(definition.url).get(), definition, tunables).build()
        val outcome = performRequest(client, request, tunables.bodyPreviewLimitBytes)
            ?: return ServiceCheckResult(
                CHECK_GOOGLE_SEARCH_CAPTCHA,
                ServiceCheckStatus.UNKNOWN,
                reason = "request failed",
            )
        val captchaPath = definition.captchaPath.orEmpty()
        val isCaptcha = (captchaPath.isNotEmpty() && outcome.location?.contains(captchaPath) == true) ||
            outcome.code == 429
        if (isCaptcha) {
            return ServiceCheckResult(
                CHECK_GOOGLE_SEARCH_CAPTCHA,
                ServiceCheckStatus.IPBLOCK,
                reason = "captcha",
                httpCode = outcome.code,
                locationHeader = outcome.location,
                bodyPreview = outcome.body,
            )
        }
        val noJsMarker = definition.noJsMarker.orEmpty()
        if (outcome.code == 200 && noJsMarker.isNotEmpty() && outcome.body.contains(noJsMarker)) {
            return ServiceCheckResult(
                CHECK_GOOGLE_SEARCH_CAPTCHA,
                ServiceCheckStatus.YES,
                httpCode = outcome.code,
                locationHeader = outcome.location,
                bodyPreview = outcome.body,
            )
        }
        return ServiceCheckResult(
            CHECK_GOOGLE_SEARCH_CAPTCHA,
            ServiceCheckStatus.UNKNOWN,
            reason = httpFailureReason(outcome.code) ?: "unexpected response",
            httpCode = outcome.code,
            locationHeader = outcome.location,
            bodyPreview = outcome.body,
        )
    }

    /**
     * The answer of the YouTube Music check.
     */
    private data class MusicOutcome(
        val result: ServiceCheckResult,
        val body: String?,
    )

    /**
     * Checks whether YouTube Music is available for the exit IP.
     *
     * The marker lists are empty by default, so the check answers `UNKNOWN` until the
     * user fills them in the asset file. The whole body is captured, the user needs it
     * to find the "not available in your area" message.
     */
    private fun checkYoutubeMusic(
        client: OkHttpClient,
        tunables: ServiceCheckTunables,
    ): MusicOutcome {
        val definition = tunables.definition(CHECK_YOUTUBE_MUSIC)
            ?: return MusicOutcome(notConfigured(CHECK_YOUTUBE_MUSIC), null)
        val request = applyHeaders(Request.Builder().url(definition.url).get(), definition, tunables).build()
        val outcome = performRequest(client, request, tunables.musicBodyLimitBytes)
            ?: return MusicOutcome(
                ServiceCheckResult(
                    CHECK_YOUTUBE_MUSIC,
                    ServiceCheckStatus.UNKNOWN,
                    reason = "request failed",
                ),
                null,
            )
        val preview = previewOf(outcome.body, tunables.bodyPreviewLimitBytes)
        val blocked = definition.blockedMarkers.firstOrNull { marker ->
            marker.isNotEmpty() && outcome.body.contains(marker)
        }
        if (blocked != null) {
            return MusicOutcome(
                ServiceCheckResult(
                    CHECK_YOUTUBE_MUSIC,
                    ServiceCheckStatus.NO,
                    reason = "blocked marker",
                    httpCode = outcome.code,
                    locationHeader = outcome.location,
                    bodyPreview = preview,
                ),
                outcome.body,
            )
        }
        val available = definition.availableMarkers.firstOrNull { marker ->
            marker.isNotEmpty() && outcome.body.contains(marker)
        }
        if (available != null) {
            return MusicOutcome(
                ServiceCheckResult(
                    CHECK_YOUTUBE_MUSIC,
                    ServiceCheckStatus.YES,
                    httpCode = outcome.code,
                    locationHeader = outcome.location,
                    bodyPreview = preview,
                ),
                outcome.body,
            )
        }
        return MusicOutcome(
            ServiceCheckResult(
                CHECK_YOUTUBE_MUSIC,
                ServiceCheckStatus.UNKNOWN,
                reason = httpFailureReason(outcome.code) ?: "no marker matched",
                httpCode = outcome.code,
                locationHeader = outcome.location,
                bodyPreview = preview,
            ),
            outcome.body,
        )
    }

    /**
     * Callback of the throwaway core, the service check does not need any core event.
     */
    private class ServiceCheckCoreCallback : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long = 0

        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
