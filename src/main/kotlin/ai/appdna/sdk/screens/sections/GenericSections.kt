package ai.appdna.sdk.screens.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.screens.ScreenSection
import ai.appdna.sdk.screens.SectionAction
import ai.appdna.sdk.screens.SectionContext
import ai.appdna.sdk.onboarding.RenderBlock
import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.core.StyleEngine

@Composable
internal fun ContentBlocksSectionRenderer(section: ScreenSection, context: SectionContext) {
    @Suppress("UNCHECKED_CAST")
    val blocksData = section.data["blocks"] as? List<Map<String, Any?>> ?: return
    val blocks = blocksData.map { map ->
        ContentBlock(
            id = map["id"] as? String ?: "",
            type = map["type"] as? String ?: "text",
            text = map["text"] as? String,
        )
    }
    val spacing = (section.data["spacing"] as? Number)?.toDouble() ?: 12.0
    val toggleValues = remember { mutableStateMapOf<String, Boolean>() }
    val inputValues = remember { mutableStateMapOf<String, Any>() }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        for (block in blocks) {
            RenderBlock(
                block = block,
                onAction = { action ->
                    when (action) {
                        "next" -> context.onAction(SectionAction.Next)
                        "dismiss" -> context.onAction(SectionAction.Dismiss)
                        "back" -> context.onAction(SectionAction.Back)
                        else -> context.onAction(SectionAction.Custom(action, null))
                    }
                },
                toggleValues = toggleValues,
                inputValues = inputValues,
                currentStepIndex = context.currentScreenIndex,
                totalSteps = context.totalScreens,
            )
        }
    }
}

@Composable
internal fun HeroSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 300.0

    Box(
        modifier = Modifier.fillMaxWidth().height(height.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Background placeholder
        Box(modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.1f)))

        // Overlay content (simplified)
        @Suppress("UNCHECKED_CAST")
        val overlayData = section.data["overlay_blocks"] as? List<Map<String, Any?>>
        if (overlayData != null) {
            Column(modifier = Modifier.padding(16.dp)) {
                val blocks = overlayData.map { map ->
                    ContentBlock(
                        id = map["id"] as? String ?: "",
                        type = map["type"] as? String ?: "text",
                        text = map["text"] as? String,
                    )
                }
                val toggleValues = remember { mutableStateMapOf<String, Boolean>() }
                val inputValues = remember { mutableStateMapOf<String, Any>() }
                for (block in blocks) {
                    RenderBlock(block, { context.onAction(SectionAction.Custom(it, null)) }, toggleValues, inputValues)
                }
            }
        }
    }
}

@Composable
internal fun SpacerSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 16.0
    Spacer(modifier = Modifier.height(height.dp))
}

@Composable
internal fun DividerSectionRenderer(section: ScreenSection, context: SectionContext) {
    val color = section.data["color"] as? String
    val thickness = (section.data["thickness"] as? Number)?.toDouble() ?: 1.0
    val insetLeft = (section.data["inset_left"] as? Number)?.toDouble() ?: 0.0
    val insetRight = (section.data["inset_right"] as? Number)?.toDouble() ?: 0.0

    HorizontalDivider(
        modifier = Modifier.padding(start = insetLeft.dp, end = insetRight.dp),
        thickness = thickness.dp,
        color = if (color != null) StyleEngine.parseColor(color) else Color.Gray.copy(alpha = 0.3f),
    )
}

@Composable
internal fun CTAFooterSectionRenderer(section: ScreenSection, context: SectionContext) {
    @Suppress("UNCHECKED_CAST")
    val primaryButton = section.data["primary_button"] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val secondaryButton = section.data["secondary_button"] as? Map<String, Any?>
    val disclaimerText = section.data["disclaimer_text"] as? String

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (primaryButton != null) {
            Button(
                onClick = { handleButtonAction(primaryButton, context) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(primaryButton["text"] as? String ?: "Continue")
            }
        }

        if (secondaryButton != null) {
            TextButton(onClick = { handleButtonAction(secondaryButton, context) }) {
                Text(secondaryButton["text"] as? String ?: "Skip")
            }
        }

        if (disclaimerText != null) {
            Text(
                text = disclaimerText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun handleButtonAction(button: Map<String, Any?>, context: SectionContext) {
    val action = button["action"] as? String ?: "next"
    val actionValue = button["action_value"] as? String

    when (action) {
        "next" -> context.onAction(SectionAction.Next)
        "dismiss" -> context.onAction(SectionAction.Dismiss)
        "back" -> context.onAction(SectionAction.Back)
        "open_url" -> actionValue?.let { context.onAction(SectionAction.OpenURL(it)) }
        "open_in_webview" -> actionValue?.let { context.onAction(SectionAction.OpenWebview(it)) }
        "deep_link" -> actionValue?.let { context.onAction(SectionAction.DeepLink(it)) }
        "show_paywall" -> context.onAction(SectionAction.ShowPaywall(actionValue))
        "show_survey" -> context.onAction(SectionAction.ShowSurvey(actionValue))
        "show_screen" -> actionValue?.let { context.onAction(SectionAction.ShowScreen(it)) }
        "share" -> context.onAction(SectionAction.Share(actionValue ?: ""))
        "open_app_settings" -> context.onAction(SectionAction.OpenAppSettings)
        else -> context.onAction(SectionAction.Custom(action, actionValue))
    }
}

@Composable
internal fun ImageSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 200.0
    val cornerRadius = (section.data["corner_radius"] as? Number)?.toDouble() ?: 0.0

    Box(
        modifier = Modifier.fillMaxWidth().height(height.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(Color.Gray.copy(alpha = 0.1f)),
    )
}

@Composable
internal fun VideoSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 200.0
    Box(modifier = Modifier.fillMaxWidth().height(height.dp).background(Color.Black))
}

@Composable
internal fun LottieSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 200.0
    Box(modifier = Modifier.fillMaxWidth().height(height.dp).background(Color.Gray.copy(alpha = 0.05f)))
}

@Composable
internal fun RiveSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 200.0
    Box(modifier = Modifier.fillMaxWidth().height(height.dp).background(Color.Gray.copy(alpha = 0.05f)))
}
