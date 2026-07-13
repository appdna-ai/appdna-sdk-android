package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 EVERY LOGIN ATTEMPT UPLOADED THE END-USER'S PLAINTEXT PASSWORD INTO OUR DATA WAREHOUSE.
 *
 * On a `login` / `register` / `change_password` step, the renderer handed the raw field map the user had
 * just typed — password included — to two sinks: `SessionDataStore` (SharedPreferences, plaintext, and
 * folded into the `{{…}}` template namespace) and the `onboarding_step_completed` event, whose
 * `selection_data` property carried it verbatim into `raw.sdk_events`.
 *
 * The host still receives the credentials in full via the delegate's `stepData` — that is how it signs
 * the user in. Nobody else needs them.
 *
 * Mirrors iOS `AuthSecretRedactionTests`, case for case.
 */
class AuthSecretRedactionTest {

    private fun block(id: String, type: String, fieldId: String? = null) =
        ContentBlock(id = id, type = type, field_id = fieldId)

    /** A login step as the console publishes it: email input, password input, button. */
    private fun loginStep() = OnboardingStep(
        id = "step_login",
        type = OnboardingStep.StepType.CUSTOM,
        config = StepConfig(
            content_blocks = listOf(
                block("b1", "input_email", "email"),
                block("b2", "input_password", "password"),
                block("b3", "button"),
            ).toImmutableList(),
        ),
    )

    /** The other way the console can say the same thing: a form FIELD of type `password`. */
    private fun formStepWithPasswordField() = OnboardingStep(
        id = "step_register",
        type = OnboardingStep.StepType.FORM,
        config = StepConfig(
            fields = listOf(
                FormField(id = "email", type = FormFieldType.EMAIL, label = "Email"),
                FormField(id = "passcode", type = FormFieldType.PASSWORD, label = "Choose a password"),
            ).toImmutableList(),
        ),
    )

    @Test
    fun `password block value is stripped and everything else survives`() {
        val typed = mapOf<String, Any>(
            "email" to "alice@example.com",
            "password" to "hunter2",
            "action" to "login",
        )

        val safe = AuthSecretRedactor.redact(typed, loginStep())

        assertNull(
            "the user's password survived redaction — it would be persisted to SharedPreferences, " +
                "folded into the {{…}} template namespace, AND uploaded to raw.sdk_events",
            safe?.get("password"),
        )
        assertEquals("alice@example.com", safe?.get("email"))
        assertEquals("login", safe?.get("action"))
    }

    /**
     * The field id is `passcode`, not `password`. A name-based guess (`id.contains("password")`) would
     * sail past this and leak it. The discriminator is the declared TYPE.
     */
    @Test
    fun `a password field not named password is still redacted`() {
        val safe = AuthSecretRedactor.redact(
            mapOf("email" to "bob@example.com", "passcode" to "s3cret!"),
            formStepWithPasswordField(),
        )

        assertNull(
            "a password field called `passcode` leaked — the redactor is matching on the field NAME " +
                "rather than its declared type",
            safe?.get("passcode"),
        )
        assertEquals("bob@example.com", safe?.get("email"))
    }

    /**
     * The converse: a non-secret field whose id merely CONTAINS the word must survive. A substring
     * oracle would eat this, silently dropping a real user answer out of the funnel.
     */
    @Test
    fun `a non-secret field whose name contains password is kept`() {
        val step = OnboardingStep(
            id = "step_hint",
            type = OnboardingStep.StepType.FORM,
            config = StepConfig(
                fields = listOf(
                    FormField(id = "password_hint", type = FormFieldType.TEXT, label = "Password hint"),
                ).toImmutableList(),
            ),
        )

        val safe = AuthSecretRedactor.redact(mapOf("password_hint" to "my dog"), step)

        assertEquals(
            "a plain text field was redacted because its NAME contains \"password\" — that is a " +
                "substring oracle, and it silently drops real user answers",
            "my dog",
            safe?.get("password_hint"),
        )
    }

    @Test
    fun `a step with no secrets is unchanged`() {
        val step = OnboardingStep(
            id = "s",
            type = OnboardingStep.StepType.QUESTION,
            config = StepConfig(
                fields = listOf(FormField(id = "goal", type = FormFieldType.TEXT, label = "Goal"))
                    .toImmutableList(),
            ),
        )

        val data = mapOf<String, Any>("goal" to "lose_weight", "action" to "next")
        val safe = AuthSecretRedactor.redact(data, step)

        assertEquals(2, safe?.size)
        assertEquals("lose_weight", safe?.get("goal"))
    }

    @Test
    fun `secret ids are collected from blocks and fields alike`() {
        assertEquals(setOf("password"), AuthSecretRedactor.secretFieldIds(loginStep()))
        assertEquals(setOf("passcode"), AuthSecretRedactor.secretFieldIds(formStepWithPasswordField()))
    }
}
