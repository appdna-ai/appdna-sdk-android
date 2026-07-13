package ai.appdna.sdk.onboarding

/**
 * 🔴 THE USER'S PASSWORD WAS BEING WRITTEN TO DISK **AND UPLOADED TO THE ANALYTICS BACKEND.**
 *
 * When the user tapped the button on a `login` / `register` / `change_password` step, the renderer
 * handed the raw field map — the values they had just typed, PASSWORD INCLUDED — to two sinks:
 *
 *  1. `responses[step.id] = data`, then `SessionDataStore.setOnboardingResponses(...)`, which persists
 *     to `SharedPreferences` in plaintext. And `TemplateEngine` folds that bucket into the `{{…}}`
 *     namespace — the same path that once rendered one user's name into another user's paywall copy.
 *     The password was one `{{onboarding.password}}` away from being DISPLAYED.
 *
 *  2. `onStepCompleted(...)` → the `onboarding_step_completed` event, whose properties carry
 *     `"selection_data" to data` verbatim (`OnboardingFlowManager.kt`). That event is enqueued,
 *     uploaded, and lands in `raw.sdk_events` — so every login attempt shipped an end-user's plaintext
 *     password into the warehouse, and kept it there.
 *
 * Neither sink needs the secret. The one party that does — the host — still receives it in full, via
 * the delegate's `stepData` in `onBeforeStepAdvance`, which is how it actually signs the user in.
 *
 * The discriminator is STRUCTURAL, taken from the console's own schema (`flow.schema.ts`): a content
 * block of type `input_password`, or a form field of type `password`. It is deliberately NOT a name
 * match on the field id — `fieldId.contains("password")` would miss `pwd` and would happily redact a
 * field called `password_hint`. Mirrors iOS `AuthSecretRedactor` exactly.
 */
internal object AuthSecretRedactor {

    /** Block types whose VALUE is a secret and must never be persisted or emitted. */
    private val SECRET_BLOCK_TYPES = setOf("input_password")

    /** The field ids on this step whose values are secrets. */
    fun secretFieldIds(step: OnboardingStep): Set<String> {
        val ids = mutableSetOf<String>()
        step.config.content_blocks
            ?.filter { it.type in SECRET_BLOCK_TYPES }
            ?.forEach { ids.add(it.field_id ?: it.id) }
        step.config.fields
            ?.filter { it.type == FormFieldType.PASSWORD }
            ?.forEach { ids.add(it.id) }
        return ids
    }

    /**
     * [data] with every secret value removed.
     *
     * The keys are DROPPED, not masked: a `"password" to "••••"` left in the template namespace is
     * still a lie a host could render, and still a field a dbt model could learn to expect.
     */
    fun redact(data: Map<String, Any>?, step: OnboardingStep): Map<String, Any>? {
        if (data == null) return null
        val secrets = secretFieldIds(step)
        if (secrets.isEmpty()) return data
        return data.filterKeys { it !in secrets }
    }
}
