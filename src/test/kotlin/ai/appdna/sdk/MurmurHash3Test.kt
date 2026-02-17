package ai.appdna.sdk

import ai.appdna.sdk.config.MurmurHash3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Cross-platform MurmurHash3 test vectors.
 * These EXACT values must match the iOS Swift implementation (ExperimentBucketer.hash32).
 * Reference: verified against mmh3 C library.
 */
class MurmurHash3Test {

    // MARK: - Exact hash value vectors (seed = 0)

    @Test
    fun testEmptyStringHash() {
        assertEquals(0u, MurmurHash3.hash32("", seed = 0u))
    }

    @Test
    fun testEmptyStringWithSeed1() {
        assertEquals(0x514E28B7u, MurmurHash3.hash32("", seed = 1u))
    }

    @Test
    fun testHelloHash() {
        assertEquals(316307400u, MurmurHash3.hash32("Hello", seed = 0u))
    }

    @Test
    fun testSingleCharHash() {
        assertEquals(1009084850u, MurmurHash3.hash32("a", seed = 0u))
    }

    @Test
    fun testTwoCharHash() {
        assertEquals(2613040991u, MurmurHash3.hash32("ab", seed = 0u))
    }

    @Test
    fun testThreeCharHash() {
        assertEquals(3017643002u, MurmurHash3.hash32("abc", seed = 0u))
    }

    @Test
    fun testFourCharHash() {
        assertEquals(1139631978u, MurmurHash3.hash32("abcd", seed = 0u))
    }

    @Test
    fun testFiveCharHash() {
        assertEquals(3902511862u, MurmurHash3.hash32("abcde", seed = 0u))
    }

    // MARK: - Experiment-style input vectors

    @Test
    fun testExperimentInputPaywall() {
        assertEquals(
            3214585791u,
            MurmurHash3.hash32("exp_paywall_v3.a8f3c9d2.user_12345", seed = 0u)
        )
    }

    @Test
    fun testExperimentInputOnboard() {
        assertEquals(
            1911481070u,
            MurmurHash3.hash32("exp_onboard.salt_x.user_99999", seed = 0u)
        )
    }

    @Test
    fun testExperimentInputTest() {
        assertEquals(
            3276853400u,
            MurmurHash3.hash32("exp_test.salt_abc.user_1", seed = 0u)
        )
    }

    // MARK: - Unicode / multi-byte vectors

    @Test
    fun testJapaneseHash() {
        assertEquals(
            3057250137u,
            MurmurHash3.hash32("日本語テスト", seed = 0u)
        )
    }

    @Test
    fun testEmojiHash() {
        assertEquals(
            665358373u,
            MurmurHash3.hash32("🎉🚀💡", seed = 0u)
        )
    }

    // MARK: - Seed variation vectors

    @Test
    fun testSeedVariation() {
        assertEquals(3222140578u, MurmurHash3.hash32("test_input", seed = 0u))
        assertEquals(1837767272u, MurmurHash3.hash32("test_input", seed = 42u))
    }

    // MARK: - Determinism

    @Test
    fun testDeterminismOver100Runs() {
        val input = "user_123.exp_001.salt_abc"
        val expected = MurmurHash3.hash32(input)
        repeat(100) {
            assertEquals(expected, MurmurHash3.hash32(input))
        }
    }

    @Test
    fun testDifferentInputsDifferentHashes() {
        assertNotEquals(
            MurmurHash3.hash32("input_a"),
            MurmurHash3.hash32("input_b")
        )
    }

    // MARK: - Bucket consistency

    @Test
    fun testBucketPaywallExperiment() {
        // hash = 3214585791, bucket = 3214585791 % 10000 = 5791
        val hash = MurmurHash3.hash32("exp_paywall_v3.a8f3c9d2.user_12345")
        val bucket = hash % 10000u
        assertEquals(5791u, bucket)
    }

    @Test
    fun testBucketOnboardExperiment() {
        // hash = 1911481070, bucket = 1911481070 % 10000 = 1070
        val hash = MurmurHash3.hash32("exp_onboard.salt_x.user_99999")
        val bucket = hash % 10000u
        assertEquals(1070u, bucket)
    }

    @Test
    fun testBucketTestExperiment() {
        // hash = 3276853400, bucket = 3276853400 % 10000 = 3400
        val hash = MurmurHash3.hash32("exp_test.salt_abc.user_1")
        val bucket = hash % 10000u
        assertEquals(3400u, bucket)
    }

    // MARK: - Distribution

    @Test
    fun testBucketDistribution5050() {
        var variantA = 0
        var variantB = 0

        for (i in 0 until 10_000) {
            val hash = MurmurHash3.hash32("user_$i.test_exp.test_salt")
            val bucket = hash % 10000u

            if (bucket < 5000u) variantA++ else variantB++
        }

        val total = (variantA + variantB).toDouble()
        val ratioA = variantA / total
        assert(ratioA > 0.48) { "Variant A ratio $ratioA too low" }
        assert(ratioA < 0.52) { "Variant A ratio $ratioA too high" }
    }
}
