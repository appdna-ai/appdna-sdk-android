package ai.appdna.sdk.paywalls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Divider
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import ai.appdna.sdk.core.entryAnimation
import ai.appdna.sdk.core.sectionStagger
import ai.appdna.sdk.core.ctaAnimation
import ai.appdna.sdk.core.planSelectionAnimation
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.StyleEngine.applyContainerStyle
import ai.appdna.sdk.core.LocalizationEngine
import ai.appdna.sdk.core.dismissAnimation
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.ConfettiOverlay
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.HapticType
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.IconReference
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
// SPEC-070-A J.11 — accessibility string resources (Close / Restore /
// Purchase). Hosts can override these via their own `strings.xml`.
import ai.appdna.sdk.R

/**
 * Activity to render paywall UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class PaywallActivity : ComponentActivity() {

    // SPEC-070-A finalization OB-5 audit-2 HIGH-3 — instance-level snapshot
    // of the dismiss callback so `onDestroy` can fire it as a backstop when
    // the OS / user kills the Activity outside the regular dismissal paths
    // (task swipe in recents, low-memory kill, finish() from elsewhere).
    // Without this, an onboarding-presented paywall that's force-killed
    // never routes its `on_dismiss_target`, leaving the host onboarding
    // flow stuck in mid-state on next resume.
    private var snapshotOnDismiss: ((DismissReason) -> Unit)? = null
    private var dispatchedDismiss: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-070-A I.16 — edge-to-edge: Compose owns insets via
        // `imePadding()`/`safeDrawingPadding()` modifiers in the renderer.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val paywallId = intent.getStringExtra(EXTRA_PAYWALL_ID) ?: run {
            finish()
            return
        }

        val config = pendingConfig ?: run {
            finish()
            return
        }

        val onAppear = pendingOnAppear
        val onDismiss = pendingOnDismiss
        val onPlanSelected = pendingOnPlanSelected
        val onPromoCodeSubmit = pendingOnPromoCodeSubmit
        // SPEC-070-A C.3 — restore lifecycle hook (delegate-fired by PaywallManager)
        val onRestoreCb = pendingOnRestore

        // SPEC-070-A finalization OB-5 audit-2 HIGH-3 — capture onDismiss
        // on the instance so onDestroy can fire it when the regular paths
        // (Compose onDismiss, onBackPressed) didn't run.
        snapshotOnDismiss = onDismiss

        // Notify appearance
        onAppear?.invoke()

        setContent {
            // SPEC-070-A D.5 — read system dark-mode pref so the renderer can
            // pick `dark` overrides from paywall content blocks. Mirrors the
            // SurveyActivity pattern landed by Phase D for surveys.
            val isDark = isSystemInDarkTheme()
            MaterialTheme {
                PaywallScreen(
                    config = config,
                    onPlanSelected = { plan, metadata ->
                        onPlanSelected?.invoke(plan, metadata)
                    },
                    onRestore = {
                        // SPEC-070-A C.3 — forward restore taps to the
                        // PaywallManager-supplied lifecycle callback.
                        onRestoreCb?.invoke()
                    },
                    onDismiss = { reason ->
                        dispatchedDismiss = true
                        onDismiss?.invoke(reason)
                        cleanup()
                    },
                    onPromoCodeSubmit = onPromoCodeSubmit,
                    isDark = isDark,
                )
            }
        }
    }

    private fun cleanup() {
        pendingConfig = null
        pendingOnAppear = null
        pendingOnDismiss = null
        pendingOnPlanSelected = null
        pendingOnPromoCodeSubmit = null
        pendingOnRestore = null
        finish()
    }

    override fun onBackPressed() {
        // SPEC-070-A I.4 — force-choice paywalls: when `dismiss.allowed == false`,
        // intercept the system back so the user can't dismiss without selecting
        // a plan or restoring. Mirrors iOS `Paywalls/PaywallRenderer.swift` which
        // wraps the paywall in `.interactiveDismissDisabled(!allowed)`.
        val allowed = pendingConfig?.dismiss?.allowed ?: true
        if (!allowed) {
            return
        }
        dispatchedDismiss = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
        pendingOnDismiss?.invoke(DismissReason.DISMISSED)
        cleanup()
    }

    override fun onDestroy() {
        // SPEC-070-A finalization OB-5 audit-2 HIGH-3 — backstop for
        // dismissal paths that don't go through `onBackPressed` or the
        // Compose `onDismiss` callback (OS kill, task-recents swipe,
        // external `finish()`). Without this an onboarding-presented
        // paywall force-killed mid-display never routes
        // `on_dismiss_target`, stranding the onboarding flow.
        if (isFinishing && !dispatchedDismiss) {
            val cb = snapshotOnDismiss
            snapshotOnDismiss = null
            cb?.invoke(DismissReason.DISMISSED)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PAYWALL_ID = "paywall_id"
        private var pendingConfig: PaywallConfig? = null
        private var pendingOnAppear: (() -> Unit)? = null
        private var pendingOnDismiss: ((DismissReason) -> Unit)? = null
        private var pendingOnPlanSelected: ((PaywallPlan, Map<String, Any>) -> Unit)? = null
        // AC-037: Promo code delegate callback
        private var pendingOnPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null
        // SPEC-070-A C.3 — restore lifecycle callback (PaywallManager fires
        // the AppDNAPaywallDelegate restore methods from this hook).
        private var pendingOnRestore: (() -> Unit)? = null

        fun launch(
            context: Context,
            paywallId: String,
            config: PaywallConfig,
            paywallContext: PaywallContext? = null,
            onAppear: (() -> Unit)? = null,
            onDismiss: ((DismissReason) -> Unit)? = null,
            onPlanSelected: ((PaywallPlan, Map<String, Any>) -> Unit)? = null,
            onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
            onRestore: (() -> Unit)? = null,
        ) {
            pendingConfig = config
            pendingOnAppear = onAppear
            pendingOnDismiss = onDismiss
            pendingOnPlanSelected = onPlanSelected
            pendingOnPromoCodeSubmit = onPromoCodeSubmit
            pendingOnRestore = onRestore
            val intent = Intent(context, PaywallActivity::class.java).apply {
                putExtra(EXTRA_PAYWALL_ID, paywallId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * SPEC-070-A C.5 — broadcast post-purchase success overlay (confetti +
         * lottie) into a live PaywallActivity composition. Mirrors iOS's
         * `NotificationCenter.default.post(.paywallPurchaseSuccess, ...)`.
         */
        @Volatile internal var postPurchaseOverlay: PostPurchaseOverlayState? = null
    }
}

/**
 * SPEC-070-A C.5 — payload for the post-purchase success overlay (confetti +
 * lottie animation rendered atop the paywall after a successful purchase).
 */
internal data class PostPurchaseOverlayState(
    val message: String? = null,
    val confetti: Boolean = false,
    val lottieUrl: String? = null,
)

@Composable
fun PaywallScreen(
    config: PaywallConfig,
    onPlanSelected: (PaywallPlan, Map<String, Any>) -> Unit,
    onRestore: () -> Unit,
    onDismiss: (DismissReason) -> Unit,
    // AC-037: Callback for promo code validation. Returns true if code is valid, false otherwise.
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // SPEC-070-A D.5 — system dark-mode pref. Renderers/blocks may apply
    // `dark` overrides from console-designed paywall content based on this.
    @Suppress("UNUSED_PARAMETER") isDark: Boolean = false,
) {
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var showDismiss by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showConfetti by remember { mutableStateOf(false) }
    // AC-038: Hoisted toggle states for inclusion in purchase metadata
    val toggleStates = remember { mutableStateMapOf<String, Boolean>() }
    // SPEC-070-A C.7 — promo state hoisted out of PaywallPromoInputSection
    // so CTA tap handlers can fold the validated code into the purchase
    // metadata. Mirrors iOS `PaywallRenderer.swift:2160-2167`.
    var promoCode by remember { mutableStateOf("") }
    var promoState by remember { mutableStateOf("idle") } // idle | loading | success | error
    // SPEC-070-A C.5 — post-purchase success overlay state. Drained from the
    // companion-object slot every recomposition so PaywallManager.handle
    // PostPurchaseSuccess can fire confetti + lottie atop the paywall while
    // the dismiss timer counts down.
    var postPurchaseOverlay by remember { mutableStateOf<PostPurchaseOverlayState?>(null) }
    LaunchedEffect(Unit) {
        // Poll the companion-object slot for new overlay payloads. Cheap
        // (one variable read per frame) — and we only enter this loop while
        // the paywall composition is alive.
        while (true) {
            val pending = PaywallActivity.postPurchaseOverlay
            if (pending != null && pending != postPurchaseOverlay) {
                postPurchaseOverlay = pending
                PaywallActivity.postPurchaseOverlay = null
            }
            kotlinx.coroutines.delay(100)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val currentView = LocalView.current

    fun triggerDismiss() {
        if (config.animation?.dismiss_animation != null) {
            isDismissing = true
            coroutineScope.launch {
                kotlinx.coroutines.delay(350)
                onDismiss(DismissReason.DISMISSED)
            }
        } else {
            onDismiss(DismissReason.DISMISSED)
        }
    }

    // Select default plan on appear + initialize toggle defaults
    LaunchedEffect(Unit) {
        // SPEC-070-A finalization PW-2 — read effective plans (sections OR
        // top-level config.plans fallback). Computed inline because
        // effectivePlans() lives in the @Composable body below; mirror its
        // logic here at the LaunchedEffect site.
        val sectionPlans = config.sections
            .firstOrNull { it.type == "plans" }
            ?.data?.plans
            ?: emptyList<PaywallPlan>()
        val plans = if (sectionPlans.isEmpty()) (config.plans ?: emptyList()) else sectionPlans
        selectedPlanId = plans.firstOrNull { it.is_default == true }?.id
            ?: plans.firstOrNull()?.id

        // AC-038: Initialize toggle defaults from config
        config.sections.filter { it.type == "toggle" }.forEach { section ->
            val key = section.data?.label ?: "toggle_${section.type}"
            if (section.data?.default_value != null) {
                toggleStates[key] = section.data.default_value
            }
        }

        // Handle dismiss delay
        val delay = config.dismiss?.delay_seconds ?: 0
        if (delay > 0) {
            kotlinx.coroutines.delay(delay * 1000L)
            showDismiss = true
        } else {
            showDismiss = true
        }
    }

    // Localization helper + SPEC-088: Template variable interpolation
    val templateContext = ai.appdna.sdk.core.TemplateEngine.buildContext()
    fun loc(key: String, fallback: String): String {
        val localized = LocalizationEngine.resolve(key, config.localizations, config.default_locale, fallback)
        return ai.appdna.sdk.core.TemplateEngine.interpolate(localized, templateContext)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dismissAnimation(config.animation?.dismiss_animation, isDismissing)
            .entryAnimation(config.animation?.entry_animation, config.animation?.entry_duration_ms)
            .then(
                if ((config.dismiss?.type ?: "x_button") == "swipe_down") {
                    Modifier.offset(y = dragOffset.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > 150) {
                                        triggerDismiss()
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragOffset + dragAmount > 0) {
                                        dragOffset += dragAmount
                                    }
                                }
                            )
                        }
                } else Modifier
            )
    ) {
        // Background
        PaywallBackground(config.background)

        // SPEC-089d: Extract sticky_footer section
        val stickyFooterSection = config.sections.firstOrNull { it.type == "sticky_footer" }

        // SPEC-070-A finalization PW-2 — `effectivePlans` helper. Mirrors iOS
        // `PaywallRenderer.swift:511-512` resolution:
        //   sectionPlans = config.sections.first(where: { type == "plans" })?.data?.plans
        //   effectivePlans = sectionPlans.isEmpty ? (config.plans ?? []) : sectionPlans
        // Without this, any paywall authored at the document root (no plans
        // section, just top-level `plans: []`) renders with no plans on
        // Android — even though parser landed top-level `config.plans`
        // support already. Also resolves selection by id.
        fun effectivePlans(): List<PaywallPlan> {
            val sectionPlans = config.sections
                .firstOrNull { it.type == "plans" }
                ?.data?.plans
                ?: emptyList<PaywallPlan>()
            return if (sectionPlans.isEmpty()) (config.plans ?: emptyList()) else sectionPlans
        }
        // SPEC-070-A finalization PW-3 — CTA text resolution chain is now
        // inlined at the CTA section render site (PaywallActivity.kt:~1497)
        // because that site lives inside PaywallSectionView, a separate
        // @Composable scope from PaywallScreen. The helper that previously
        // lived here was dead code — audit round 2 D1 cleanup.

        // Content in a Column with scrollable area + sticky footer
        Column(modifier = Modifier.fillMaxSize()) {
            // SPEC-070-A J.20 — convert eager `Column.verticalScroll` to
            // `LazyColumn` with stable keys so the recompose-window only
            // touches sections that scrolled into view + plan/feature
            // sub-lists don't fully reconstruct when a single section
            // re-emits. iOS uses `ScrollView { LazyVStack }` for the same
            // reason (PaywallRenderer.swift:38-67).
            val staggerDelay = config.animation?.section_stagger_delay_ms ?: 0
            // SPEC-070-A audit Round 2 finding 3: filter `sticky_footer`
            // (rendered separately below) so it doesn't render twice.
            // Mirrors iOS PaywallRenderer.swift:38-67,119 partition.
            val scrollableSections = config.sections.filter {
                it.type != "sticky_footer"
            }
            // SPEC-070-A finalization PW-11 — collapse_on_scroll wiring.
            // Mirrors iOS PaywallScrollOffsetPrefKey-driven collapse: as user
            // scrolls, sections marked collapse_on_scroll==true fade their
            // alpha + slightly translate up. We hoist a LazyListState here
            // and derive a SINGLE global scroll offset that all marked
            // sections share — see globalCollapseRatio below.
            val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
            // SPEC-070-A finalization P0 audit-1 HIGH-1 — collapse_on_scroll
            // is GLOBAL on iOS, not per-section. iOS PaywallRenderer.swift:
            // 120-128 derives `collapseProgress = min(max(scrollOffset/50, 0), 1)`
            // from a single page-level PaywallScrollOffsetPrefKey, then ALL
            // sections marked collapse_on_scroll==true fade together as soon
            // as the user scrolls past 50px. Per-section logic (each section
            // waiting until it's the first visible item before fading) breaks
            // multi-section collapse layouts: a `collapse_on_scroll` banner
            // at index 0 + a `collapse_on_scroll` testimonial at index 5
            // would fade independently on Android instead of together.
            //
            // Approximation: any scroll past the first item (i.e.
            // firstVisibleItemIndex > 0) means user has progressed past 50px.
            // Otherwise use the offset within the first item. Same 50px
            // hard-collapse distance as iOS.
            val globalCollapseRatio by androidx.compose.runtime.remember {
                androidx.compose.runtime.derivedStateOf {
                    val first = lazyListState.firstVisibleItemIndex
                    val offset = lazyListState.firstVisibleItemScrollOffset
                    val pxScrolled: Float =
                        if (first > 0) Float.POSITIVE_INFINITY
                        else offset.toFloat()
                    (pxScrolled / 50f).coerceIn(0f, 1f)
                }
            }
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .weight(1f)
                    .padding((config.layout.padding ?: 20f).dp),
            ) {
                itemsIndexed(
                    items = scrollableSections,
                    key = { idx, section -> "${section.type}_${section.id ?: idx}" },
                ) { index, section ->
                    val collapseRatio = if (section.collapse_on_scroll == true) globalCollapseRatio else 0f
                    val collapseAlpha = 1f - collapseRatio
                    val collapseHeight = if (collapseRatio >= 1f) 0.dp else androidx.compose.ui.unit.Dp.Unspecified
                    Box(
                        modifier = Modifier
                            .then(if (collapseHeight == 0.dp) Modifier.height(0.dp) else Modifier)
                            .alpha(collapseAlpha)
                            .sectionStagger(
                                config.animation?.section_stagger,
                                delayMs = staggerDelay * index,
                            )
                    ) {
                        PaywallSectionView(
                            section = section,
                            config = config,
                            selectedPlanId = selectedPlanId,
                            isPurchasing = isPurchasing,
                            onPlanSelect = { planId ->
                                selectedPlanId = planId
                                // SPEC-085: Haptic on plan select
                                HapticEngine.triggerIfEnabled(
                                    currentView,
                                    config.haptic?.triggers?.on_plan_select,
                                    config.haptic,
                                )
                            },
                            onCTATap = {
                                // PW-2 — top-level plans fallback.
                                val plans = effectivePlans()
                                val plan = plans.firstOrNull { p -> p.id == selectedPlanId }
                                if (plan != null) {
                                    isPurchasing = true
                                    // SPEC-085: Haptic on CTA tap
                                    HapticEngine.triggerIfEnabled(
                                        currentView,
                                        config.haptic?.triggers?.on_button_tap,
                                        config.haptic,
                                    )
                                    // SPEC-085: Confetti on purchase
                                    if (config.particle_effect != null) {
                                        showConfetti = true
                                    }
                                    // SPEC-070-A C.7 — fold validated promo
                                    // code into purchase metadata. Mirrors
                                    // iOS PaywallRenderer.swift:2160-2167.
                                    val md = toggleStates.toMutableMap<String, Any>()
                                    if (promoState == "success" && promoCode.isNotBlank()) {
                                        md["promo_code"] = promoCode
                                    }
                                    onPlanSelected(plan, md.toMap())
                                }
                            },
                            onRestore = onRestore,
                            loc = ::loc,
                            toggleStates = toggleStates,
                            onPromoCodeSubmit = onPromoCodeSubmit,
                            promoCode = promoCode,
                            promoState = promoState,
                            onPromoCodeChange = { promoCode = it },
                            onPromoStateChange = { promoState = it },
                        )
                    }
                    Spacer(modifier = Modifier.height((config.layout.spacing ?: 16f).dp))
                }
            }

            // SPEC-089d: Sticky footer pinned to bottom
            if (stickyFooterSection != null) {
                PaywallStickyFooter(
                    section = stickyFooterSection,
                    isPurchasing = isPurchasing,
                    onCTATap = {
                        // PW-2 — top-level plans fallback (sticky footer path).
                        val plans = effectivePlans()
                        val plan = plans.firstOrNull { p -> p.id == selectedPlanId }
                        if (plan != null) {
                            isPurchasing = true
                            HapticEngine.triggerIfEnabled(
                                currentView,
                                config.haptic?.triggers?.on_button_tap,
                                config.haptic,
                            )
                            if (config.particle_effect != null) {
                                showConfetti = true
                            }
                            // SPEC-070-A C.7 — fold validated promo code
                            // into purchase metadata (sticky footer path).
                            val md = toggleStates.toMutableMap<String, Any>()
                            if (promoState == "success" && promoCode.isNotBlank()) {
                                md["promo_code"] = promoCode
                            }
                            onPlanSelected(plan, md.toMap())
                        }
                    },
                    onRestore = onRestore,
                    loc = ::loc,
                )
            }
        }

        // SPEC-085: Confetti overlay
        if (showConfetti && config.particle_effect != null) {
            ConfettiOverlay(
                effect = config.particle_effect,
                trigger = showConfetti,
            )
        }

        // SPEC-070-A C.5 — post-purchase success overlay (confetti + lottie).
        // Mirrors iOS `PaywallRenderer.swift:346` `.paywallPurchaseSuccess`
        // notification observer. Confetti uses an ad-hoc ParticleEffect when
        // the paywall has no `particle_effect` config of its own. Lottie URL
        // renders via the shared LottieBlockView.
        postPurchaseOverlay?.let { overlay ->
            if (overlay.confetti) {
                ConfettiOverlay(
                    effect = config.particle_effect ?: ai.appdna.sdk.core.ParticleEffect(
                        type = "confetti",
                        trigger = "on_purchase",
                        duration_ms = 2500,
                        intensity = "medium",
                    ),
                    trigger = true,
                )
            }
            if (!overlay.lottieUrl.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LottieBlockView(
                        block = LottieBlock(
                            lottie_url = overlay.lottieUrl,
                            loop = false,
                            autoplay = true,
                        ),
                    )
                    if (!overlay.message.isNullOrEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                        ) {
                            Text(
                                text = overlay.message,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
                }
            } else if (!overlay.message.isNullOrEmpty()) {
                // Message-only success overlay (no lottie configured) — surface
                // a centered toast-style message atop the paywall.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E9E51)),
                    ) {
                        Text(
                            text = overlay.message,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }

        // Dismiss control
        if (showDismiss) {
            val dismissType = config.dismiss?.type ?: "x_button"
            when (dismissType) {
                "text_link" -> {
                    val dismissCd = stringResource(R.string.appdna_a11y_paywall_dismiss)
                    TextButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            // SPEC-070-A J.11 — text-link dismiss has no icon
                            // semantic so we attach an explicit a11y label.
                            .semantics { contentDescription = dismissCd },
                    ) {
                        Text(
                            text = loc("dismiss.text", config.dismiss?.text ?: "No thanks"),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                }
                "swipe_down" -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .width(36.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.5f))
                    )
                }
                else -> { // x_button (default)
                    val closeCd = stringResource(R.string.appdna_a11y_paywall_close)
                    IconButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
                            // SPEC-070-A J.11 \u2014 close X has no semantic icon
                            // (it's a Text composable with the unicode glyph),
                            // so attach an a11y label that TalkBack reads.
                            .semantics { contentDescription = closeCd },
                    ) {
                        Text(
                            text = "\u2715",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaywallBackground(background: PaywallBackground?) {
    // SPEC-070-A finalization PW-7 / PW-8 — full PaywallBackground matrix
    // mirroring iOS' PaywallRenderer LegacyBackground/BackgroundStyle paths
    // (PaywallRenderer.swift:386-449). Adds: gradient.stops+angle parsing,
    // video background composable, image_fit honoring, overlay color, and
    // a transparent/clear/none type that returns Color.Transparent.
    when (background?.type) {
        "gradient" -> {
            // Prefer canonical `gradient.stops + angle` (Firestore writes it
            // this way per validator). Fall back to legacy `colors` array.
            val stops = background.gradient?.stops
            val angle = background.gradient?.angle ?: 180.0 // default top→bottom
            val brush: Brush? = when {
                stops != null && stops.size >= 2 -> {
                    val colorStopList = stops.mapNotNull { stop ->
                        val c = stop.color ?: return@mapNotNull null
                        val pos = stop.position?.toFloat() ?: 0f
                        pos to parseHexColor(c)
                    }.toTypedArray()
                    if (colorStopList.size >= 2) {
                        // Compute unit-points from angle. iOS:
                        // start.x = 0.5 - sin(rads)/2, etc.
                        val rads = angle * Math.PI / 180.0
                        val sx = (0.5 - kotlin.math.sin(rads) / 2).toFloat()
                        val sy = (0.5 + kotlin.math.cos(rads) / 2).toFloat()
                        val ex = (0.5 + kotlin.math.sin(rads) / 2).toFloat()
                        val ey = (0.5 - kotlin.math.cos(rads) / 2).toFloat()
                        Brush.linearGradient(
                            colorStops = colorStopList,
                            start = androidx.compose.ui.geometry.Offset(sx, sy),
                            end = androidx.compose.ui.geometry.Offset(ex, ey),
                        )
                    } else null
                }
                background.colors != null && background.colors.size >= 2 -> {
                    val cs = background.colors.map { parseHexColor(it) }
                    Brush.verticalGradient(cs)
                }
                else -> null
            }
            if (brush != null) {
                Box(modifier = Modifier.fillMaxSize().background(brush))
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
        "image" -> {
            Box(modifier = Modifier.fillMaxSize()) {
                // Prefer canonical `image_url`, fall back to legacy `value`.
                val imageUrl = background.image_url ?: background.value
                if (imageUrl != null) {
                    val scale = when (background.image_fit) {
                        "contain" -> androidx.compose.ui.layout.ContentScale.Fit
                        "fill" -> androidx.compose.ui.layout.ContentScale.FillBounds
                        else -> androidx.compose.ui.layout.ContentScale.Crop // "cover" default
                    }
                    ai.appdna.sdk.core.NetworkImage(
                        url = imageUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = scale,
                    )
                    // Optional overlay color tint (iOS PaywallRenderer:593).
                    background.overlay?.let { overlayHex ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(parseHexColor(overlayHex)),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
        "video" -> {
            // PW-8 — Media3/ExoPlayer-backed video background. Mirrors iOS'
            // `VideoBackgroundView` (PaywallRenderer.swift:416). Auto-mutes
            // unless `video_muted == false`, loops by default.
            VideoBackgroundView(
                url = background.video_url ?: background.value,
                posterUrl = background.video_poster_url,
                muted = background.video_muted ?: true,
                loop = background.video_loop ?: true,
                modifier = Modifier.fillMaxSize(),
            )
            background.overlay?.let { overlayHex ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(parseHexColor(overlayHex)),
                )
            }
        }
        "color" -> {
            // PW-6 fix already in parser: `value` now folds in `bg.color`.
            // SPEC-070-A finalization audit follow-up — iOS treats the literal
            // strings "transparent" / "clear" as Color.clear
            // (PaywallRenderer.swift:427-431). Android parseHexColor("transparent")
            // falls through to Color.Black, so console-authored
            // bg.color="transparent" rendered BLACK on Android and CLEAR on iOS.
            // Special-case those literals here before parseHexColor is called.
            val color = when (background.value?.lowercase()?.trim()) {
                null, "" -> Color.Black
                "transparent", "clear" -> Color.Transparent
                else -> background.value?.let { parseHexColor(it) } ?: Color.Black
            }
            Box(modifier = Modifier.fillMaxSize().background(color))
        }
        "transparent", "clear", "none" -> {
            // Explicit transparent type — composable but renders nothing so
            // the host app's underlying screen shows through.
            Box(modifier = Modifier.fillMaxSize())
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
}

/**
 * SPEC-070-A finalization PW-8 — video background composable backed by
 * Media3/ExoPlayer. Mirrors iOS' `VideoBackgroundView` (AVPlayer-backed):
 * auto-plays muted, loops, hides controls.
 *
 * Lifecycle:
 *  - DisposableEffect tears the player down on composition exit so the SDK
 *    doesn't hold an audio focus token across paywall dismissals.
 *  - Renders a poster image as a placeholder until the player is ready.
 */
@Composable
private fun VideoBackgroundView(
    url: String?,
    posterUrl: String?,
    muted: Boolean,
    loop: Boolean,
    modifier: Modifier = Modifier,
) {
    if (url.isNullOrBlank()) {
        // Bare poster fallback — useful if video URL fails to resolve.
        Box(modifier = modifier) {
            posterUrl?.let { p ->
                ai.appdna.sdk.core.NetworkImage(
                    url = p,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            volume = if (muted) 0f else 1f
            repeatMode = if (loop) {
                androidx.media3.common.Player.REPEAT_MODE_ONE
            } else {
                androidx.media3.common.Player.REPEAT_MODE_OFF
            }
            playWhenReady = true
            prepare()
        }
    }
    androidx.compose.runtime.DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                player = exoPlayer
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallSectionView(
    section: PaywallSection,
    config: PaywallConfig,
    selectedPlanId: String?,
    isPurchasing: Boolean,
    onPlanSelect: (String) -> Unit,
    onCTATap: () -> Unit,
    onRestore: () -> Unit,
    loc: (String, String) -> String,
    toggleStates: MutableMap<String, Boolean> = mutableMapOf(),
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // SPEC-070-A C.7 — hoisted promo state for purchase metadata.
    promoCode: String = "",
    promoState: String = "idle",
    onPromoCodeChange: (String) -> Unit = {},
    onPromoStateChange: (String) -> Unit = {},
) {
    when (section.type) {
        "header" -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
            ) {
                section.data?.title?.let {
                    val titleStyle = StyleEngine.applyTextStyle(
                        TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp, textAlign = TextAlign.Center),
                        section.style?.elements?.get("title")?.text_style
                    )
                    Text(
                        text = loc("section-header.title", it),
                        style = titleStyle,
                        // SPEC-070-A J.11 — paywall hero/header title is the
                        // accessibility heading for the screen, mirroring iOS
                        // `accessibilityAddTraits(.isHeader)`.
                        modifier = Modifier.semantics { heading() },
                    )
                }
                section.data?.subtitle?.let {
                    Spacer(Modifier.height(8.dp))
                    val subtitleStyle = StyleEngine.applyTextStyle(
                        TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, textAlign = TextAlign.Center),
                        section.style?.elements?.get("subtitle")?.text_style
                    )
                    Text(
                        text = loc("section-header.subtitle", it),
                        style = subtitleStyle,
                    )
                }
            }
        }
        "features" -> {
            val featureItemStyle = StyleEngine.applyTextStyle(
                TextStyle(color = Color.White, fontSize = 16.sp),
                section.style?.elements?.get("item")?.text_style
            )
            Column(modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }) {
                section.data?.features?.forEachIndexed { index, feature ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        Text(text = "\u2713", color = Color(0xFF22C55E), fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(text = loc("feature.$index", feature), style = featureItemStyle)
                    }
                }
            }
        }
        "plans" -> {
            // Gap 10-11: Read plan_display_style from section data, fallback to layout.type
            val displayStyle = section.data?.plan_display_style ?: config.layout.type
            // SPEC-070-A finalization PW-2 — fall back to top-level
            // `config.plans` when section has no plans (matches iOS
            // PaywallRenderer.swift:511-512 effectivePlans logic).
            val sectionPlansRaw = section.data?.plans ?: emptyList()
            val plans = if (sectionPlansRaw.isEmpty()) (config.plans ?: emptyList()) else sectionPlansRaw

            // Card styling from config
            val cardRadius = (section.data?.card_corner_radius ?: 12f).dp
            val cardPad = (section.data?.card_padding ?: 16f).dp
            val cardGap = (section.data?.card_gap ?: 8f).dp
            val cardShape = RoundedCornerShape(cardRadius)
            val cardShadowEnabled = section.data?.card_shadow ?: false

            // Text styles
            val planNameStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp),
                section.style?.elements?.get("plan_name")?.text_style
            )
            val priceStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp),
                section.style?.elements?.get("price")?.text_style
            )
            val periodStyle = StyleEngine.applyTextStyle(
                TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)),
                section.style?.elements?.get("period")?.text_style
            )

            // Badge styling
            val badgeBg = section.data?.badge_bg_color?.let { parseHexColor(it) } ?: Color(0xFF22C55E)
            val badgeTxt = section.data?.badge_text_color?.let { parseHexColor(it) } ?: Color.White
            val badgeFontSize = (section.data?.badge_font_size ?: 11f).sp
            val badgeShapeStr = section.data?.badge_shape ?: "pill"
            val badgeCorner = when (badgeShapeStr) {
                "square" -> RoundedCornerShape(0.dp)
                "rounded" -> RoundedCornerShape(4.dp)
                else -> RoundedCornerShape(999.dp) // pill
            }
            val badgePosition = section.data?.badge_position ?: "inline"

            @Composable
            fun BadgeView(badgeText: String) {
                Text(
                    text = badgeText,
                    color = badgeTxt,
                    fontSize = badgeFontSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(badgeBg, badgeCorner)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            // SPEC-070-A finalization PW-9 — pull all 5 plan-card show-flags
            // + selection styling colors from section.data. Mirrors iOS'
            // PlanCard rendering flags (PaywallConfig.swift:254-258).
            val showPlanIcons = section.data?.show_plan_icons ?: false
            val showPlanImages = section.data?.show_plan_images ?: false
            val showPlanSubtitles = section.data?.show_plan_subtitles ?: false
            val showPlanFeatures = section.data?.show_plan_features ?: false
            val showSavings = section.data?.show_savings ?: false
            val subtitlePosition = section.data?.subtitle_position ?: "below_price"
            val selectedTextColor = section.data?.selected_text_color?.let { parseHexColor(it) }
            val unselectedBorderColor = section.data?.unselected_border_color?.let { parseHexColor(it) }
            val unselectedBgColor = section.data?.unselected_bg_color?.let { parseHexColor(it) }
            val customSelectedBorder = section.data?.selected_border_color?.let { parseHexColor(it) }
            val customSelectedBg = section.data?.selected_bg_color?.let { parseHexColor(it) }

            @Composable
            fun PlanCard(plan: PaywallPlan, planIdx: Int, modifier: Modifier = Modifier) {
                val isSelected = selectedPlanId == plan.id
                val elevation = if (cardShadowEnabled) 4.dp else 0.dp
                // PW-9: honor authored selected/unselected border + bg colors.
                val selectedBorderColor = customSelectedBorder ?: Color(0xFF6366F1)
                val unselectedBorder = unselectedBorderColor
                val selectedBg = customSelectedBg ?: Color(0xFF6366F1).copy(alpha = 0.1f)
                val unselectedBg = unselectedBgColor ?: Color.White.copy(alpha = 0.1f)
                // PW-9: text color flips when selected if author provided one.
                val resolvedTextColor = if (isSelected) {
                    selectedTextColor ?: Color.Unspecified
                } else {
                    Color.Unspecified
                }
                Card(
                    modifier = modifier
                        .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                        .then(
                            when {
                                isSelected -> Modifier.border(2.dp, selectedBorderColor, cardShape)
                                unselectedBorder != null -> Modifier.border(1.dp, unselectedBorder, cardShape)
                                else -> Modifier
                            }
                        )
                        .clickable { onPlanSelect(plan.id) },
                    shape = cardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) selectedBg else unselectedBg,
                    ),
                ) {
                    Box {
                        Column(modifier = Modifier.padding(cardPad)) {
                            // PW-9: optional plan icon (top of card; iOS SF
                            // Symbol rendered via IconView). Resolved from
                            // plan.icon when show_plan_icons flag is true.
                            if (showPlanIcons && !plan.icon.isNullOrBlank()) {
                                ai.appdna.sdk.core.IconView(
                                    ref = ai.appdna.sdk.core.IconReference(
                                        library = "material",
                                        name = plan.icon,
                                        size = 24f,
                                    ),
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // PW-9: optional plan image (above name; iOS path).
                            if (showPlanImages && !plan.image_url.isNullOrBlank()) {
                                ai.appdna.sdk.core.NetworkImage(
                                    url = plan.image_url,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // PW-12 — `plan.displayName` mirrors iOS' computed
                            // `label ?? name` accessor; respect resolvedTextColor.
                            Text(
                                text = loc("plan.$planIdx.name", plan.displayName),
                                style = planNameStyle,
                                color = resolvedTextColor,
                            )

                            // PW-9: subtitle above price if `subtitle_position == "above_price"`.
                            if (showPlanSubtitles && subtitlePosition == "above_price" && !plan.description.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = loc("plan.$planIdx.description", plan.description),
                                    fontSize = 12.sp,
                                    color = resolvedTextColor.takeIf { it != Color.Unspecified } ?: Color.Gray,
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            // PW-12 — `plan.displayPrice` mirrors `price_display ?? price`.
                            Text(
                                text = loc("plan.$planIdx.price", plan.displayPrice),
                                style = priceStyle,
                                color = resolvedTextColor,
                            )
                            plan.period?.let {
                                Text(
                                    text = loc("plan.$planIdx.period", it),
                                    style = periodStyle,
                                    color = resolvedTextColor,
                                )
                            }

                            // PW-9: subtitle below price (default position).
                            if (showPlanSubtitles && subtitlePosition != "above_price" && !plan.description.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = loc("plan.$planIdx.description", plan.description),
                                    fontSize = 12.sp,
                                    color = resolvedTextColor.takeIf { it != Color.Unspecified } ?: Color.Gray,
                                )
                            }

                            // PW-9: per-plan feature checkmark list.
                            if (showPlanFeatures && !plan.features.isNullOrEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                plan.features.forEachIndexed { fIdx, feature ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "✓ ", color = Color(0xFF10B981), fontSize = 13.sp)
                                        Text(
                                            text = loc("plan.$planIdx.feature.$fIdx", feature),
                                            fontSize = 13.sp,
                                            color = resolvedTextColor,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                }
                            }

                            // PW-9: per-plan savings text (typically "Save 20%").
                            if (showSavings && !plan.savings_text.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = loc("plan.$planIdx.savings", plan.savings_text),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF10B981), // green by convention
                                )
                            }

                            // PW-12 — `plan.trialLabel` shows trial copy if present
                            // (computed from `trial?.label ?? trial_duration`).
                            plan.trialLabel?.takeIf { it.isNotBlank() }?.let { trialText ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = loc("plan.$planIdx.trial", trialText),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = resolvedTextColor.takeIf { it != Color.Unspecified } ?: Color(0xFF6366F1),
                                )
                            }

                            plan.badge?.let {
                                if (badgePosition == "inline") {
                                    Spacer(Modifier.height(8.dp))
                                    BadgeView(loc("plan.$planIdx.badge", it))
                                }
                            }
                        }
                        // Positioned badge (non-inline)
                        plan.badge?.let { badge ->
                            if (badgePosition != "inline") {
                                val alignment = when (badgePosition) {
                                    "top_left" -> Alignment.TopStart
                                    "top_center" -> Alignment.TopCenter
                                    else -> Alignment.TopEnd // top_right default
                                }
                                Box(
                                    modifier = Modifier.align(alignment).padding(8.dp),
                                ) {
                                    BadgeView(loc("plan.$planIdx.badge", badge))
                                }
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }) {
                when (displayStyle) {
                    "horizontal_scroll", "carousel_cards", "carousel" -> {
                        // Horizontally scrollable plan cards / carousel
                        val pagerState = rememberPagerState(
                            initialPage = plans.indexOfFirst { it.id == selectedPlanId }.coerceAtLeast(0),
                            pageCount = { plans.size }
                        )
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            pageSpacing = cardGap,
                        ) { planIdx ->
                            PlanCard(plan = plans[planIdx], planIdx = planIdx, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    "pill_selector", "segmented_toggle", "minimal_chips" -> {
                        // Row of chips / segments
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(cardGap),
                        ) {
                            plans.forEachIndexed { planIdx, plan ->
                                val isSelected = selectedPlanId == plan.id
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(cardShape)
                                        .background(
                                            if (isSelected) Color(0xFF6366F1) else Color.White.copy(alpha = 0.1f),
                                            cardShape,
                                        )
                                        .border(
                                            if (isSelected) 0.dp else 1.dp,
                                            if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.3f),
                                            cardShape,
                                        )
                                        .clickable { onPlanSelect(plan.id) }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = loc("plan.$planIdx.name", plan.displayName),
                                            style = planNameStyle.copy(fontSize = 14.sp),
                                            textAlign = TextAlign.Center,
                                        )
                                        Text(
                                            text = loc("plan.$planIdx.price", plan.displayPrice),
                                            style = priceStyle.copy(fontSize = 14.sp),
                                            textAlign = TextAlign.Center,
                                        )
                                        plan.badge?.let {
                                            Spacer(Modifier.height(4.dp))
                                            BadgeView(loc("plan.$planIdx.badge", it))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "radio_list" -> {
                        // Column with radio buttons
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2)
                                    .clip(cardShape)
                                    .background(
                                        if (isSelected) Color(0xFF6366F1).copy(alpha = 0.1f) else Color.Transparent,
                                        cardShape,
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color(0xFF6366F1) else Color.White.copy(alpha = 0.2f),
                                        cardShape,
                                    )
                                    .clickable { onPlanSelect(plan.id) }
                                    .padding(cardPad),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = isSelected,
                                    onClick = { onPlanSelect(plan.id) },
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF6366F1),
                                        unselectedColor = Color.White.copy(alpha = 0.5f),
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle)
                                    plan.period?.let { Text(text = loc("plan.$planIdx.period", it), style = periodStyle) }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle)
                                    plan.badge?.let {
                                        Spacer(Modifier.height(4.dp))
                                        BadgeView(loc("plan.$planIdx.badge", it))
                                    }
                                }
                            }
                        }
                    }
                    "accordion" -> {
                        // Expandable vertical list — selected plan shows expanded details
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2)
                                    .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, Color(0xFF6366F1), cardShape) else Modifier
                                    )
                                    .clickable { onPlanSelect(plan.id) },
                                shape = cardShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f)
                                ),
                            ) {
                                Column(modifier = Modifier.padding(cardPad)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle)
                                        Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle)
                                    }
                                    if (isSelected) {
                                        Spacer(Modifier.height(8.dp))
                                        plan.period?.let { Text(text = loc("plan.$planIdx.period", it), style = periodStyle) }
                                        plan.badge?.let {
                                            Spacer(Modifier.height(4.dp))
                                            BadgeView(loc("plan.$planIdx.badge", it))
                                        }
                                        plan.trialLabel?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(text = it, style = periodStyle)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "grid" -> {
                        // 2-column grid
                        val chunked = plans.chunked(2)
                        chunked.forEachIndexed { chunkIdx, row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                                row.forEachIndexed { colIdx, plan ->
                                    val planIdx = chunkIdx * 2 + colIdx
                                    PlanCard(plan = plan, planIdx = planIdx, modifier = Modifier.weight(1f).padding(vertical = cardGap / 2))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    "toggle_cards" -> {
                        // Row of toggle-style cards (2 plans side by side)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(cardGap),
                        ) {
                            plans.forEachIndexed { planIdx, plan ->
                                PlanCard(plan = plan, planIdx = planIdx, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    else -> {
                        // vertical_stack (default), feature_comparison, pricing_table, tiered_slider → Column layout
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2)
                                    .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, Color(0xFF6366F1), cardShape) else Modifier
                                    )
                                    .clickable { onPlanSelect(plan.id) },
                                shape = cardShape,
                                elevation = CardDefaults.cardElevation(defaultElevation = if (cardShadowEnabled) 4.dp else 0.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f)
                                )
                            ) {
                                Box {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(cardPad),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle)
                                            plan.period?.let { Text(text = loc("plan.$planIdx.period", it), style = periodStyle) }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle)
                                            plan.badge?.let {
                                                if (badgePosition == "inline") {
                                                    BadgeView(loc("plan.$planIdx.badge", it))
                                                }
                                            }
                                        }
                                    }
                                    plan.badge?.let { badge ->
                                        if (badgePosition != "inline") {
                                            val alignment = when (badgePosition) {
                                                "top_left" -> Alignment.TopStart
                                                "top_center" -> Alignment.TopCenter
                                                else -> Alignment.TopEnd
                                            }
                                            Box(modifier = Modifier.align(alignment).padding(8.dp)) {
                                                BadgeView(loc("plan.$planIdx.badge", badge))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val restoreCd = stringResource(R.string.appdna_a11y_paywall_restore)
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        // SPEC-070-A J.11 — restore button a11y label so
                        // screen readers announce purpose, not just visible
                        // text (which may be localized in future).
                        .semantics { contentDescription = restoreCd },
                ) {
                    Text(loc("restore.text", "Restore Purchases"), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
        }
        "cta" -> {
            // SPEC-070-A finalization PW-10 — full CTA section with optional
            // restore button placed above OR below the main CTA, plus CTA
            // gradient + cta_height + cta_font_size honored from authored
            // values. Mirrors iOS PaywallRenderer CTA branch with the
            // RestoreLinkView wrapping (PaywallRenderer.swift:146-205).
            val buttonBgColor = section.style?.elements?.get("button")?.background?.color?.let {
                StyleEngine.parseColor(it)
            } ?: Color(0xFF6366F1)
            // PW-10: prefer authored cta_font_size over the 17.sp baseline.
            val ctaFontSize = (section.data?.cta_font_size ?: section.data?.cta?.font_size?.toFloat() ?: 17f).sp
            val ctaHeight = (section.data?.cta_height ?: section.data?.cta?.height?.toFloat() ?: 56f).dp
            val buttonTextStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, fontSize = ctaFontSize),
                section.style?.elements?.get("button")?.text_style,
            )
            // PW-10 / iOS PaywallRenderer.swift:1052+ — CTA gradient brush
            // built from gradient.stops + angle. Falls back to solid color
            // when no gradient is authored.
            val ctaGradient = section.data?.cta_gradient
            val ctaBrush: Brush? = ctaGradient?.takeIf { (it.stops?.size ?: 0) >= 2 }?.let { g ->
                val stops = g.stops!!.mapNotNull { s ->
                    val c = s.color ?: return@mapNotNull null
                    val pos = s.position?.toFloat() ?: 0f
                    pos to parseHexColor(c)
                }.toTypedArray()
                if (stops.size < 2) return@let null
                val rads = (g.angle ?: 90.0) * Math.PI / 180.0
                val sx = (0.5 - kotlin.math.sin(rads) / 2).toFloat()
                val sy = (0.5 + kotlin.math.cos(rads) / 2).toFloat()
                val ex = (0.5 + kotlin.math.sin(rads) / 2).toFloat()
                val ey = (0.5 - kotlin.math.cos(rads) / 2).toFloat()
                Brush.linearGradient(
                    colorStops = stops,
                    start = androidx.compose.ui.geometry.Offset(sx, sy),
                    end = androidx.compose.ui.geometry.Offset(ex, ey),
                )
            }

            // PW-10 — restore link rendering helper.
            val showRestoreLink = section.data?.show_restore == true
            val restorePosition = section.data?.restore_position ?: "below"
            val restoreText = section.data?.restore_text ?: "Restore Purchases"
            val restoreColor = section.data?.restore_text_color?.let { parseHexColor(it) }
                ?: Color.White.copy(alpha = 0.6f)
            val restoreFontSize = (section.data?.restore_font_size ?: 13f).sp

            @Composable
            fun RestoreLink() {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Text(
                    text = loc("cta.restore_text", restoreText),
                    color = restoreColor,
                    fontSize = restoreFontSize,
                    modifier = Modifier
                        .clickable { onRestore() }
                        .padding(vertical = 8.dp)
                        .semantics {
                            contentDescription = ctx.getString(R.string.appdna_a11y_paywall_restore)
                        },
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (showRestoreLink && restorePosition == "above") {
                    RestoreLink()
                    Spacer(Modifier.height(8.dp))
                }
                // CTA Button — paints gradient if present, else solid color.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ctaHeight)
                        .ctaAnimation(config.animation?.cta_animation)
                        .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
                        .clip(RoundedCornerShape((section.data?.cta?.corner_radius?.toFloat() ?: 14f).dp))
                        .then(
                            if (ctaBrush != null) Modifier.background(ctaBrush)
                            else Modifier.background(buttonBgColor)
                        )
                        .clickable(enabled = !isPurchasing) { onCTATap() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPurchasing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            // SPEC-070-A finalization PW-3 — iOS resolution chain.
                            // `config.cta?.text ?? section.data?.cta?.text ??
                            // section.data?.cta_text ?? section.data?.text ?? "Continue"`.
                            // Inlined here (not via helper) because PaywallSectionView
                            // is a separate @Composable scope from PaywallScreen.
                            text = loc(
                                "cta.text",
                                config.cta?.text?.takeIf { it.isNotBlank() }
                                    ?: section.data?.cta?.text?.takeIf { it.isNotBlank() }
                                    ?: section.data?.cta_text?.takeIf { it.isNotBlank() }
                                    ?: section.data?.text?.takeIf { it.isNotBlank() }
                                    ?: "Continue",
                            ),
                            style = buttonTextStyle.copy(color = Color.White),
                        )
                    }
                }
                if (showRestoreLink && restorePosition != "above") {
                    Spacer(Modifier.height(8.dp))
                    RestoreLink()
                }
            }
        }
        "social_proof" -> {
            // SPEC-084: Social proof sub-types
            val subType = section.data?.sub_type ?: "app_rating"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
            ) {
                when (subType) {
                    "countdown" -> {
                        CountdownTimer(
                            seconds = section.data?.countdown_seconds ?: 86400,
                            valueTextStyle = section.style?.elements?.get("value")?.text_style,
                        )
                    }
                    "trial_badge" -> {
                        val trialBadgeStyle = StyleEngine.applyTextStyle(
                            TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6366F1)),
                            section.style?.elements?.get("value")?.text_style
                        )
                        Text(
                            text = loc("social_proof.trial_badge", section.data?.text ?: "Free Trial"),
                            style = trialBadgeStyle,
                            modifier = Modifier
                                .background(
                                    Color(0xFF6366F1).copy(alpha = 0.15f),
                                    RoundedCornerShape(999.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    else -> { // app_rating
                        val socialValueStyle = StyleEngine.applyTextStyle(
                            TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
                            section.style?.elements?.get("value")?.text_style
                        )
                        section.data?.rating?.let { rating ->
                            val stars = "\u2605".repeat(rating.toInt())
                            Text(text = stars, color = Color(0xFFFBBF24), fontSize = 20.sp)
                            section.data.review_count?.let { count ->
                                Text(
                                    text = loc("social_proof.review_text", "$rating from $count reviews"),
                                    style = socialValueStyle,
                                )
                            }
                        }
                        section.data?.testimonial?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "\"${loc("social_proof.testimonial", it)}\"",
                                style = socialValueStyle,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        "guarantee" -> {
            section.data?.guarantee_text?.let {
                val guaranteeTextStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center),
                    section.style?.elements?.get("text")?.text_style
                )
                Text(
                    text = loc("guarantee.text", it),
                    style = guaranteeTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
                )
            }
        }
        // SPEC-084: Missing sections
        "image" -> {
            ai.appdna.sdk.core.NetworkImage(
                url = section.data?.image_url,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((section.data?.height ?: 240f).dp)
                    .clip(RoundedCornerShape((section.data?.corner_radius ?: 12f).dp))
                    .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        "spacer" -> {
            Spacer(modifier = Modifier.height((section.data?.spacer_height ?: 24f).dp).run { with(StyleEngine) { applyContainerStyle(section.style?.container) } })
        }
        // SPEC-085: Lottie section
        "lottie" -> {
            section.data?.lottie_url?.let { url ->
                LottieBlockView(
                    block = LottieBlock(
                        lottie_url = url,
                        loop = section.data.lottie_loop ?: true,
                        speed = section.data.lottie_speed ?: 1.0f,
                        height = section.data.height ?: 200f,
                    )
                )
            }
        }
        // SPEC-085: Rive section
        "rive" -> {
            section.data?.rive_url?.let { url ->
                RiveBlockView(
                    block = RiveBlock(
                        rive_url = url,
                        state_machine = section.data.rive_state_machine,
                        height = section.data.height ?: 200f,
                    )
                )
            }
        }
        // SPEC-085 + SPEC-070-A audit attempt 9 F1: also handle
        // `video_background` so console-published full-bleed background-video
        // sections render. iOS PaywallRenderer.swift:549 dispatches both
        // strings to the same renderer.
        "video", "video_background" -> {
            section.data?.video_url?.let { url ->
                CoreVideoBlockView(
                    block = CoreVideoBlock(
                        video_url = url,
                        video_thumbnail_url = section.data.video_thumbnail_url ?: section.data.image_url,
                        video_height = section.data.height ?: 200f,
                        video_corner_radius = section.data.corner_radius,
                        autoplay = section.data.video_autoplay,
                        loop = section.data.video_loop,
                    )
                )
            }
        }
        "testimonial" -> {
            val quoteStyle = StyleEngine.applyTextStyle(
                TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center),
                section.style?.elements?.get("quote")?.text_style
            )
            val authorNameStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp),
                section.style?.elements?.get("author_name")?.text_style
            )
            val authorRoleStyle = StyleEngine.applyTextStyle(
                TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
                section.style?.elements?.get("author_role")?.text_style
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
            ) {
                Text(
                    text = "\u201C",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1),
                )
                Text(
                    text = loc("testimonial.quote", section.data?.quote ?: section.data?.testimonial ?: ""),
                    style = quoteStyle,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Author avatar (URL) or initials fallback
                    val avatarUrl = section.data?.avatar_url
                    if (!avatarUrl.isNullOrBlank()) {
                        ai.appdna.sdk.core.NetworkImage(
                            url = avatarUrl,
                            modifier = Modifier.size(40.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        section.data?.author_name?.let { name ->
                            val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }.take(2).joinToString("")
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF6366F1).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(initials, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF6366F1))
                            }
                        }
                    }
                    Column {
                        section.data?.author_name?.let {
                            Text(loc("testimonial.author_name", it), style = authorNameStyle)
                        }
                        section.data?.author_role?.let {
                            Text(loc("testimonial.author_role", it), style = authorRoleStyle)
                        }
                    }
                }
            }
        }
        // SPEC-089d: 12 new paywall section types
        "countdown" -> {
            PaywallCountdownSection(section = section, loc = loc)
        }
        "legal" -> {
            PaywallLegalSection(section = section, loc = loc)
        }
        "divider" -> {
            PaywallDividerSection(section = section)
        }
        "sticky_footer" -> {
            // Rendered outside scrollable content — see PaywallScreen
        }
        "card" -> {
            PaywallCardSection(section = section, loc = loc)
        }
        "carousel" -> {
            PaywallCarouselSection(section = section, config = config, loc = loc)
        }
        "timeline" -> {
            PaywallTimelineSection(section = section, loc = loc)
        }
        "icon_grid" -> {
            PaywallIconGridSection(section = section, loc = loc)
        }
        "comparison_table" -> {
            PaywallComparisonTableSection(section = section, loc = loc)
        }
        "promo_input" -> {
            PaywallPromoInputSection(
                section = section,
                loc = loc,
                onPromoCodeSubmit = onPromoCodeSubmit,
                // SPEC-070-A C.7 — hoisted state plumbing
                promoCode = promoCode,
                promoState = promoState,
                onPromoCodeChange = onPromoCodeChange,
                onPromoStateChange = onPromoStateChange,
            )
        }
        "toggle" -> {
            PaywallToggleSection(section = section, loc = loc, toggleStates = toggleStates)
        }
        "reviews_carousel" -> {
            PaywallReviewsCarouselSection(section = section, loc = loc)
        }
    }
}

// MARK: - SPEC-089d: Countdown section (AC-028)

@Composable
private fun PaywallCountdownSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val duration = section.data?.duration_seconds ?: section.data?.countdown_seconds ?: 3600
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        CountdownTimer(
            seconds = duration,
            valueTextStyle = section.style?.elements?.get("value")?.text_style,
        )
    }
}

// MARK: - SPEC-089d: Legal section (AC-029)

@Composable
private fun PaywallLegalSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val textColor = section.data?.color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.5f)
    val fontSize = section.data?.font_size ?: 11f
    val textAlignment = when (section.data?.alignment) {
        "left" -> TextAlign.Start
        "right" -> TextAlign.End
        else -> TextAlign.Center
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        section.data?.text?.let { text ->
            // Parse markdown links [text](url)
            val annotated = buildAnnotatedStringWithLinks(text)
            androidx.compose.foundation.text.ClickableText(
                text = annotated,
                style = TextStyle(color = textColor, fontSize = fontSize.sp, textAlign = textAlignment),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(annotation.item))
                            context.startActivity(intent)
                        }
                },
            )
        }
        val links = section.data?.links
        if (!links.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                links.forEach { link ->
                    val accentColor = section.data.accent_color?.let { parseHexColor(it) } ?: Color(0xFF6366F1)
                    Text(
                        text = link.label,
                        color = accentColor,
                        fontSize = fontSize.sp,
                        modifier = Modifier.clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url))
                            context.startActivity(intent)
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun buildAnnotatedStringWithLinks(text: String): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val pattern = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    var lastIndex = 0
    pattern.findAll(text).forEach { match ->
        // Add text before the match
        builder.append(text.substring(lastIndex, match.range.first))
        // Add the link text with annotation
        val label = match.groupValues[1]
        val url = match.groupValues[2]
        builder.pushStringAnnotation(tag = "URL", annotation = url)
        builder.pushStyle(
            androidx.compose.ui.text.SpanStyle(
                color = Color(0xFF6366F1),
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            )
        )
        builder.append(label)
        builder.pop() // style
        builder.pop() // annotation
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }
    return builder.toAnnotatedString()
}

// MARK: - SPEC-089d: Divider section (AC-030)

@Composable
private fun PaywallDividerSection(section: PaywallSection) {
    val dividerColor = section.data?.color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.2f)
    val thickness = section.data?.thickness ?: 1f
    val lineStyle = section.data?.line_style ?: "solid"
    val mTop = section.data?.margin_top ?: 8f
    val mBottom = section.data?.margin_bottom ?: 8f
    val mH = section.data?.margin_horizontal ?: 0f

    Box(
        modifier = Modifier
            .padding(top = mTop.dp, bottom = mBottom.dp, start = mH.dp, end = mH.dp)
            .fillMaxWidth(),
    ) {
        val labelText = section.data?.label_text
        if (labelText != null) {
            // Divider with centered label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DividerLine(color = dividerColor, thickness = thickness, style = lineStyle, modifier = Modifier.weight(1f))
                Text(
                    text = labelText,
                    color = section.data.label_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.5f),
                    fontSize = (section.data.label_font_size ?: 12f).sp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .background(
                            section.data.label_bg_color?.let { parseHexColor(it) } ?: Color.Transparent,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp),
                )
                DividerLine(color = dividerColor, thickness = thickness, style = lineStyle, modifier = Modifier.weight(1f))
            }
        } else {
            DividerLine(color = dividerColor, thickness = thickness, style = lineStyle, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DividerLine(color: Color, thickness: Float, style: String, modifier: Modifier = Modifier) {
    when (style) {
        "dashed", "dotted" -> {
            val dashLength = if (style == "dashed") 6f else 2f
            val gapLength = if (style == "dashed") 3f else 2f
            androidx.compose.foundation.Canvas(
                modifier = modifier.height(thickness.dp),
            ) {
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                    strokeWidth = thickness,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(dashLength * density, gapLength * density),
                        0f,
                    ),
                )
            }
        }
        else -> { // solid
            Divider(
                thickness = thickness.dp,
                color = color,
                modifier = modifier,
            )
        }
    }
}

// MARK: - SPEC-089d: Card section (AC-032)

@Composable
private fun PaywallCardSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val radius = section.data?.corner_radius ?: 16f
    val bgColor = section.data?.background_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.08f)
    val borderClr = section.data?.border_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.15f)

    Card(
        shape = RoundedCornerShape(radius.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderClr, RoundedCornerShape(radius.dp))
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        Column(modifier = Modifier.padding((section.data?.padding ?: 16f).dp)) {
            section.data?.title?.let {
                Text(
                    text = loc("card.title", it),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
            }
            section.data?.subtitle?.let {
                Text(
                    text = loc("card.subtitle", it),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
            }
            section.data?.text?.let {
                Text(
                    text = loc("card.body", it),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

// MARK: - SPEC-089d: Carousel section (AC-033)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallCarouselSection(
    section: PaywallSection,
    config: PaywallConfig,
    loc: (String, String) -> String,
) {
    val pages = section.data?.pages ?: return
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()
    val autoScroll = section.data.auto_scroll ?: false
    val intervalMs = section.data.auto_scroll_interval_ms ?: 3000
    val showIndicators = section.data.show_indicators ?: true

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height((section.data.height ?: 200f).dp),
        ) { pageIdx ->
            val page = pages[pageIdx]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                page.children?.forEach { child ->
                    child.data?.title?.let {
                        Text(text = it, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    child.data?.subtitle?.let {
                        Text(text = it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    child.data?.image_url?.let { url ->
                        ai.appdna.sdk.core.NetworkImage(
                            url = url,
                            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
            }
        }

        if (showIndicators) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { idx ->
                    val activeColor = section.data.indicator_active_color?.let { parseHexColor(it) } ?: Color.White
                    val inactiveColor = section.data.indicator_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (idx == pagerState.currentPage) activeColor else inactiveColor),
                    )
                }
            }
        }
    }

    // Auto-scroll
    if (autoScroll) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(intervalMs.toLong())
                val nextPage = (pagerState.currentPage + 1) % pages.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }
}

// MARK: - SPEC-089d: Timeline section (AC-034)

@Composable
private fun PaywallTimelineSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val items = section.data?.items ?: return
    val isCompact = section.data.compact ?: false
    val showLine = section.data.show_line ?: true

    Column(
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 24.dp),
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        items.forEachIndexed { index, item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Status indicator column
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp),
                ) {
                    val statusColor = when (item.status) {
                        "completed" -> parseHexColor(section.data.completed_color ?: "#22C55E")
                        "current" -> parseHexColor(section.data.current_color ?: "#6366F1")
                        else -> parseHexColor(section.data.upcoming_color ?: "#666666")
                    }
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(statusColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.status == "completed") {
                            Text("\u2713", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (showLine && index < items.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(if (isCompact) 12.dp else 24.dp)
                                .background(parseHexColor(section.data.line_color ?: "#333333")),
                        )
                    }
                }

                // Content column
                Column(modifier = Modifier.weight(1f)) {
                    item.title?.let {
                        Text(text = it, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    }
                    item.subtitle?.let {
                        Text(text = it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// MARK: - SPEC-089d: Icon grid section (AC-035)

@Composable
private fun PaywallIconGridSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val items = section.data?.items ?: return
    val columnCount = section.data.columns ?: 3
    val gridSpacing = section.data.spacing ?: 16f
    val iconSz = section.data.icon_size ?: 32f
    val iconClr = section.data.icon_color?.let { parseHexColor(it) } ?: Color(0xFF6366F1)

    val titleStyle = section.style?.elements?.get("title")?.text_style
    val descStyle = section.style?.elements?.get("description")?.text_style

    // Manual grid since LazyVerticalGrid can't be nested in scrollable Column
    val chunked = items.chunked(columnCount)
    Column(
        verticalArrangement = Arrangement.spacedBy(gridSpacing.dp),
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        chunked.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gridSpacing.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        item.icon?.let { icon ->
                            // Check if emoji (non-ASCII) or icon name
                            if (icon.any { it.code > 127 }) {
                                Text(text = icon, fontSize = iconSz.sp)
                            } else {
                                IconView(
                                    ref = IconReference(library = "material", name = icon, size = iconSz, color = null),
                                    modifier = Modifier.size(iconSz.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        val itemLabel = item.label ?: item.title
                        itemLabel?.let {
                            val ts = if (titleStyle != null) StyleEngine.applyTextStyle(
                                TextStyle(fontWeight = FontWeight.Medium, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center),
                                titleStyle,
                            ) else TextStyle(fontWeight = FontWeight.Medium, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Text(text = it, style = ts, textAlign = TextAlign.Center)
                        }
                        val itemDesc = item.description ?: item.subtitle
                        itemDesc?.let {
                            val ds = if (descStyle != null) StyleEngine.applyTextStyle(
                                TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center),
                                descStyle,
                            ) else TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center)
                            Text(text = it, style = ds, textAlign = TextAlign.Center)
                        }
                    }
                }
                // Fill remaining slots for last row
                repeat(columnCount - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// MARK: - SPEC-089d: Comparison table section (AC-036)

@Composable
private fun PaywallComparisonTableSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val cols = section.data?.table_columns ?: return
    val rows = section.data.rows ?: return
    val checkColor = section.data.check_color?.let { parseHexColor(it) } ?: Color(0xFF22C55E)
    val crossColor = section.data.cross_color?.let { parseHexColor(it) } ?: Color(0xFFEF4444)
    val highlightClr = section.data.highlight_color?.let { parseHexColor(it) } ?: Color(0xFF6366F1)
    val borderClr = section.data.border_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.2f)
    val radius = section.data.corner_radius ?: 12f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.dp))
            .border(1.dp, borderClr, RoundedCornerShape(radius.dp))
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)),
        ) {
            // Feature label column header
            Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            cols.forEachIndexed { _, col ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (col.highlighted == true) highlightClr.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = col.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Divider(color = borderClr, thickness = 0.5.dp)

        // Data rows
        rows.forEachIndexed { rowIdx, row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = row.feature,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                )
                row.values.forEachIndexed { valIdx, value ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (valIdx < cols.size && cols[valIdx].highlighted == true) highlightClr.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (value.lowercase()) {
                            "check" -> Text("\u2713", color = checkColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            "cross" -> Text("\u2715", color = crossColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            "partial" -> Text("\u2014", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            else -> Text(value, fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (rowIdx < rows.size - 1) {
                Divider(color = borderClr.copy(alpha = 0.3f), thickness = 0.5.dp)
            }
        }
    }
}

// MARK: - SPEC-089d: Promo input section (AC-037)

@Composable
private fun PaywallPromoInputSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // SPEC-070-A C.7 — state hoisted to parent so CTA tap handlers can read
    // the validated code. When omitted (e.g. legacy callers), falls back to
    // local state.
    promoCode: String? = null,
    promoState: String? = null,
    onPromoCodeChange: ((String) -> Unit)? = null,
    onPromoStateChange: ((String) -> Unit)? = null,
) {
    // Local fallback when parent did not hoist
    var localPromoCode by remember { mutableStateOf("") }
    var localPromoState by remember { mutableStateOf("idle") }
    val effectivePromoCode = promoCode ?: localPromoCode
    val effectivePromoState = promoState ?: localPromoState
    val updateCode: (String) -> Unit = { newCode ->
        if (onPromoCodeChange != null) onPromoCodeChange(newCode) else localPromoCode = newCode
    }
    val updateState: (String) -> Unit = { newState ->
        if (onPromoStateChange != null) onPromoStateChange(newState) else localPromoState = newState
    }

    Column(
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = effectivePromoCode,
                onValueChange = updateCode,
                placeholder = { Text(section.data?.placeholder ?: "Promo code", color = Color.White.copy(alpha = 0.4f)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                ),
            )
            Button(
                onClick = {
                    updateState("loading")
                    // AC-037: Submit promo code via delegate callback
                    if (onPromoCodeSubmit != null) {
                        onPromoCodeSubmit(effectivePromoCode) { isValid ->
                            updateState(if (isValid) "success" else "error")
                        }
                    } else {
                        // No delegate configured — basic non-empty check fallback
                        updateState(if (effectivePromoCode.isNotBlank()) "success" else "error")
                    }
                },
                enabled = effectivePromoState != "loading",
                colors = ButtonDefaults.buttonColors(
                    containerColor = section.data?.accent_color?.let { parseHexColor(it) } ?: Color(0xFF6366F1),
                ),
            ) {
                Text(
                    text = loc("promo.button", section.data?.button_text ?: "Apply"),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }
        when (effectivePromoState) {
            "success" -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.data?.success_text ?: "Code applied!",
                    color = Color(0xFF22C55E),
                    fontSize = 12.sp,
                )
            }
            "error" -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.data?.error_text ?: "Invalid code",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// MARK: - SPEC-089d: Toggle section (AC-038)

@Composable
private fun PaywallToggleSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    toggleStates: MutableMap<String, Boolean> = mutableMapOf(),
) {
    val toggleKey = section.data?.label ?: "toggle_${section.type}"
    var isToggled by remember { mutableStateOf(toggleStates[toggleKey] ?: section.data?.default_value ?: false) }
    val onColor = section.data?.on_color?.let { parseHexColor(it) } ?: Color(0xFF6366F1)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        section.data?.icon?.let { iconName ->
            IconView(
                ref = IconReference(library = "material", name = iconName, size = 24f, color = section.data?.accent_color),
                modifier = Modifier.size(24.dp).padding(end = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            section.data?.label?.let {
                Text(
                    text = loc("toggle.label", it),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = section.data.label_color_val?.let { c -> parseHexColor(c) } ?: Color.White,
                )
            }
            section.data?.description?.let {
                Text(
                    text = loc("toggle.description", it),
                    fontSize = 12.sp,
                    color = section.data.description_color?.let { c -> parseHexColor(c) } ?: Color.White.copy(alpha = 0.6f),
                )
            }
        }

        Switch(
            checked = isToggled,
            onCheckedChange = { isToggled = it; toggleStates[toggleKey] = it },
            colors = SwitchDefaults.colors(checkedTrackColor = onColor),
        )
    }
}

// MARK: - SPEC-089d: Reviews carousel section (AC-039)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallReviewsCarouselSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val reviews = section.data?.reviews ?: return
    if (reviews.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { reviews.size })
    val autoScroll = section.data.auto_scroll ?: true
    val intervalMs = section.data.auto_scroll_interval_ms ?: 4000
    val showStars = section.data.show_rating_stars ?: true
    val starClr = section.data.star_color?.let { parseHexColor(it) } ?: Color(0xFFFBBF24)
    val textStyleConfig = section.style?.elements?.get("text")?.text_style
    val authorStyleConfig = section.style?.elements?.get("author")?.text_style

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        ) { pageIdx ->
            val review = reviews[pageIdx]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                // Star rating
                if (showStars && review.rating != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(5) { star ->
                            Text(
                                text = if (star < review.rating.toInt()) "\u2605" else "\u2606",
                                color = starClr,
                                fontSize = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Quote text
                val quoteTextStyle = if (textStyleConfig != null) {
                    StyleEngine.applyTextStyle(
                        TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center),
                        textStyleConfig,
                    )
                } else {
                    TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                Text(
                    text = "\u201C${review.text}\u201D",
                    style = quoteTextStyle,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))

                // Author
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    review.avatar_url?.let { url ->
                        ai.appdna.sdk.core.NetworkImage(
                            url = url,
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                    val authorStyle = if (authorStyleConfig != null) {
                        StyleEngine.applyTextStyle(
                            TextStyle(fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
                            authorStyleConfig,
                        )
                    } else {
                        TextStyle(fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Text(text = review.author, style = authorStyle)
                    review.date?.let {
                        Text(text = it, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }
        }

        // Page indicators
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(reviews.size) { idx ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (idx == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.3f)),
                )
            }
        }
    }

    // Auto-scroll
    if (autoScroll) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(intervalMs.toLong())
                val nextPage = (pagerState.currentPage + 1) % reviews.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }
}

// SPEC-084: Countdown timer for social proof
@Composable
private fun CountdownTimer(seconds: Int, valueTextStyle: ai.appdna.sdk.core.TextStyleConfig? = null) {
    var remaining by remember { mutableIntStateOf(seconds) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }

    val hours = remaining / 3600
    val minutes = (remaining % 3600) / 60
    val secs = remaining % 60

    val digitStyle = StyleEngine.applyTextStyle(
        TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White),
        valueTextStyle
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(hours to "h", minutes to "m", secs to "s").forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Text(":", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    // SPEC-070-A I.10 — `Locale.US` keeps the countdown digits
                    // ASCII (`05` not `۰۵`) so the paywall countdown reads the
                    // same in every locale. Mirrors iOS NumberFormatter.
                    text = String.format(java.util.Locale.US, "%02d", value),
                    style = digitStyle,
                )
                Text(text = label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

// MARK: - SPEC-089d: Sticky footer section (AC-031)

@Composable
private fun PaywallStickyFooter(
    section: PaywallSection,
    isPurchasing: Boolean,
    onCTATap: () -> Unit,
    onRestore: () -> Unit,
    loc: (String, String) -> String,
) {
    val bgColor = section.data?.background_color?.let { parseHexColor(it) } ?: Color.Black.copy(alpha = 0.95f)
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = (section.data?.padding ?: 20f).dp, vertical = 16.dp)
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        // CTA button
        section.data?.cta_text?.let { ctaText ->
            Button(
                onClick = onCTATap,
                enabled = !isPurchasing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape((section.data.cta_corner_radius ?: 14f).dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = section.data.cta_bg_color?.let { parseHexColor(it) } ?: Color(0xFF6366F1),
                ),
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = loc("sticky_footer.cta", ctaText),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = section.data.cta_text_color?.let { parseHexColor(it) } ?: Color.White,
                    )
                }
            }
        }

        // Secondary action
        section.data?.secondary_text?.let { secondaryText ->
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                when (section.data.secondary_action) {
                    "restore" -> onRestore()
                    "link" -> {
                        section.data.secondary_url?.let { url ->
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            context.startActivity(intent)
                        }
                    }
                }
            }) {
                Text(
                    text = loc("sticky_footer.secondary", secondaryText),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
        }

        // Legal text
        section.data?.legal_text?.let { legalText ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = loc("sticky_footer.legal", legalText),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun parseHexColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val colorLong = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000 or colorLong)
            8 -> Color(colorLong)
            else -> Color.Black
        }
    } catch (_: Exception) {
        Color.Black
    }
}
