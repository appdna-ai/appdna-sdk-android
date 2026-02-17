package ai.appdna.sdk.config

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker

/**
 * Manages experiment variant assignment via deterministic MurmurHash3 bucketing.
 * Tracks exposure events once per session per experiment.
 *
 * MurmurHash3 implementation MUST produce identical output to the iOS Swift version.
 */
internal class ExperimentManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val identityManager: IdentityManager,
    private val eventTracker: EventTracker
) {
    private val exposedExperiments = mutableSetOf<String>()

    /**
     * Get the variant for an experiment. Returns null if not eligible.
     * Auto-tracks exposure event on first call per session.
     */
    fun getVariant(experimentId: String): ExperimentVariant? {
        val config = resolveConfig(experimentId) ?: return null

        val identity = identityManager.currentIdentity
        val userId = identity.userId ?: identity.anonId

        val variant = assignVariant(
            experimentId = experimentId,
            userId = userId,
            salt = config.salt,
            variants = config.variants
        ) ?: return null

        // Track exposure once per session
        trackExposure(experimentId, variant.id)

        return variant
    }

    /**
     * Check if the user is assigned to a specific variant.
     */
    fun isInVariant(experimentId: String, variantId: String): Boolean {
        return getVariant(experimentId)?.id == variantId
    }

    /**
     * Get a specific config value from the assigned variant.
     */
    fun getExperimentConfig(experimentId: String, key: String): Any? {
        val variant = getVariant(experimentId) ?: return null
        return variant.config[key]
    }

    /**
     * Reset exposure tracking (called on identity reset or new session).
     */
    fun resetExposures() {
        synchronized(exposedExperiments) {
            exposedExperiments.clear()
        }
    }

    // MARK: - Private

    private fun resolveConfig(experimentId: String): ExperimentConfig? {
        val config = remoteConfigManager.getExperimentConfig(experimentId) ?: run {
            Log.debug("Experiment '$experimentId' not found in config")
            return null
        }

        if (config.status != "running") {
            Log.debug("Experiment '$experimentId' is not running (status: ${config.status})")
            return null
        }

        if (!config.platforms.contains("android")) {
            Log.debug("Experiment '$experimentId' does not target Android")
            return null
        }

        return config
    }

    private fun assignVariant(
        experimentId: String,
        userId: String,
        salt: String,
        variants: List<ExperimentVariant>
    ): ExperimentVariant? {
        if (variants.isEmpty()) return null

        val hashInput = "$experimentId.$salt.$userId"
        val hash = MurmurHash3.hash32(hashInput)
        val bucket = hash.toUInt() % 10000u

        var cumulative = 0u
        for (variant in variants) {
            cumulative += (variant.weight * 10000).toUInt()
            if (bucket < cumulative) {
                return variant
            }
        }

        return variants.last()
    }

    private fun trackExposure(experimentId: String, variantId: String) {
        synchronized(exposedExperiments) {
            if (!exposedExperiments.contains(experimentId)) {
                exposedExperiments.add(experimentId)
                eventTracker.track("experiment_exposure", mapOf(
                    "experiment_id" to experimentId,
                    "variant" to variantId,
                    "source" to "sdk"
                ))
            }
        }
    }
}

/**
 * MurmurHash3 32-bit implementation — MUST produce identical output to iOS Swift version.
 */
object MurmurHash3 {
    /**
     * Standard MurmurHash3 32-bit hash.
     */
    fun hash32(key: String, seed: UInt = 0u): UInt {
        val data = key.toByteArray(Charsets.UTF_8)
        val len = data.size
        val nblocks = len / 4

        var h1 = seed

        val c1: UInt = 0xcc9e2d51u
        val c2: UInt = 0x1b873593u

        // Body — process 4-byte blocks
        for (i in 0 until nblocks) {
            val offset = i * 4
            var k1: UInt = (data[offset].toUInt() and 0xFFu) or
                    ((data[offset + 1].toUInt() and 0xFFu) shl 8) or
                    ((data[offset + 2].toUInt() and 0xFFu) shl 16) or
                    ((data[offset + 3].toUInt() and 0xFFu) shl 24)

            k1 *= c1
            k1 = k1.rotateLeft(15)
            k1 *= c2

            h1 = h1 xor k1
            h1 = h1.rotateLeft(13)
            h1 = h1 * 5u + 0xe6546b64u
        }

        // Tail — process remaining bytes
        val tail = nblocks * 4
        var k1: UInt = 0u

        when (len and 3) {
            3 -> {
                k1 = k1 xor ((data[tail + 2].toUInt() and 0xFFu) shl 16)
                k1 = k1 xor ((data[tail + 1].toUInt() and 0xFFu) shl 8)
                k1 = k1 xor (data[tail].toUInt() and 0xFFu)
                k1 *= c1
                k1 = k1.rotateLeft(15)
                k1 *= c2
                h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((data[tail + 1].toUInt() and 0xFFu) shl 8)
                k1 = k1 xor (data[tail].toUInt() and 0xFFu)
                k1 *= c1
                k1 = k1.rotateLeft(15)
                k1 *= c2
                h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (data[tail].toUInt() and 0xFFu)
                k1 *= c1
                k1 = k1.rotateLeft(15)
                k1 *= c2
                h1 = h1 xor k1
            }
        }

        // Finalization
        h1 = h1 xor len.toUInt()
        h1 = h1 xor (h1 shr 16)
        h1 *= 0x85ebca6bu
        h1 = h1 xor (h1 shr 13)
        h1 *= 0xc2b2ae35u
        h1 = h1 xor (h1 shr 16)

        return h1
    }

    private fun UInt.rotateLeft(distance: Int): UInt {
        return (this shl distance) or (this shr (32 - distance))
    }
}
