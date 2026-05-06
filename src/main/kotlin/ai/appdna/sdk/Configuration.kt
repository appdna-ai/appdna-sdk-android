package ai.appdna.sdk

/**
 * SDK environment targeting.
 */
enum class Environment(val baseUrl: String) {
    // Both environments share the same backend host — iOS parity. The
    // SANDBOX vs PRODUCTION distinction is enforced by API-key prefix
    // (adn_test_* vs adn_live_*) which the backend routes accordingly.
    // iOS reference: Core/Network/APIClient.swift:107-108.
    PRODUCTION("https://api.appdna.ai"),
    SANDBOX("https://api.appdna.ai")
}

/**
 * Log verbosity levels.
 */
enum class LogLevel(val level: Int) {
    NONE(0),
    ERROR(1),
    WARNING(2),
    INFO(3),
    DEBUG(4)
}

/**
 * Configuration options for the AppDNA SDK.
 */
data class AppDNAOptions(
    /** Automatic flush interval in seconds. Default: 30. */
    val flushInterval: Long = 30L,
    /** Number of events per flush batch. Default: 20. */
    val batchSize: Int = 20,
    /** Remote config cache TTL in seconds. Default: 3600 (1 hour). SPEC-067. */
    val configTTL: Long = 3600L,
    /** Log verbosity. Default: WARNING. */
    val logLevel: LogLevel = LogLevel.WARNING
)

/**
 * Internal logger.
 *
 * SPEC-070-A G.22: Lambda-variant API — message providers are only invoked when
 * the level is enabled, so callers can safely build expensive strings (e.g. JSON
 * dumps) without paying the cost when `LogLevel.WARNING` is the floor.
 *
 * Both lambda + string forms are supported so existing call sites compile
 * unchanged. New call sites should prefer the lambda variant:
 *
 *     Log.debug { "Tracked event: $event with ${properties?.size} props" }
 */
internal object Log {
    var level: LogLevel = LogLevel.WARNING

    fun error(messageProvider: () -> String) {
        if (level.level >= LogLevel.ERROR.level) println("[AppDNA][ERROR] ${messageProvider()}")
    }

    fun warning(messageProvider: () -> String) {
        if (level.level >= LogLevel.WARNING.level) println("[AppDNA][WARN] ${messageProvider()}")
    }

    fun info(messageProvider: () -> String) {
        if (level.level >= LogLevel.INFO.level) println("[AppDNA][INFO] ${messageProvider()}")
    }

    fun debug(messageProvider: () -> String) {
        if (level.level >= LogLevel.DEBUG.level) println("[AppDNA][DEBUG] ${messageProvider()}")
    }

    // String overloads for backward compatibility with existing call sites.
    // `Log.warning("msg")` continues to work alongside `Log.warning { "msg" }`.
    fun error(message: String) {
        if (level.level >= LogLevel.ERROR.level) println("[AppDNA][ERROR] $message")
    }

    fun warning(message: String) {
        if (level.level >= LogLevel.WARNING.level) println("[AppDNA][WARN] $message")
    }

    fun info(message: String) {
        if (level.level >= LogLevel.INFO.level) println("[AppDNA][INFO] $message")
    }

    fun debug(message: String) {
        if (level.level >= LogLevel.DEBUG.level) println("[AppDNA][DEBUG] $message")
    }
}
