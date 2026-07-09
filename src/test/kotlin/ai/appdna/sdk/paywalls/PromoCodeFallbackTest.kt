package ai.appdna.sdk.paywalls

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A paywall presented with NO delegate used to accept any promo code.
 *
 *     } else {
 *         // No delegate configured — basic non-empty check fallback
 *         updateState(if (code.isNotBlank()) "success" else "error")
 *     }
 *
 * Any non-blank string rendered "Code applied!", and the CTA path then folded
 * that unvalidated string into purchase metadata as `promo_code`. Both platforms
 * now reject when they cannot validate.
 *
 * This drives the real composable, so it exercises the branch that shipped —
 * not a reimplementation of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class PromoCodeFallbackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val section = PaywallSection(
        type = "promo_input",
        data = PaywallSectionData(
            placeholder = "Promo code",
            button_text = "Apply",
            success_text = "Code applied!",
            error_text = "Invalid code",
        ),
    )

    private fun setContent(onSubmit: ((String, (Boolean) -> Unit) -> Unit)?) {
        compose.setContent {
            PaywallPromoInputSection(
                section = section,
                loc = { _, fallback -> fallback },
                onPromoCodeSubmit = onSubmit,
            )
        }
    }

    private fun typeAndApply(code: String) {
        compose.onNodeWithText("Promo code").performTextInput(code)
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `no delegate - a non-blank code is REJECTED, not silently applied`() {
        setContent(onSubmit = null)
        typeAndApply("TOTALLY-MADE-UP")

        compose.onNodeWithText("Invalid code").assertIsDisplayed()
        compose.onNodeWithText("Code applied!").assertDoesNotExist()
    }

    @Test
    fun `no delegate - rejection does not depend on the code`() {
        setContent(onSubmit = null)
        typeAndApply("SAVE90")

        compose.onNodeWithText("Invalid code").assertIsDisplayed()
    }

    @Test
    fun `no delegate - a blank code is still an error`() {
        setContent(onSubmit = null)
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Code applied!").assertDoesNotExist()
    }

    @Test
    fun `delegate says valid - the code IS applied`() {
        // The host validated it, so success is legitimate. Guards against
        // "fixing" the bug by hard-coding rejection.
        setContent(onSubmit = { _, completion -> completion(true) })
        typeAndApply("REAL-CODE")

        compose.onNodeWithText("Code applied!").assertIsDisplayed()
        compose.onNodeWithText("Invalid code").assertDoesNotExist()
    }

    @Test
    fun `delegate says invalid - the code is rejected`() {
        setContent(onSubmit = { _, completion -> completion(false) })
        typeAndApply("BAD-CODE")

        compose.onNodeWithText("Invalid code").assertIsDisplayed()
    }
}
