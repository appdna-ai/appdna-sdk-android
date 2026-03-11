package ai.appdna.sdk.paywalls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.TextStyle

/**
 * Activity to render paywall UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class PaywallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // Notify appearance
        onAppear?.invoke()

        setContent {
            MaterialTheme {
                PaywallScreen(
                    config = config,
                    onPlanSelected = { plan ->
                        onPlanSelected?.invoke(plan)
                    },
                    onRestore = {
                        // Restore action - tracked by the caller
                    },
                    onDismiss = { reason ->
                        onDismiss?.invoke(reason)
                        cleanup()
                    }
                )
            }
        }
    }

    private fun cleanup() {
        pendingConfig = null
        pendingOnAppear = null
        pendingOnDismiss = null
        pendingOnPlanSelected = null
        finish()
    }

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        pendingOnDismiss?.invoke(DismissReason.DISMISSED)
        cleanup()
    }

    companion object {
        private const val EXTRA_PAYWALL_ID = "paywall_id"
        private var pendingConfig: PaywallConfig? = null
        private var pendingOnAppear: (() -> Unit)? = null
        private var pendingOnDismiss: ((DismissReason) -> Unit)? = null
        private var pendingOnPlanSelected: ((PaywallPlan) -> Unit)? = null

        fun launch(
            context: Context,
            paywallId: String,
            config: PaywallConfig,
            paywallContext: PaywallContext? = null,
            onAppear: (() -> Unit)? = null,
            onDismiss: ((DismissReason) -> Unit)? = null,
            onPlanSelected: ((PaywallPlan) -> Unit)? = null
        ) {
            pendingConfig = config
            pendingOnAppear = onAppear
            pendingOnDismiss = onDismiss
            pendingOnPlanSelected = onPlanSelected
            val intent = Intent(context, PaywallActivity::class.java).apply {
                putExtra(EXTRA_PAYWALL_ID, paywallId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun PaywallScreen(
    config: PaywallConfig,
    onPlanSelected: (PaywallPlan) -> Unit,
    onRestore: () -> Unit,
    onDismiss: (DismissReason) -> Unit
) {
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var showDismiss by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

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

    // Select default plan on appear
    LaunchedEffect(Unit) {
        val plans = config.sections
            .firstOrNull { it.type == "plans" }
            ?.data?.plans
        selectedPlanId = plans?.firstOrNull { it.is_default == true }?.id
            ?: plans?.firstOrNull()?.id

        // Handle dismiss delay
        val delay = config.dismiss?.delay_seconds ?: 0
        if (delay > 0) {
            kotlinx.coroutines.delay(delay * 1000L)
            showDismiss = true
        } else {
            showDismiss = true
        }
    }

    // Localization helper
    fun loc(key: String, fallback: String): String {
        return LocalizationEngine.resolve(key, config.localizations, config.default_locale, fallback)
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

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding((config.layout.padding ?: 20f).dp)
        ) {
            val staggerDelay = config.animation?.section_stagger_delay_ms ?: 0
            config.sections.forEachIndexed { index, section ->
                Box(
                    modifier = Modifier.sectionStagger(
                        config.animation?.section_stagger,
                        delayMs = staggerDelay * index,
                    )
                ) {
                    PaywallSectionView(
                        section = section,
                        config = config,
                        selectedPlanId = selectedPlanId,
                        isPurchasing = isPurchasing,
                        onPlanSelect = { selectedPlanId = it },
                        onCTATap = {
                            val plans = config.sections
                                .firstOrNull { s -> s.type == "plans" }
                                ?.data?.plans
                            val plan = plans?.firstOrNull { p -> p.id == selectedPlanId }
                            if (plan != null) {
                                isPurchasing = true
                                onPlanSelected(plan)
                            }
                        },
                        onRestore = onRestore,
                        loc = ::loc,
                    )
                }
                Spacer(modifier = Modifier.height((config.layout.spacing ?: 16f).dp))
            }
        }

        // Dismiss control
        if (showDismiss) {
            val dismissType = config.dismiss?.type ?: "x_button"
            when (dismissType) {
                "text_link" -> {
                    TextButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
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
                    IconButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
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
    when (background?.type) {
        "gradient" -> {
            val colors = background.colors?.map { parseHexColor(it) }
            if (colors != null && colors.size >= 2) {
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors)))
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
        "image" -> {
            Box(modifier = Modifier.fillMaxSize()) {
                background.value?.let { url ->
                    ai.appdna.sdk.core.NetworkImage(
                        url = url,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } ?: Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
        "color" -> {
            val color = background.value?.let { parseHexColor(it) } ?: Color.Black
            Box(modifier = Modifier.fillMaxSize().background(color))
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
}

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
            val layoutType = config.layout.type
            // SPEC-084: Grid / carousel / stack plan layouts
            if (layoutType == "carousel") {
                // Horizontally scrollable plan cards
                val plans = section.data?.plans ?: emptyList()
                val carouselPlanNameStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp),
                    section.style?.elements?.get("plan_name")?.text_style
                )
                val carouselPriceStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp),
                    section.style?.elements?.get("price")?.text_style
                )
                val carouselPeriodStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)),
                    section.style?.elements?.get("period")?.text_style
                )
                val carouselBadgeStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontSize = 11.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold),
                    section.style?.elements?.get("badge")?.text_style
                )
                @OptIn(ExperimentalFoundationApi::class)
                Column(modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }) {
                    val pagerState = rememberPagerState(
                        initialPage = plans.indexOfFirst { it.id == selectedPlanId }.coerceAtLeast(0),
                        pageCount = { plans.size }
                    )
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        pageSpacing = 12.dp,
                    ) { planIdx ->
                        val plan = plans[planIdx]
                        val isSelected = selectedPlanId == plan.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, Color(0xFF6366F1), RoundedCornerShape(12.dp)) else Modifier
                                )
                                .clickable { onPlanSelect(plan.id) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = loc("plan.$planIdx.name", plan.name), style = carouselPlanNameStyle)
                                Spacer(Modifier.height(4.dp))
                                Text(text = loc("plan.$planIdx.price", plan.price), style = carouselPriceStyle)
                                plan.period?.let {
                                    Text(text = loc("plan.$planIdx.period", it), style = carouselPeriodStyle)
                                }
                                plan.badge?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(text = loc("plan.$planIdx.badge", it), style = carouselBadgeStyle)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(loc("restore.text", "Restore Purchases"), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            } else if (layoutType == "grid") {
                // 2-column grid
                val plans = section.data?.plans ?: emptyList()
                val chunked = plans.chunked(2)
                val gridPlanNameStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp),
                    section.style?.elements?.get("plan_name")?.text_style
                )
                val gridPriceStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp),
                    section.style?.elements?.get("price")?.text_style
                )
                val gridPeriodStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)),
                    section.style?.elements?.get("period")?.text_style
                )
                val gridBadgeStyle = StyleEngine.applyTextStyle(
                    TextStyle(fontSize = 11.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold),
                    section.style?.elements?.get("badge")?.text_style
                )
                Column(modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }) {
                    chunked.forEachIndexed { chunkIdx, row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { colIdx, plan ->
                                val planIdx = chunkIdx * 2 + colIdx
                                val isSelected = selectedPlanId == plan.id
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 4.dp)
                                        .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, Color(0xFF6366F1), RoundedCornerShape(12.dp)) else Modifier
                                        )
                                        .clickable { onPlanSelect(plan.id) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(text = loc("plan.$planIdx.name", plan.name), style = gridPlanNameStyle)
                                        Text(text = loc("plan.$planIdx.price", plan.price), style = gridPriceStyle)
                                        plan.period?.let { Text(text = loc("plan.$planIdx.period", it), style = gridPeriodStyle) }
                                        plan.badge?.let { Text(text = loc("plan.$planIdx.badge", it), style = gridBadgeStyle) }
                                    }
                                }
                            }
                            // Fill empty slot in last row
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(loc("restore.text", "Restore Purchases"), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            } else {
            // Default stack layout
            val stackPlanNameStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White),
                section.style?.elements?.get("plan_name")?.text_style
            )
            val stackPriceStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.Bold, color = Color.White),
                section.style?.elements?.get("price")?.text_style
            )
            val stackPeriodStyle = StyleEngine.applyTextStyle(
                TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)),
                section.style?.elements?.get("period")?.text_style
            )
            val stackBadgeStyle = StyleEngine.applyTextStyle(
                TextStyle(fontSize = 11.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold),
                section.style?.elements?.get("badge")?.text_style
            )
            Column(modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }) {
                section.data?.plans?.forEachIndexed { planIdx, plan ->
                    val isSelected = selectedPlanId == plan.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                            .then(
                                if (isSelected) Modifier.border(
                                    2.dp,
                                    Color(0xFF6366F1),
                                    RoundedCornerShape(12.dp)
                                ) else Modifier
                            )
                            .clickable { onPlanSelect(plan.id) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = loc("plan.$planIdx.name", plan.name),
                                    style = stackPlanNameStyle,
                                )
                                plan.period?.let {
                                    Text(text = loc("plan.$planIdx.period", it), style = stackPeriodStyle)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = loc("plan.$planIdx.price", plan.price),
                                    style = stackPriceStyle,
                                )
                                plan.badge?.let {
                                    Text(
                                        text = loc("plan.$planIdx.badge", it),
                                        style = stackBadgeStyle,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(loc("restore.text", "Restore Purchases"), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            }
        }
        "cta" -> {
            val buttonBgColor = section.style?.elements?.get("button")?.background?.color?.let {
                StyleEngine.parseColor(it)
            } ?: Color(0xFF6366F1)
            val buttonTextStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                section.style?.elements?.get("button")?.text_style
            )
            Button(
                onClick = onCTATap,
                enabled = !isPurchasing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .ctaAnimation(config.animation?.cta_animation)
                    .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonBgColor)
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = loc("cta.text", section.data?.cta?.text ?: "Continue"),
                        style = buttonTextStyle,
                    )
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
                            Text(it, style = authorNameStyle)
                        }
                        section.data?.author_role?.let {
                            Text(it, style = authorRoleStyle)
                        }
                    }
                }
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
                    text = String.format("%02d", value),
                    style = digitStyle,
                )
                Text(text = label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
            }
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
