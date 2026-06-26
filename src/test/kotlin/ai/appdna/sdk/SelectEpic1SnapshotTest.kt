package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SPEC-419 EPIC-1 (Select overhaul) — visual snapshot (surface #4: onboarding select).
 *
 * Renders a stacked input_select exercising the EPIC-1 per-option features:
 *   - leading_text + trailing_text on one row ("5 min/day … Easy")
 *   - a positionable badge ("RECOMMENDED")
 *   - per-option subtitle
 *
 * 100%-confirmation harness: Robolectric renders the REAL Compose UI on the JVM (no device,
 * no Firestore, no config cache); captureRoboImage writes the actual pixels. The golden is
 * written to a bridge-readable path so it can be pulled + reviewed. Run in record mode:
 *   ./gradlew recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectEpic1SnapshotTest {

    @Test
    fun stackedSelect_leadingTrailingBadge() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>(
                                "id" to "o1", "label" to "Casual",
                                "leading_text" to "5 min/day", "trailing_text" to "Easy",
                                "badge" to mapOf<String, Any>(
                                    "text" to "RECOMMENDED", "bg_color" to "#22C55E",
                                    "text_color" to "#FFFFFF", "position" to "top_trailing",
                                ),
                            ),
                            mapOf<String, Any>(
                                "id" to "o2", "label" to "Regular",
                                "leading_text" to "10 min/day", "trailing_text" to "Steady",
                            ),
                            mapOf<String, Any>(
                                "id" to "o3", "label" to "Serious", "subtitle" to "Big goals",
                                "leading_text" to "15 min/day", "trailing_text" to "Hard",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_epic1.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf(),
                    )
                }
            }
        }
    }

    @Test
    fun stackedSelect_centerAligned() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel2",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "c1", "label" to "Beginner", "subtitle" to "Just starting out", "text_alignment" to "center"),
                            mapOf<String, Any>("id" to "c2", "label" to "Intermediate", "subtitle" to "Some experience", "text_alignment" to "center"),
                            mapOf<String, Any>("id" to "c3", "label" to "Advanced", "subtitle" to "Very experienced", "text_alignment" to "center"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_center.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf(),
                    )
                }
            }
        }
    }

    @Test
    fun stackedSelect_imageOverlay() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel3",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "i1", "label" to "Circle", "image_url" to "https://example.com/a.png", "image_overlay_color" to "#FF5722", "image_overlay_opacity" to 0.85),
                            mapOf<String, Any>("id" to "i2", "label" to "Rounded", "image_url" to "https://example.com/b.png", "image_shape" to "rounded", "image_overlay_color" to "#2196F3", "image_overlay_opacity" to 0.85),
                            mapOf<String, Any>("id" to "i3", "label" to "Square", "image_url" to "https://example.com/c.png", "image_shape" to "square", "image_overlay_color" to "#22C55E", "image_overlay_opacity" to 0.85),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_overlay.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf(),
                    )
                }
            }
        }
    }

    @Test
    fun stackedSelect_selectedState() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel4",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_style" to mapOf<String, Any>("fill_color" to "#22C55E"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "a", "value" to "a", "label" to "Casual", "subtitle" to "Easy pace"),
                            mapOf<String, Any>("id" to "b", "value" to "b", "label" to "Regular", "subtitle" to "Recommended"),
                            mapOf<String, Any>("id" to "c", "value" to "c", "label" to "Serious", "subtitle" to "Intense"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_selected.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf("sel4" to "b"),
                    )
                }
            }
        }
    }

    @Test
    fun stackedSelect_selectedImageTint() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel5",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "p", "value" to "p", "label" to "Picked", "image_url" to "https://example.com/a.png", "image_overlay_color" to "#9CA3AF", "selected_image_overlay_color" to "#22C55E", "image_overlay_opacity" to 0.85, "selected_image_overlay_opacity" to 0.85),
                            mapOf<String, Any>("id" to "q", "value" to "q", "label" to "Other", "image_url" to "https://example.com/b.png", "image_overlay_color" to "#9CA3AF", "image_overlay_opacity" to 0.85),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_sel_imgtint.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf("sel5" to "p"),
                    )
                }
            }
        }
    }

    @Test
    fun imageTiles() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "tiles1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "image_tiles", "grid_columns" to 2),
                        "field_style" to mapOf<String, Any>("fill_color" to "#FACC15"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "run", "value" to "run", "label" to "Running", "image_url" to "https://example.com/a.png", "image_overlay_color" to "#E11D48", "image_overlay_opacity" to 0.9),
                            mapOf<String, Any>("id" to "lift", "value" to "lift", "label" to "Lifting", "image_url" to "https://example.com/b.png", "image_overlay_color" to "#2563EB", "image_overlay_opacity" to 0.9),
                            mapOf<String, Any>("id" to "yoga", "value" to "yoga", "label" to "Yoga", "image_url" to "https://example.com/c.png", "image_overlay_color" to "#7C3AED", "image_overlay_opacity" to 0.9),
                            mapOf<String, Any>("id" to "swim", "value" to "swim", "label" to "Swimming", "image_url" to "https://example.com/d.png", "image_overlay_color" to "#059669", "image_overlay_opacity" to 0.9),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_tiles.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf("tiles1" to "lift"),
                    )
                }
            }
        }
    }

    @Test
    fun bubbleChips() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "bubble1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "bubble"),
                        "field_style" to mapOf<String, Any>("fill_color" to "#22C55E", "text_color" to "#FFFFFF"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "running", "value" to "running", "label" to "Running"),
                            mapOf<String, Any>("id" to "yoga", "value" to "yoga", "label" to "Yoga"),
                            mapOf<String, Any>("id" to "cycling", "value" to "cycling", "label" to "Cycling"),
                            mapOf<String, Any>("id" to "swimming", "value" to "swimming", "label" to "Swimming"),
                            mapOf<String, Any>("id" to "boxing", "value" to "boxing", "label" to "Boxing"),
                            mapOf<String, Any>("id" to "pilates", "value" to "pilates", "label" to "Pilates"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_bubble.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf("bubble1" to "running"),
                    )
                }
            }
        }
    }
}
