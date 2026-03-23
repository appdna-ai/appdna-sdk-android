package ai.appdna.sdk.screens

import ai.appdna.sdk.core.ConditionEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FlowManager(
    val flowConfig: FlowConfig,
    val screens: Map<String, ScreenConfig>,
) {
    private val _currentScreenIndex = MutableStateFlow(0)
    val currentScreenIndex: StateFlow<Int> = _currentScreenIndex

    private val navigationStack = mutableListOf<String>()
    val responses = mutableMapOf<String, Any>()
    val screensViewed = mutableListOf<String>()
    val startTime = System.currentTimeMillis()

    var onComplete: ((FlowResult) -> Unit)? = null

    val currentScreen: ScreenConfig?
        get() {
            val idx = _currentScreenIndex.value
            if (idx >= flowConfig.screens.size) return null
            return screens[flowConfig.screens[idx].screenId]
        }

    val currentScreenId: String?
        get() {
            val idx = _currentScreenIndex.value
            if (idx >= flowConfig.screens.size) return null
            return flowConfig.screens[idx].screenId
        }

    init {
        val startIdx = flowConfig.screens.indexOfFirst { it.screenId == flowConfig.startScreenId }
        if (startIdx >= 0) _currentScreenIndex.value = startIdx
        currentScreenId?.let { screensViewed.add(it) }
    }

    fun handleAction(action: SectionAction) {
        when (action) {
            is SectionAction.Next -> advanceToNextScreen()
            is SectionAction.Back -> navigateBack()
            is SectionAction.Dismiss -> dismissFlow()
            is SectionAction.Navigate -> navigateToScreen(action.screenId)
            else -> {}
        }
    }

    private fun advanceToNextScreen() {
        val idx = _currentScreenIndex.value
        if (idx >= flowConfig.screens.size) { completeFlow(); return }

        val currentRef = flowConfig.screens[idx]
        for (rule in currentRef.navigationRules) {
            if (evaluateCondition(rule)) {
                when (rule.target) {
                    "next" -> moveToNext()
                    "end" -> completeFlow()
                    else -> navigateToScreen(rule.target)
                }
                return
            }
        }
        moveToNext()
    }

    private fun moveToNext() {
        val idx = _currentScreenIndex.value
        if (idx + 1 < flowConfig.screens.size) {
            currentScreenId?.let { navigationStack.add(it) }
            _currentScreenIndex.value = idx + 1
            currentScreenId?.let { screensViewed.add(it) }
        } else {
            completeFlow()
        }
    }

    private fun navigateBack() {
        val prev = navigationStack.removeLastOrNull() ?: return
        val newIdx = flowConfig.screens.indexOfFirst { it.screenId == prev }
        if (newIdx >= 0) _currentScreenIndex.value = newIdx
    }

    private fun navigateToScreen(screenId: String) {
        val newIdx = flowConfig.screens.indexOfFirst { it.screenId == screenId }
        if (newIdx >= 0) {
            currentScreenId?.let { navigationStack.add(it) }
            _currentScreenIndex.value = newIdx
            screensViewed.add(screenId)
        }
    }

    private fun completeFlow() {
        val duration = (System.currentTimeMillis() - startTime).toInt()
        onComplete?.invoke(FlowResult(flowConfig.id, true, currentScreenId ?: "", responses, screensViewed, duration))
    }

    fun dismissFlow() {
        val duration = (System.currentTimeMillis() - startTime).toInt()
        onComplete?.invoke(FlowResult(flowConfig.id, false, currentScreenId ?: "", responses, screensViewed, duration))
    }

    private fun evaluateCondition(rule: NavigationRule): Boolean {
        return ConditionEvaluator.evaluateCondition(rule.condition, rule.variable, rule.value, mapOf("responses" to responses))
    }

    fun mergeResponses(newResponses: Map<String, Any>) { responses.putAll(newResponses) }
}
