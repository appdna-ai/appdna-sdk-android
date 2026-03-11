package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Activity to render onboarding flow UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val flow = pendingFlow ?: run {
            finish()
            return
        }

        val delegate = pendingDelegate
        val onStepViewed = pendingOnStepViewed
        val onStepCompleted = pendingOnStepCompleted
        val onStepSkipped = pendingOnStepSkipped
        val onFlowCompleted = pendingOnFlowCompleted
        val onFlowDismissed = pendingOnFlowDismissed

        setContent {
            MaterialTheme {
                OnboardingFlowHost(
                    flow = flow,
                    delegate = delegate,
                    onStepViewed = { stepId, stepIndex ->
                        onStepViewed?.invoke(stepId, stepIndex)
                    },
                    onStepCompleted = { stepId, stepIndex, data ->
                        onStepCompleted?.invoke(stepId, stepIndex, data)
                    },
                    onStepSkipped = { stepId, stepIndex ->
                        onStepSkipped?.invoke(stepId, stepIndex)
                    },
                    onFlowCompleted = { responses ->
                        onFlowCompleted?.invoke(responses)
                        cleanup()
                    },
                    onFlowDismissed = { lastStepId, lastStepIndex ->
                        onFlowDismissed?.invoke(lastStepId, lastStepIndex)
                        cleanup()
                    }
                )
            }
        }
    }

    private fun cleanup() {
        pendingFlow = null
        pendingDelegate = null
        pendingOnStepViewed = null
        pendingOnStepCompleted = null
        pendingOnStepSkipped = null
        pendingOnFlowCompleted = null
        pendingOnFlowDismissed = null
        finish()
    }

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        pendingFlow?.let { flow ->
            // Treat back press as dismiss
            pendingOnFlowDismissed?.invoke(flow.steps.firstOrNull()?.id ?: "", 0)
        }
        cleanup()
    }

    companion object {
        private var pendingFlow: OnboardingFlowConfig? = null
        private var pendingDelegate: AppDNAOnboardingDelegate? = null
        private var pendingOnStepViewed: ((String, Int) -> Unit)? = null
        private var pendingOnStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null
        private var pendingOnStepSkipped: ((String, Int) -> Unit)? = null
        private var pendingOnFlowCompleted: ((Map<String, Any>) -> Unit)? = null
        private var pendingOnFlowDismissed: ((String, Int) -> Unit)? = null

        fun launch(
            context: Context,
            flow: OnboardingFlowConfig,
            delegate: AppDNAOnboardingDelegate? = null,
            onStepViewed: ((String, Int) -> Unit)? = null,
            onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null,
            onStepSkipped: ((String, Int) -> Unit)? = null,
            onFlowCompleted: ((Map<String, Any>) -> Unit)? = null,
            onFlowDismissed: ((String, Int) -> Unit)? = null
        ) {
            pendingFlow = flow
            pendingDelegate = delegate
            pendingOnStepViewed = onStepViewed
            pendingOnStepCompleted = onStepCompleted
            pendingOnStepSkipped = onStepSkipped
            pendingOnFlowCompleted = onFlowCompleted
            pendingOnFlowDismissed = onFlowDismissed

            val intent = Intent(context, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun OnboardingFlowHost(
    flow: OnboardingFlowConfig,
    delegate: AppDNAOnboardingDelegate? = null,
    onStepViewed: (String, Int) -> Unit,
    onStepCompleted: (String, Int, Map<String, Any>?) -> Unit,
    onStepSkipped: (String, Int) -> Unit,
    onFlowCompleted: (Map<String, Any>) -> Unit,
    onFlowDismissed: (String, Int) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val responses = remember { mutableStateMapOf<String, Any>() }
    val coroutineScope = rememberCoroutineScope()

    // SPEC-083: Hook state
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    val configOverrides = remember { mutableStateMapOf<String, StepConfigOverride>() }

    val progress = if (flow.steps.isNotEmpty()) {
        (currentIndex + 1).toFloat() / flow.steps.size
    } else 0f

    // SPEC-083: Before-render hook + step viewed tracking
    LaunchedEffect(currentIndex) {
        if (currentIndex < flow.steps.size) {
            val step = flow.steps[currentIndex]
            // Call onBeforeStepRender hook
            delegate?.let { d ->
                val override = d.onBeforeStepRender(
                    flowId = flow.id,
                    stepId = step.id,
                    stepIndex = currentIndex,
                    stepType = step.type.value,
                    responses = responses.toMap()
                )
                if (override != null) {
                    configOverrides[step.id] = override
                }
            }
            onStepViewed(step.id, currentIndex)
        }
    }

    // Auto-dismiss error after 5 seconds
    LaunchedEffect(showError) {
        if (showError) {
            kotlinx.coroutines.delay(5000)
            showError = false
            errorMessage = null
        }
    }

    // Helper functions
    fun advanceOrComplete() {
        if (currentIndex + 1 >= flow.steps.size) {
            @Suppress("UNCHECKED_CAST")
            onFlowCompleted(responses.toMap() as Map<String, Any>)
        } else {
            currentIndex++
        }
    }

    fun skipToStep(targetStepId: String) {
        val targetIndex = flow.steps.indexOfFirst { it.id == targetStepId }
        if (targetIndex >= 0) {
            currentIndex = targetIndex
        } else {
            advanceOrComplete()
        }
    }

    fun mergeData(extraData: Map<String, Any>, stepId: String) {
        val existing = responses[stepId]
        if (existing is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val merged = (existing as Map<String, Any>).toMutableMap()
            merged.putAll(extraData)
            responses[stepId] = merged
        } else {
            responses[stepId] = extraData
        }
    }

    fun applyOverrides(config: StepConfig, stepId: String): StepConfig {
        val override = configOverrides[stepId] ?: return config
        return config.copy(
            title = override.title ?: config.title,
            subtitle = override.subtitle ?: config.subtitle,
            cta_text = override.ctaText ?: config.cta_text,
            field_defaults = override.fieldDefaults ?: config.field_defaults
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Progress bar
            if (flow.settings.show_progress) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Gray.copy(alpha = 0.2f)
                )
            }

            // Navigation bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                if (flow.settings.allow_back && currentIndex > 0) {
                    IconButton(
                        onClick = { currentIndex-- },
                        enabled = !isProcessing
                    ) {
                        Text(
                            text = "\u2190",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                // Dismiss button
                IconButton(
                    onClick = {
                        if (currentIndex < flow.steps.size) {
                            val step = flow.steps[currentIndex]
                            onFlowDismissed(step.id, currentIndex)
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text(
                        text = "\u2715",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            // Step content
            if (currentIndex < flow.steps.size) {
                val step = flow.steps[currentIndex]
                val effectiveConfig = applyOverrides(step.config, step.id)

                OnboardingStepView(
                    step = step,
                    effectiveConfig = effectiveConfig,
                    onNext = { data ->
                        if (data != null) {
                            responses[step.id] = data
                        }
                        onStepCompleted(step.id, currentIndex, data)

                        // SPEC-083: Call async hook before advancing
                        if (delegate != null) {
                            isProcessing = true
                            coroutineScope.launch {
                                val result = delegate.onBeforeStepAdvance(
                                    flowId = flow.id,
                                    fromStepId = step.id,
                                    stepIndex = currentIndex,
                                    stepType = step.type.value,
                                    responses = responses.toMap(),
                                    stepData = data
                                )
                                isProcessing = false

                                when (result) {
                                    is StepAdvanceResult.Proceed -> advanceOrComplete()
                                    is StepAdvanceResult.ProceedWithData -> {
                                        mergeData(result.data, step.id)
                                        advanceOrComplete()
                                    }
                                    is StepAdvanceResult.Block -> {
                                        errorMessage = result.message
                                        showError = true
                                    }
                                    is StepAdvanceResult.SkipTo -> {
                                        result.data?.let { mergeData(it, step.id) }
                                        skipToStep(result.stepId)
                                    }
                                }
                            }
                        } else {
                            advanceOrComplete()
                        }
                    },
                    onSkip = {
                        onStepSkipped(step.id, currentIndex)
                        advanceOrComplete()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }

        // SPEC-083: Error banner
        if (showError && errorMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
                    .padding(top = if (flow.settings.show_progress) 56.dp else 52.dp)
                    .background(Color.Red, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showError = false; errorMessage = null },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text("\u2715", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        }

        // SPEC-083: Loading overlay
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Processing...",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingStepView(
    step: OnboardingStep,
    effectiveConfig: StepConfig,
    onNext: (Map<String, Any>?) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step.type) {
            OnboardingStep.StepType.WELCOME -> WelcomeStep(effectiveConfig, onNext)
            OnboardingStep.StepType.QUESTION -> QuestionStep(effectiveConfig, onNext)
            OnboardingStep.StepType.VALUE_PROP -> ValuePropStep(effectiveConfig, onNext)
            OnboardingStep.StepType.CUSTOM -> CustomStep(effectiveConfig, onNext)
            OnboardingStep.StepType.FORM -> FormStep(effectiveConfig, onNext)
        }

        // Skip button
        if (step.config.skip_enabled == true) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSkip) {
                Text("Skip", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun WelcomeStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(48.dp))

        config.title?.let {
            Text(
                text = it,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        config.subtitle?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = { onNext(null) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                text = config.cta_text ?: "Get Started",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun QuestionStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    val selectedOptions = remember { mutableStateListOf<String>() }
    val isMulti = config.selection_mode == SelectionMode.MULTI

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        config.title?.let {
            Text(
                text = it,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        config.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(24.dp))

        config.options?.forEach { option ->
            val isSelected = selectedOptions.contains(option.id)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .then(
                        if (isSelected) Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp)
                        ) else Modifier
                    )
                    .clickable {
                        if (isMulti) {
                            if (isSelected) selectedOptions.remove(option.id)
                            else selectedOptions.add(option.id)
                        } else {
                            selectedOptions.clear()
                            selectedOptions.add(option.id)
                        }
                    },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    option.icon?.let {
                        Text(text = it, fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(text = option.label, fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val data = mapOf("selected" to selectedOptions.toList())
                onNext(data)
            },
            enabled = selectedOptions.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = config.cta_text ?: "Continue",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun ValuePropStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        config.title?.let {
            Text(
                text = it,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        config.items?.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(text = item.icon, fontSize = 28.sp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = item.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = item.subtitle,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { onNext(null) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = config.cta_text ?: "Continue",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun CustomStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        config.title?.let {
            Text(
                text = it,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        config.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { onNext(null) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = config.cta_text ?: "Continue",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}
