package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// SPEC-070-A J.11 — accessibility string resources for onboarding chrome
// (back / close / dismiss). Hosts can override via their own strings.xml.
import ai.appdna.sdk.R
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

    /**
     * SPEC-070-A J.21 — VM-backed state survives config changes / process
     * death without leaking pre-launch lambdas across distinct flow launches.
     * `by viewModels()` scopes the VM to THIS Activity instance (each launch
     * is a new ViewModelStoreOwner), so Activity-A's flow never bleeds into
     * Activity-B's flow.
     */
    private val viewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-070-A I.16 — edge-to-edge so Compose `imePadding()`/`safeDrawingPadding()`
        // modifiers in the renderer roots resolve correctly and keyboards don't
        // crop content. The manifest declares `windowSoftInputMode="adjustResize"`
        // so Compose receives IME inset changes.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // SPEC-070-A J.21 — first-launch path: drain the next-launch payload
        // into the VM. On a config-change recreate the VM is already bound,
        // so we skip rebinding (preserving live currentIndex / responses).
        if (!viewModel.isBound) {
            val payload = consumePendingLaunchPayload()
            if (payload == null) {
                // No payload + no bound VM == process was killed before
                // onCreate ran (cold restart with no flow to resume). Bail.
                finish()
                return
            }
            viewModel.bind(payload)
        }

        val flow = viewModel.flow ?: run {
            finish()
            return
        }

        // SPEC-070-A I.7 — restore (currentStepIndex, responses) from
        // `savedInstanceState` so process-death / config-change rotations
        // re-enter the flow on the same step with prior answers intact.
        // Mirrors iOS `OnboardingRenderer` `@SceneStorage` round-trip.
        //
        // Process-death case: the VM was destroyed along with the Activity,
        // so `viewModel.responses` is empty here — we hydrate it from the
        // bundle. Config-change case: the VM survived, so we DON'T overwrite
        // its in-memory state with a (potentially stale) bundle snapshot.
        if (savedInstanceState != null && viewModel.responses.isEmpty() &&
            viewModel.currentIndex.intValue == 0
        ) {
            val savedIndex = savedInstanceState.getInt(KEY_CURRENT_STEP_INDEX, 0)
            val savedResponsesJson = savedInstanceState.getString(KEY_RESPONSES)
            viewModel.restoredStepIndex = savedIndex.coerceAtLeast(0)
            viewModel.restoredResponses = savedResponsesJson?.let { json ->
                try {
                    // SPEC-401-A R33 P1 — deep-convert JSONObject/JSONArray
                    // → Map/List recursively. iOS Codable round-trip yields
                    // native [String:Any]; Android `obj.opt(k)` returns raw
                    // JSONObject/JSONArray for nested values, which then
                    // fails `as? Map<String, Any?>` cast in
                    // NextStepRuleEvaluator.kt:50, falling through to the
                    // `else -> true` branch and picking the wrong route
                    // for any form/multi-select/chat-transcript step after
                    // process death.
                    fromJsonAny(org.json.JSONObject(json)) as? Map<String, Any>
                } catch (_: Throwable) { null }
            }
            // SPEC-070-A finalization P0 audit-8 D2 — restore navigationHistory
            // alongside currentIndex / responses so previous_step_* rules
            // continue to evaluate correctly after process death.
            savedInstanceState.getStringArray(KEY_NAV_HISTORY)?.let { arr ->
                viewModel.navigationHistory.clear()
                viewModel.navigationHistory.addAll(arr)
            }
        }

        setContent {
            // SPEC-070-A D.5 — system dark-mode pref so onboarding renderers
            // can pick `dark` overrides from console content blocks.
            val isDark = isSystemInDarkTheme()
            MaterialTheme {
                OnboardingFlowHost(
                    flow = flow,
                    viewModel = viewModel,
                    delegate = viewModel.delegate,
                    eventTracker = viewModel.eventTracker,
                    isDark = isDark,
                    onStepViewed = { stepId, stepIndex ->
                        viewModel.onStepViewed?.invoke(stepId, stepIndex)
                    },
                    onStepCompleted = { stepId, stepIndex, data ->
                        viewModel.onStepCompleted?.invoke(stepId, stepIndex, data)
                    },
                    onStepSkipped = { stepId, stepIndex ->
                        viewModel.onStepSkipped?.invoke(stepId, stepIndex)
                    },
                    onFlowCompleted = { responses ->
                        viewModel.onFlowCompleted?.invoke(responses)
                        cleanup()
                    },
                    onFlowDismissed = { lastStepId, lastStepIndex ->
                        viewModel.onFlowDismissed?.invoke(lastStepId, lastStepIndex)
                        cleanup()
                    }
                )
            }
        }
    }

    private fun cleanup() {
        // SPEC-070-A J.21 — null out delegate + lambda captures eagerly so a
        // stray reference to the VM (e.g. held by a still-running coroutine)
        // doesn't keep the host's strategy/view-model graph alive past the
        // flow end. The full VM is also auto-cleared by Android on finish().
        viewModel.reset()
        finish()
    }

    override fun onBackPressed() {
        // SPEC-070-A I.4 — respect `flow.settings.dismiss_allowed` (canDismiss)
        // when handling the system back button. When dismissal is disallowed
        // the flow intercepts back, mirroring iOS force-flow behavior.
        // (allow_back gates step-back navigation, not the global dismiss.)
        val flow = viewModel.flow
        val canDismiss = flow?.settings?.dismiss_allowed ?: true
        if (!canDismiss) {
            // Force-flow: ignore system back to prevent accidental abandonment.
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
        // SPEC-401-A R9 — report the currently-visible step (matches iOS
        // OnboardingRenderer.swift:280-281 which uses `currentStep`/
        // `currentIndex`). Previously reported `steps.first()` + index 0
        // which broke `onOnboardingDismissed(atStep:)` semantics and
        // corrupted dismiss analytics for any flow with >1 step.
        flow?.let { f ->
            val idx = viewModel.currentIndex.intValue.coerceIn(0, (f.steps.size - 1).coerceAtLeast(0))
            val stepId = f.steps.getOrNull(idx)?.id ?: ""
            viewModel.onFlowDismissed?.invoke(stepId, idx)
        }
        cleanup()
    }

    /**
     * SPEC-070-A I.7 — persist current step + responses across config
     * changes / process death so the flow re-enters on the same step.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_STEP_INDEX, viewModel.currentIndex.intValue)
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in viewModel.responses) {
                obj.put(k, when (v) {
                    is Map<*, *> -> org.json.JSONObject(v as Map<String, Any?>)
                    is List<*> -> org.json.JSONArray(v)
                    else -> v
                })
            }
            outState.putString(KEY_RESPONSES, obj.toString())
        } catch (_: Throwable) { /* best-effort */ }
        // SPEC-070-A finalization P0 audit-8 D2 — persist navigationHistory
        // alongside currentIndex / responses. previous_step_* rules need
        // this for correctness after rotation / process death.
        outState.putStringArray(KEY_NAV_HISTORY, viewModel.navigationHistory.toTypedArray())
    }

    /**
     * SPEC-070-A J.21 — typed payload handed off from
     * [OnboardingFlowManager.present] to the next [OnboardingActivity]
     * `onCreate`. Replaces the previous bag-of-companion-statics. Each
     * Activity instance consumes the slot exactly once on first
     * `onCreate` then writes it into its [OnboardingViewModel] so the
     * payload's lifetime is bounded to that Activity instance.
     */
    internal data class LaunchPayload(
        val flow: OnboardingFlowConfig,
        val delegate: AppDNAOnboardingDelegate?,
        val eventTracker: EventTracker?,
        val onStepViewed: ((String, Int) -> Unit)?,
        val onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)?,
        val onStepSkipped: ((String, Int) -> Unit)?,
        val onFlowCompleted: ((Map<String, Any>) -> Unit)?,
        val onFlowDismissed: ((String, Int) -> Unit)?,
    )

    companion object {
        private const val KEY_CURRENT_STEP_INDEX = "appdna_onboarding_current_step_index"
        private const val KEY_RESPONSES = "appdna_onboarding_responses"
        // SPEC-070-A finalization P0 audit-8 D2 — navigation history for
        // previous_step_* rule operators (iOS OnboardingRenderer.swift:982-988).
        private const val KEY_NAV_HISTORY = "appdna_onboarding_nav_history"

        /**
         * SPEC-070-A J.21 — single-slot next-launch payload. Set by [launch]
         * immediately before `startActivity()` and consumed by `onCreate`
         * via [consumePendingLaunchPayload]. Volatile because [launch]
         * may be called from any thread and `onCreate` runs on Main.
         *
         * Only one onboarding flow can be in-flight at a time. The flow
         * manager doesn't enforce this — but the app itself does, since
         * `presentOnboarding(...)` always blocks the calling Activity
         * until the system stacks the new OnboardingActivity. If a host
         * stacks a second `launch()` before the first `onCreate` ran the
         * later one wins (matches the previous companion-static behavior;
         * we tolerate this edge case rather than queue).
         */
        @Volatile
        private var pendingLaunchPayload: LaunchPayload? = null

        /**
         * Atomically read + clear the next-launch payload. Returns null if
         * no flow was queued (cold restart with no caller, or a same-process
         * recreate where the VM should already be bound).
         */
        @Synchronized
        private fun consumePendingLaunchPayload(): LaunchPayload? {
            val p = pendingLaunchPayload
            pendingLaunchPayload = null
            return p
        }

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
            pendingLaunchPayload = LaunchPayload(
                flow = flow,
                delegate = delegate,
                eventTracker = eventTracker,
                onStepViewed = onStepViewed,
                onStepCompleted = onStepCompleted,
                onStepSkipped = onStepSkipped,
                onFlowCompleted = onFlowCompleted,
                onFlowDismissed = onFlowDismissed,
            )

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
    // SPEC-070-A J.21 — VM is the single source of truth for `currentIndex`
    // + `responses`. The composable observes Compose state inside the VM
    // directly so writes survive Activity recreation without a separate
    // mirroring `LaunchedEffect`. Optional for tests / previews that build
    // the host without an Activity — when null we fall back to local
    // `remember`-backed state, mirroring the original behavior.
    viewModel: OnboardingViewModel? = null,
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
    // SPEC-070-A J.21 — when running under an Activity, use the VM's state
    // directly so the composable's `currentIndex` / `responses` survive
    // Activity recreation. When the VM is null (test / preview path) fall
    // back to local `remember`-backed state seeded from the legacy
    // process-death restore route below.
    val currentIndexState = viewModel?.currentIndex
        ?: remember { mutableIntStateOf(0) }
    val responses: SnapshotStateMap<String, Any> = viewModel?.responses
        ?: remember { mutableStateMapOf() }

    // SPEC-070-A I.7 — one-shot consume the process-death restore snapshot.
    // The VM holds the snapshot when the Activity rehydrated from
    // `savedInstanceState` and the responses map was empty (cold restore).
    LaunchedEffect(viewModel) {
        val vm = viewModel ?: return@LaunchedEffect
        val ri = vm.restoredStepIndex
        val rr = vm.restoredResponses
        if (ri != null) {
            currentIndexState.intValue = ri
            vm.restoredStepIndex = null
        }
        if (rr != null) {
            responses.putAll(rr)
            vm.restoredResponses = null
        }
    }

    // SPEC-070-A J.21 — Compose's `MutableIntState` `getValue` / `setValue`
    // operator extensions let us bind a local `var` to the VM-backed state
    // so the rest of the composable body keeps its existing assignment
    // syntax (`currentIndex++`, `currentIndex = targetIndex`) while writes
    // route through `currentIndexState.intValue` and survive recreation.
    var currentIndex by currentIndexState

    // SPEC-070-A J.2 — haptic feedback for step advance / back / dismiss /
    // button-tap interactions, gated by `flow.settings.haptic.enabled`.
    val hostView = LocalView.current
    val hapticConfig = flow.settings.haptic
    val coroutineScope = rememberCoroutineScope()
    // SPEC-070-A finalization OB-5 — hoist Activity context for the
    // OnboardingPaywallBridge presentation path. AppDNA.paywall.present
    // requires an Activity, and the OnboardingActivity itself is the
    // topmost; this Composable is rendered inside it.
    val activityCtx = androidx.compose.ui.platform.LocalContext.current

    // SPEC-070-A finalization P0 audit-7+8 — navigation history stack for
    // `previous_step_equals` / `previous_step_in` rule operators
    // (iOS OnboardingRenderer.swift:982-988, 1065-1088). Push the step
    // id we're LEAVING from before changing currentIndex; rule evaluator
    // reads `lastOrNull()` as the user's previous step. Mirrors iOS
    // `navigationHistory: [String]`.
    //
    // Audit-8 D2: hoisted into OnboardingViewModel so it survives
    // Activity recreation alongside currentIndex / responses. Test path
    // (no VM available) falls back to a `remember` list — recreation
    // wouldn't apply there.
    val navigationHistory = viewModel?.navigationHistory ?: remember { mutableStateListOf<String>() }

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

    // SPEC-070-A finalization OB-7 — image preload at flow init.
    // iOS `OnboardingRenderer.swift:130-139` walks every step's content_blocks
    // recursively, collects every image_url referenced, and pre-warms
    // NSURLCache + UIImage decode so the first paint of each step doesn't
    // do a synchronous network fetch. Without this, every step renders
    // blank-image placeholders that snap to loaded mid-scroll.
    //
    // The Android `ImagePreloader` (core/ImagePreloader.kt) was already
    // shipped at SPEC-070-A G.13 but never CALLED. Wire it here once at
    // flow start; Coil dedupes its own work so a second call (e.g. from
    // a hook-driven config refresh) is safe.
    val preloaderContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(flow.id) {
        try {
            val urls = collectFlowImageURLs(flow)
            if (urls.isNotEmpty()) {
                ai.appdna.sdk.core.ImagePreloader(preloaderContext).prefetch(urls)
            }
        } catch (e: Throwable) {
            // Non-fatal — image preload is purely a UX speedup.
            ai.appdna.sdk.Log.warning { "Image preload failed: ${e.message}" }
        }
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

    // SPEC-070-A finalization OB-5 — present a paywall_trigger node by id.
    // Mirrors iOS OnboardingRenderer.swift:1186-1272 (`presentPaywallTrigger`)
    // + `routeOutcome`. Extracted as a local function so it can be invoked
    // from BOTH the rule-evaluation `RuleTarget.PaywallTrigger` branch AND
    // recursively from within `routeOutcome`'s "default" branch when an
    // outcome target points at another paywall_trigger node (winback / soft
    // → hard chain). iOS handles chained triggers via `navigateToTarget`;
    // this Android local function mirrors the same routing surface.
    //
    // `depth` is a recursion guard — chained paywalls deeper than 8 levels
    // are almost certainly a config loop; collapse to flow-complete.
    fun presentPaywallTriggerNode(triggerNodeId: String, depth: Int = 0) {
        if (depth > 8) {
            ai.appdna.sdk.Log.warning(
                "OnboardingPaywallBridge: chained paywall depth >8, completing flow"
            )
            @Suppress("UNCHECKED_CAST")
            onFlowCompleted(responses.toMap() as Map<String, Any>)
            return
        }
        // Resolve trigger data — mirrors iOS resolvePaywallTriggerData:
        // prefer graph_nodes, fall back to graph_layout.nodes for back-compat.
        @Suppress("UNCHECKED_CAST")
        val triggerData: Map<String, Any?>? = run {
            val fromNodes = flow.graph_nodes?.get(triggerNodeId) as? Map<String, Any?>
            if (fromNodes != null) return@run fromNodes
            val nodes = flow.graph_layout?.get("nodes") as? List<*>
            val match = nodes?.firstOrNull {
                ((it as? Map<*, *>)?.get("id") as? String) == triggerNodeId
            } as? Map<*, *>
            (match?.get("data") as? Map<String, Any?>)
        }
        // Resolve paywall id — mirrors iOS resolvePaywallFromTrigger.
        val paywallId = (triggerData?.get("paywall_id") as? String)?.takeIf { it.isNotBlank() }
            ?: (triggerData?.get("paywallId") as? String)?.takeIf { it.isNotBlank() }
            ?: triggerNodeId.removePrefix("paywall_trigger_")
        val onSuccessTarget = (triggerData?.get("on_success_target") as? String)?.takeIf { it.isNotBlank() }
        val onFailTarget = (triggerData?.get("on_fail_target") as? String)?.takeIf { it.isNotBlank() }
        val onDismissTarget = (triggerData?.get("on_dismiss_target") as? String)?.takeIf { it.isNotBlank() }
        val legacyDismiss = triggerData?.get("on_dismiss") as? String ?: "continue"
        val edgeTarget = triggerData?.get("next_target") as? String
        // Detect whether a target id refers to a paywall_trigger node.
        // Mirrors iOS `graphNodeType(for:)` plus the `paywall_trigger_` prefix
        // fallback at OnboardingRenderer.swift:1166-1184. Used by routeOutcome
        // below to detect chained paywall_trigger destinations.
        fun isPaywallTriggerTarget(target: String): Boolean {
            if (target.startsWith("paywall_trigger_")) return true
            val nodeData = flow.graph_nodes?.get(target) as? Map<*, *>
            return (nodeData?.get("type") as? String) == "paywall_trigger"
        }
        // Outcome router — mirrors iOS `routeOutcome` closure at lines
        // 1207-1245. Recurses into `presentPaywallTriggerNode` when the
        // chosen target is itself a paywall_trigger (winback chain).
        val routeOutcome: (String?, String, String) -> Unit = { configured, defaultBehavior, reason ->
            val chosen = configured ?: defaultBehavior
            when (chosen) {
                "stay" -> {
                    eventTracker?.track(
                        "onboarding_paywall_stay",
                        mapOf(
                            "flow_id" to flow.id,
                            "paywall_id" to paywallId,
                            "reason" to reason,
                        ),
                    )
                }
                "complete_flow", "" -> {
                    eventTracker?.track(
                        "onboarding_completed",
                        mapOf(
                            "flow_id" to flow.id,
                            "paywall_id" to paywallId,
                            "completed_via" to reason,
                        ),
                    )
                    @Suppress("UNCHECKED_CAST")
                    onFlowCompleted(responses.toMap() as Map<String, Any>)
                }
                "continue" -> {
                    if (!edgeTarget.isNullOrBlank()) {
                        if (isPaywallTriggerTarget(edgeTarget)) {
                            presentPaywallTriggerNode(edgeTarget, depth + 1)
                        } else {
                            val tIdx = flow.steps.indexOfFirst { it.id == edgeTarget }
                            if (tIdx >= 0) {
                                // SPEC-401-A R3 — DO NOT push history here.
                                // advanceOrComplete already pushed the leaving
                                // step before invoking presentPaywallTriggerNode
                                // (line 711). Pushing again caused back-nav to
                                // consume two history entries to move one step
                                // back.
                                currentIndex = tIdx
                            }
                            else {
                                // SPEC-401-A R6 — same fallback as the
                                // sibling "chosen" branch: unknown
                                // edgeTarget continues the flow rather
                                // than terminating. iOS
                                // navigateToTarget(edge) falls through
                                // to advanceOrComplete()
                                // (OnboardingRenderer.swift:1166-1184),
                                // re-running step rules. Android cannot
                                // call advanceOrComplete from this
                                // lambda due to local-fun forward-ref
                                // scoping; simplified single-step
                                // continuation covers the most common
                                // case so a stale/typo'd next_target
                                // doesn't collapse the whole flow.
                                if (currentIndex + 1 < flow.steps.size) {
                                    currentIndex++
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    onFlowCompleted(responses.toMap() as Map<String, Any>)
                                }
                            }
                        }
                    } else {
                        eventTracker?.track(
                            "onboarding_completed",
                            mapOf(
                                "flow_id" to flow.id,
                                "paywall_id" to paywallId,
                                "completed_via" to reason,
                            ),
                        )
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                    }
                }
                else -> {
                    // Detect chained paywall_trigger or end node first
                    // (iOS navigateToTarget at OnboardingRenderer.swift:1166).
                    if (isPaywallTriggerTarget(chosen)) {
                        presentPaywallTriggerNode(chosen, depth + 1)
                    } else if (chosen.startsWith("end_") ||
                        ((flow.graph_nodes?.get(chosen) as? Map<*, *>)?.get("type") as? String) == "end") {
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                    } else {
                        // Treat as a step ID — navigate.
                        val tIdx = flow.steps.indexOfFirst { it.id == chosen }
                        if (tIdx >= 0) {
                            // SPEC-401-A R3 — DO NOT push history here.
                            // advanceOrComplete already pushed the leaving
                            // step (line 711) before invoking
                            // presentPaywallTriggerNode. Pushing again
                            // caused double-pop on back-nav.
                            currentIndex = tIdx
                        } else {
                            // SPEC-401-A R4 — unknown target should
                            // continue the flow rather than terminate.
                            // iOS calls advanceOrComplete() here
                            // (OnboardingRenderer.swift:1180-1184) so
                            // typo'd targets follow the next step rules.
                            // Local-function forward-ref restrictions
                            // prevent calling advanceOrComplete directly
                            // from this lambda; the simplified fallback
                            // below covers the most common case
                            // (single-step continuation) so the flow
                            // doesn't dead-end on a config typo. Full
                            // rule re-evaluation would require lifting
                            // routeOutcome out of presentPaywallTriggerNode.
                            if (currentIndex + 1 < flow.steps.size) {
                                currentIndex++
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                onFlowCompleted(responses.toMap() as Map<String, Any>)
                            }
                        }
                    }
                }
            }
        }
        val legacyDismissDefault: String = when (legacyDismiss) {
            "block", "skip_to_end" -> "complete_flow"
            "continue" -> "continue"
            else -> "continue"
        }

        // SPEC-401 Fix 1A — entitlement-aware skip gate.
        // Default `true` matches the new SDK contract: paywalls auto-skip
        // for already-subscribed users unless the author explicitly opts
        // out (upsell paywalls). Older flows that never authored the field
        // resolve to null → defaults to true here. `hasActiveSubscription()`
        // is synchronous on Android (reads from `EntitlementCache`); cold
        // start with empty cache returns false, so the paywall presents
        // (acceptable defensive fallback per spec edge cases).
        val skipIfSubscribed = (triggerData?.get("skip_if_subscribed") as? Boolean) ?: true
        if (skipIfSubscribed && AppDNA.billing.hasActiveSubscription()) {
            eventTracker?.track(
                "onboarding_paywall_skip",
                mapOf(
                    "flow_id" to flow.id,
                    "paywall_id" to paywallId,
                    "reason" to "user_already_subscribed",
                ),
            )
            // Reuse the same routing primitive used after a real purchase
            // so success-target wiring (continue / specific node / complete)
            // takes a single code path. Default fallback is "continue" —
            // mirrors iOS OnboardingRenderer presentPaywallTrigger.
            routeOutcome(onSuccessTarget, "continue", "user_already_subscribed")
            return
        }

        val bridge = OnboardingPaywallBridge(
            onPurchased = { routeOutcome(onSuccessTarget, "continue", "paywall_purchased") },
            onFailed = { routeOutcome(onFailTarget, "stay", "paywall_payment_failed") },
            onDismissedWithoutPurchase = {
                routeOutcome(onDismissTarget, legacyDismissDefault, "paywall_dismissed")
            },
        )
        val activity = activityCtx as? android.app.Activity
        if (activity != null) {
            // SPEC-401-A — 100ms delay before presenting paywall mirrors
            // iOS Task.sleep(100ms) at OnboardingRenderer.swift:1280 so
            // the host fade-out cadence is preserved. Android previously
            // called presentPaywall immediately producing a snappier-but-
            // jarring transition.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Use the static AppDNA.presentPaywall — it accepts a per-call
                // listener (AppDNA.paywall.present instance method only re-uses
                // the global listener slot). The bridge then forwards every
                // delegate event to the global host listener via
                // AppDNA.paywall.listener (OnboardingPaywallBridge.forwardOnMain).
                AppDNA.presentPaywall(
                    activity = activity,
                    id = paywallId,
                    context = null,
                    listener = bridge,
                )
            }, 100)
        } else {
            ai.appdna.sdk.Log.warning(
                "OnboardingPaywallBridge: no Activity context; falling back to legacy __paywall_trigger marker."
            )
            val merged = responses.toMutableMap()
            merged["__paywall_trigger"] = paywallId
            @Suppress("UNCHECKED_CAST")
            onFlowCompleted(merged.toMap() as Map<String, Any>)
        }
    }

    fun advanceOrComplete() {
        // SPEC-070-A J.2 — fire `on_step_advance` haptic before evaluating
        // next-step rules (mirrors iOS HapticController invocation in
        // OnboardingRenderer.advanceStep()).
        HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_step_advance, hapticConfig)
        // SPEC-070-A finalization P0 audit-7 — push the step we're
        // leaving onto navigationHistory before any branch mutates
        // currentIndex below. Mirrors iOS `navigationHistory.append(...)`
        // at every `navigate(to:)` call site. We push BEFORE the rules
        // branch decides where to go because every routing path
        // (target step, paywall_trigger, end, advance, skip) advances
        // away from the current step. Back navigation (handleAction
        // "back") POPs instead and is handled at its own call site.
        flow.steps.getOrNull(currentIndex)?.id?.let { navigationHistory.add(it) }
        val step = if (currentIndex < flow.steps.size) flow.steps[currentIndex] else null
        // SPEC-070-A audit Round 2-restart attempt 2 F1: prefer the
        // layout-level `step.config.next_step_rules` (Logic-panel-authored)
        // when it carries richer `conditions[]` than the step-level rules.
        // Mirrors iOS OnboardingRenderer.swift:761-766.
        val stepRules = step?.next_step_rules
        val layoutRules = step?.config?.next_step_rules
        val hasLayoutConditions = layoutRules?.any { !it.conditions.isNullOrEmpty() } == true
        val hasStepConditions = stepRules?.any { !it.conditions.isNullOrEmpty() } == true
        val rules = when {
            hasLayoutConditions && !hasStepConditions -> layoutRules
            !stepRules.isNullOrEmpty() -> stepRules
            else -> layoutRules
        }
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
                    // SPEC-070-A finalization P0 audit-7 — mirror iOS
                    // OnboardingRenderer.swift `previousStepId` source.
                    // SPEC-401-A R50 (Lens B P0) — read the step BEFORE
                    // the just-pushed current step. Line 769 pushed the
                    // step we're leaving (`step.id`) onto navigationHistory
                    // before this loop runs, so `.last()` would return the
                    // CURRENT step, inverting `previous_step_equals` rule
                    // semantics. iOS pushes inside `navigate(to:)` AFTER
                    // rule classification (OnboardingRenderer.swift:841-848),
                    // so iOS `navigationHistory.last` is correctly the
                    // entry-prior step. We read `size - 2` to recover the
                    // same value without restructuring the call sites.
                    previousStepId = navigationHistory.elementAtOrNull(navigationHistory.size - 2),
                )
                if (!matches) continue

                // SPEC-070-A finalization Phase D — short-id analytics_event
                // upgrade. Console emits e.g. `analytics2` (no legacy prefix);
                // graph_nodes[target].type == "analytics_event" tells us to
                // re-classify from RuleTarget.Step → RuleTarget.AnalyticsEvent.
                @Suppress("UNCHECKED_CAST")
                val rawClassified = classifyRuleTarget(rule.target_step_id)
                // SPEC-401-A R10 — also upgrade short-id paywall_trigger /
                // end graph nodes (`paywall1` / `end1`) at the entry-point
                // classification, mirroring iOS dual prefix-or-nodeType
                // detection at OnboardingRenderer.swift:808,814. Without
                // this the editor's short-id targets fell into
                // `RuleTarget.Step` and silently no-oped.
                when (val classified = upgradeToShortIdRuleTarget(rawClassified, flow.graph_nodes as? Map<String, Any?>)) {
                    is RuleTarget.Empty -> continue
                    is RuleTarget.PaywallTrigger -> {
                        presentPaywallTriggerNode(classified.rawTarget)
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
                    is RuleTarget.AnalyticsEvent -> {
                        // SPEC-070-A finalization Phase D — analytics_event
                        // graph node. Mirrors iOS OnboardingRenderer.swift:
                        // 789-801 exactly:
                        //   1. Resolve event_name from
                        //      `flow.graph_nodes[nodeId].event_name`,
                        //      default to literal "onboarding_analytics".
                        //   2. Fire eventTracker.track(eventName, payload)
                        //      with payload {flow_id, node_id, step_id}.
                        //      `node_id` (NOT `step_index`) is the
                        //      load-bearing key for ETL grouping.
                        //   3. If `nodeData.next_target` is set, follow
                        //      it (recursively classify; supports chained
                        //      analytics → step / analytics → analytics).
                        //   4. Otherwise fall through to natural advance
                        //      via `continue`.
                        @Suppress("UNCHECKED_CAST")
                        val nodeData = (flow.graph_nodes?.get(classified.nodeId) as? Map<String, Any?>)
                        val eventName = (nodeData?.get("event_name") as? String) ?: "onboarding_analytics"
                        eventTracker?.track(
                            eventName,
                            mapOf(
                                "flow_id" to flow.id,
                                "node_id" to classified.nodeId,
                                "step_id" to step.id,
                            ),
                        )
                        val nextTarget = nodeData?.get("next_target") as? String
                        if (!nextTarget.isNullOrBlank()) {
                            val tIdx = flow.steps.indexOfFirst { it.id == nextTarget }
                            if (tIdx >= 0) {
                                // SPEC-401-A R3 — DO NOT push history here.
                                // advanceOrComplete already pushed the
                                // leaving step at line 711 before reaching
                                // this analytics_event branch. Pushing again
                                // caused double-pop on back-nav.
                                currentIndex = tIdx
                                return
                            }
                        }
                        // No next_target — continue rule loop; eventually
                        // falls through to natural advancement.
                        continue
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
            // SPEC-070-A finalization P0 audit-8 D1 — push the leaving
            // step before skip-target navigation so the destination's
            // previous_step_* rules see the correct prevId. Mirrors
            // iOS navigate(to:appendHistory:) which is called from
            // every navigation site, including hook-driven SkipTo.
            flow.steps.getOrNull(currentIndex)?.id?.let { navigationHistory.add(it) }
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

    // SPEC-401-A R19 — adapt status bar icon tint to onboarding background
    // luminance. With edge-to-edge layout, the system status bar paints over
    // our content; without setting `isAppearanceLightStatusBars` based on
    // background luminance, white system icons are invisible on a light
    // onboarding background and vice versa. iOS handles this automatically
    // via `preferredStatusBarStyle` inheritance from the parent VC.
    val bgColorForStatusBar = MaterialTheme.colorScheme.background
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val luminance = 0.2126 * bgColorForStatusBar.red +
                0.7152 * bgColorForStatusBar.green +
                0.0722 * bgColorForStatusBar.blue
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = luminance > 0.5
            // SPEC-401-A R20 — also adapt the bottom nav-bar icon tint.
            // Same edge-to-edge problem as the status bar: without this, the
            // host app's nav-bar tint persists into onboarding and 3-button
            // nav icons are invisible against the onboarding background.
            controller.isAppearanceLightNavigationBars = luminance > 0.5
        }
    }

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
                // SPEC-070-A audit Round 2-restart attempt 2 F2: progress
                // color resolution mirrors iOS OnboardingRenderer.swift:174-186
                // — step.config.progress_color > step.config.element_style?
                // .background?.color > flow.settings.progress_color > default.
                val stepProgressColor = currentStep?.config?.progress_color
                    ?: currentStep?.config?.element_style?.background?.color
                val progressColor = (stepProgressColor ?: flow.settings.progress_color)?.let {
                    ai.appdna.sdk.core.StyleEngine.parseColor(it)
                } ?: MaterialTheme.colorScheme.primary
                val progressTrackColor = flow.settings.progress_track_color?.let {
                    ai.appdna.sdk.core.StyleEngine.parseColor(it)
                } ?: Color.Gray.copy(alpha = 0.2f)
                // SPEC-070-A finalization Phase B — progress_style branches.
                // Mirrors iOS OnboardingRenderer.swift progress style switch:
                //   "dots" — N circles, filled to currentIndex
                //   "segmented_bar" — N segments, filled to currentIndex
                //   "fraction" — Text "i / N"
                //   "none" — render nothing (still in show_progress branch)
                //   default / "continuous_bar" — LinearProgressIndicator (existing)
                val totalSteps = flow.steps.size.coerceAtLeast(1)
                val current = (currentIndex + 1).coerceIn(1, totalSteps)
                // SPEC-401-A R36 (Lens C #3) — animate progress style transitions
                // to mirror iOS .animation(.easeInOut(duration: 0.2-0.3)) on
                // OnboardingRenderer.swift:199, 211, 235. Was snapping per
                // step → visible flicker on each `next`.
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 300),
                    label = "progressAnim",
                )
                when (flow.settings.progress_style?.lowercase()) {
                    "none" -> { /* explicit suppress */ }
                    "dots" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            for (i in 1..totalSteps) {
                                val filled = i <= current
                                // SPEC-401-A R39 — drop dot-size animation added in
                                // R36. iOS OnboardingRenderer.swift:198 uses fixed
                                // .frame(width: 8, height: 8) for every dot — only
                                // fill color animates. Was causing visible "pop"
                                // larger drift from iOS.
                                val animatedColor by animateColorAsState(
                                    targetValue = if (filled) progressColor else progressTrackColor,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "dotColor",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(animatedColor),
                                )
                            }
                        }
                    }
                    "segmented_bar" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (i in 1..totalSteps) {
                                val filled = i <= current
                                val animatedColor by animateColorAsState(
                                    targetValue = if (filled) progressColor else progressTrackColor,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "segColor",
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(animatedColor),
                                )
                            }
                        }
                    }
                    "fraction" -> {
                        // SPEC-401-A R33 — match iOS OnboardingRenderer.swift:218
                        // `"\(current+1)/\(total)"` (no spaces) at
                        // `.font(.caption.monospacedDigit())` ~12pt monospaced.
                        // Android was rendering `"3 / 10"` at 14sp non-mono so
                        // digits jittered on each tick.
                        Text(
                            text = "$current/$totalSteps",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    else -> {
                        // SPEC-401-A R22 — Material3 1.2 deprecated the
                        // `progress: Float` overload in favor of the lambda
                        // form. Pre-emptive switch silences runtime warnings
                        // and unblocks the next BOM bump.
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = progressColor,
                            trackColor = progressTrackColor
                        )
                    }
                }
            }

            // SPEC-401-A — gate entire nav-bar Row when both back +
            // dismiss are hidden. iOS doesn't render the empty VStack
            // child in that case (OnboardingRenderer.swift:46-49); on
            // Android the IconButton spacers reserve ~48dp regardless,
            // so authored "no-chrome" flows had a phantom band at top.
            val navBackVisible = flow.settings.allow_back && navigationHistory.isNotEmpty()
            val navDismissAllowed = flow.settings.dismiss_allowed ?: true
            if (navBackVisible || navDismissAllowed) {
            // Navigation bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // SPEC-070-A finalization B4 P1 — gate the back arrow on
                // navigationHistory.isNotEmpty() rather than currentIndex>0.
                // Mirrors iOS OnboardingRenderer.swift:257. After a skipTo
                // jump that clears the history, currentIndex can be >0 with
                // empty history; the old gate showed a back arrow that
                // popped to a step the user never visited.
                // SPEC-070-A finalization B4 P1 — wire back_button_style
                // (icon_size + icon_color) and dismiss_allowed. iOS reads
                // these from OnboardingRenderer.swift:250-292.
                val bbStyle = flow.settings.back_button_style
                // SPEC-401-A — default 16sp matches iOS
                // OnboardingRenderer.swift:252 (16pt). Android previously
                // defaulted to 20sp making the back glyph noticeably
                // larger than iOS for the same flow config.
                val backIconSize = (bbStyle?.icon_size?.toFloat() ?: 16f).sp
                // SPEC-401-A R5 — default to iOS gray (#6B7280) instead
                // of Material onBackground (black in light theme).
                // Mirrors iOS OnboardingRenderer.swift:253.
                val backIconColor = bbStyle?.icon_color?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                    ?: ai.appdna.sdk.core.StyleEngine.parseColor("#6B7280")
                if (flow.settings.allow_back && navigationHistory.isNotEmpty()) {
                    val backCd = stringResource(R.string.appdna_a11y_onboarding_back)
                    IconButton(
                        onClick = {
                            // SPEC-070-A J.2 — back navigation reuses
                            // `on_step_advance` haptic style.
                            HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_step_advance, hapticConfig)
                            // SPEC-401-A R3 — jump to the popped step's
                            // index, NOT currentIndex-1. iOS uses the popped
                            // index (OnboardingRenderer.swift:259-262) so
                            // non-linear flows (rule-skip, paywall continue,
                            // analytics→target) back-nav lands on the step
                            // the user actually came FROM. The old
                            // `currentIndex--` path landed on whatever step
                            // sat at index-1 even if the user had skipped
                            // over it. Android tracks step IDs (not Ints)
                            // so we look up the index by id; if the id no
                            // longer resolves (e.g. flow rebuilt), fall
                            // back to currentIndex-1.
                            val previousId = navigationHistory.lastOrNull()
                            val previousIndex = previousId
                                ?.let { id -> flow.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                                ?: maxOf(currentIndex - 1, 0)
                            if (navigationHistory.isNotEmpty()) {
                                navigationHistory.removeAt(navigationHistory.lastIndex)
                            }
                            currentIndex = previousIndex
                        },
                        enabled = !isProcessing,
                        // SPEC-070-A J.11 — back arrow rendered as Text glyph
                        // (no semantic icon); attach contentDescription so
                        // TalkBack announces purpose.
                        modifier = Modifier.semantics { contentDescription = backCd },
                    ) {
                        Text(
                            text = "\u2190",
                            fontSize = backIconSize,
                            fontWeight = FontWeight.SemiBold,
                            color = backIconColor,
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                // SPEC-070-A finalization B4 P1 — gate dismiss X on
                // `flow.settings.dismiss_allowed`. iOS suppresses dismiss
                // when set false (e.g. mandatory onboarding flows).
                if (!navDismissAllowed) {
                    Spacer(Modifier.size(48.dp))
                } else {
                val dismissCd = stringResource(R.string.appdna_a11y_onboarding_close)
                IconButton(
                    onClick = {
                        // SPEC-070-A J.2 — dismiss reuses on_button_tap haptic.
                        HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_button_tap, hapticConfig)
                        if (currentIndex < flow.steps.size) {
                            val step = flow.steps[currentIndex]
                            onFlowDismissed(step.id, currentIndex)
                        }
                    },
                    enabled = !isProcessing,
                    // SPEC-070-A J.11 — close X is a Text glyph with no
                    // semantic label by default.
                    modifier = Modifier.semantics { contentDescription = dismissCd },
                ) {
                    Text(
                        text = "\u2715",
                        // SPEC-401-A R5 \u2014 match iOS dismiss X font.system(size:14).
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                } // end dismissAllowed
            }
            } // end SPEC-401-A navBarVisible gate

            // Step content with animated transitions
            if (currentIndex < flow.steps.size) {
                AnimatedContent<Int>(
                    targetState = currentIndex,
                    transitionSpec = {
                        // SPEC-401-A R8 — match iOS step transition: pure
                        // horizontal slide with `easeInOut(0.25s)` curve.
                        // Previous Compose default added cross-dissolve
                        // (fadeIn/fadeOut) and used a 300ms FastOutSlowIn
                        // tween, both visible side-by-side with iOS.
                        val spec = tween<IntOffset>(durationMillis = 250, easing = FastOutSlowInEasing)
                        slideInHorizontally(animationSpec = spec) { it } togetherWith
                            slideOutHorizontally(animationSpec = spec) { -it }
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
                    // SPEC-401-A R11 — pass accumulated responses (immutable
                    // snapshot of the host's SnapshotStateMap) so block-level
                    // `visibility_condition` operators evaluate against real
                    // user answers, not an empty map.
                    accumulatedResponses = responses.toMap(),
                    // SPEC-070-A finalization B4 P1 — when revisiting a step
                    // via back navigation, restore previously-entered field
                    // values. Mirrors iOS OnboardingStepRouter savedResponses
                    // (OnboardingRenderer.swift:1316,1448) which is threaded
                    // into FormStepView/BlockBasedStepView so inputValues
                    // pre-populate from `responses[step.id]`.
                    savedResponses = (responses[step.id] as? Map<*, *>)?.let { raw ->
                        @Suppress("UNCHECKED_CAST")
                        raw as Map<String, Any>
                    },
                    onNext = { data ->
                        if (data != null) {
                            responses[step.id] = data
                        }
                        // SPEC-087: Persist responses incrementally so TemplateEngine has fresh data for next step.
                        // SPEC-070-A finalization spec audit-4 — was unsafe
                        // `as Map<String, Map<String, Any>>` cast that
                        // throws ClassCastException whenever a step writes
                        // a primitive top-level value (very common). Drop
                        // the cast — `setOnboardingResponses` already takes
                        // `Map<String, Any>` (SessionDataStore.kt:70) so
                        // pass-through is type-safe.
                        ai.appdna.sdk.core.SessionDataStore.instance?.setOnboardingResponses(responses.toMap())
                        onStepCompleted(step.id, currentIndex, data)

                        // SPEC-083: Determine hook type — client delegate takes priority
                        if (delegate != null) {
                            // Client-side hook
                            // SPEC-401-A R11 — match iOS 300ms grace window
                            // (OnboardingRenderer.swift:483-503). iOS schedules a
                            // DispatchWorkItem to flip isProcessing=true after
                            // 300ms, cancelled if the hook returns first. Without
                            // this, every <50ms hook flashes the dimmer + spinner.
                            loadingText = step.hook?.loading_text ?: "Processing..."
                            val hookFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                            val startTime = System.currentTimeMillis()
                            trackHookEvent("onboarding_hook_started", step, mapOf("hook_type" to "client"))

                            // Grace timer — only show spinner if hook hasn't returned within 300ms.
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(300)
                                if (!hookFinished.get()) {
                                    isProcessing = true
                                }
                            }

                            coroutineScope.launch {
                                val result = delegate.onBeforeStepAdvance(
                                    flowId = flow.id,
                                    fromStepId = step.id,
                                    stepIndex = currentIndex,
                                    stepType = step.type.value,
                                    responses = responses.toMap(),
                                    stepData = data
                                )
                                hookFinished.set(true)
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
        // SPEC-401-A R35 — wrap in AnimatedVisibility to mirror iOS
        // OnboardingRenderer.swift:80-83,318-347 transition + the success
        // banner sibling below. Was popping in/out instantly.
        AnimatedVisibility(
            visible = showError && errorMessage != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = if (flow.settings.show_progress && currentStep?.hide_progress != true) 56.dp else 52.dp)
                    .background(Color.Red, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        // SPEC-401-A R37 (Lens C #4) — assertive a11y
                        // announce when the error banner mounts. iOS auto-
                        // announces SwiftUI Text inserted into the hierarchy.
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
                val errorDismissCd = stringResource(R.string.appdna_a11y_onboarding_dismiss_error)
                IconButton(
                    onClick = { showError = false; errorMessage = null },
                    // SPEC-070-A J.11 — error-banner close (Text glyph).
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = errorDismissCd },
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
                    modifier = Modifier
                        .weight(1f)
                        // SPEC-401-A R37 (Lens C #4) — polite a11y announce
                        // for success banner mount (lower priority than error).
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                val successDismissCd = stringResource(R.string.appdna_a11y_onboarding_dismiss_success)
                IconButton(
                    onClick = { showSuccess = false; successMessage = null },
                    modifier = Modifier.size(24.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Close,
                        // SPEC-070-A J.11 — pull from string resource so RTL
                        // locales / hosts can override the a11y label.
                        contentDescription = successDismissCd,
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
                    .background(Color.Black.copy(alpha = 0.5f))
                    // SPEC-401-A R36 (Lens C #2) — consume pointer events so
                    // step CTAs underneath stop receiving taps during async
                    // webhook waits. iOS uses `.ignoresSafeArea()` on a
                    // hit-testable Color (OnboardingRenderer.swift:296-313).
                    // Compose Box.background paints but doesn't consume hits;
                    // users could double-fire `next`/`continue` during the
                    // 300ms-3s loading spinner.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    // SPEC-401-A R37 (Lens C #3) — TalkBack announcement
                    // when overlay appears + traversal trap. iOS SwiftUI
                    // ProgressView is auto-announced by VoiceOver and modal
                    // overlays trap a11y focus. Compose needs explicit
                    // liveRegion + isTraversalGroup.
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = loadingText
                        isTraversalGroup = true
                    },
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
        // SPEC-070-A final audit pass C F1 — ensure socket + input stream are
        // released on every exit path. Without this, retry_count up to 3 ×
        // every step → keep-alive sockets accumulate until GC. Mirrors the
        // sibling pattern in ChatStepComposable.fireWebhook.
        try {
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
                val responseBody = conn.inputStream.use { it.bufferedReader().readText() }
                return@withContext parseWebhookResponse(responseBody, hookConfig)
            }

            throw java.io.IOException("HTTP $responseCode")
        } finally {
            runCatching { conn.disconnect() }
        }

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

        // SPEC-401-A R34 P1 — deep-convert nested JSONObject/JSONArray to
        // native Map/List using the same helper R33 added for process-death
        // restore. Was returning raw JSONObject/JSONArray for nested values
        // → host code reading `responses[stepId]["profile"]` via the
        // delegate broke `as? Map` casts; iOS Codable round-trips into
        // native [String: Any] so works.
        @Suppress("UNCHECKED_CAST")
        val responseData: Map<String, Any>? = if (json.has("data") && !json.isNull("data")) {
            fromJsonAny(json.get("data")) as? Map<String, Any>
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

/**
 * SPEC-401-A R33 P1 — recursively convert JSONObject/JSONArray trees into
 * native Map/List so downstream code (NextStepRuleEvaluator,
 * AppDNAOnboardingDelegate, host code reading `responses[stepId]`) sees
 * the same shape iOS Codable round-trips into Foundation types. Without
 * this, restored responses for form/multi-select/chat steps surface as
 * raw JSONObject/JSONArray and fail every `as? Map`/`as? List` cast.
 */
private fun fromJsonAny(value: Any?): Any? = when (value) {
    null, org.json.JSONObject.NULL -> null
    is org.json.JSONObject -> {
        val map = mutableMapOf<String, Any?>()
        value.keys().forEach { k -> map[k] = fromJsonAny(value.opt(k)) }
        map
    }
    is org.json.JSONArray -> {
        (0 until value.length()).map { i -> fromJsonAny(value.opt(i)) }
    }
    else -> value
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
    /**
     * SPEC-070-A finalization B4 P1 — previously-entered responses for
     * THIS step, threaded down so back-nav restores text/selection/toggle
     * state. Mirrors iOS OnboardingStepRouter.savedResponses
     * (OnboardingRenderer.swift:1316,1448).
     */
    savedResponses: Map<String, Any>? = null,
    // SPEC-401-A R11 — accumulated `responses` (across all prior steps)
    // and per-step `hookData` propagated down to the visibility-condition
    // evaluator. iOS plumbs through OnboardingStepView → ThreeZoneStepLayout.
    accumulatedResponses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
) {
    // SPEC-070-A finalization B4 P1 — pre-populate from savedResponses on
    // first composition. `step.id` keying the remember ensures a fresh
    // clear when the user navigates forward to a different step.
    val toggleValues = remember(step.id) {
        mutableMapOf<String, Boolean>().apply {
            savedResponses?.forEach { (k, v) ->
                if (k.startsWith("toggle_") && v is Boolean) put(k.removePrefix("toggle_"), v)
            }
        }
    }
    val inputValues = remember(step.id) {
        mutableMapOf<String, Any>().apply {
            savedResponses?.forEach { (k, v) ->
                if (!k.startsWith("toggle_") && k != "action" && k != "selected" && k != "selection_mode") {
                    put(k, v)
                }
            }
        }
    }

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
            // SPEC-401-A R11 — flow visibility-eval data through.
            responses = accumulatedResponses,
            hookData = hookData,
            // SPEC-401-A R11 — identity for auth-action analytics emit.
            flowId = flowId,
            stepId = step.id,
        )
    } else {
        // Legacy rendering
        // SPEC-401-A R7 P0 — chat step contains a LazyColumn that
        // crashes inside a verticalScroll-wrapping parent
        // ("Vertically scrollable component was measured with an
        // infinity maximum height constraints"). iOS uses a plain
        // VStack at OnboardingRenderer.swift:1466-1490 with no
        // ScrollView, so ChatStepView's own ScrollView lays out
        // fine. On Android, branch the wrapper: chat gets a
        // non-scrolling Column with fillMaxSize so its internal
        // LazyColumn measures correctly. Other step types keep the
        // verticalScroll fallback for long-content overflow.
        if (step.type == OnboardingStep.StepType.INTERACTIVE_CHAT) {
            Column(
                modifier = modifier
                    .entryAnimation(effectiveConfig.animation?.entry_animation, effectiveConfig.animation?.entry_duration_ms)
                    .fillMaxSize(),
            ) {
                ChatStepComposable(
                    step = step,
                    flowId = flowId,
                    onNext = { data -> onNext(data) },
                    onSkip = { onSkip() },
                    // SPEC-401-A — pass prior step transcript so back-nav
                    // restores chat bubbles + completion state. iOS does
                    // the same via `savedTranscript: savedResponses` at
                    // OnboardingRenderer.swift:1480.
                    savedTranscript = savedResponses,
                )
                // Skip button
                if (step.config.skip_enabled == true) {
                    TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Skip", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            }
            return
        }
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
                // SPEC-401-A — `info` + `permission` mirror iOS
                // OnboardingRenderer.swift:1497 routing through
                // CustomStepView. Authored content-blocks render via the
                // block-based path; this handles the no-blocks fallback so
                // configured title/subtitle/cta still appear.
                OnboardingStep.StepType.CUSTOM,
                OnboardingStep.StepType.INFO,
                OnboardingStep.StepType.PERMISSION -> CustomStep(effectiveConfig, onNext)
                OnboardingStep.StepType.FORM -> FormStep(effectiveConfig, onNext, savedResponses)
                // INTERACTIVE_CHAT handled via the early return above.
                OnboardingStep.StepType.INTERACTIVE_CHAT -> Unit
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
// SPEC-070-A finalization OB-4 — actions iOS' AuthActionPolicy says
// REQUIRE a host-registered onboarding delegate (`onBeforeStepAdvance`)
// before they can advance. iOS source-of-truth:
// `OnboardingRenderer.swift:1605-1618 AuthActionPolicy.delegateRequiredActions`.
// Without a delegate, sensitive credentials (email/password/OTP) would
// silently flow through `onNext` -> `responses` payload while no host
// authentication side-effect actually runs. iOS logs a warning and
// stays on the step so the user notices auth didn't happen.
private val AUTH_ACTIONS_REQUIRING_DELEGATE = setOf(
    "login",
    "register",
    "reset_password",
    "magic_link",
    "verify_email",
    "resend_verification",
    "enable_biometric",
    "email_login",
    "request_otp",
    "verify_otp",
    "logout",
    "change_password",
    "set_new_password",
    "delete_account",
    "update_profile",
)

/**
 * SPEC-070-A finalization B4#P0 — pure resolver for the OTP delivery
 * channel used by `request_otp` / `verify_otp` buttons. Mirrors iOS
 * `OtpChannelResolver` at OnboardingRenderer.swift:1625-1674.
 *
 * Resolution order:
 *   1) Explicit `actionValue` from button config — "sms"|"email"|"whatsapp"|"voice"
 *   2) Auto-detect: exactly one phone-typed block → "sms";
 *      exactly one email-typed block → "email"
 *   3) (null, null) — ambiguous. Host must fail explicitly rather than guess.
 *
 * Recipient is taken from `inputValues[block.field_id ?: block.id]` for the
 * matching block.
 */
internal object OtpChannelResolver {
    private val SUPPORTED = setOf("sms", "email", "whatsapp", "voice")

    fun resolve(
        actionValue: String?,
        blocks: List<ContentBlock>,
        inputValues: Map<String, Any>,
    ): Pair<String?, String?> {
        val phoneBlocks = blocks.filter { it.type == "input_phone" }
        val emailBlocks = blocks.filter { it.type == "input_email" }

        val raw = actionValue?.lowercase()
        if (raw != null && raw in SUPPORTED) {
            val recipient = when (raw) {
                "sms", "whatsapp", "voice" ->
                    phoneBlocks.firstOrNull()?.let { inputValues[it.field_id ?: it.id] as? String }
                "email" ->
                    emailBlocks.firstOrNull()?.let { inputValues[it.field_id ?: it.id] as? String }
                else -> null
            }
            return raw to recipient
        }

        if (phoneBlocks.size == 1 && emailBlocks.isEmpty()) {
            val b = phoneBlocks[0]
            return "sms" to (inputValues[b.field_id ?: b.id] as? String)
        }
        if (emailBlocks.size == 1 && phoneBlocks.isEmpty()) {
            val b = emailBlocks[0]
            return "email" to (inputValues[b.field_id ?: b.id] as? String)
        }
        return null to null
    }
}

private fun emitAuthAction(
    action: String,
    actionValue: String?,
    toggleValues: Map<String, Boolean>,
    inputValues: Map<String, Any>,
    onNext: (Map<String, Any>?) -> Unit,
    blocks: List<ContentBlock> = emptyList(),
    // SPEC-401-A R11 — context for the analytics fire on the no-delegate
    // gate. iOS handleStepCompleted (OnboardingRenderer.swift:444-446)
    // fires `onboarding_step_completed` BEFORE the requiresDelegate gate;
    // Android previously returned early without emitting, so warehouse
    // queries on auth attempts saw the event on iOS but not Android.
    flowId: String? = null,
    stepId: String? = null,
    stepIndex: Int? = null,
) {
    // SPEC-070-A finalization OB-4 — auth-action delegate gate. If the
    // host has not registered an AppDNAOnboardingDelegate (which is the
    // contract surface for `onBeforeStepAdvance`), refuse to advance
    // for actions in AUTH_ACTIONS_REQUIRING_DELEGATE. Mirrors iOS
    // OnboardingRenderer.swift:1465-1474 (warn + return early). Logging
    // via SDK Log so production builds (BuildConfig.DEBUG=false) don't
    // suppress the message — auth flow misconfiguration is a developer
    // contract issue worth surfacing in release builds too.
    if (action in AUTH_ACTIONS_REQUIRING_DELEGATE) {
        val hasDelegate = ai.appdna.sdk.AppDNA.onboarding.listener != null
        if (!hasDelegate) {
            // SPEC-401-A R11 — fire `onboarding_step_completed` analytics
            // BEFORE the early return, mirroring iOS handleStepCompleted
            // pre-gate emit. Hosts looking at warehouse data for "user
            // attempted login but flow misconfigured" now see the same
            // event on both platforms.
            if (flowId != null && stepId != null && stepIndex != null) {
                val data = mutableMapOf<String, Any>().also { d ->
                    d.putAll(inputValues)
                    for ((k, v) in toggleValues) d["toggle_$k"] = v
                    d["action"] = action
                    if (actionValue != null) d["action_value"] = actionValue
                }
                ai.appdna.sdk.AppDNA.track("onboarding_step_completed", mapOf(
                    "flow_id" to flowId,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "selection_data" to data,
                    "blocked_reason" to "no_delegate",
                ))
            }
            ai.appdna.sdk.Log.warning(
                "Auth action `$action` was triggered but no AppDNAOnboardingDelegate " +
                "is registered. Register via AppDNA.onboarding.setDelegate(...) and " +
                "implement onBeforeStepAdvance to handle authentication. Step will NOT " +
                "advance to avoid silently leaking credentials into the responses payload."
            )
            return
        }
    }

    val data = mutableMapOf<String, Any>()
    // input values first so SDK-controlled keys overwrite collisions
    data.putAll(inputValues)
    for ((k, v) in toggleValues) {
        data["toggle_$k"] = v
    }
    data["action"] = action
    when (action) {
        "request_otp", "verify_otp" -> {
            // SPEC-070-A finalization B4#P0 — emit BOTH `channel` (sms|email|whatsapp|voice)
            // AND `recipient` (resolved from inputValues via OtpChannelResolver),
            // matching iOS OnboardingRenderer.swift:1559-1594. Previously Android
            // only emitted `recipient = actionValue` (which is actually the channel
            // string, not a phone/email) and never emitted `channel` at all,
            // breaking every host that branches on the OTP delivery method.
            val (channel, recipient) = OtpChannelResolver.resolve(actionValue, blocks, inputValues)
            if (channel != null) data["channel"] = channel
            if (recipient != null) data["recipient"] = recipient
        }
        else -> {
            if (actionValue != null) data["action_value"] = actionValue
        }
    }
    onNext(data)
}

// SPEC-070-A finalization OB-8 — three-zone block layout helper.
// SPEC-401-A revised — full parity with iOS `ThreeZoneStepLayout.swift`:
//   - Top + center are scrollable together (iOS line 40: `ScrollView`)
//   - Bottom is sticky / keyboard-aware (iOS line 80: `.safeAreaInset(edge: .bottom)`
//     + `.ignoresSafeArea(.keyboard, edges: .bottom)`)
//   - Default unzoned blocks go to TOP, not center (iOS line 118: `default: top.append`)
//   - Tap on empty area dismisses keyboard (iOS line 53: `resignFirstResponder`)
//   - Responsive horizontal padding `max(24dp, screenWidth * 8%)` (iOS line 106)
//   - Zone top paddings: 16dp top + 20dp center (iOS lines 44, 48)
//   - "only-center" path centers a lone center block vertically (iOS lines 30-37)
//
// Without these, the CTA authored at the bottom of a step + footer-pinned
// legal text scrolled inline with the question content, making every step's
// vertical rhythm wrong vs iOS. The keyboard would also push the CTA off
// the screen on Android, while iOS pinned it via safeAreaInset.
@androidx.compose.runtime.Composable
private fun ThreeZoneBlockLayout(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
    currentStepIndex: Int,
    totalSteps: Int,
    // SPEC-401-A R11 — `responses` + `hookData` flow into the visibility
    // filter. iOS threads these through ThreeZoneStepLayout
    // (ThreeZoneStepLayout.swift:13-14, 23-26); without them every
    // `visibility_condition` operator (when_equals / when_not_empty /
    // when_gt / when_lt) silently evaluates against an empty map,
    // breaking conditional content rendering on Android.
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    // SPEC-401-A R11 — apply `visibility_condition` BEFORE partitioning,
    // mirroring iOS at ThreeZoneStepLayout.swift:23-26. Previously Android
    // partitioned ALL blocks first (visible + hidden) and the inner
    // ContentBlockRendererView filtered per-zone, so the
    // `onlyCenterContent` decision used the unfiltered top count and
    // took the wrong layout branch when a top block was hidden.
    val visibleBlocks = blocks.filter { block ->
        evaluateVisibilityCondition(block.visibility_condition, responses = responses, hookData = hookData)
    }
    val anyZoned = visibleBlocks.any { !it.zone.isNullOrBlank() }
    if (!anyZoned) {
        ContentBlockRendererView(
            blocks = visibleBlocks,
            onAction = onAction,
            toggleValues = toggleValues,
            inputValues = inputValues,
            loc = loc,
            responses = responses,
            hookData = hookData,
            currentStepIndex = currentStepIndex,
            totalSteps = totalSteps,
        )
        return
    }
    // SPEC-401-A — iOS partition (`ThreeZoneStepLayout.swift:113-119`)
    // routes UNKNOWN/empty zones to TOP via `default: top.append(block)`,
    // not center. Earlier Android comment was factually wrong about iOS.
    // Fixing makes the same flow render with the same vertical rhythm
    // on both platforms (was P0: a block authored without an explicit
    // zone landed in different parts of the screen on each native).
    val top = mutableListOf<ContentBlock>()
    val center = mutableListOf<ContentBlock>()
    val bottom = mutableListOf<ContentBlock>()
    for (block in visibleBlocks) {
        val effectiveZone = (block.zone ?: block.vertical_align ?: "top").lowercase()
        when (effectiveZone) {
            "center" -> center.add(block)
            "bottom" -> bottom.add(block)
            else -> top.add(block)
        }
    }
    val onlyCenterContent = top.isEmpty() && center.isNotEmpty()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // SPEC-401-A — iOS uses `max(24, screenWidth * 0.08)` for responsive
    // horizontal margins (`ThreeZoneStepLayout.swift:106`). Mirror the
    // formula in dp so wider screens (foldables, tablets) get proportional
    // breathing room and small phones stay at the 24dp floor.
    val horizontalPadding = maxOf(24, (configuration.screenWidthDp * 0.08).toInt()).dp

    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            // SPEC-401-A R36 (Lens C #1) — root-level keyboard dismiss to
            // match iOS OnboardingRenderer.swift:123-129. Was scoped to the
            // inner scroll Column only, so taps in the bottom-zone gutter,
            // nav-bar gutter, or progress-bar gutter left the keyboard up
            // and the user got stuck.
            .pointerInput(Unit) {
                detectTapGestures {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            },
    ) {
        // ── TOP + CENTER (scrollable, keyboard-aware) ─────────────────────
        if (onlyCenterContent) {
            // Only center content (e.g. loading spinner) — vertically center it.
            // Mirrors iOS `ThreeZoneStepLayout.swift:30-37`.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = if (bottom.isNotEmpty()) 80.dp else 0.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                ContentBlockRendererView(
                    blocks = center,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    responses = responses,
                    hookData = hookData,
                    currentStepIndex = currentStepIndex,
                    totalSteps = totalSteps,
                )
            }
        } else {
            // Normal: top scrolls with center; tap empty area dismisses
            // keyboard; bottom zone leaves room via padding(bottom = 80dp)
            // (iOS uses safeAreaInset with intrinsic height — 80dp covers
            // the typical CTA + small footer).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (bottom.isNotEmpty()) 80.dp else 0.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
            ) {
                if (top.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    ContentBlockRendererView(
                        blocks = top,
                        onAction = onAction,
                        toggleValues = toggleValues,
                        inputValues = inputValues,
                        loc = loc,
                        responses = responses,
                        hookData = hookData,
                        currentStepIndex = currentStepIndex,
                        totalSteps = totalSteps,
                    )
                }
                if (center.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    ContentBlockRendererView(
                        blocks = center,
                        onAction = onAction,
                        toggleValues = toggleValues,
                        inputValues = inputValues,
                        loc = loc,
                        responses = responses,
                        hookData = hookData,
                        currentStepIndex = currentStepIndex,
                        totalSteps = totalSteps,
                    )
                }
            }
        }

        // ── BOTTOM (sticky, keyboard-aware) ──────────────────────────────
        // SPEC-401-A — iOS pins the CTA via `safeAreaInset(edge: .bottom)`
        // and explicitly calls `ignoresSafeArea(.keyboard, edges: .bottom)`
        // so the IME doesn't push it. Compose's `Modifier.imePadding()`
        // is the standard Android idiom for the same behavior.
        if (bottom.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = 8.dp)
                    .imePadding(),
            ) {
                ContentBlockRendererView(
                    blocks = bottom,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    responses = responses,
                    hookData = hookData,
                    currentStepIndex = currentStepIndex,
                    totalSteps = totalSteps,
                )
            }
        }
    }
}

// SPEC-070-A finalization OB-7 — collect every image URL referenced by a
// flow so [ImagePreloader.prefetch] can warm Coil's cache before the first
// paint. Mirrors iOS `OnboardingRenderer.swift:866-916` `collectImageURLs`
// recursive walk over content_blocks, step.config fields, and per-block
// option lists. Returns trimmed, non-empty URLs.
private fun collectFlowImageURLs(flow: OnboardingFlowConfig): List<String> {
    val urls = mutableListOf<String>()
    fun pushIfPresent(s: String?) {
        val t = s?.trim()
        if (!t.isNullOrEmpty()) urls += t
    }
    fun walkBlocks(blocks: List<ContentBlock>?) {
        if (blocks.isNullOrEmpty()) return
        for (b in blocks) {
            pushIfPresent(b.image_url)
            // SPEC-070-A finalization B4 P1 — also preload `placeholder_image_url`.
            // iOS collectImageURLs walks every block.placeholder_image_url so the
            // first-frame poster paints immediately while Lottie/video stream.
            // Android previously skipped it even though the field IS in the DTO,
            // making lottie blocks flash empty until the network response landed.
            pushIfPresent(b.placeholder_image_url)
            pushIfPresent(b.video_thumbnail_url)
            pushIfPresent(b.lottie_url) // pre-warm Lottie too (Coil renders the JSON URL as a network resource)
            pushIfPresent(b.rive_url)
            // Nested blocks: card.children, row.children, stack.children.
            walkBlocks(b.children)
            // InputOption-shaped images for select/chips/segmented blocks.
            b.field_options?.forEach { opt ->
                pushIfPresent(opt.image_url)
            }
        }
    }
    for (step in flow.steps) {
        pushIfPresent(step.config.image_url)
        pushIfPresent(step.config.background?.image_url)
        walkBlocks(step.config.content_blocks)
    }
    return urls.distinct()
}

// SPEC-070-A finalization OB-1 — auth-class actions that ALSO require
// required-field validation (you can't login without an email, etc.).
// Mirrors the validation policy iOS' OnboardingRenderer applies inside
// `emitAuthAction` (`OnboardingRenderer.swift:1561-1567`): canAdvance
// runs BEFORE invoking the auth handler.
private val AUTH_ACTIONS_REQUIRING_VALIDATION = setOf(
    "login",
    "register",
    "reset_password",
    "magic_link",
    "verify_email",
    "email_login",
    "request_otp",
    "verify_otp",
    "change_password",
    "set_new_password",
    "update_profile",
    "social_login", // provider already chosen; submitted profile fields still validated
)

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
    // SPEC-401-A R11 — flow `responses` + `hookData` through to the
    // ThreeZoneBlockLayout / ContentBlockRendererView so authored
    // `visibility_condition` operators evaluate against real responses
    // instead of an empty map. Mirrors iOS BlockBasedStepView path
    // (OnboardingRenderer.swift:1452-1460).
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    // SPEC-401-A R11 — flowId/stepId so the auth-action no-delegate
    // path can fire `onboarding_step_completed` analytics with the
    // correct identity (matches iOS handleStepCompleted pre-gate emit).
    flowId: String = "",
    stepId: String = "",
) {
    val variant = effectiveConfig.layout_variant ?: "no_image"

    // SPEC-401-A R35 — in-app validation pill (Lens C #4). iOS shows a
    // bottom-aligned styled pill `OnboardingRenderer.swift:1383-1402`
    // instead of system Toast. android.widget.Toast inherits OEM theming
    // and looks foreign vs. the rest of the SDK UI.
    var validationMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(validationMessage) {
        if (validationMessage != null) {
            kotlinx.coroutines.delay(2500)
            validationMessage = null
        }
    }

    // SPEC-084: Localization helper for step text
    // SPEC-087: Also interpolates {{variables}} after localization
    fun loc(key: String, fallback: String): String {
        val localized = LocalizationEngine.resolve(key, effectiveConfig.localizations, effectiveConfig.default_locale, fallback)
        return localized.interpolated()
    }

    // SPEC-070-A finalization OB-1 — required-field validation gate.
    // Mirrors iOS `OnboardingRenderer.swift:1465-1487` `canAdvance` walk:
    // enumerate every block where `field_required == true`, check whether
    // `inputValues[field_id ?: id]` is non-empty, return false (with the
    // first failing block's id for highlighting / error copy) if any are
    // empty. iOS shows `showValidationToast`; Android Toast is the
    // equivalent surface. Without this gate, the BlockBasedStepView's
    // "next" branch advanced unconditionally — required fields had a red
    // asterisk in the label but no actual gating.
    fun canAdvance(): Pair<Boolean, String?> {
        for (block in blocks) {
            if (block.field_required != true) continue
            val fieldId = block.field_id ?: block.id
            val v = inputValues[fieldId]
            val empty = when (v) {
                null -> true
                is String -> v.isBlank()
                is Collection<*> -> v.isEmpty()
                is Map<*, *> -> v.isEmpty()
                else -> false
            }
            if (empty) {
                return false to (block.field_label ?: block.label ?: fieldId)
            }
        }
        return true to null
    }

    val activityCtx = androidx.compose.ui.platform.LocalContext.current

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

        // OB-1 — actions that finalize/submit the step must pass validation.
        // Auth-class actions (login, register, request_otp, email_login, ...)
        // also gate on required fields because the host can't authenticate
        // with empty credentials.
        val requiresValidation = rawAction == "next" || rawAction in AUTH_ACTIONS_REQUIRING_VALIDATION
        if (requiresValidation) {
            val (ok, fieldLabel) = canAdvance()
            if (!ok) {
                val msg = if (fieldLabel != null) {
                    "$fieldLabel is required"
                } else {
                    "Please complete required fields"
                }
                // SPEC-401-A R35 — surface as in-app pill instead of system
                // Toast (iOS OnboardingRenderer.swift:1383-1402).
                validationMessage = msg
                return
            }
        }

        when (rawAction) {
            "next" -> {
                // SPEC-401-A R15 — match iOS OnboardingRenderer.swift:1518-1527.
                // iOS prefixes every toggle key with `toggle_` so the namespaces
                // never collide with form input keys; Android previously merged
                // raw toggle keys, breaking cross-platform analytics aggregation
                // and any NextStepRule JSON authored against `toggle_<id>`.
                // Order: inputs first, then toggle_<key> entries — matches iOS
                // line ordering at 1519-1526.
                val merged = mutableMapOf<String, Any>()
                merged.putAll(inputValues)
                for ((key, value) in toggleValues) {
                    merged["toggle_$key"] = value
                }
                onNext(if (merged.isEmpty()) null else merged)
            }
            "skip" -> onSkip?.invoke()
            // SPEC-070-A C.1 — social_login retains its existing data shape
            // (`{provider, action: "social_login"}`) for backwards compatibility
            // with hosts that switch on `action == "social_login"`. iOS
            // `OnboardingRenderer.swift:1507-1524`.
            "social_login" -> {
                // SPEC-070-A finalization B4 P1 — delegate gate. Without an
                // AppDNAOnboardingDelegate to handle social-login, advancing
                // would silently leak provider info into the responses
                // payload with no auth performed. Match the gate iOS uses
                // for `request_otp`/`login`/`register` etc.
                val hasDelegate = ai.appdna.sdk.AppDNA.onboarding.listener != null
                if (!hasDelegate) {
                    // SPEC-401-A R13 — fire onboarding_step_completed BEFORE
                    // early return, mirroring the emitAuthAction pre-gate
                    // pattern + iOS handleStepCompleted (OnboardingRenderer
                    // .swift:440-468). Without this hosts running BigQuery
                    // queries on misconfigured social-login attempts see
                    // hits on iOS but blanks on Android.
                    val data = mutableMapOf<String, Any>(
                        "provider" to (actionValue ?: "unknown"),
                        "action" to "social_login",
                    )
                    data.putAll(inputValues)
                    ai.appdna.sdk.AppDNA.track("onboarding_step_completed", mapOf(
                        "flow_id" to flowId,
                        "step_id" to stepId,
                        "step_index" to currentStepIndex,
                        "selection_data" to data,
                        "blocked_reason" to "no_delegate",
                    ))
                    ai.appdna.sdk.Log.warning(
                        "social_login button tapped but no AppDNAOnboardingDelegate is " +
                        "registered. Register via AppDNA.onboarding.setDelegate(...) and " +
                        "implement onBeforeStepAdvance to handle the OAuth flow. Step will " +
                        "NOT advance until a delegate is provided."
                    )
                    return
                }
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
            // OTP actions — channel/recipient resolved via OtpChannelResolver
            // (SPEC-070-A finalization B4#P0 parity with iOS):
            "request_otp", "verify_otp",
            // Account lifecycle:
            "logout", "change_password", "set_new_password",
            "delete_account", "update_profile" -> {
                emitAuthAction(
                    rawAction, actionValue, toggleValues, inputValues, onNext, blocks,
                    flowId = flowId, stepId = stepId, stepIndex = currentStepIndex,
                )
            }
            // SPEC-070-A C.1 — `permission` is a SPEC-086 hook site. For now
            // advance as safe fallback so existing hosts don't get stuck on
            // a permission-tagged button without a runtime permission infra
            // wired up. Mirrors iOS `OnboardingRenderer.swift:1525-1529`.
            // SPEC-401-A R13 — pass `null` to match iOS exactly. iOS emits
            // `onNext(nil)` so `responses[step.id]` is never written and
            // hosts switching on `data["action"]` don't see a populated
            // map only on Android. Defer the typed payload (action/
            // permission_type) to SPEC-086 when runtime permission infra
            // ships and gets explicit spec approval.
            "permission" -> onNext(null)
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
                        // SPEC-401-A R15 — read `image_fit` from DTO instead
                        // of hardcoding Crop. Matches iOS imageFit() helper at
                        // StyleEngine.swift:337 — "fit" → letterbox, "fill"/
                        // "cover"/null → fill.
                        val imageScale = when (bg.image_fit?.lowercase()) {
                            "fit", "contain" -> androidx.compose.ui.layout.ContentScale.Fit
                            else -> androidx.compose.ui.layout.ContentScale.Crop
                        }
                        ai.appdna.sdk.core.NetworkImage(
                            url = bg.image_url,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = imageScale,
                        )
                        // SPEC-401-A R15 — overlay opacity per iOS
                        // StyleEngine.swift:343-358. Pure black/white default
                        // hexes (editor default) get 0.4 opacity so the image
                        // remains visible; explicit overlay_opacity wins.
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
                        }
                    }
                }
                "lottie" -> {
                    // SPEC-401-A R15 — full-screen Lottie background
                    // (iOS StyleEngine.swift:361-392). Without this branch
                    // a console-authored Lottie background rendered as a
                    // transparent / theme-default screen on Android.
                    Box(Modifier.fillMaxSize()) {
                        bg.lottie_url?.let { url ->
                            // LottieBlockView constrains height; for a
                            // full-screen background pass screenHeightDp.
                            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
                            ai.appdna.sdk.core.LottieBlockView(
                                block = ai.appdna.sdk.core.LottieBlock(
                                    lottie_url = url,
                                    lottie_json = null,
                                    autoplay = true,
                                    loop = bg.animation_loop ?: true,
                                    speed = 1.0f,
                                    width = null,
                                    height = screenHeight,
                                    alignment = "center",
                                    play_on_scroll = null,
                                    play_on_tap = null,
                                    color_overrides = null,
                                ),
                            )
                        }
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
                        }
                    }
                }
                "rive" -> {
                    // SPEC-401-A R35 — full-screen Rive background (iOS
                    // StyleEngine.swift:393-425). Mirrors the "lottie" branch.
                    Box(Modifier.fillMaxSize()) {
                        bg.rive_url?.let { url ->
                            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
                            ai.appdna.sdk.core.RiveBlockView(
                                block = ai.appdna.sdk.core.RiveBlock(
                                    rive_url = url,
                                    autoplay = true,
                                    height = screenHeight,
                                    alignment = "center",
                                ),
                            )
                        }
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
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
                        ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
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
                        ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
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
                    ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
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
                    ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                }
            }
            else -> { // no_image
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
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

        // SPEC-401-A R35 — in-app validation pill (Lens C #4). Mirrors iOS
        // bottom-pill at OnboardingRenderer.swift:1383-1402 — `.background(
        // Color.black.opacity(0.8))`, `.cornerRadius(12)`, slide+fade
        // transition. Replaces system Toast which inherited OEM theming.
        AnimatedVisibility(
            visible = validationMessage != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = validationMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    // SPEC-401-A R37 (Lens C #4) — assertive announce so
                    // screen-reader users know why Continue didn't advance.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().fillMaxHeight()
    ) {
        // SPEC-401-A — flexible top spacer instead of fixed 48dp.
        // iOS uses Spacer() top + Spacer() between image-block and
        // CTA so the content vertically centers on tall screens.
        // Fixed 48dp top-aligned the content; on tablets the layout
        // looked top-heavy compared to iOS.
        Spacer(Modifier.weight(1f))
        // SPEC-401-A — legacy step image_url. Mirrors iOS WelcomeStepView
        // .swift:13-21 — 280×280dp ContentScale.Fit (matches scaledToFit)
        // with rounded 16dp corners.
        config.image_url?.takeIf { it.isNotBlank() }?.let { url ->
            // SPEC-401-A R48 (Lens A #3) — match iOS WelcomeStepView.swift:20
            // `.frame(maxWidth: 280, maxHeight: 280)`. Was fixed 280dp square
            // → non-square images letterboxed.
            ai.appdna.sdk.core.NetworkImage(
                url = url,
                contentDescription = null,
                modifier = androidx.compose.ui.Modifier
                    .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Spacer(Modifier.height(24.dp))
        }
        config.title?.let {
            // SPEC-401-A R48 (Lens A #2) — iOS .largeTitle ≈ 34pt
            // (was 28sp). SPEC-070-A J.11 — step title is the screen heading
            // for a11y. Lens A #4 — horizontal padding 32dp matching iOS
            // .padding(.horizontal, 32) (WelcomeStepView.swift:45).
            Text(
                text = it.interpolated(),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .semantics { heading() },
            )
        }
        config.subtitle?.let {
            Spacer(Modifier.height(12.dp))
            // SPEC-401-A R48 (Lens A #4) — horizontal padding 32dp.
            Text(
                text = it.interpolated(),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        // SPEC-401-A R9 — match iOS WelcomeStepView.swift:10,47 vertical
        // distribution: `Spacer()` top + `Spacer()` bottom so content
        // vertically centers on tall screens. Previous Android layout
        // used a single top weight + fixed gaps; on tablets the title
        // group sat top-heavy with a wide gap between CTA and bottom edge.
        Spacer(Modifier.weight(1f))
        // SPEC-401-A R48 (Lens A #1+#5) — CTA height 54dp matching iOS
        // .frame(height: 54); horizontal/bottom padding matching iOS
        // .padding(.horizontal, 24).padding(.bottom, 32).
        Button(
            onClick = { onNext(null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R10 — match iOS WelcomeStepView.swift:55 fixed
            // indigo `#6366F1` so the same flow renders the same CTA
            // color on both natives, regardless of host's Material theme
            // primary override.
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
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
            // SPEC-070-A J.11 — question step title is the screen heading.
            // SPEC-401-A R49 (Lens A #7) — title 24→22sp matching iOS
            // QuestionStepView.swift:57 `.font(.title2.bold())` (~22pt).
            Text(
                text = it.interpolated(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        config.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it.interpolated(), fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(24.dp))

        // SPEC-401-A R49 (Lens A #6) — 2-column grid mirrors iOS
        // QuestionStepView.swift:63-78 manual grid (`rowCount =
        // (count + 1) / 2`, 12pt spacing). Was a single-column vertical
        // list — same JSON rendered visually different layouts on
        // each platform. When a row has a lone option, the second slot
        // is filled with a transparent weight Spacer so the populated
        // card keeps half-width.
        // SPEC-401-A R49 (Lens C #6, P1) — element_style passthrough.
        // iOS QuestionStepView.swift:147-154 derives accent + container
        // colors from element_style. Without this Android renders every
        // brand-overridden question step in default Material primary.
        val accentColor = config.element_style?.border?.color
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: config.element_style?.background?.color
                ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: Color(0xFF6366F1)
        val selectedBgColor = accentColor.copy(alpha = 0.15f)
        val optionBgColor = config.element_style?.background?.overlay
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: config.element_style?.background?.color
                ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it).copy(alpha = 0.1f) }
            ?: MaterialTheme.colorScheme.surface
        config.options?.chunked(2)?.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { option ->
                    val isSelected = selectedOptions.contains(option.id)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) accentColor else accentColor.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                if (isMulti) {
                                    if (isSelected) selectedOptions.remove(option.id) else selectedOptions.add(option.id)
                                } else {
                                    selectedOptions.clear(); selectedOptions.add(option.id)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) selectedBgColor else optionBgColor,
                        ),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            option.icon?.let { Text(text = it, fontSize = 20.sp); Spacer(Modifier.width(12.dp)) }
                            // SPEC-070-A finalization parity audit R2 — render
                            // option.subtitle below the label when non-empty.
                            // Mirrors iOS QuestionStepView.swift:136-142 caption
                            // text style (smaller font, muted color).
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = option.label.interpolated(), fontSize = 16.sp)
                                option.subtitle?.takeIf { it.isNotEmpty() }?.let { sub ->
                                    Text(
                                        text = sub.interpolated(),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                    )
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            // SPEC-070-A finalization B4 P2 — include `selection_mode` in the
            // CTA payload (iOS QuestionStepView.swift:86-90 emits
            // {"selected": [...], "selection_mode": "single"|"multi"}). Hosts
            // that branch on the mode (e.g. multi-select rendering vs radio)
            // were missing this field on Android.
            onClick = {
                onNext(
                    mapOf(
                        "selected" to selectedOptions.toList(),
                        "selection_mode" to if (isMulti) "multi" else "single",
                    )
                )
            },
            enabled = selectedOptions.isNotEmpty(),
            // SPEC-401-A R48 (Lens C #1) — CTA height 52→54dp matching iOS.
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R48 (Lens C #3) — lock CTA color to iOS-canonical
            // #6366F1 (was MaterialTheme primary — bled through host theme
            // overrides). iOS QuestionStepView.swift:97 uses fixed accentColor
            // chain falling back to #6366F1; disabled state uses Color.gray.
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6366F1),
                disabledContainerColor = Color.Gray,
            ),
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        // SPEC-401-A R49 (Lens A #8) — bottom padding 32dp matching iOS
        // QuestionStepView.swift:101-102 `.padding(.bottom, 32)`.
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ValuePropStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        config.title?.let {
            // SPEC-401-A R48 (Lens A #2 polish) — iOS .title2.bold() ≈ 22pt
            // (was 24sp). SPEC-070-A J.11 — heading semantics.
            Text(
                text = it.interpolated(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(24.dp))
        // SPEC-401-A R48 (Lens A #6) — wrap items in scrollable Column so
        // long bullet lists don't clip on small screens. iOS uses ScrollView
        // (ValuePropStepView.swift:19-41).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            config.items?.forEach { item ->
                // SPEC-401-A R48 (Lens A #9) — horizontal padding 24dp matching
                // iOS .padding(.horizontal, 24). Lens A #7 — icon 36sp inside
                // 48dp Box for stable column alignment regardless of emoji
                // intrinsic width.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = item.icon, fontSize = 36.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    // SPEC-401-A R48 (Lens A #8) — title↔subtitle 4dp gap
                    // matching iOS VStack(spacing: 4).
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = item.title.interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(text = item.subtitle.interpolated(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { onNext(null) },
            // SPEC-401-A R48 (Lens C #1) — CTA height 52→54dp matching iOS.
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R12 — match iOS ValuePropStepView.swift:51 fixed
            // indigo `#6366F1` so flows render the same CTA color on both
            // natives regardless of host's MaterialTheme primary override.
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun CustomStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        config.title?.let {
            // SPEC-070-A J.11 — custom step title is the screen heading.
            Text(
                text = it.interpolated(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        config.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it.interpolated(), fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        // SPEC-401-A — legacy step image_url. Mirrors iOS CustomStepView
        // .swift:29-41 — image renders AFTER title+subtitle, ContentScale
        // .Fit (matches scaledToFit so non-square assets are letterboxed
        // not cropped), and NO rounded corners (iOS uses no clip-shape).
        // Affects custom/info/permission step types.
        config.image_url?.takeIf { it.isNotBlank() }?.let { url ->
            Spacer(Modifier.height(24.dp))
            ai.appdna.sdk.core.NetworkImage(
                url = url,
                contentDescription = null,
                modifier = androidx.compose.ui.Modifier
                    .size(280.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { onNext(null) },
            // SPEC-401-A R48 (Lens C #1) — CTA height 52→54dp matching iOS.
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R12 — match iOS CustomStepView.swift:51 fixed
            // indigo `#6366F1`. Affects custom/info/permission steps.
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}
