package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/** Name of the asset file that holds the tunables of the service checks. */
private const val SERVICE_CHECK_ASSET = "ipregion_checks.json"

/** Default timeout of one HTTP check, same value as ipregion.sh DEFAULT_TIMEOUT. */
private const val SERVICE_CHECK_DEFAULT_TIMEOUT_SECONDS = 5L

/** Default time the temporary core may take to open its SOCKS inbound. */
private const val SERVICE_CHECK_DEFAULT_PORT_TIMEOUT_MS = 3_000L

/** Default pause between two checks of the same config. */
private const val SERVICE_CHECK_DEFAULT_INTERVAL_MS = 300L

/** Default number of bytes of a body preview. */
private const val SERVICE_CHECK_DEFAULT_BODY_PREVIEW_BYTES = 4_096L

/** Default number of bytes of the captured YouTube Music body. */
private const val SERVICE_CHECK_DEFAULT_MUSIC_BODY_BYTES = 65_536L

/**
 * One check definition of the asset file.
 *
 * The definition holds only values that can change, the decision logic stays in
 * [ServiceCheckRunner].
 *
 * @param url The URL of the request.
 * @param method The HTTP method, `GET` or `POST`.
 * @param useUserAgent Whether the shared user agent is sent. The checks of ipregion.sh
 * that send no `User-Agent` keep this false, so the HTTP client uses its own default.
 * @param headers The extra headers of the request.
 * @param body The request body of a `POST` request.
 * @param marker The marker the body of the YouTube Premium answer must contain.
 * @param jsonPrefix The prefix that is stripped before the YouTube country answer is parsed.
 * @param countryPattern The regular expression that extracts the Google country code.
 * @param captchaPath The path of the Google captcha answer.
 * @param noJsMarker The marker of the Google answer that has no captcha.
 * @param blockedMarkers The markers of a blocked YouTube Music answer.
 * @param availableMarkers The markers of an available YouTube Music answer.
 */
internal data class ServiceCheckDefinition(
    val url: String,
    val method: String,
    val useUserAgent: Boolean,
    val headers: Map<String, String>,
    val body: String?,
    val marker: String?,
    val jsonPrefix: String?,
    val countryPattern: String?,
    val captchaPath: String?,
    val noJsMarker: String?,
    val blockedMarkers: List<String>,
    val availableMarkers: List<String>,
)

/**
 * All tunables of the service checks.
 *
 * @param timeoutSeconds The timeout of one HTTP check.
 * @param portTimeoutMs The time the temporary core may take to open its SOCKS inbound.
 * @param checkIntervalMs The pause between two checks of the same config.
 * @param bodyPreviewLimitBytes The number of bytes of a body preview.
 * @param musicBodyLimitBytes The number of bytes of the captured YouTube Music body.
 * @param userAgent The user agent of the checks that send one.
 * @param checks The definition of every check, the key is the check id.
 */
internal data class ServiceCheckTunables(
    val timeoutSeconds: Long,
    val portTimeoutMs: Long,
    val checkIntervalMs: Long,
    val bodyPreviewLimitBytes: Long,
    val musicBodyLimitBytes: Long,
    val userAgent: String,
    val checks: Map<String, ServiceCheckDefinition>,
) {
    /**
     * The definition of a check, null when the check is not configured.
     */
    fun definition(checkId: String): ServiceCheckDefinition? {
        val definition = checks[checkId] ?: return null
        if (definition.url.isBlank()) return null
        return definition
    }
}

/**
 * Reads the tunables of the service checks from `assets/ipregion_checks.json`.
 *
 * The asset file holds only values, every value has a default in this file, so a
 * missing or broken entry never stops the app. The file is parsed with the JSON tree
 * API of the JSON library of the project, so a field that is absent from the file
 * simply keeps its default.
 */
internal object ServiceCheckConfig {

    @Volatile
    private var cached: ServiceCheckTunables? = null

    /**
     * Returns the tunables, the asset file is read only once.
     */
    fun load(context: Context): ServiceCheckTunables {
        cached?.let { return it }
        val parsed = parse(Utils.readTextFromAssets(context.applicationContext, SERVICE_CHECK_ASSET))
        // A broken file is not cached, so a later read can still succeed.
        if (parsed.checks.isNotEmpty()) {
            cached = parsed
        }
        return parsed
    }

    /**
     * Parses the asset file, an unreadable file gives the default tunables.
     */
    private fun parse(json: String): ServiceCheckTunables {
        val root = JsonUtil.parseString(json)
        if (root == null) {
            LogUtil.e(AppConfig.TAG, "Service check config $SERVICE_CHECK_ASSET is not a JSON object")
            return defaults()
        }
        return ServiceCheckTunables(
            timeoutSeconds = root.optLong("timeoutSeconds") ?: SERVICE_CHECK_DEFAULT_TIMEOUT_SECONDS,
            portTimeoutMs = root.optLong("portTimeoutMs") ?: SERVICE_CHECK_DEFAULT_PORT_TIMEOUT_MS,
            checkIntervalMs = root.optLong("checkIntervalMs") ?: SERVICE_CHECK_DEFAULT_INTERVAL_MS,
            bodyPreviewLimitBytes = root.optLong("bodyPreviewLimitBytes")
                ?: SERVICE_CHECK_DEFAULT_BODY_PREVIEW_BYTES,
            musicBodyLimitBytes = root.optLong("musicBodyLimitBytes")
                ?: SERVICE_CHECK_DEFAULT_MUSIC_BODY_BYTES,
            userAgent = root.optString("userAgent").orEmpty(),
            checks = parseChecks(root.optObject("checks")),
        )
    }

    /**
     * Parses the definition of every check.
     */
    private fun parseChecks(checks: JsonObject?): Map<String, ServiceCheckDefinition> {
        if (checks == null) return emptyMap()
        val result = LinkedHashMap<String, ServiceCheckDefinition>()
        checks.entrySet().forEach { (id, element) ->
            val definition = element as? JsonObject ?: return@forEach
            result[id] = ServiceCheckDefinition(
                url = definition.optString("url").orEmpty(),
                method = definition.optString("method") ?: "GET",
                useUserAgent = definition.optBoolean("useUserAgent") ?: false,
                headers = parseHeaders(definition.optObject("headers")),
                body = definition.optString("body"),
                marker = definition.optString("marker"),
                jsonPrefix = definition.optString("jsonPrefix"),
                countryPattern = definition.optString("countryPattern"),
                captchaPath = definition.optString("captchaPath"),
                noJsMarker = definition.optString("noJsMarker"),
                blockedMarkers = parseStrings(definition.optArray("blockedMarkers")),
                availableMarkers = parseStrings(definition.optArray("availableMarkers")),
            )
        }
        return result
    }

    /**
     * Parses a map of headers.
     */
    private fun parseHeaders(headers: JsonObject?): Map<String, String> {
        if (headers == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        headers.entrySet().forEach { (name, element) ->
            if (name.isBlank() || !element.isJsonPrimitive) return@forEach
            result[name] = element.asString
        }
        return result
    }

    /**
     * Parses a list of strings.
     */
    private fun parseStrings(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.size())
        for (index in 0 until array.size()) {
            val element = array.get(index)
            if (element.isJsonPrimitive) {
                result.add(element.asString)
            }
        }
        return result
    }

    /**
     * The tunables that are used when the asset file cannot be read.
     */
    private fun defaults(): ServiceCheckTunables = ServiceCheckTunables(
        timeoutSeconds = SERVICE_CHECK_DEFAULT_TIMEOUT_SECONDS,
        portTimeoutMs = SERVICE_CHECK_DEFAULT_PORT_TIMEOUT_MS,
        checkIntervalMs = SERVICE_CHECK_DEFAULT_INTERVAL_MS,
        bodyPreviewLimitBytes = SERVICE_CHECK_DEFAULT_BODY_PREVIEW_BYTES,
        musicBodyLimitBytes = SERVICE_CHECK_DEFAULT_MUSIC_BODY_BYTES,
        userAgent = "",
        checks = emptyMap(),
    )
}

/**
 * Reads a string of a JSON object, null when it is absent or not a primitive.
 */
private fun JsonObject.optString(name: String): String? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return element.asString
}

/**
 * Reads a number of a JSON object, null when it is absent or not a primitive.
 */
private fun JsonObject.optLong(name: String): Long? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asLong }.getOrNull()
}

/**
 * Reads a boolean of a JSON object, null when it is absent or not a primitive.
 */
private fun JsonObject.optBoolean(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asBoolean }.getOrNull()
}

/**
 * Reads a nested JSON object, null when it is absent or not an object.
 */
private fun JsonObject.optObject(name: String): JsonObject? = get(name) as? JsonObject

/**
 * Reads a JSON array, null when it is absent or not an array.
 */
private fun JsonObject.optArray(name: String): JsonArray? = get(name) as? JsonArray
