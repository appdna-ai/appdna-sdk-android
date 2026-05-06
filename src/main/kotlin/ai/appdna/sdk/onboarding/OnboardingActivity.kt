package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.LocalizationEngine
import ai.appdna.sdk.core.entryAnimation
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.interpolated
import ai.appdna.sdk.events.EventTracker
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Activity to render onboarding flow UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-070-A I.16 — edge-to-edge so Compose `imePadding()`/`safeDrawingPadding()`
        // modifiers in the renderer roots resolve correctly and keyboards don't
        // crop content. The manifest declares `windowSoftInputMode="adjustResize"`
        // so Compose receives IME inset changes.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val flow = pendingFlow ?: run {
            finish()
            return
        }

        // SPEC-070-A I.7 — restore (currentStepIndex, responses) from
        // `savedInstanceState` so process-death / config-change rotations
        // re-enter the flow on the same step with prior answers intact.
        // Mirrors iOS `OnboardingRenderer` `@SceneStorage` round-trip.
        if (savedInstanceState != null) {
            val savedIndex = savedInstanceState.getInt(KEY_CURRENT_STEP_INDEX, 0)
            val savedResponsesJson = savedInstanceState.getString(KEY_RESPONSES)
            restoredStepIndex = savedIndex.coerceAtLeast(0)
            restoredResponses = savedResponsesJson?.let { json ->
                try {
                    val obj = org.json.JSONObject(json)
                    val map = mutableMapOf<String, Any>()
                    obj.keys().forEach { k -> obj.opt(k)?.let { map[k] = it } }
                    map
                } catch (_: Throwable) { null }
            }
        }

        val delegate = pendingDelegate
        val eventTracker = pendingEventTracker
        val onStepViewed = pendingOnStepViewed
        val onStepCompleted = pendingOnStepCompleted
        val onStepSkipped = pendingOnStepSkipped
        val onFlowCompleted = pendingOnFlowCompleted
        val onFlowDismissed = pendingOnFlowDismissed

        setContent {
            // SPEC-070-A D.5 — system dark-mode pref so onboarding renderers
            // can pick `dark` overrides from console content blocks.
            val isDark = isSystemInDarkTheme()
            MaterialTheme {
                OnboardingFlowHost(
                    flow = flow,
                    delegate = delegate,
                    eventTracker = eventTracker,
                    isDark = isDark,
                    onStepViewed = { stepId, stepIndex ->
                        onStepViewed?.invoke(stepId, stepIndex)
                    },
                    onStepCompleted = { stepId, stepIndex, data ->
                        onStepCompleted?.invoke(stepId, stepIndex, data)
                    },
                    onStepSkipped = { stepId, stepIndex ->
                        onStepSkipped?.invoke(stepId, stepIndex)
                    },
                    onFlowCompleted = { responses ->
                        onFlowCompleted?.invoke(responses)
                        cleanup()
                    },
                    onFlowDismissed = { lastStepId, lastStepIndex ->
                        onFlowDismissed?.invoke(lastStepId, lastStepIndex)
                        cleanup()
                    }
                )
            }
        }
    }

    private fun cleanup() {
        pendingFlow = null
        pendingDelegate = null
        pendingEventTracker = null
        pendingOnStepViewed = null
        pendingOnStepCompleted = null
        pendingOnStepSkipped = null
        pendingOnFlowCompleted = null
        pendingOnFlowDismissed = null
        finish()
    }

    override fun onBackPressed() {
        // SPEC-070-A I.4 — respect `flow.settings.dismiss_allowed` (canDismiss)
        // when handling the system back button. When dismissal is disallowed
        // the flow intercepts back, mirroring iOS force-flow behavior.
        // (allow_back gates step-back navigation, not the global dismiss.)
        val flow = pendingFlow
        val canDismiss = flow?.settings?.dismiss_allowed ?: true
        if (!canDismiss) {
            // Force-flow: ignore system back to prevent accidental abandonment.
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
        flow?.let { f ->
            pendingOnFlowDismissed?.invoke(f.steps.firstOrNull()?.id ?: "", 0)
        }
        cleanup()
    }

    /**
     * SPEC-070-A I.7 — persist current step + responses across config
     * changes / process death so the flow re-enters on the same step.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_STEP_INDEX, savedStepIndex)
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in savedResponses) {
                obj.put(k, when (v) {
                    is Map<*, *> -> org.json.JSONObject(v as Map<String, Any?>)
                    is List<*> -> org.json.JSONArray(v)
                    else -> v
                })
            }
            outState.putString(KEY_RESPONSES, obj.toString())
        } catch (_: Throwable) { /* best-effort */ }
    }

    companion object {
        private const val KEY_CURRENT_STEP_INDEX = "appdna_onboarding_current_step_index"
        private const val KEY_RESPONSES = "appdna_onboarding_responses"
        @Volatile internal var restoredStepIndex: Int? = null
        @Volatile internal var restoredResponses: Map<String, Any>? = null
        @Volatile internal var savedStepIndex: Int = 0
        @Volatile internal var savedResponses: Map<String, Any> = emptyMap()
        private var pendingFlow: OnboardingFlowConfig? = null
        private var pendingDelegate: AppDNAOnboardingDelegate? = null
        private var pendingEventTracker: EventTracker? = null
        private var pendingOnStepViewed: ((String, Int) -> Unit)? = null
        private var pendingOnStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null
        private var pendingOnStepSkipped: ((String, Int) -> Unit)? = null
        private var pendingOnFlowCompleted: ((Map<String, Any>) -> Unit)? = null
        private var pendingOnFlowDismissed: ((String, Int) -> Unit)? = null

        internal fun launch(
            context: Context,
            flow: OnboardingFlowConfig,
            delegate: AppDNAOnboardingDelegate? = null,
            eventTracker: EventTracker? = null,
            onStepViewed: ((String, Int) -> Unit)? = null,
            onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null,
            onStepSkipped: ((String, Int) -> Unit)? = null,
            onFlowCompleted: ((Map<String, Any>) -> Unit)? = null,
            onFlowDismissed: ((String, Int) -> Unit)? = null
        ) {
            pendingFlow = flow
            pendingDelegate = delegate
            pendingEventTracker = eventTracker
            pendingOnStepViewed = onStepViewed
            pendingOnStepCompleted = onStepCompleted
            pendingOnStepSkipped = onStepSkipped
            pendingOnFlowCompleted = onFlowCompleted
            pendingOnFlowDismissed = onFlowDismissed

            val intent = Intent(context, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
internal fun OnboardingFlowHost(
    flow: OnboardingFlowConfig,
    delegate: AppDNAOnboardingDelegate? = null,
    eventTracker: EventTracker? = null,
    onStepViewed: (String, Int) -> Unit,
    onStepCompleted: (String, Int, Map<String, Any>?) -> Unit,
    onStepSkipped: (String, Int) -> Unit,
    onFlowCompleted: (Map<String, Any>) -> Unit,
    onFlowDismissed: (String, Int) -> Unit,
    // SPEC-070-A D.5 — propagated to step renderers so block-level `dark`
    // overrides from the console are picked correctly.
    @Suppress("UNUSED_PARAMETER") isDark: Boolean = false,
) {
    // SPEC-070-A I.7 — initial state seeds from Activity-restored values when
    // available (config-change / process-death rotation), otherwise default.
    val initialIndex = OnboardingActivity.restoredStepIndex ?: 0
    val initialResponses = OnboardingActivity.restoredResponses ?: emptyMap()
    var currentIndex by rememberSaveable { mutableIntStateOf(initialIndex) }
    val responses = remember { mutableStateMapOf<String, Any>().apply { putAll(initialResponses) } }
    // SPEC-070-A J.2 — haptic feedback for step advance / back / dismiss /
    // button-tap interactions, gated by `flow.settings.haptic.enabled`.
    val hostView = LocalView.current
    val hapticConfig = flow.settings.haptic
    // Mirror current state back to the Activity companion so onSaveInstanceState
    // can write the latest values when the system asks to persist.
    LaunchedEffect(currentIndex) { OnboardingActivity.savedStepIndex = currentIndex }
    LaunchedEffect(responses.size) { OnboardingActivity.savedResponses = responses.toMap() }
    val coroutineScope = rememberCoroutineScope()

    // SPEC-083: Hook state
    var isProcessing by remember { mutableStateOf(false) }
    var loadingText by remember { mutableStateOf("Processing...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    // SPEC-070-A C.8 — success banner state (port of iOS v1.0.60 SPEC-083
    // amendment `.stay(message:)`). Mirrors the existing error banner pair.
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showSuccess by remember { mutableStateOf(false) }
    val configOverrides = remember { mutableStateMapOf<String, StepConfigOverride>() }

    val progress = if (flow.steps.isNotEmpty()) {
        (currentIndex + 1).toFloat() / flow.steps.size
    } else 0f

    // Hook event tracking helper
    fun trackHookEvent(event: String, step: OnboardingStep, extra: Map<String, Any> = emptyMap()) {
        val props = mutableMapOf<String, Any>(
            "flow_id" to flow.id,
            "step_id" to step.id,
        )
        props.putAll(extra)
        eventTracker?.track(event, props)
    }

    // SPEC-083: Before-render hook + step viewed tracking
    LaunchedEffect(currentIndex) {
        if (currentIndex < flow.steps.size) {
            val step = flow.steps[currentIndex]
            delegate?.let { d ->
                val override = d.onBeforeStepRender(
                    flowId = flow.id,
                    stepId = step.id,
                    stepIndex = currentIndex,
                    stepType = step.type.value,
                    responses = responses.toMap()
                )
                if (override != null) {
                    configOverrides[step.id] = override
                }
            }
            onStepViewed(step.id, currentIndex)
        }
    }

    // Auto-dismiss error after 5 seconds
    LaunchedEffect(showError) {
        if (showError) {
            kotlinx.coroutines.delay(5000)
            showError = false
            errorMessage = null
        }
    }

    // SPEC-070-A C.8 — auto-dismiss success after 4 seconds (mirrors iOS
    // `OnboardingRenderer.swift:351-388` 4000ms LaunchedEffect timer).
    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            kotlinx.coroutines.delay(4000)
            showSuccess = false
            successMessage = null
        }
    }

    // SPEC-070-A A.21 — emit `flow_completed_via_fallback` whenever the flow
    // ends because no next_step_rule matched on the last step (rule-failure
    // bailout) instead of from a natural sequential end. Lets ETL distinguish
    // fully-authored flows from misconfigured ones.
    fun emitFallbackCompletion() {
        eventTracker?.track(
            FLOW_COMPLETED_VIA_FALLBACK_EVENT,
            mapOf(
                "flow_id" to flow.id,
                "step_id" to (flow.steps.lastOrNull()?.id ?: ""),
                "step_index" to (flow.steps.size - 1).coerceAtLeast(0),
            ),
        )
    }

    // Helper functions
    fun advanceOrComplete() {
        // SPEC-070-A J.2 — fire `on_step_advance` haptic before evaluating
        // next-step rules (mirrors iOS HapticController invocation in
        // OnboardingRenderer.advanceStep()).
        HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_step_advance, hapticConfig)
        val step = if (currentIndex < flow.steps.size) flow.steps[currentIndex] else null
        val rules = step?.next_step_rules
        val isLastStep = currentIndex >= flow.steps.size - 1
        if (step != null && !rules.isNullOrEmpty()) {
            // SPEC-070-A A.21: evaluate `condition` / `conditions[]` per iOS
            // `OnboardingRenderer.swift:851-924`. First matching rule wins;
            // unmatched rules fall through.
            for (rule in rules) {
                val matches = NextStepRuleEvaluator.evaluateRule(
                    rule = rule,
                    stepId = step.id,
                    responses = responses.toMap(),
                    step = step,
                )
                if (!matches) continue

                when (val classified = classifyRuleTarget(rule.target_step_id)) {
                    is RuleTarget.Empty -> continue
                    is RuleTarget.PaywallTrigger -> {
                        val merged = responses.toMutableMap()
                        merged["__paywall_trigger"] = classified.rawTarget.removePrefix("paywall_trigger_")
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(merged.toMap() as Map<String, Any>)
                        return
                    }
                    is RuleTarget.EndFlow -> {
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                        return
                    }
                    is RuleTarget.Permission -> {
                        // Mirror iOS auto-route: surface the permission name in the
                        // completion payload so the host (or paywall bridge) can
                        // request it. Marker sentinel matches `__paywall_trigger`.
                        val merged = responses.toMutableMap()
                        merged["__permission_request"] = classified.name
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(merged.toMap() as Map<String, Any>)
                        return
                    }
                    is RuleTarget.Screen -> {
                        val merged = responses.toMutableMap()
                        merged["__screen_present"] = classified.screenId
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(merged.toMap() as Map<String, Any>)
                        return
                    }
                    is RuleTarget.SubFlow -> {
                        val merged = responses.toMutableMap()
                        merged["__sub_flow"] = classified.flowId
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(merged.toMap() as Map<String, Any>)
                        return
                    }
                    is RuleTarget.Step -> {
                        val targetIndex = flow.steps.indexOfFirst { it.id == classified.stepId }
                        if (targetIndex >= 0) {
                            currentIndex = targetIndex
                            return
                        }
                    }
                    is RuleTarget.Unknown -> continue
                }
            }
            // No rule matched. If we're on the last step this is a
            // rule-failure-bailout, not a natural end — emit the
            // distinguishing event so ETL can tell them apart.
            if (isLastStep) {
                emitFallbackCompletion()
                @Suppress("UNCHECKED_CAST")
                onFlowCompleted(responses.toMap() as Map<String, Any>)
                return
            }
        }
        // Fallback: sequential advance.
        if (currentIndex + 1 >= flow.steps.size) {
            @Suppress("UNCHECKED_CAST")
            onFlowCompleted(responses.toMap() as Map<String, Any>)
        } else {
            currentIndex++
        }
    }

    fun skipToStep(targetStepId: String) {
        val targetIndex = flow.steps.indexOfFirst { it.id == targetStepId }
        if (targetIndex >= 0) {
            currentIndex = targetIndex
        } else {
            advanceOrComplete()
        }
    }

    fun mergeData(extraData: Map<String, Any>, stepId: String) {
        val existing = responses[stepId]
        if (existing is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val merged = (existing as Map<String, Any>).toMutableMap()
            merged.putAll(extraData)
            responses[stepId] = merged
        } else {
            responses[stepId] = extraData
        }
    }

    fun applyOverrides(config: StepConfig, stepId: String): StepConfig {
        val override = configOverrides[stepId] ?: return config
        return config.copy(
            title = override.title ?: config.title,
            subtitle = override.subtitle ?: config.subtitle,
            cta_text = override.ctaText ?: config.cta_text,
            field_defaults = override.fieldDefaults ?: config.field_defaults
        )
    }

    fun handleHookResult(result: StepAdvanceResult, step: OnboardingStep) {
        when (result) {
            is StepAdvanceResult.Proceed -> advanceOrComplete()
            is StepAdvanceResult.ProceedWithData -> {
                mergeData(result.data, step.id)
                // SPEC-088: Persist computed data for cross-module access
                ai.appdna.sdk.core.SessionDataStore.instance?.mergeComputedData(result.data)
                advanceOrComplete()
            }
            is StepAdvanceResult.Block -> {
                errorMessage = result.message
                showError = true
            }
            is StepAdvanceResult.SkipTo -> {
                result.data?.let { data ->
                    mergeData(data, step.id)
                    // SPEC-088: Persist computed data for cross-module access
                    ai.appdna.sdk.core.SessionDataStore.instance?.mergeComputedData(data)
                }
                skipToStep(result.stepId)
            }
            // SPEC-070-A C.8 — Stay branch. Renders SuccessBanner if message
            // present, otherwise stays silent so host can drive its own UI.
            is StepAdvanceResult.Stay -> {
                if (!result.message.isNullOrEmpty()) {
                    successMessage = result.message
                    showSuccess = true
                }
                // else: host handled UI — stay silently.
            }
        }
    }

    fun resultName(result: StepAdvanceResult): String = when (result) {
        is StepAdvanceResult.Proceed -> "proceed"
        is StepAdvanceResult.ProceedWithData -> "proceed_with_data"
        is StepAdvanceResult.Block -> "block"
        is StepAdvanceResult.SkipTo -> "skip_to"
        is StepAdvanceResult.Stay -> "stay"
    }

    val currentStep = if (currentIndex < flow.steps.size) flow.steps[currentIndex] else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            // SPEC-070-A I.16 — edge-to-edge IME + system-bar insets so the
            // keyboard pushes content up + status/nav bars don't overlap.
            .imePadding()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Progress bar (Gap 9: custom progress_color/progress_track_color)
            // hide_progress per-step: hidden on this step but still counts in total
            if (flow.settings.show_progress && currentStep?.hide_progress != true) {
                val progressColor = flow.settings.progress_color?.let {
                    ai.appdna.sdk.core.StyleEngine.parseColor(it)
                } ?: MaterialTheme.colorScheme.primary
                val progressTrackColor = flow.settings.progress_track_color?.let {
                    ai.appdna.sdk.core.StyleEngine.parseColor(it)
                } ?: Color.Gray.copy(alpha = 0.2f)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = progressColor,
                    trackColor = progressTrackColor
                )
            }

            // Navigation bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (flow.settings.allow_back && currentIndex > 0) {
                    IconButton(
                        onClick = {
                            // SPEC-070-A J.2 — back navigation reuses
                            // `on_step_advance` haptic style.
                            HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_step_advance, hapticConfig)
                            currentIndex--
                        },
                        enabled = !isProcessing
                    ) {
                        Text(
                            text = "\u2190",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                IconButton(
                    onClick = {
                        // SPEC-070-A J.2 — dismiss reuses on_button_tap haptic.
                        HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_button_tap, hapticConfig)
                        if (currentIndex < flow.steps.size) {
                            val step = flow.steps[currentIndex]
                            onFlowDismissed(step.id, currentIndex)
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text(
                        text = "\u2715",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            // Step content with animated transitions
            if (currentIndex < flow.steps.size) {
                AnimatedContent<Int>(
                    targetState = currentIndex,
                    transitionSpec = {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                    },
                    label = "step_transition",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { stepIdx ->
                val step = flow.steps[stepIdx]
                val effectiveConfig = applyOverrides(step.config, step.id)

                OnboardingStepView(
                    step = step,
                    effectiveConfig = effectiveConfig,
                    flowId = flow.id,
                    onNext = { data ->
                        if (data != null) {
                            responses[step.id] = data
                        }
                        // SPEC-087: Persist responses incrementally so TemplateEngine has fresh data for next step
                        @Suppress("UNCHECKED_CAST")
                        ai.appdna.sdk.core.SessionDataStore.instance?.setOnboardingResponses(responses.toMap() as Map<String, Map<String, Any>>)
                        onStepCompleted(step.id, currentIndex, data)

                        // SPEC-083: Determine hook type — client delegate takes priority
                        if (delegate != null) {
                            // Client-side hook
                            loadingText = step.hook?.loading_text ?: "Processing..."
                            isProcessing = true
                            val startTime = System.currentTimeMillis()
                            trackHookEvent("onboarding_hook_started", step, mapOf("hook_type" to "client"))

                            coroutineScope.launch {
                                val result = delegate.onBeforeStepAdvance(
                                    flowId = flow.id,
                                    fromStepId = step.id,
                                    stepIndex = currentIndex,
                                    stepType = step.type.value,
                                    responses = responses.toMap(),
                                    stepData = data
                                )
                                val durationMs = System.currentTimeMillis() - startTime
                                isProcessing = false
                                trackHookEvent("onboarding_hook_completed", step, mapOf(
                                    "hook_type" to "client",
                                    "result" to resultName(result),
                                    "duration_ms" to durationMs,
                                ))
                                handleHookResult(result, step)
                            }
                        } else {
                            val hookConfig = step.hook?.takeIf { it.enabled }
                            if (hookConfig != null) {
                            // Server-side hook (P1)
                            loadingText = hookConfig.loading_text ?: "Processing..."
                            isProcessing = true
                            val startTime = System.currentTimeMillis()
                            trackHookEvent("onboarding_hook_started", step, mapOf(
                                "hook_type" to "server",
                                "webhook_url" to hookConfig.webhook_url,
                            ))

                            coroutineScope.launch {
                                val result = executeWebhook(
                                    flow = flow,
                                    step = step,
                                    data = data,
                                    responses = responses.toMap(),
                                    hookConfig = hookConfig,
                                    currentIndex = currentIndex,
                                    attempt = 0,
                                    onRetry = { attemptNum ->
                                        trackHookEvent("onboarding_hook_retry", step, mapOf(
                                            "attempt_number" to attemptNum
                                        ))
                                    },
                                    onError = { errorType, errorMsg ->
                                        trackHookEvent("onboarding_hook_error", step, mapOf(
                                            "hook_type" to "server",
                                            "error_type" to errorType,
                                            "error_message" to errorMsg,
                                        ))
                                    }
                                )
                                val durationMs = System.currentTimeMillis() - startTime
                                isProcessing = false
                                trackHookEvent("onboarding_hook_completed", step, mapOf(
                                    "hook_type" to "server",
                                    "result" to resultName(result),
                                    "duration_ms" to durationMs,
                                ))
                                handleHookResult(result, step)
                            }
                            } else {
                                advanceOrComplete()
                            }
                        }
                    },
                    onSkip = {
                        onStepSkipped(step.id, currentIndex)
                        advanceOrComplete()
                    },
                    modifier = Modifier.fillMaxSize(),
                    currentStepIndex = currentIndex,
                    totalSteps = flow.steps.size,
                )
                }
            }
        }

        // SPEC-083: Error banner
        if (showError && errorMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
                    .padding(top = if (flow.settings.show_progress && currentStep?.hide_progress != true) 56.dp else 52.dp)
                    .background(Color.Red, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showError = false; errorMessage = null },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text("\u2715", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        }

        // SPEC-070-A C.8 \u2014 success banner (port of iOS v1.0.60 SPEC-083
        // amendment `OnboardingRenderer.swift:351-388`). Same scaffold slot
        // as the error banner above. Animated entry/exit.
        AnimatedVisibility(
            visible = showSuccess && successMessage != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = if (flow.settings.show_progress && currentStep?.hide_progress != true) 56.dp else 52.dp)
                    .background(Color(0xFF2E9E51), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = successMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showSuccess = false; successMessage = null },
                    modifier = Modifier.size(24.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // SPEC-083: Loading overlay
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            loadingText,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Server-side webhook execution (SPEC-083 P1)

@Suppress("UNCHECKED_CAST")
private suspend fun executeWebhook(
    flow: OnboardingFlowConfig,
    step: OnboardingStep,
    data: Map<String, Any>?,
    responses: Map<String, Any>,
    hookConfig: StepHookConfig,
    currentIndex: Int,
    attempt: Int,
    onRetry: (Int) -> Unit,
    onError: (String, String) -> Unit
): StepAdvanceResult = withContext(Dispatchers.IO) {
    try {
        val url = URL(hookConfig.webhook_url)
        val conn = url.openConnection() as? HttpURLConnection
            ?: return@withContext StepAdvanceResult.Block("Webhook URL must use HTTP(S)")
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = hookConfig.timeout_ms
        conn.readTimeout = hookConfig.timeout_ms
        conn.doOutput = true

        // Apply custom headers with variable interpolation
        hookConfig.headers?.forEach { (key, value) ->
            conn.setRequestProperty(key, interpolateVariables(value))
        }

        // Build request body
        val body = JSONObject().apply {
            put("flow_id", flow.id)
            put("step_id", step.id)
            put("step_index", currentIndex)
            put("step_type", step.type.value)
            put("step_data", JSONObject(data ?: emptyMap<String, Any>()))
            put("responses", JSONObject(responses))
            put("user_id", AppDNA.getCurrentUserId() ?: "")
            put("app_id", AppDNA.getCurrentAppId() ?: "")
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val responseCode = conn.responseCode
        if (responseCode in 200..299) {
            val responseBody = conn.inputStream.bufferedReader().readText()
            return@withContext parseWebhookResponse(responseBody, hookConfig)
        }

        throw java.io.IOException("HTTP $responseCode")

    } catch (e: SocketTimeoutException) {
        val maxRetries = minOf(hookConfig.retry_count, 3)
        if (attempt < maxRetries) {
            onRetry(attempt + 1)
            val delay = Math.pow(2.0, attempt.toDouble()).toLong() * 1000
            kotlinx.coroutines.delay(delay)
            return@withContext executeWebhook(
                flow, step, data, responses, hookConfig,
                currentIndex, attempt + 1, onRetry, onError
            )
        }
        onError("timeout", "Request timed out")
        StepAdvanceResult.Block(hookConfig.error_text ?: "Request timed out. Please try again.")

    } catch (e: Exception) {
        val maxRetries = minOf(hookConfig.retry_count, 3)
        if (attempt < maxRetries) {
            onRetry(attempt + 1)
            val delay = Math.pow(2.0, attempt.toDouble()).toLong() * 1000
            kotlinx.coroutines.delay(delay)
            return@withContext executeWebhook(
                flow, step, data, responses, hookConfig,
                currentIndex, attempt + 1, onRetry, onError
            )
        }
        onError("network", e.message ?: "Network error")
        StepAdvanceResult.Block(hookConfig.error_text ?: "Network error. Please check your connection.")
    }
}

@Suppress("UNCHECKED_CAST")
private fun parseWebhookResponse(responseBody: String, hookConfig: StepHookConfig): StepAdvanceResult {
    return try {
        val json = JSONObject(responseBody)
        val action = json.optString("action", "proceed")
        val message = json.optString("message", null as String?)
        val targetStepId = json.optString("target_step_id", null as String?)

        val responseData: Map<String, Any>? = if (json.has("data") && !json.isNull("data")) {
            val dataObj = json.getJSONObject("data")
            val map = mutableMapOf<String, Any>()
            dataObj.keys().forEach { key -> map[key] = dataObj.get(key) }
            map
        } else null

        when (action) {
            "proceed" -> {
                if (responseData != null) StepAdvanceResult.ProceedWithData(responseData)
                else StepAdvanceResult.Proceed
            }
            "proceed_with_data" -> StepAdvanceResult.ProceedWithData(responseData ?: emptyMap())
            "block" -> StepAdvanceResult.Block(message ?: hookConfig.error_text ?: "Request blocked by server.")
            "skip_to" -> {
                if (targetStepId != null) StepAdvanceResult.SkipTo(targetStepId, responseData)
                else StepAdvanceResult.Proceed
            }
            // SPEC-070-A C.8 — `"stay"` webhook action surfaces the success
            // banner. Mirrors iOS v1.0.60 `parseWebhookResponse` extension.
            "stay" -> StepAdvanceResult.Stay(message)
            else -> StepAdvanceResult.Proceed
        }
    } catch (e: Exception) {
        StepAdvanceResult.Block(hookConfig.error_text ?: "Invalid server response.")
    }
}

private fun interpolateVariables(value: String): String {
    // SPEC-088: Delegate to shared TemplateEngine
    val ctx = ai.appdna.sdk.core.TemplateEngine.buildContext()
    return ai.appdna.sdk.core.TemplateEngine.interpolate(value, ctx)
}

@Composable
fun OnboardingStepView(
    step: OnboardingStep,
    effectiveConfig: StepConfig,
    onNext: (Map<String, Any>?) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    flowId: String = "",
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
) {
    val toggleValues = remember { mutableMapOf<String, Boolean>() }
    val inputValues = remember { mutableMapOf<String, Any>() }

    // SPEC-084: Block-based vs legacy rendering
    val blocks = effectiveConfig.content_blocks
    if (!blocks.isNullOrEmpty()) {
        // Block-based rendering with layout variants
        BlockBasedStepView(
            effectiveConfig = effectiveConfig,
            blocks = blocks,
            toggleValues = toggleValues,
            inputValues = inputValues,
            onNext = onNext,
            onSkip = if (step.config.skip_enabled == true) onSkip else null,
            modifier = modifier,
            currentStepIndex = currentStepIndex,
            totalSteps = totalSteps,
        )
    } else {
        // Legacy rendering
        Column(
            modifier = modifier
                .entryAnimation(effectiveConfig.animation?.entry_animation, effectiveConfig.animation?.entry_duration_ms)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step.type) {
                OnboardingStep.StepType.WELCOME -> WelcomeStep(effectiveConfig, onNext)
                OnboardingStep.StepType.QUESTION -> QuestionStep(effectiveConfig, onNext)
                OnboardingStep.StepType.VALUE_PROP -> ValuePropStep(effectiveConfig, onNext)
                OnboardingStep.StepType.CUSTOM -> CustomStep(effectiveConfig, onNext)
                OnboardingStep.StepType.FORM -> FormStep(effectiveConfig, onNext)
                OnboardingStep.StepType.INTERACTIVE_CHAT -> ChatStepComposable(step = step, flowId = flowId, onNext = { data -> onNext(data) }, onSkip = { onSkip() })
            }

            // Skip button
            if (step.config.skip_enabled == true) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onSkip) {
                    Text("Skip", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * SPEC-070-A C.1 — Strict-typed auth/account action emitter for
 * Android. Mirrors iOS `OnboardingRenderer.swift:1559-1583`
 * `emitAuthAction(...)`. Builds a `{action, [recipient?], ...inputValues}`
 * payload and dispatches via `onNext` so the host can route through
 * `onBeforeStepAdvance`. Stays on the step (no auto-advance) — host is
 * expected to return `.Block("Signing in…")` while the side effect runs.
 *
 * Merge order matches iOS: inputValues first so SDK-controlled keys
 * (`action`, `recipient`) always win on a field-id collision. We do not
 * resolve OTP channel here (Android does not yet ship OtpChannelResolver
 * parity — SPEC-086 follow-up). The `actionValue` half of the colon-
 * encoded action surfaces as `recipient` for OTP flows and
 * `action_value` for everything else, leaving channel resolution to the
 * host.
 */
private fun emitAuthAction(
    action: String,
    actionValue: String?,
    toggleValues: Map<String, Boolean>,
    inputValues: Map<String, Any>,
    onNext: (Map<String, Any>?) -> Unit,
) {
    val data = mutableMapOf<String, Any>()
    // input values first so SDK-controlled keys overwrite collisions
    data.putAll(inputValues)
    for ((k, v) in toggleValues) {
        data["toggle_$k"] = v
    }
    data["action"] = action
    if (actionValue != null) {
        when (action) {
            "request_otp", "verify_otp" -> data["recipient"] = actionValue
            else -> data["action_value"] = actionValue
        }
    }
    onNext(data)
}

// SPEC-084: Block-based step view with 5 layout variants
@Composable
private fun BlockBasedStepView(
    effectiveConfig: StepConfig,
    blocks: List<ContentBlock>,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    onNext: (Map<String, Any>?) -> Unit,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
) {
    val variant = effectiveConfig.layout_variant ?: "no_image"

    // SPEC-084: Localization helper for step text
    // SPEC-087: Also interpolates {{variables}} after localization
    fun loc(key: String, fallback: String): String {
        val localized = LocalizationEngine.resolve(key, effectiveConfig.localizations, effectiveConfig.default_locale, fallback)
        return localized.interpolated()
    }

    fun handleAction(action: String) {
        // SPEC-070-A C.1 — strict-typed auth/account action cases mirror iOS
        // `OnboardingRenderer.swift:1531-1545`. Action strings travel as either
        // a bare token ("login", "logout", ...) OR as a colon-encoded pair
        // ("social_login:google", "request_otp:sms", "email_login:email") so
        // the existing `(String) -> Unit` callback shape stays intact across
        // ContentBlockRenderer call sites. The recipient/value half — when
        // present — is forwarded to the host via `data["action_value"]` so
        // `onBeforeStepAdvance` can branch on it.
        val (rawAction, actionValue) = when {
            action.contains(":") -> {
                val idx = action.indexOf(':')
                action.substring(0, idx) to action.substring(idx + 1)
            }
            else -> action to null
        }
        when (rawAction) {
            "next" -> {
                // Merge toggleValues and inputValues (rating, etc.) into responses
                val merged = mutableMapOf<String, Any>()
                merged.putAll(toggleValues.mapValues { it.value as Any })
                merged.putAll(inputValues)
                onNext(merged)
            }
            "skip" -> onSkip?.invoke()
            // SPEC-070-A C.1 — social_login retains its existing data shape
            // (`{provider, action: "social_login"}`) for backwards compatibility
            // with hosts that switch on `action == "social_login"`. iOS
            // `OnboardingRenderer.swift:1507-1524`.
            "social_login" -> {
                val data = mutableMapOf<String, Any>(
                    "provider" to (actionValue ?: "unknown"),
                    "action" to "social_login",
                )
                data.putAll(inputValues)
                onNext(data)
            }
            // SPEC-070-A C.1 — strict-typed auth/account actions. Every case
            // emits `onNext({action, [recipient?], ...inputValues})` so the
            // host can route via `onBeforeStepAdvance`. Mirrors iOS
            // `OnboardingRenderer.swift:1531-1546` `emitAuthAction(...)`.
            //
            // Auth entry actions:
            "login", "register", "reset_password", "magic_link",
            "verify_email", "resend_verification", "enable_biometric",
            "email_login",
            // OTP actions (channel resolution is host's responsibility on
            // Android until SPEC-086 lands `OtpChannelResolver` parity):
            "request_otp", "verify_otp",
            // Account lifecycle:
            "logout", "change_password", "set_new_password",
            "delete_account", "update_profile" -> {
                emitAuthAction(rawAction, actionValue, toggleValues, inputValues, onNext)
            }
            // SPEC-070-A C.1 — `permission` is a SPEC-086 hook site. For now
            // advance as safe fallback so existing hosts don't get stuck on
            // a permission-tagged button without a runtime permission infra
            // wired up. Mirrors iOS `OnboardingRenderer.swift:1525-1529`.
            "permission" -> {
                val data = mutableMapOf<String, Any>(
                    "action" to "permission",
                )
                if (actionValue != null) data["permission_type"] = actionValue
                data.putAll(inputValues)
                onNext(data)
            }
            else -> onNext(null)
        }
    }

    Box(modifier = modifier.entryAnimation(effectiveConfig.animation?.entry_animation, effectiveConfig.animation?.entry_duration_ms)) {
        // Step-level background (color, gradient, image)
        effectiveConfig.background?.let { bg ->
            when (bg.type) {
                "color" -> bg.color?.let {
                    Box(Modifier.fillMaxSize().background(ai.appdna.sdk.core.StyleEngine.parseColor(it)))
                }
                "gradient" -> bg.gradient?.stops?.let { stops ->
                    if (stops.size >= 2) {
                        val colors = stops.map { ai.appdna.sdk.core.StyleEngine.parseColor(it.color) }
                        val brush = when (bg.gradient.type) {
                            "radial" -> Brush.radialGradient(colors)
                            else -> {
                                val angle = bg.gradient.angle ?: 180.0
                                val rads = Math.toRadians(angle)
                                val dx = sin(rads).toFloat()
                                val dy = -cos(rads).toFloat()
                                Brush.linearGradient(
                                    colors = colors,
                                    start = Offset(
                                        (0.5f - dx / 2f) * 1000f,
                                        (0.5f - dy / 2f) * 1000f,
                                    ),
                                    end = Offset(
                                        (0.5f + dx / 2f) * 1000f,
                                        (0.5f + dy / 2f) * 1000f,
                                    ),
                                )
                            }
                        }
                        Box(Modifier.fillMaxSize().background(brush))
                    }
                }
                "image" -> {
                    Box(Modifier.fillMaxSize()) {
                        ai.appdna.sdk.core.NetworkImage(
                            url = bg.image_url,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        bg.overlay?.let { overlay ->
                            Box(Modifier.fillMaxSize().background(ai.appdna.sdk.core.StyleEngine.parseColor(overlay)))
                        }
                    }
                }
                else -> {}
            }
        }

        when (variant) {
            "image_fullscreen" -> {
                Box(Modifier.fillMaxSize()) {
                    // Image background with gradient overlay
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // Gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                                    startY = 300f,
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Spacer(Modifier.height(200.dp))
                        ContentBlockRendererView(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                    }
                }
            }
            "image_split" -> {
                Row(Modifier.fillMaxSize()) {
                    // Image side (40%)
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.weight(0.4f).fillMaxHeight(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // Content side (60%)
                    Column(
                        modifier = Modifier
                            .weight(0.6f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        ContentBlockRendererView(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                    }
                }
            }
            "image_bottom" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    ContentBlockRendererView(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                    Spacer(Modifier.height(16.dp))
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
            "image_top" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Spacer(Modifier.height(16.dp))
                    ContentBlockRendererView(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                }
            }
            else -> { // no_image
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    ContentBlockRendererView(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                }
            }
        }

        // Skip button at bottom
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                Text("Skip", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun WelcomeStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(48.dp))
        config.title?.let {
            Text(text = it.interpolated(), fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        config.subtitle?.let {
            Spacer(Modifier.height(12.dp))
            Text(text = it.interpolated(), fontSize = 16.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = { onNext(null) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(text = (config.cta_text ?: "Get Started").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun QuestionStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    val selectedOptions = remember { mutableStateListOf<String>() }
    val isMulti = config.selection_mode == SelectionMode.MULTI

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        config.title?.let {
            Text(text = it.interpolated(), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        config.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it.interpolated(), fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(24.dp))

        config.options?.forEach { option ->
            val isSelected = selectedOptions.contains(option.id)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .then(
                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .clickable {
                        if (isMulti) {
                            if (isSelected) selectedOptions.remove(option.id) else selectedOptions.add(option.id)
                        } else {
                            selectedOptions.clear(); selectedOptions.add(option.id)
                        }
                    },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    option.icon?.let { Text(text = it, fontSize = 20.sp); Spacer(Modifier.width(12.dp)) }
                    Text(text = option.label.interpolated(), fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onNext(mapOf("selected" to selectedOptions.toList())) },
            enabled = selectedOptions.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun ValuePropStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        config.title?.let {
            Text(text = it.interpolated(), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        config.items?.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Text(text = item.icon, fontSize = 28.sp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(text = item.title.interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(text = item.subtitle.interpolated(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { onNext(null) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun CustomStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        config.title?.let {
            Text(text = it.interpolated(), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        config.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it.interpolated(), fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { onNext(null) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}
