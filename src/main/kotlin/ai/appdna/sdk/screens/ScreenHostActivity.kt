package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.core.PresentationCoordinator
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.entryAnimation
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Host Activity for SDUI screens presented via [AppDNA.showScreen].
 *
 * Mirrors iOS `Sources/AppDNASDK/Screens/ScreenPresenter.swift`, which wraps
 * a SwiftUI `ScreenRenderer` in a `UIHostingController` and presents it via
 * `UIViewController.present(...)` honoring `config.presentation` (fullscreen
 * / pageSheet / bottom_sheet) and `config.transition` (slide_up / slide_left
 * / fade / none).
 *
 * Android limits us to per-Activity transitions; we use the standard
 * `overridePendingTransition` after `startActivity` to honor the requested
 * style. Compose handles the actual section rendering through
 * [SectionRegistry] — the same registry used by [AppDNAScreenSlot].
 */
class ScreenHostActivity : ComponentActivity() {

    private var launchToken: String? = null
    private var dispatchedDismiss: Boolean = false
    private var snapshotOnDismiss: ((ScreenResult) -> Unit)? = null
    private var snapshotScreenId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val screenId = intent.getStringExtra(EXTRA_SCREEN_ID) ?: run {
            finish()
            return
        }
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val slot = token?.let { activeLaunches[it] } ?: run {
            // SPEC-070-A finalization (Lens B P0) — process-death recovery.
            // Mirrors PaywallActivity: if the static slot is gone but the OS
            // recreated us from `savedInstanceState`, fire a synthetic
            // `screen_dismissed` and onScreenDismissed delegate so analytics
            // + host stays consistent.
            if (savedInstanceState != null) {
                try {
                    // Process-death recovery: in-memory `onDismiss` closure is gone.
                    // Emit analytics so dashboards see the dismissal; host delegate
                    // re-fire is impossible (closure not serializable).
                    AppDNA.track("screen_dismissed", mapOf(
                        "screen_id" to screenId,
                        "reason" to "process_death",
                        "duration_ms" to 0,
                    ))
                } catch (e: Throwable) {
                    Log.warning { "ScreenHost process-death recovery failed: ${e.message}" }
                }
            }
            finish()
            return
        }
        launchToken = token
        snapshotOnDismiss = slot.onDismiss
        snapshotScreenId = screenId
        val config = slot.config

        // SPEC-070-A finalization R3 P1 (Lens D) — honor `config.transition`
        // via overridePendingTransition. iOS uses UIModalTransitionStyle on
        // ScreenPresenter; Android equivalent is the per-Activity enter/exit
        // anim pair applied immediately after the Intent fires. Falls back to
        // platform default ("none") when the value is unknown or missing.
        // SPEC-401-A R67 (Lens C P1) — `slide_up` now uses custom
        // R.anim.appdna_slide_up_in (vertical translateY 100%→0) matching
        // iOS UIModalTransitionStyle.coverVertical at
        // ScreenPresenter.swift:80. Stock `slide_in_left` is horizontal —
        // visible cross-platform divergence.
        // (Lens C R67 P2) — unknown / null transition defaults to slide_up
        // matching iOS `default: return .coverVertical` at
        // ScreenPresenter.swift:84.
        @Suppress("DEPRECATION")
        when (config.transition) {
            "slide_up" -> overridePendingTransition(
                ai.appdna.sdk.R.anim.appdna_slide_up_in,
                android.R.anim.fade_out,
            )
            "slide_left" -> overridePendingTransition(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right,
            )
            "fade" -> overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            "none" -> overridePendingTransition(0, 0)
            else -> overridePendingTransition(
                ai.appdna.sdk.R.anim.appdna_slide_up_in,
                android.R.anim.fade_out,
            )
        }

        setContent {
            val isDark = isSystemInDarkTheme()
            MaterialTheme {
                ScreenHostBody(
                    config = config,
                    isDark = isDark,
                    onDismiss = {
                        dispatchedDismiss = true
                        slot.onDismiss?.invoke(
                            ScreenResult(
                                screenId = screenId,
                                dismissed = true,
                                durationMs = (System.currentTimeMillis() - slot.startedAtMs).toInt(),
                            )
                        )
                        cleanup()
                    },
                )
            }
        }
    }

    override fun onBackPressed() {
        // Honor `dismiss.enabled == false` for force-modal screens.
        val allowed = launchToken?.let { activeLaunches[it]?.config?.dismiss?.enabled } ?: true
        if (allowed == false) return
        dispatchedDismiss = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
        snapshotOnDismiss?.invoke(
            ScreenResult(
                screenId = snapshotScreenId ?: "",
                dismissed = true,
            )
        )
        cleanup()
    }

    private fun cleanup() {
        // SPEC-401-A R67 (Lens C P1) — pair the symmetric exit animation to
        // the entry transition so dismiss matches present. iOS
        // `modalTransitionStyle` (ScreenPresenter.swift:20) is symmetric;
        // Android requires explicit `overridePendingTransition` AFTER finish().
        val transition = launchToken?.let { activeLaunches[it]?.config?.transition }
        finish()
        @Suppress("DEPRECATION")
        when (transition) {
            "slide_up" -> overridePendingTransition(
                android.R.anim.fade_in,
                ai.appdna.sdk.R.anim.appdna_slide_down_out,
            )
            "slide_left" -> overridePendingTransition(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right,
            )
            "fade" -> overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            "none" -> overridePendingTransition(0, 0)
            else -> overridePendingTransition(
                android.R.anim.fade_in,
                ai.appdna.sdk.R.anim.appdna_slide_down_out,
            )
        }
    }

    override fun onDestroy() {
        // Backstop: if isFinishing but no dismiss was dispatched, fire one.
        if (isFinishing && !dispatchedDismiss) {
            val cb = snapshotOnDismiss
            snapshotOnDismiss = null
            cb?.invoke(
                ScreenResult(
                    screenId = snapshotScreenId ?: "",
                    dismissed = true,
                )
            )
        }
        // Only purge slot on real dismissal, not config-change recreate.
        if (isFinishing && !isChangingConfigurations) {
            launchToken?.let { activeLaunches.remove(it) }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCREEN_ID = "appdna.screen.id"
        const val EXTRA_LAUNCH_TOKEN = "appdna.screen.launch_token"

        internal data class ScreenLaunchSlot(
            val config: ScreenConfig,
            val startedAtMs: Long,
            val onDismiss: ((ScreenResult) -> Unit)?,
        )

        private val activeLaunches = java.util.concurrent.ConcurrentHashMap<String, ScreenLaunchSlot>()

        /**
         * SPEC-070-A finalization R3 P0 (Lens D) — clear all in-flight slot
         * captures so SDK shutdown doesn't leak ScreenConfig + onDismiss
         * closures. Called from `AppDNA.shutdown()`. Mirrors the same call
         * shape as PaywallActivity / SurveyActivity static-map cleanup.
         */
        @JvmStatic
        internal fun clearActiveLaunches() {
            activeLaunches.clear()
        }

        @JvmStatic
        fun launch(
            context: Context,
            screenId: String,
            config: ScreenConfig,
            onDismiss: ((ScreenResult) -> Unit)? = null,
        ) {
            val token = java.util.UUID.randomUUID().toString()
            activeLaunches[token] = ScreenLaunchSlot(
                config = config,
                startedAtMs = System.currentTimeMillis(),
                onDismiss = onDismiss,
            )
            val intent = Intent(context, ScreenHostActivity::class.java).apply {
                putExtra(EXTRA_SCREEN_ID, screenId)
                putExtra(EXTRA_LAUNCH_TOKEN, token)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ScreenHostBody(
    config: ScreenConfig,
    isDark: Boolean,
    onDismiss: () -> Unit,
) {
    val activityContext = LocalContext.current
    val bgColor = config.background?.color?.let { StyleEngine.parseColor(it) }
        ?: MaterialTheme.colorScheme.background

    // SPEC-070-A finalization (Lens B P1) — fire haptic + particle effect on
    // present, mirroring iOS ScreenRenderer.swift:82-90 (ConfettiOverlay) +
    // 101-106 (HapticEngine.trigger). DTOs were parsed at ScreenConfig.kt:37-38
    // but no render path consumed them.
    val currentView = androidx.compose.ui.platform.LocalView.current
    val showParticlesState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val showParticles = showParticlesState.value
    androidx.compose.runtime.LaunchedEffect(config.id) {
        if (config.haptic?.onPresent == true) {
            val hapticType = ai.appdna.sdk.core.HapticType.fromString(config.haptic.type)
            if (hapticType != null) {
                ai.appdna.sdk.core.HapticEngine.trigger(currentView, hapticType)
            }
        }
        if (config.particleEffect?.onPresent == true) {
            showParticlesState.value = true
        }
    }

    // Build a SectionContext that routes section actions for the host screen.
    // Mirrors the dispatch in AppDNAScreenSlot but supports `Dismiss` since
    // the host activity owns dismissal. Veto via ScreenManager.handleScreenAction
    // so host delegates can intercept actions on the full-screen surface too.
    val sectionContext = SectionContext(
        screenId = config.id,
        onAction = { action ->
            val payload = mapOf<String, Any?>(
                "type" to (action::class.simpleName ?: "unknown")
            )
            // SPEC-070-C D10 — route through dispatchScreenAction so BOTH the
            // synchronous delegate veto AND the optional async wrapper-veto are
            // consulted before the action is performed. With no async veto
            // registered (native hosts) the `perform` lambda runs inline.
            ScreenManager.shared.dispatchScreenAction(config.id, payload) {
                when (action) {
                    is SectionAction.OpenURL -> {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action.url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activityContext.startActivity(intent)
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                    is SectionAction.DeepLink -> {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action.url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activityContext.startActivity(intent)
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                    is SectionAction.ShowScreen -> ScreenManager.shared.showScreen(action.id)
                    is SectionAction.ShowPaywall -> action.id?.let { AppDNA.showPaywall(it) }
                    is SectionAction.ShowSurvey -> action.id?.let { AppDNA.showSurvey(it) }
                    is SectionAction.Dismiss -> onDismiss()
                    else -> {}
                }
            }
        },
    )
    val safeAreaModifier = if (config.layout.safeArea != false) {
        Modifier.windowInsetsPadding(WindowInsets.systemBars)
    } else Modifier

    val mainSections = config.sections.filter {
        it.type != "sticky_footer" && it.type != "paywall_sticky_footer"
    }
    val stickyFooters = config.sections.filter {
        it.type == "sticky_footer" || it.type == "paywall_sticky_footer"
    }
    val spacing = (config.layout.spacing ?: 12.0).dp

    // SPEC-070-A finalization R4 P1 (Lens B) — presentation-mode container.
    // iOS ScreenPresenter routes via UIModalPresentationStyle:
    //   "fullscreen" → fillMaxSize + edge-to-edge
    //   "page_sheet" → top inset + rounded top + dim backdrop
    //   "bottom_sheet" → 70% height aligned bottom + grabber bar + dim backdrop
    // Android ScreenHostActivity uses Theme.Translucent for ALL modes, so the
    // visual differentiation lives in the Compose body's positioning + chrome.
    val presentation = config.presentation
    val isBottomSheet = presentation == "bottom_sheet"
    val isPageSheet = presentation == "page_sheet" || presentation == "modal"

    val outerModifier = Modifier
        .fillMaxSize()
        .let { mod ->
            // Dim backdrop for sheet presentations; fullscreen has no backdrop.
            if (isBottomSheet || isPageSheet) {
                mod.background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = { if (config.dismiss?.enabled != false) onDismiss() },
                    )
            } else mod
        }

    val bodyAlignment = when {
        isBottomSheet -> Alignment.BottomCenter
        isPageSheet -> Alignment.Center
        else -> Alignment.TopStart
    }

    Box(
        modifier = outerModifier,
        contentAlignment = bodyAlignment,
    ) {
        // Inner container holds the actual screen content. For sheets this is
        // a sized child; for fullscreen it fills.
        val innerSheetShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (isBottomSheet || isPageSheet) 16.dp else 0.dp,
            topEnd = if (isBottomSheet || isPageSheet) 16.dp else 0.dp,
            bottomStart = if (isPageSheet) 16.dp else 0.dp,
            bottomEnd = if (isPageSheet) 16.dp else 0.dp,
        )
        val innerModifier = when {
            isBottomSheet -> Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .clip(innerSheetShape)
                .background(bgColor)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                    onClick = {},
                )
                .then(safeAreaModifier)
            isPageSheet -> Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(innerSheetShape)
                .background(bgColor)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                    onClick = {},
                )
                .then(safeAreaModifier)
            else -> Modifier
                .fillMaxSize()
                .background(bgColor)
                .then(safeAreaModifier)
        }
    Box(
        modifier = innerModifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // SPEC-070-A finalization R4 P1 (Lens B) — bottom-sheet grabber
            // affordance. iOS pageSheet + bottom_sheet show a grabber pill at
            // the top edge by default; Android needs to draw one explicitly
            // since we host in a translucent Activity, not a BottomSheetDialog.
            if (isBottomSheet) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp, 4.dp)
                            .background(
                                color = Color.Gray.copy(alpha = 0.4f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                            ),
                    ) {}
                }
            }

            // Optional nav bar — title + close button.
            config.navBar?.let { nav ->
                val navBg = nav.backgroundColor?.let { StyleEngine.parseColor(it) }
                    ?: Color.Transparent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(navBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (nav.showBack == true) {
                        TextButton(onClick = onDismiss) { Text("←") }
                    } else Spacer(Modifier.padding(horizontal = 20.dp))
                    nav.title?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    if (nav.showClose == true || config.dismiss?.enabled == true) {
                        TextButton(onClick = onDismiss) { Text("✕") }
                    } else Spacer(Modifier.padding(horizontal = 20.dp))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (config.layout.type == "fixed") mod
                        else mod.verticalScroll(rememberScrollState())
                    },
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (section in mainSections) {
                    val animType = section.entranceAnimation?.type
                    val animDur = section.entranceAnimation?.durationMs
                    if (animType != null && animType != "none") {
                        Box(modifier = Modifier.entryAnimation(animType, animDur)) {
                            SectionRegistry.Render(section, sectionContext)
                        }
                    } else {
                        SectionRegistry.Render(section, sectionContext)
                    }
                }
            }
        }
        if (stickyFooters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (section in stickyFooters) {
                    SectionRegistry.Render(section, sectionContext)
                }
            }
        }
        // Confetti / particle overlay drawn last so it renders on top of all
        // sections + sticky footers. Mirrors iOS ScreenRenderer overlay layer.
        if (showParticles) {
            val pe = config.particleEffect
            if (pe != null) {
                ai.appdna.sdk.core.ConfettiOverlay(
                    effect = ai.appdna.sdk.core.ParticleEffect(
                        type = pe.type ?: "confetti",
                        trigger = "on_appear",
                        duration_ms = pe.durationMs ?: 2500,
                        intensity = pe.intensity ?: "medium",
                    ),
                    trigger = true,
                )
            }
        }
        } // close inner Box (sized presentation container)
    }
}
