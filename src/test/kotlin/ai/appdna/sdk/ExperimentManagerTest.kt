package ai.appdna.sdk

import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.ExperimentVariant
import ai.appdna.sdk.config.MurmurHash3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experiment variant assignment tests — mirrors iOS ExperimentManagerTests.
 * Cross-platform: these must produce identical results to iOS.
 */
class ExperimentManagerTest {

    // MARK: - Variant assignment via bucket

    @Test
    fun testAssignVariant5050_paywallUser() {
        // hash("exp_paywall_v3.a8f3c9d2.user_12345") = 3214585791, bucket = 5791
        // 50/50 split: cumulative after control = 5000, 5791 >= 5000 → variant_b
        val variants = listOf(
            ExperimentVariant(id = "control", weight = 0.5),
            ExperimentVariant(id = "variant_b", weight = 0.5)
        )

        val result = assignVariant("exp_paywall_v3", "user_12345", "a8f3c9d2", variants)
        assertEquals("variant_b", result)
    }

    @Test
    fun testAssignVariant5050_onboardUser() {
        // hash("exp_onboard.salt_x.user_99999") = 1911481070, bucket = 1070
        // 50/50: cumulative after control = 5000, 1070 < 5000 → control
        val variants = listOf(
            ExperimentVariant(id = "control", weight = 0.5),
            ExperimentVariant(id = "treatment", weight = 0.5)
        )

        val result = assignVariant("exp_onboard", "user_99999", "salt_x", variants)
        assertEquals("control", result)
    }

    @Test
    fun testAssignVariant7030() {
        // hash("exp_test.salt_abc.user_1") = 3276853400, bucket = 3400
        // 70/30: cumulative after A = 7000, 3400 < 7000 → variant_a
        val variants = listOf(
            ExperimentVariant(id = "variant_a", weight = 0.7),
            ExperimentVariant(id = "variant_b", weight = 0.3)
        )

        val result = assignVariant("exp_test", "user_1", "salt_abc", variants)
        assertEquals("variant_a", result)
    }

    // MARK: - Three-way split distribution

    @Test
    fun testThreeVariantDistribution() {
        val variants = listOf(
            ExperimentVariant(id = "control", weight = 0.34),
            ExperimentVariant(id = "variant_a", weight = 0.33),
            ExperimentVariant(id = "variant_b", weight = 0.33)
        )

        val counts = mutableMapOf("control" to 0, "variant_a" to 0, "variant_b" to 0)

        for (i in 0 until 10_000) {
            val result = assignVariant("three_exp", "user_$i", "salt_3", variants)
            counts[result!!] = counts[result]!! + 1
        }

        for ((id, count) in counts) {
            val ratio = count / 10_000.0
            assertTrue("$id ratio $ratio too low", ratio > 0.30)
            assertTrue("$id ratio $ratio too high", ratio < 0.37)
        }
    }

    // MARK: - Stability

    @Test
    fun testSameUserAlwaysSameVariant() {
        val variants = listOf(
            ExperimentVariant(id = "control", weight = 0.5),
            ExperimentVariant(id = "treatment", weight = 0.5)
        )

        val first = assignVariant("stable_exp", "stable_user_123", "stable_salt", variants)

        repeat(100) {
            assertEquals(first, assignVariant("stable_exp", "stable_user_123", "stable_salt", variants))
        }
    }

    // MARK: - Empty variants

    @Test
    fun testEmptyVariantsReturnsNull() {
        val result = assignVariant("exp", "user", "salt", emptyList())
        assertEquals(null, result)
    }

    // MARK: - Helper (mirrors ExperimentManager.assignVariant logic)

    private fun assignVariant(
        experimentId: String,
        userId: String,
        salt: String,
        variants: List<ExperimentVariant>
    ): String? {
        if (variants.isEmpty()) return null

        val hashInput = "$experimentId.$salt.$userId"
        val hash = MurmurHash3.hash32(hashInput)
        val bucket = hash % 10000u

        var cumulative = 0u
        for (variant in variants) {
            cumulative += (variant.weight * 10000).toUInt()
            if (bucket < cumulative) {
                return variant.id
            }
        }

        return variants.last().id
    }
}
