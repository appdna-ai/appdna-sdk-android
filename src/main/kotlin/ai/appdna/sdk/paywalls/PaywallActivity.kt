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
import ai.appdna.sdk.core.entryAnimation
import ai.appdna.sdk.core.sectionStagger
import ai.appdna.sdk.core.ctaAnimation
import ai.appdna.sdk.core.planSelectionAnimation
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.LocalizationEngine

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
            .entryAnimation(config.animation?.entry_animation, config.animation?.entry_duration_ms)
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
                        onClick = { onDismiss(DismissReason.DISMISSED) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    ) {
                        Text(
                            text = config.dismiss?.text ?: "No thanks",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                }
                else -> { // x_button (default)
                    IconButton(
                        onClick = { onDismiss(DismissReason.DISMISSED) },
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colors))
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
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
                modifier = Modifier.fillMaxWidth()
            ) {
                section.data?.title?.let {
                    Text(
                        text = loc("section-header.title", it),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                section.data?.subtitle?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = loc("section-header.subtitle", it),
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        "features" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                section.data?.features?.forEach { feature ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        Text(text = "\u2713", color = Color(0xFF22C55E), fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(text = feature, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
        "plans" -> {
            val layoutType = config.layout.type
            // SPEC-084: Grid / stack plan layouts (carousel deferred — needs HorizontalPager dependency)
            if (layoutType == "grid") {
                // 2-column grid
                val plans = section.data?.plans ?: emptyList()
                val chunked = plans.chunked(2)
                Column(modifier = Modifier.fillMaxWidth()) {
                    chunked.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { plan ->
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
                                        Text(text = plan.name, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                                        Text(text = plan.price, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                                        plan.badge?.let { Text(text = it, fontSize = 11.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold) }
                                    }
                                }
                            }
                            // Fill empty slot in last row
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Restore Purchases", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            } else {
            // Default stack layout
            Column(modifier = Modifier.fillMaxWidth()) {
                section.data?.plans?.forEach { plan ->
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
                                    text = plan.name,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                plan.period?.let {
                                    Text(text = it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = plan.price,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                plan.badge?.let {
                                    Text(
                                        text = it,
                                        fontSize = 11.sp,
                                        color = Color(0xFF22C55E),
                                        fontWeight = FontWeight.SemiBold
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
                    Text("Restore Purchases", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            }
        }
        "cta" -> {
            Button(
                onClick = onCTATap,
                enabled = !isPurchasing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .ctaAnimation(config.animation?.cta_animation),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
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
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )
                }
            }
        }
        "social_proof" -> {
            // SPEC-084: Social proof sub-types
            val subType = section.data?.sub_type ?: "app_rating"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (subType) {
                    "countdown" -> {
                        CountdownTimer(seconds = section.data?.countdown_seconds ?: 86400)
                    }
                    "trial_badge" -> {
                        Text(
                            text = section.data?.text ?: "Free Trial",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF6366F1),
                            modifier = Modifier
                                .background(
                                    Color(0xFF6366F1).copy(alpha = 0.15f),
                                    RoundedCornerShape(999.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    else -> { // app_rating
                        section.data?.rating?.let { rating ->
                            val stars = "\u2605".repeat(rating.toInt())
                            Text(text = stars, color = Color(0xFFFBBF24), fontSize = 20.sp)
                            section.data.review_count?.let { count ->
                                Text(
                                    text = "$rating from $count reviews",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                        section.data?.testimonial?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "\"$it\"",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Light
                            )
                        }
                    }
                }
            }
        }
        "guarantee" -> {
            section.data?.guarantee_text?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
        // SPEC-084: Missing sections
        "image" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((section.data?.height ?: 240f).dp)
                    .clip(RoundedCornerShape((section.data?.corner_radius ?: 12f).dp))
                    .background(Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\uD83D\uDDBC", fontSize = 32.sp) // Image placeholder
            }
        }
        "spacer" -> {
            Spacer(modifier = Modifier.height((section.data?.spacer_height ?: 24f).dp))
        }
        "testimonial" -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "\u201C",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1),
                )
                Text(
                    text = section.data?.quote ?: section.data?.testimonial ?: "",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Author initials circle
                    section.data?.author_name?.let { name ->
                        val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }.take(2).joinToString("")
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initials, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF6366F1))
                        }
                    }
                    Column {
                        section.data?.author_name?.let {
                            Text(it, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        }
                        section.data?.author_role?.let {
                            Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// SPEC-084: Countdown timer for social proof
@Composable
private fun CountdownTimer(seconds: Int) {
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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
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
