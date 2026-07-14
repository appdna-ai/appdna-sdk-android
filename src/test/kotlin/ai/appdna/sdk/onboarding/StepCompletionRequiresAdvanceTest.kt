package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 A FAILED LOGIN COUNTED AS A COMPLETED STEP.
 *
 * `onboarding_step_completed` was emitted the moment the user tapped the button — BEFORE the hook had
 * decided anything. But on a hook step the HOOK decides whether the step completes. A `login` step whose
 * delegate answers `Block("Wrong password")` does not complete: the user stays exactly where they were.
 * The event had already gone out.
 *
 * Mistype a password three times and the funnel records FOUR completions of a step never completed; the
 * successful fourth attempt makes five. It corrupts step-completion and funnel conversion — the metrics
 * the product is sold on — and it over-counts worst at the credential step, the step users actually FAIL
 * at, so the flows that convert worst look the healthiest.
 *
 * Mirrors iOS `StepCompletionRequiresAdvanceTests`, case for case. Drives the REAL state machine with
 * real hook results rather than asserting on the enum in isolation, which would prove only that `Stay`
 * is spelled correctly.
 */
class StepCompletionRequiresAdvanceTest {

    private fun flow(stepCount: Int = 3) = OnboardingFlowConfig(
        id = "flow_1",
        name = "Test",
        version = 1,
        settings = OnboardingSettings(),
        steps = (0 until stepCount).map { i ->
            OnboardingStep(
                id = "step_$i",
                type = OnboardingStep.StepType.QUESTION,
                config = StepConfig(),
            )
        }.toImmutableList(),
    )

    private fun navigation(result: StepAdvanceResult): OnboardingAdvance.Navigation =
        OnboardingAdvance.apply(
            flow = flow(),
            currentIndex = 0,
            responses = emptyMap(),
            result = result,
        ).navigation

    /** The one that cost us the funnel: the host said no, so nothing completed. */
    @Test
    fun `a blocked hook does not complete the step`() {
        assertFalse(
            "a BLOCKED hook counted as a completed step — the user typed the wrong password, stayed on " +
                "the credential step, and the funnel recorded a completion anyway",
            navigation(StepAdvanceResult.Block("Wrong password")).completesStep,
        )
    }

    /** `Stay` is the same story wearing a friendlier face — the user is still on the step. */
    @Test
    fun `a stay does not complete the step`() {
        assertFalse(navigation(StepAdvanceResult.Stay("Check your email")).completesStep)
        assertFalse(navigation(StepAdvanceResult.Stay()).completesStep)
    }

    /**
     * The converse, which is what stops this fix becoming an UNDER-count: every outcome that actually
     * leaves the step MUST complete it. A fix that simply silenced the event would pass the two tests
     * above and destroy the funnel in the other direction.
     */
    @Test
    fun `every advancing outcome completes the step`() {
        assertTrue(navigation(StepAdvanceResult.Proceed).completesStep)
        assertTrue(navigation(StepAdvanceResult.ProceedWithData(mapOf("plan" to "pro"))).completesStep)
        assertTrue(
            "skipping onward still LEAVES the step — it completed",
            navigation(StepAdvanceResult.SkipTo("step_2")).completesStep,
        )
    }

    /**
     * Reaching the end of the flow completes its final step. Pinned because `CompleteFlow` is the one
     * navigation that is neither a `GoToIndex` nor a `Stay`, and a check written in haste against
     * `GoToIndex` alone would silently drop the LAST step out of every funnel.
     */
    @Test
    fun `completing the flow completes its final step`() {
        val nav = OnboardingAdvance.apply(
            flow = flow(stepCount = 1),
            currentIndex = 0,
            responses = emptyMap(),
            result = StepAdvanceResult.Proceed,
        ).navigation

        assertTrue("expected CompleteFlow, got $nav", nav is OnboardingAdvance.Navigation.CompleteFlow)
        assertTrue("the final step of a flow completed and was not counted", nav.completesStep)
    }
}
