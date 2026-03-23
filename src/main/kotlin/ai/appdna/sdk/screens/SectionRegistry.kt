package ai.appdna.sdk.screens

import androidx.compose.runtime.Composable
import ai.appdna.sdk.screens.sections.*

internal object SectionRegistry {
    private val renderers = mutableMapOf<String, @Composable (ScreenSection, SectionContext) -> Unit>()

    fun register(type: String, renderer: @Composable (ScreenSection, SectionContext) -> Unit) {
        renderers[type] = renderer
    }

    @Composable
    fun Render(section: ScreenSection, context: SectionContext) {
        val renderer = renderers[section.type]
        if (renderer != null) {
            renderer(section, context)
        }
        // Unknown sections render nothing (AC-066, AC-089)
    }

    fun hasRenderer(type: String): Boolean = renderers.containsKey(type)

    fun registerBuiltInSections() {
        register("content_blocks") { section, context -> ContentBlocksSectionRenderer(section, context) }
        register("hero") { section, context -> HeroSectionRenderer(section, context) }
        register("spacer") { section, context -> SpacerSectionRenderer(section, context) }
        register("divider") { section, context -> DividerSectionRenderer(section, context) }
        register("cta_footer") { section, context -> CTAFooterSectionRenderer(section, context) }
        register("sticky_footer") { section, context -> CTAFooterSectionRenderer(section, context) }
        register("image_section") { section, context -> ImageSectionRenderer(section, context) }
        register("video_section") { section, context -> VideoSectionRenderer(section, context) }
        register("lottie_section") { section, context -> LottieSectionRenderer(section, context) }
        register("rive_section") { section, context -> RiveSectionRenderer(section, context) }
    }
}
