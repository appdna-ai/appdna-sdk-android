package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.AudienceRuleEvaluator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A Composable that renders a server-driven screen's sections inline.
 * Growth teams assign screens to named slots from the console.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun HomeScreen() {
 *     Column {
 *         AppDNAScreenSlot("home_hero")
 *         // ... app content ...
 *         AppDNAScreenSlot("home_bottom")
 *     }
 * }
 * ```
 */
@Composable
fun AppDNAScreenSlot(name: String) {
    var screenConfig by remember { mutableStateOf<ScreenConfig?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        // Track slot registration
        AppDNA.track("slot_registered", mapOf("slot_name" to name, "platform" to "android"))

        // Check consent
        if (!AppDNA.isConsentGranted()) {
            isLoading = false
            isEmpty = true
            return@LaunchedEffect
        }

        // Look up slot assignment
        val result = ScreenManager.shared.screenForSlot(name)
        if (result != null) {
            val (screenId, config) = result
            screenConfig = config
            isLoading = false

            AppDNA.track("slot_rendered", mapOf(
                "slot_name" to name,
                "screen_id" to screenId,
                "screen_name" to config.name,
            ))
        } else {
            isEmpty = true
            isLoading = false

            AppDNA.track("slot_empty", mapOf("slot_name" to name))
        }
    }

    when {
        isLoading -> {
            // Shimmer placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Gray.copy(alpha = 0.1f)),
            )
        }
        screenConfig != null -> {
            val config = screenConfig ?: return
            val slotConfig = config.slotConfig
            val maxHeight = slotConfig?.maxHeight

            val context = SectionContext(
                screenId = config.id,
                onAction = { action ->
                    when (action) {
                        is SectionAction.OpenURL -> { /* open URL */ }
                        is SectionAction.DeepLink -> { /* deep link */ }
                        is SectionAction.ShowScreen -> ScreenManager.shared.showScreen(action.id)
                        is SectionAction.ShowPaywall -> action.id?.let { AppDNA.showPaywall(it) }
                        else -> {}
                    }
                },
            )

            val modifier = Modifier.fillMaxWidth().let { mod ->
                if (maxHeight != null) mod.heightIn(max = maxHeight.dp) else mod
            }.let { mod ->
                if (slotConfig?.presentation == "overlay" || slotConfig?.tapToExpand == true) {
                    mod.clickable {
                        ScreenManager.shared.showScreen(config.id)
                    }
                } else mod
            }

            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy((config.layout.spacing ?: 12.0).dp)) {
                for (section in config.sections) {
                    SectionRegistry.Render(section, context)
                }
            }
        }
        isEmpty -> {
            // Empty slot renders nothing (AC-040c)
        }
    }
}
