package ai.appdna.sdk.screens

data class SectionContext(
    val screenId: String,
    val flowId: String? = null,
    var responses: MutableMap<String, Any> = mutableMapOf(),
    var hookData: Map<String, Any>? = null,
    var userTraits: Map<String, Any>? = null,
    val onAction: (SectionAction) -> Unit,
    val onNavigate: (String) -> Unit = {},
    val currentScreenIndex: Int = 0,
    val totalScreens: Int = 1,
    val locale: String = "en",
    val localizations: Map<String, Map<String, String>>? = null,
) {
    fun localize(key: String): String {
        return localizations?.get(locale)?.get(key)
            ?: localizations?.get("en")?.get(key)
            ?: key
    }

    fun buildEvaluationContext(): Map<String, Any> {
        val ctx = mutableMapOf<String, Any>("responses" to responses)
        hookData?.let { ctx["hook_data"] = it }
        userTraits?.let { ctx["user"] = it }
        ctx["session"] = mapOf("screen_index" to currentScreenIndex, "total_screens" to totalScreens)
        return ctx
    }
}

sealed class SectionAction {
    data object Next : SectionAction()
    data object Back : SectionAction()
    data object Dismiss : SectionAction()
    data class Navigate(val screenId: String) : SectionAction()
    data class OpenURL(val url: String) : SectionAction()
    data class OpenWebview(val url: String) : SectionAction()
    data object OpenAppSettings : SectionAction()
    data class Share(val text: String) : SectionAction()
    data class DeepLink(val url: String) : SectionAction()
    data class ShowPaywall(val id: String?) : SectionAction()
    data class ShowSurvey(val id: String?) : SectionAction()
    data class ShowScreen(val id: String) : SectionAction()
    data class SubmitForm(val data: Map<String, Any>) : SectionAction()
    data class Track(val event: String, val properties: Map<String, Any>? = null) : SectionAction()
    data class Haptic(val type: String) : SectionAction()
    data class Custom(val type: String, val value: String? = null) : SectionAction()
}
