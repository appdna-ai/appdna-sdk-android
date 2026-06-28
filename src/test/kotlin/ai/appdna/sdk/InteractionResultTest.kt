package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ElementInteractionResult
import ai.appdna.sdk.onboarding.applyInteractionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC-419 EPIC-11 — pure logic of the interactive-state-contract result application. */
class InteractionResultTest {

    @Test
    fun mergesInputValuePatchesOverExisting() {
        val result = ElementInteractionResult(inputValuePatches = mapOf("name" to "Alex", "age" to 25))
        val applied = applyInteractionResult(result, mapOf("name" to "old", "city" to "NYC"))
        assertEquals("Alex", applied.inputValues["name"])  // patched
        assertEquals(25, applied.inputValues["age"])        // added
        assertEquals("NYC", applied.inputValues["city"])    // untouched
        assertTrue(applied.fieldConfigOverrides.isEmpty())
        assertFalse(applied.advance)
    }

    @Test
    fun exposesFieldConfigOverridesAndAdvance() {
        val result = ElementInteractionResult(
            fieldConfigPatches = mapOf("cal" to mapOf("selected_days" to listOf(1, 2, 3))),
            advance = true,
        )
        val applied = applyInteractionResult(result, emptyMap())
        assertEquals(listOf(1, 2, 3), applied.fieldConfigOverrides["cal"]?.get("selected_days"))
        assertTrue(applied.advance)
    }

    @Test
    fun emptyResultIsNoOp() {
        val applied = applyInteractionResult(ElementInteractionResult(), mapOf("k" to "v"))
        assertEquals("v", applied.inputValues["k"])
        assertTrue(applied.fieldConfigOverrides.isEmpty())
        assertFalse(applied.advance)
    }
}
