package ai.appdna.sdk.screens.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.screens.SectionAction
import ai.appdna.sdk.screens.SectionContext
import ai.appdna.sdk.screens.ScreenSection
import ai.appdna.sdk.screens.SectionRegistry

// Paywall section wrapper — delegates to a placeholder that shows section type and basic data
@Composable
internal fun PaywallSectionWrapper(section: ScreenSection, context: SectionContext) {
    Column(modifier = Modifier.padding(16.dp)) {
        val title = section.data["title"] as? String
        val subtitle = section.data["subtitle"] as? String
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Survey section wrapper
@Composable
internal fun SurveySectionWrapper(section: ScreenSection, context: SectionContext) {
    Column(modifier = Modifier.padding(16.dp)) {
        val text = section.data["text"] as? String
        if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// Message section wrapper
@Composable
internal fun MessageSectionWrapper(section: ScreenSection, context: SectionContext) {
    Column(modifier = Modifier.padding(16.dp)) {
        val title = section.data["title"] as? String
        val body = section.data["body"] as? String
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

// Onboarding section wrapper
@Composable
internal fun OnboardingSectionWrapper(section: ScreenSection, context: SectionContext) {
    when (section.type) {
        "progress_indicator" -> {
            val current = (section.data["current"] as? Number)?.toInt() ?: context.currentScreenIndex
            val total = (section.data["total"] as? Number)?.toInt() ?: context.totalScreens
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(total) { i ->
                    Box(
                        modifier = Modifier.weight(1f).height(4.dp)
                            .then(
                                if (i <= current)
                                    Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                else
                                    Modifier.background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                            ),
                    )
                }
            }
        }
        "navigation_controls" -> {
            val showBack = section.data["show_back"] as? Boolean ?: true
            val ctaText = section.data["cta_text"] as? String ?: "Next"
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) TextButton(onClick = { context.onAction(SectionAction.Back) }) { Text("Back") }
                else Spacer(Modifier)
                Button(onClick = { context.onAction(SectionAction.Next) }) { Text(ctaText) }
            }
        }
        else -> { /* onboarding_step or unknown */ }
    }
}

// Extension to register all module sections
internal fun SectionRegistry.registerModuleSections() {
    val paywallTypes = listOf(
        "paywall_header", "paywall_features", "paywall_plans", "paywall_cta",
        "paywall_social_proof", "paywall_guarantee", "paywall_testimonial",
        "paywall_countdown", "paywall_legal", "paywall_comparison",
        "paywall_promo", "paywall_reviews", "paywall_toggle",
        "paywall_icon_grid", "paywall_carousel", "paywall_card",
        "paywall_timeline", "paywall_image", "paywall_video",
        "paywall_lottie", "paywall_rive", "paywall_spacer",
        "paywall_divider", "paywall_sticky_footer",
    )
    for (type in paywallTypes) register(type) { s, c -> PaywallSectionWrapper(s, c) }

    val surveyTypes = listOf("survey_question", "survey_nps", "survey_csat", "survey_rating", "survey_free_text", "survey_thank_you")
    for (type in surveyTypes) register(type) { s, c -> SurveySectionWrapper(s, c) }

    val messageTypes = listOf("message_banner", "message_modal", "message_content")
    for (type in messageTypes) register(type) { s, c -> MessageSectionWrapper(s, c) }

    val onboardingTypes = listOf("onboarding_step", "progress_indicator", "navigation_controls")
    for (type in onboardingTypes) register(type) { s, c -> OnboardingSectionWrapper(s, c) }
}
