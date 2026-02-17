package ai.appdna.sdk

/**
 * SDK environment targeting.
 */
enum class Environment(val baseUrl: String) {
    PRODUCTION("https://api.appdna.ai"),
    SANDBOX("https://sandbox-api.appdna.ai")
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
    /** Remote config cache TTL in seconds. Default: 300 (5 min). */
    val configTTL: Long = 300L,
    /** Log verbosity. Default: WARNING. */
    val logLevel: LogLevel = LogLevel.WARNING
)

/**
 * Internal logger.
 */
internal object Log {
    var level: LogLevel = LogLevel.WARNING

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
