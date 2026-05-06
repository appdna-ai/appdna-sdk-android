package ai.appdna.sdk.feedback

// SPEC-070-A J.22 — re-wrap interpolated options as ImmutableList for Compose stability.
import kotlinx.collections.immutable.toImmutableList
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.views.*
// SPEC-070-A J.11 — accessibility string resources for survey chrome.
import ai.appdna.sdk.R
import ai.appdna.sdk.core.FontResolver
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.ConfettiOverlay
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.applyBlurBackdrop
import androidx.compose.ui.platform.LocalView

/**
 * Activity to render survey UI using Jetpack Compose.
 * Supports bottom_sheet, modal, and fullscreen presentations.
 */
class SurveyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-070-A I.16 — edge-to-edge so Compose receives IME inset changes
        // via `imePadding()` + `safeDrawingPadding()` modifiers in SurveyScreen.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val surveyId = intent.getStringExtra(EXTRA_SURVEY_ID) ?: run {
            finish()
            return
        }

        val config = pendingSurveyConfig ?: run {
            finish()
            return
        }

        val callback = pendingCallback
        val qaCallback = questionAnsweredCallback
        val presentation = config.appearance.presentation

        // Apply presentation style
        when (presentation) {
            "bottom_sheet" -> {
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }
            "modal" -> {
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }
            // "fullscreen" -> default full activity
        }

        val onComplete: (List<SurveyAnswer>) -> Unit = { answers ->
            callback?.invoke(SurveyResult.Completed(answers))
            cleanup()
        }
        val onDismiss: (Int) -> Unit = { answeredCount ->
            callback?.invoke(SurveyResult.Dismissed(answeredCount))
            cleanup()
        }

        // SPEC-070-A I.9 — system back: when on a question past the first, decrement
        // instead of dismissing. iOS SurveyRenderer pops the question stack on
        // swipe-back; Android maps that to the system back button. The Compose
        // recomposition reads `currentQuestionIndex` from the companion field
        // which is updated by SurveyScreen's "Back" button click handler too.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cb = surveyBackHandler
                if (cb == null || !cb.invoke()) {
                    // No question to step back to → dismiss.
                    isEnabled = false
                    @Suppress("DEPRECATION")
                    onBackPressedDispatcher.onBackPressed()
                    pendingCallback?.invoke(SurveyResult.Dismissed(0))
                    cleanup()
                }
            }
        })

        setContent {
            // SPEC-205 / SPEC-070-A D.5: read system color scheme at the
            // Compose root and propagate to renderers so dark-mode overrides
            // on the survey theme apply when the user has dark mode enabled.
            val isDark = isSystemInDarkTheme()
            // SPEC-070-A D.6: build a MaterialTheme color scheme seeded with
            // the resolved (light/dark-merged) survey theme colors when the
            // console author supplied them, so child Material widgets
            // (TextField, ProgressIndicator, etc.) pick up the brand colors
            // without each composable having to re-thread them. The renderer
            // still resolves per-token colors via [SurveyAppearance.resolveTheme]
            // for full fidelity.
            val resolved = config.appearance.resolveTheme(isDark)
            val baseScheme = if (isDark) darkColorScheme() else lightColorScheme()
            val seeded = baseScheme.copy(
                primary = resolved?.accentColor?.let { parseColor(it) } ?: baseScheme.primary,
                surface = resolved?.backgroundColor?.let { parseColor(it) } ?: baseScheme.surface,
                background = resolved?.backgroundColor?.let { parseColor(it) } ?: baseScheme.background,
                onSurface = resolved?.textColor?.let { parseColor(it) } ?: baseScheme.onSurface,
                onBackground = resolved?.textColor?.let { parseColor(it) } ?: baseScheme.onBackground,
            )
            MaterialTheme(colorScheme = seeded) {
                when (presentation) {
                    "bottom_sheet" -> BottomSheetSurveyWrapper(config, onComplete, onDismiss, qaCallback, isDark)
                    "modal" -> ModalSurveyWrapper(config, onComplete, onDismiss, qaCallback, isDark)
                    else -> SurveyScreen(config, onComplete, onDismiss, qaCallback, isDark)
                }
            }
        }
    }

    private fun cleanup() {
        pendingSurveyConfig = null
        pendingCallback = null
        finish()
    }

    companion object {
        private const val EXTRA_SURVEY_ID = "survey_id"
        private var pendingSurveyConfig: SurveyConfig? = null
        private var pendingCallback: ((SurveyResult) -> Unit)? = null
        internal var questionAnsweredCallback: ((String, String, Any) -> Unit)? = null

        /**
         * SPEC-070-A I.9 — set by SurveyScreen so the Activity's back-press
         * callback can decrement question index instead of dismissing the
         * Activity. Returns true when the question pointer was decremented;
         * false means we're on the first (or only) question and the host
         * should fall through to dismiss.
         */
        @Volatile internal var surveyBackHandler: (() -> Boolean)? = null

        fun launch(context: Context, surveyId: String, config: SurveyConfig, callback: (SurveyResult) -> Unit) {
            pendingSurveyConfig = config
            pendingCallback = callback
            val intent = Intent(context, SurveyActivity::class.java).apply {
                putExtra(EXTRA_SURVEY_ID, surveyId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun SurveyScreen(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null,
    // SPEC-205 / SPEC-070-A D.4: optional override; defaults to the system
    // setting when not supplied. Activity callers pass an explicit value so
    // the resolved theme matches the MaterialTheme color scheme they seeded.
    isDark: Boolean = isSystemInDarkTheme(),
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateMapOf<String, SurveyAnswer>() }

    // SPEC-070-A I.9 — register a back-handler so the Activity's
    // OnBackPressedDispatcher can decrement question index instead of
    // dismissing. Cleared on dispose so the handler doesn't survive
    // SurveyScreen recompositions across surveys.
    DisposableEffect(Unit) {
        SurveyActivity.surveyBackHandler = {
            if (currentIndex > 0) {
                currentIndex--
                true
            } else {
                false
            }
        }
        onDispose { SurveyActivity.surveyBackHandler = null }
    }
    var showCompletion by remember { mutableStateOf(false) }
    val currentView = LocalView.current
    val visibleQuestions by remember {
        derivedStateOf {
            config.questions.filter { q ->
                val showIf = q.showIf ?: return@filter true
                val prev = answers[showIf.questionId] ?: return@filter false
                showIf.answerIn.any { "${prev.answer}" == "$it" }
            }
        }
    }

    // SPEC-205 / SPEC-070-A D.4: resolve the theme for the current scheme.
    // In dark mode, fields set on `theme.dark` override the matching field
    // on `theme.light`; unset dark fields fall back to light (sparse merge).
    // Mirrors iOS `SurveyRenderer.theme` accessor.
    val theme = config.appearance.resolveTheme(isDark)
    // SPEC-205: scheme-aware fallback colors so an unstyled survey still
    // looks correct in dark mode (matches iOS defaults #1a1a1a / #FFFFFF).
    val bgColor = theme?.backgroundColor?.let { parseColor(it) }
        ?: if (isDark) Color(0xFF1A1A1A) else Color.White
    val textColor = theme?.textColor?.let { parseColor(it) }
        ?: if (isDark) Color.White else Color(0xFF1A1A1A)
    val accentColor = theme?.accentColor?.let { parseColor(it) } ?: Color(0xFF6366F1)
    val buttonColor = theme?.buttonColor?.let { parseColor(it) } ?: Color(0xFF6366F1)
    val fontFamily = theme?.fontFamily?.let { FontResolver.resolve(it) }
    val containerRadius = (config.appearance.cornerRadius ?: 0).dp

    val canAdvance = if (currentIndex < visibleQuestions.size) {
        val q = visibleQuestions[currentIndex]
        if (q.required) answers.containsKey(q.id) else true
    } else false

    val customColors = MaterialTheme.colorScheme.copy(
        onSurface = textColor,
        onBackground = textColor
    )

    val customTypography = fontFamily?.let { ff ->
        val base = MaterialTheme.typography
        base.copy(
            bodyLarge = base.bodyLarge.copy(fontFamily = ff),
            bodyMedium = base.bodyMedium.copy(fontFamily = ff),
            bodySmall = base.bodySmall.copy(fontFamily = ff),
            titleLarge = base.titleLarge.copy(fontFamily = ff),
            titleMedium = base.titleMedium.copy(fontFamily = ff),
            labelLarge = base.labelLarge.copy(fontFamily = ff),
        )
    } ?: MaterialTheme.typography

    MaterialTheme(colorScheme = customColors, typography = customTypography) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // SPEC-070-A I.16 — IME insets so keyboard pushes content up; safe
            // drawing keeps content out of system bars while the survey
            // itself paints the background edge-to-edge.
            .imePadding()
            .safeDrawingPadding()
            .clip(RoundedCornerShape(topStart = containerRadius, topEnd = containerRadius))
            .background(bgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // SPEC-085: Intro Lottie animation
        if (currentIndex == 0 && config.appearance.introLottieUrl != null) {
            LottieBlockView(
                block = LottieBlock(
                    lottie_url = config.appearance.introLottieUrl,
                    autoplay = true,
                    loop = false,
                    height = 120f,
                )
            )
            Spacer(Modifier.height(12.dp))
        }

        // Progress
        if (config.appearance.showProgress && visibleQuestions.isNotEmpty()) {
            LinearProgressIndicator(
                progress = (currentIndex + 1).toFloat() / visibleQuestions.size,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                color = accentColor,
            )
        }

        Spacer(Modifier.weight(1f))

        // Current question
        if (currentIndex < visibleQuestions.size) {
            val question = visibleQuestions[currentIndex]
            val answer = answers[question.id]

            val onAnswer: (SurveyAnswer) -> Unit = { ans ->
                answers[question.id] = ans
                onQuestionAnswered?.invoke(question.id, question.type, ans.answer)
                // SPEC-085: Haptic on option select
                HapticEngine.triggerIfEnabled(
                    currentView,
                    config.appearance.haptic?.triggers?.on_option_select,
                    config.appearance.haptic,
                )
            }

            // SPEC-084: Gap #20 — resolve question text style from appearance token
            val questionTextStyle = StyleEngine.applyTextStyle(
                MaterialTheme.typography.titleMedium,
                config.appearance.questionTextStyle
            )

            // SPEC-088: Interpolate question text, option text, NPS labels
            val tCtx = ai.appdna.sdk.core.TemplateEngine.buildContext()
            val te = ai.appdna.sdk.core.TemplateEngine
            val q = question.copy(
                text = te.interpolate(question.text, tCtx),
                npsConfig = question.npsConfig?.copy(
                    lowLabel = question.npsConfig.lowLabel?.let { te.interpolate(it, tCtx) },
                    highLabel = question.npsConfig.highLabel?.let { te.interpolate(it, tCtx) }
                ),
                // SPEC-070-A J.1: interpolate Likert anchor labels too so
                // template tokens like "{{user.name}}" work consistently
                // with the NPS path.
                likertConfig = question.likertConfig?.copy(
                    lowLabel = question.likertConfig.lowLabel?.let { te.interpolate(it, tCtx) },
                    highLabel = question.likertConfig.highLabel?.let { te.interpolate(it, tCtx) },
                ),
                // SPEC-070-A J.22 — re-wrap as ImmutableList after interpolation
                // map (SurveyQuestion.options is ImmutableList<QuestionOption>?).
                options = question.options?.map { opt ->
                    opt.copy(text = te.interpolate(opt.text, tCtx))
                }?.toImmutableList()
            )

            when (q.type) {
                "nps" -> NpsQuestionView(q, answer, onAnswer, questionTextStyle)
                "csat" -> CsatQuestionView(q, answer, onAnswer, questionTextStyle)
                "rating" -> RatingQuestionView(q, answer, onAnswer, questionTextStyle)
                // SPEC-084: Gap #21 — pass optionStyle to choice views
                "single_choice" -> SingleChoiceView(q, answer, onAnswer, questionTextStyle, config.appearance.optionStyle)
                "multi_choice" -> MultiChoiceView(q, answer, onAnswer, questionTextStyle, config.appearance.optionStyle)
                "free_text" -> FreeTextView(q, answer, onAnswer, questionTextStyle)
                "yes_no" -> YesNoView(q, answer, onAnswer, questionTextStyle)
                "emoji_scale" -> EmojiScaleView(q, answer, onAnswer, questionTextStyle)
                // SPEC-070-A J.1: Likert scale (numeric scale with optional
                // anchor labels). Accept both `likert` (canonical) and
                // `scale` (legacy alias) so older console payloads still
                // render on newer SDKs.
                "likert", "scale" -> LikertQuestionView(q, answer, onAnswer, questionTextStyle)
            }
        }

        Spacer(Modifier.weight(1f))

        // Navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (currentIndex > 0) {
                val backCd = stringResource(R.string.appdna_a11y_survey_back)
                TextButton(
                    onClick = { currentIndex-- },
                    // SPEC-070-A J.11 — explicit a11y label so TalkBack
                    // announces "Previous question" instead of just "Back".
                    modifier = Modifier.semantics { contentDescription = backCd },
                ) {
                    Text("Back", color = accentColor)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            if (currentIndex < visibleQuestions.size - 1) {
                val nextCd = stringResource(R.string.appdna_a11y_survey_next)
                Button(
                    onClick = { currentIndex++ },
                    enabled = canAdvance,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    modifier = Modifier.semantics { contentDescription = nextCd },
                ) { Text("Next") }
            } else {
                val submitCd = stringResource(R.string.appdna_a11y_survey_submit)
                Button(
                    onClick = {
                        // SPEC-085: Haptic on submit
                        HapticEngine.triggerIfEnabled(
                            currentView,
                            config.appearance.haptic?.triggers?.on_form_submit,
                            config.appearance.haptic,
                        )
                        showCompletion = true
                        val allAnswers = visibleQuestions.mapNotNull { answers[it.id] }
                        onComplete(allAnswers)
                    },
                    enabled = canAdvance,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    modifier = Modifier.semantics { contentDescription = submitCd },
                ) { Text("Submit") }
            }
        }

        // Dismiss
        if (config.appearance.dismissAllowed) {
            val dismissCd = stringResource(R.string.appdna_a11y_survey_dismiss)
            TextButton(
                onClick = { onDismiss(answers.size) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    // SPEC-070-A J.11 — "Not now" is the visible label;
                    // the a11y label disambiguates as "Dismiss survey".
                    .semantics { contentDescription = dismissCd },
            ) {
                Text("Not now", color = Color.Gray)
            }
        }

        // SPEC-085: Thank-you Lottie on completion + SPEC-088: Interpolated thank-you text
        if (showCompletion) {
            if (config.appearance.thankyouLottieUrl != null) {
                Spacer(Modifier.height(8.dp))
                LottieBlockView(
                    block = LottieBlock(
                        lottie_url = config.appearance.thankyouLottieUrl,
                        autoplay = true,
                        loop = false,
                        height = 100f,
                    )
                )
            }
            val thankCtx = ai.appdna.sdk.core.TemplateEngine.buildContext()
            val thankText = ai.appdna.sdk.core.TemplateEngine.interpolate(
                config.appearance.thankYouText ?: "Thank you!", thankCtx
            )
            Text(
                text = thankText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                ),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // SPEC-085: Confetti on completion
        if (showCompletion && config.appearance.thankyouParticleEffect != null) {
            ConfettiOverlay(
                effect = config.appearance.thankyouParticleEffect,
                trigger = showCompletion,
            )
        }
    }
    } // MaterialTheme
}

private fun parseColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val colorInt = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000 or colorInt)
            8 -> Color(colorInt)
            else -> Color.White
        }
    } catch (_: Exception) {
        Color.White
    }
}

@Composable
fun BottomSheetSurveyWrapper(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
) {
    // SPEC-205 / SPEC-070-A D.4: resolve dark-merged theme for the wrapper
    // chrome (sheet container background) so it matches the inner survey.
    val theme = config.appearance.resolveTheme(isDark)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (config.appearance.dismissAllowed) onDismiss(0)
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(
                    theme?.backgroundColor?.let { parseColor(it) }
                        ?: if (isDark) Color(0xFF1A1A1A) else Color.White
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            // Grabber handle
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Gray.copy(alpha = 0.4f))
            )
            SurveyScreen(
                config = config,
                onComplete = onComplete,
                onDismiss = onDismiss,
                onQuestionAnswered = onQuestionAnswered,
                isDark = isDark,
            )
        }
    }
}

@Composable
fun ModalSurveyWrapper(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
) {
    // SPEC-205 / SPEC-070-A D.4: resolve dark-merged theme for the modal chrome.
    val theme = config.appearance.resolveTheme(isDark)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (config.appearance.dismissAllowed) onDismiss(0)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    theme?.backgroundColor?.let { parseColor(it) }
                        ?: if (isDark) Color(0xFF1A1A1A) else Color.White
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            SurveyScreen(
                config = config,
                onComplete = onComplete,
                onDismiss = onDismiss,
                onQuestionAnswered = onQuestionAnswered,
                isDark = isDark,
            )
        }
    }
}

