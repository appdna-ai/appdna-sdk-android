package ai.appdna.sdk.events

import ai.appdna.sdk.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC-070-A G.19: Wire-format integration test that locks the JSON shape
 * Android emits against iOS Codable behavior.
 *
 * iOS rule (Sources/AppDNASDK/Events/EventSchema.swift `SDKEvent.encode(to:)`):
 *   - `properties: [String: AnyCodable]?` — when null OR empty, the field is
 *     omitted entirely from the envelope (Codable optional encoding).
 *   - `experiment_exposures` — likewise omitted when nil/empty.
 *
 * Android must mirror this so cross-platform analytics ingest sees a stable
 * shape regardless of which SDK emitted the event.
 *
 * SPEC-070-A G.10/G.15/G.16/G.17 are also covered here so the field-level
 * serialization is locked alongside the omit-when-empty rule.
 */
class EventEnvelopeWireFormatTest {

    private val identity = DeviceIdentity(anonId = "anon-1", userId = "user-2")

    @Test
    fun propertiesField_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        assertFalse(
            "properties must be omitted when null (iOS Codable parity)",
            envelope.has("properties")
        )
    }

    @Test
    fun propertiesField_isOmitted_whenEmptyMap() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = emptyMap(),
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        assertFalse(
            "properties must be omitted when empty (iOS Codable parity)",
            envelope.has("properties")
        )
    }

    @Test
    fun propertiesField_isPresent_whenNonEmpty() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = mapOf("k" to "v"),
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        assertTrue(envelope.has("properties"))
        assertEquals("v", envelope.getJSONObject("properties").getString("k"))
    }

    @Test
    fun experimentExposures_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            experimentExposures = null,
        )
        val context = envelope.getJSONObject("context")
        assertFalse(context.has("experiment_exposures"))
    }

    @Test
    fun experimentExposures_isOmitted_whenEmpty() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            experimentExposures = emptyList(),
        )
        val context = envelope.getJSONObject("context")
        assertFalse(context.has("experiment_exposures"))
    }

    @Test
    fun experimentExposures_emitsTuples_whenPresent() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            experimentExposures = listOf(
                ExperimentExposure("exp-A", "var-1"),
                ExperimentExposure("exp-B", "var-2"),
            ),
        )
        val arr = envelope.getJSONObject("context").getJSONArray("experiment_exposures")
        assertEquals(2, arr.length())
        assertEquals("exp-A", arr.getJSONObject(0).getString("exp"))
        assertEquals("var-1", arr.getJSONObject(0).getString("variant"))
        assertEquals("exp-B", arr.getJSONObject(1).getString("exp"))
    }

    // SPEC-070-A G.10: environment is omitted when null, present when supplied.
    @Test
    fun environment_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            environment = null,
        )
        assertFalse(envelope.has("environment"))
    }

    @Test
    fun environment_isPresent_whenSupplied() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            environment = "sandbox",
        )
        assertEquals("sandbox", envelope.getString("environment"))
    }

    // SPEC-070-A G.17: context.screen is omitted when null, present when supplied.
    @Test
    fun contextScreen_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            screen = null,
        )
        val context = envelope.getJSONObject("context")
        assertFalse(context.has("screen"))
    }

    @Test
    fun contextScreen_isPresent_whenSupplied() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            screen = "HomeFeed",
        )
        val context = envelope.getJSONObject("context")
        assertEquals("HomeFeed", context.getString("screen"))
    }

    // SPEC-070-A G.15: device.os is normalized to a 3-part dotted string.
    @Test
    fun deviceOs_isNormalizedToThreeParts() {
        // We can't easily mock Build.VERSION.RELEASE in unit tests so we test
        // the helper directly. The envelope path simply forwards to it.
        assertEquals("14.0.0", EventSchema.normalizeOsVersion("14"))
        assertEquals("14.0.0", EventSchema.normalizeOsVersion("14.0"))
        assertEquals("14.0.1", EventSchema.normalizeOsVersion("14.0.1"))
        assertEquals("0.0.0", EventSchema.normalizeOsVersion(""))
        assertEquals("0.0.0", EventSchema.normalizeOsVersion(null))
        // 4+ parts are passed through (iOS does the same).
        assertEquals("14.0.1.2", EventSchema.normalizeOsVersion("14.0.1.2"))
    }

    // SPEC-070-A G.16: device.locale is BCP-47 not legacy `Locale.toString()`.
    @Test
    fun deviceLocale_isBcp47() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        val locale = envelope.getJSONObject("device").getString("locale")
        // BCP-47 uses '-', legacy uses '_'. Whatever the host's default is,
        // the field must NEVER contain an underscore.
        assertFalse(
            "device.locale must be BCP-47 (no underscores)",
            locale.contains("_")
        )
        // Must not be empty or null
        assertNotNull(locale)
        assertTrue(locale.isNotBlank())
    }
}
