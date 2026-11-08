package com.v2ray.ang.core

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Number of configs that are checked at the same time.
 *
 * The real-ping worker uses the user setting for this, but a service check starts one
 * native core per config, so the amount of parallel cores is kept low and constant.
 */
const val SERVICE_CHECK_CONCURRENCY = 3

/** Pause between two checks of the same config. */
const val SERVICE_CHECK_INTERVAL_MS = 300L

/** Timeout of one HTTP check, same value as ipregion.sh DEFAULT_TIMEOUT. */
private const val SERVICE_CHECK_TIMEOUT_SECONDS = 5L

/** How long the temporary core may take to open its SOCKS inbound. */
private const val SERVICE_CHECK_PORT_TIMEOUT_MS = 3_000L

/** Protocols that never carry the traffic of a profile. */
private val SERVICE_CHECK_NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns")

/** ipregion.sh constant IPV4_PATTERN. */
private val SERVICE_CHECK_IPV4_PATTERN = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")

/** ipregion.sh constant IPV6_PATTERN. */
private val SERVICE_CHECK_IPV6_PATTERN = Regex("^[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){2,7}$")

/** Result of one check. */
enum class ServiceCheckStatus {
    YES,
    NO,
    IPBLOCK,
    UNKNOWN
}

/** Result of one check. */
data class ServiceCheckResult(
    val checkId: String,
    val status: ServiceCheckStatus,
    val value: String? = null,
    val reason: String? = null,
    val httpCode: Int? = null,
)

/** Results of every check of one config. */
data class ServiceCheckReport(
    val guid: String,
    val results: List<ServiceCheckResult>,
    val error: String? = null,
) {
    fun result(checkId: String): ServiceCheckResult? = results.firstOrNull { it.checkId == checkId }
}

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

    /** ipregion.sh fetches the external IP from this list without any extra header. */
    private const val EXIT_IP_URL = "https://api.ipify.org"

    /** ipregion.sh constant YOUTUBE_INNERTUBE_BROWSE_URL. */
    private const val YOUTUBE_PREMIUM_URL = "https://www.youtube.com/youtubei/v1/browse"

    /** ipregion.sh constant YOUTUBE_PREMIUM_QUERY. */
    private const val YOUTUBE_PREMIUM_BODY =
        "{\"context\":{\"client\":{\"clientName\":\"MWEB\",\"clientVersion\":\"2.20261020.00.00\"," +
            "\"hl\":\"en\",\"gl\":\"US\"}},\"browseId\":\"SPunlimited\"}"

    /** ipregion.sh constant YOUTUBE_PREMIUM_PURCHASE_MARKER. */
    private const val YOUTUBE_PREMIUM_MARKER = "premiumPurchaseButtonRenderer"

    /**
     * Runs every check of one config and returns its report.
     *
     * The temporary core is always stopped, also when the caller is cancelled.
     */
    suspend fun check(context: Context, guid: String): ServiceCheckReport = withContext(Dispatchers.IO) {
        val port = try {
            Utils.findRandomFreePort()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Service check: no free port for $guid", e)
            return@withContext ServiceCheckReport(guid, emptyList(), e.message ?: e.javaClass.simpleName)
        }

        val configJson = try {
            buildConfig(context, guid, port)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service check: failed to build the config for $guid", e)
            return@withContext ServiceCheckReport(guid, emptyList(), e.message ?: e.javaClass.simpleName)
        }

        var controller: CoreController? = null
        try {
            CoreNativeManager.initCoreEnv(context.applicationContext)
            controller = startCore(configJson)

            if (!waitForSocksPort(port, SERVICE_CHECK_PORT_TIMEOUT_MS)) {
                return@withContext ServiceCheckReport(guid, emptyList(), "SOCKS inbound did not open")
            }

            val client = buildClient(port)
            val results = mutableListOf<ServiceCheckResult>()
            results += checkExitIp(client)
            delay(SERVICE_CHECK_INTERVAL_MS)
            results += checkYoutubePremium(client)
            ServiceCheckReport(guid, results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service check: failed for $guid", e)
            ServiceCheckReport(guid, emptyList(), e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                stopCore(controller, guid)
            }
        }
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
    private fun buildClient(port: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port)))
        .callTimeout(SERVICE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(SERVICE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SERVICE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Runs one request and returns its status code and body, null when it failed.
     *
     * A connection error is retried once, the same way curl retries with --retry 1.
     */
    private fun performRequest(client: OkHttpClient, request: Request): Pair<Int, String>? {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                client.newCall(request).execute().use { response ->
                    return response.code to response.body.string()
                }
            } catch (e: IOException) {
                LogUtil.w(AppConfig.TAG, "Service check: request failed, attempt $attempt", e)
            }
        }
        return null
    }

    /**
     * Reads the exit IP of the config, the body of the page is the address itself.
     */
    private fun checkExitIp(client: OkHttpClient): ServiceCheckResult {
        val request = Request.Builder().url(EXIT_IP_URL).get().build()
        val outcome = performRequest(client, request)
            ?: return ServiceCheckResult(CHECK_EXIT_IP, ServiceCheckStatus.UNKNOWN, reason = "request failed")
        val (code, body) = outcome
        val ip = body.trim()
        if (code == 200 && isIpAddress(ip)) {
            return ServiceCheckResult(CHECK_EXIT_IP, ServiceCheckStatus.YES, value = ip, httpCode = code)
        }
        return ServiceCheckResult(
            CHECK_EXIT_IP,
            ServiceCheckStatus.UNKNOWN,
            reason = "unexpected response",
            httpCode = code,
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
    private fun checkYoutubePremium(client: OkHttpClient): ServiceCheckResult {
        val body = YOUTUBE_PREMIUM_BODY.toByteArray()
            .toRequestBody("application/json".toMediaTypeOrNull())
        // curl's --json sets both the content type and the accept header.
        val request = Request.Builder()
            .url(YOUTUBE_PREMIUM_URL)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val outcome = performRequest(client, request)
            ?: return ServiceCheckResult(
                CHECK_YOUTUBE_PREMIUM,
                ServiceCheckStatus.UNKNOWN,
                reason = "request failed",
            )
        val (code, responseBody) = outcome
        if (code != 200) {
            return ServiceCheckResult(
                CHECK_YOUTUBE_PREMIUM,
                ServiceCheckStatus.UNKNOWN,
                reason = "http $code",
                httpCode = code,
            )
        }
        val status = if (responseBody.contains(YOUTUBE_PREMIUM_MARKER)) {
            ServiceCheckStatus.YES
        } else {
            ServiceCheckStatus.NO
        }
        return ServiceCheckResult(CHECK_YOUTUBE_PREMIUM, status, httpCode = code)
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
