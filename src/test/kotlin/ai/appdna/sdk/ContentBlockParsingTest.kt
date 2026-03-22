package ai.appdna.sdk

import ai.appdna.sdk.onboarding.OnboardingConfigParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that ContentBlock correctly parses from Firestore Map data.
 * Prevents field name mismatches between editor JSON and SDK parsing.
 */
class ContentBlockParsingTest {

    private fun parseBlocks(vararg blocks: Map<String, Any>): List<ai.appdna.sdk.onboarding.ContentBlock> {
        val stepMap = mapOf<String, Any>(
            "type" to "custom",
            "name" to "test",
            "analytics_name" to "test",
            "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to blocks.toList()
            )
        )
        val step = OnboardingConfigParser.parseStepForTest(stepMap)
        return step?.config?.content_blocks ?: emptyList()
    }

    // MARK: - Unknown block type doesn't crash (AC-002)

    @Test
    fun unknownBlockTypeRendersEmpty() {
        val blocks = parseBlocks(
            mapOf("id" to "b1", "type" to "future_block_type_2030")
        )
        assertEquals(1, blocks.size)
        assertEquals("future_block_type_2030", blocks[0].type)
    }

    // MARK: - Basic block types

    @Test
    fun parseHeadingBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "h1",
                "type" to "heading",
                "text" to "Welcome",
                "level" to 1,
                "style" to mapOf("font_size" to 28, "font_weight" to 700, "color" to "#ffffff")
            )
        )
        assertEquals(1, blocks.size)
        assertEquals("heading", blocks[0].type)
        assertEquals("Welcome", blocks[0].text)
        assertEquals(1, blocks[0].level)
    }

    @Test
    fun parseButtonBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "btn1",
                "type" to "button",
                "text" to "Continue",
                "variant" to "primary",
                "action" to "next",
                "bg_color" to "#6366f1",
                "text_color" to "#ffffff",
                "button_corner_radius" to 12
            )
        )
        assertEquals("button", blocks[0].type)
        assertEquals("Continue", blocks[0].text)
        assertEquals("primary", blocks[0].variant)
        assertEquals("#6366f1", blocks[0].bg_color)
        assertEquals(12.0, blocks[0].button_corner_radius ?: 0.0, 0.01)
    }

    @Test
    fun parseImageBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "img1",
                "type" to "image",
                "image_url" to "https://example.com/photo.jpg",
                "height" to 200,
                "corner_radius" to 16
            )
        )
        assertEquals("image", blocks[0].type)
        assertEquals("https://example.com/photo.jpg", blocks[0].image_url)
        assertEquals(200.0, blocks[0].height ?: 0.0, 0.01)
    }

    // MARK: - SPEC-089d new block types

    @Test
    fun parsePageIndicator() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pi1",
                "type" to "page_indicator",
                "dot_count" to 5,
                "active_index" to 2,
                "active_color" to "#6366f1",
                "inactive_color" to "#d1d5db",
                "dot_size" to 8,
                "dot_spacing" to 6,
                "active_dot_width" to 24
            )
        )
        assertEquals("page_indicator", blocks[0].type)
        assertEquals(5, blocks[0].dot_count)
        assertEquals(2, blocks[0].active_index)
        assertEquals("#6366f1", blocks[0].active_color)
        assertEquals(8.0, blocks[0].dot_size ?: 0.0, 0.01)
    }

    @Test
    fun parseSocialLogin() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "sl1",
                "type" to "social_login",
                "providers" to listOf(
                    mapOf("type" to "apple", "enabled" to true),
                    mapOf("type" to "google", "enabled" to true),
                    mapOf("type" to "email", "label" to "Continue with Email", "enabled" to true)
                ),
                "button_style" to "filled",
                "button_height" to 52,
                "show_divider" to true,
                "divider_text" to "or continue with"
            )
        )
        assertEquals("social_login", blocks[0].type)
        assertEquals(3, blocks[0].providers?.size)
        assertEquals("apple", blocks[0].providers?.get(0)?.type)
        assertEquals("Continue with Email", blocks[0].providers?.get(2)?.label)
        assertEquals("filled", blocks[0].button_style)
        assertEquals(true, blocks[0].show_divider)
    }

    @Test
    fun parseCountdownTimer() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "ct1",
                "type" to "countdown_timer",
                "duration_seconds" to 900,
                "show_hours" to true,
                "show_minutes" to true,
                "show_seconds" to true,
                "on_expire_action" to "auto_advance",
                "accent_color" to "#ef4444"
            )
        )
        assertEquals("countdown_timer", blocks[0].type)
        assertEquals(900, blocks[0].duration_seconds)
        assertEquals("auto_advance", blocks[0].on_expire_action)
    }

    @Test
    fun parseRating() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "r1",
                "type" to "rating",
                "max_stars" to 5,
                "star_size" to 32,
                "filled_color" to "#f59e0b",
                "empty_color" to "#d1d5db",
                "allow_half" to true,
                "field_id" to "satisfaction"
            )
        )
        assertEquals("rating", blocks[0].type)
        assertEquals(5, blocks[0].max_stars)
        // Editor writes "filled_color", Android maps to active_rating_color
        assertEquals("#f59e0b", blocks[0].active_rating_color)
        assertEquals(true, blocks[0].allow_half)
    }

    @Test
    fun parseTimeline() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "tl1",
                "type" to "timeline",
                "timeline_items" to listOf(
                    mapOf("id" to "s1", "title" to "Sign up", "subtitle" to "Create account", "status" to "completed"),
                    mapOf("id" to "s2", "title" to "Profile", "status" to "current"),
                    mapOf("id" to "s3", "title" to "Ready!", "status" to "upcoming")
                ),
                "line_color" to "#e5e7eb",
                "completed_color" to "#10b981",
                "show_line" to true
            )
        )
        assertEquals("timeline", blocks[0].type)
        assertEquals(3, blocks[0].timeline_items?.size)
        assertEquals("completed", blocks[0].timeline_items?.get(0)?.status)
        assertEquals("Profile", blocks[0].timeline_items?.get(1)?.title)
        assertEquals(true, blocks[0].show_line)
    }

    @Test
    fun parseProgressBar() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pb1",
                "type" to "progress_bar",
                "progress_variant" to "segmented",
                "total_segments" to 5,
                "filled_segments" to 3,
                "bar_height" to 6,
                "bar_color" to "#6366f1",
                "track_color" to "#e5e7eb",
                "show_label" to true,
                "segment_gap" to 4
            )
        )
        assertEquals("progress_bar", blocks[0].type)
        assertEquals(5, blocks[0].segment_count)
        assertEquals(3, blocks[0].active_segments)
    }

    // MARK: - Block Style Design Tokens (AC-005 through AC-009)

    @Test
    fun parseBlockStyle() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "styled1",
                "type" to "text",
                "text" to "Styled",
                "block_style" to mapOf(
                    "background_color" to "#1a1a2e",
                    "border_color" to "#374151",
                    "border_width" to 1,
                    "border_radius" to 12,
                    "shadow" to mapOf("x" to 0, "y" to 4, "blur" to 12, "spread" to 0, "color" to "rgba(0,0,0,0.15)"),
                    "padding_top" to 16,
                    "padding_right" to 16,
                    "padding_bottom" to 16,
                    "padding_left" to 16,
                    "margin_top" to 8,
                    "opacity" to 0.95
                )
            )
        )
        val bs = blocks[0].block_style
        assertNotNull(bs)
        assertEquals("#1a1a2e", bs?.background_color)
        assertEquals("#374151", bs?.border_color)
        assertEquals(1.0, bs?.border_width ?: 0.0, 0.01)
        assertEquals(12.0, bs?.border_radius ?: 0.0, 0.01)
        assertNotNull(bs?.shadow)
        assertEquals(12.0, bs?.shadow?.blur ?: 0.0, 0.01)
        assertEquals(16.0, bs?.padding_top ?: 0.0, 0.01)
        assertEquals(0.95, bs?.opacity ?: 0.0, 0.01)
    }

    // MARK: - 2D Positioning + Relative Sizing

    @Test
    fun parsePositioningAndSizing() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pos1",
                "type" to "button",
                "text" to "Centered",
                "vertical_align" to "center",
                "horizontal_align" to "center",
                "vertical_offset" to 10,
                "horizontal_offset" to -5
            )
        )
        assertEquals("center", blocks[0].vertical_align)
        assertEquals("center", blocks[0].horizontal_align)
        assertEquals(10.0, blocks[0].vertical_offset ?: 0.0, 0.01)
        assertEquals(-5.0, blocks[0].horizontal_offset ?: 0.0, 0.01)
    }

    // MARK: - Form Input Blocks (AC-040 through AC-053)

    @Test
    fun parseFormInputText() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fi1",
                "type" to "input_text",
                "field_id" to "full_name",
                "field_label" to "Your Name",
                "field_placeholder" to "Enter your name",
                "field_required" to true
            )
        )
        assertEquals("input_text", blocks[0].type)
        assertEquals("full_name", blocks[0].field_id)
        assertEquals("Your Name", blocks[0].field_label)
        assertEquals(true, blocks[0].field_required)
    }

    @Test
    fun parseFormInputSelect() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fi2",
                "type" to "input_select",
                "field_id" to "gender",
                "field_label" to "Gender",
                "field_options" to listOf(
                    mapOf("id" to "m", "label" to "Male"),
                    mapOf("id" to "f", "label" to "Female")
                )
            )
        )
        assertEquals("input_select", blocks[0].type)
        assertEquals(2, blocks[0].field_options?.size)
        assertEquals("m", blocks[0].field_options?.get(0)?.value)
    }

    // MARK: - Minimal block doesn't crash

    @Test
    fun parseMinimalBlock() {
        val blocks = parseBlocks(
            mapOf("id" to "min1", "type" to "spacer")
        )
        assertEquals("spacer", blocks[0].type)
        assertNull(blocks[0].text)
        assertNull(blocks[0].block_style)
    }

    // MARK: - Extra unknown fields don't crash

    @Test
    fun parseBlockWithUnknownFields() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "extra1",
                "type" to "text",
                "text" to "Hello",
                "future_field_2030" to "some value",
                "another_unknown" to 42
            )
        )
        assertEquals("text", blocks[0].type)
        assertEquals("Hello", blocks[0].text)
    }
}
