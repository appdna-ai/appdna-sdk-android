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

        val onStepViewed = pendingOnStepViewed
        val onStepCompleted = pendingOnStepCompleted
        val onStepSkipped = pendingOnStepSkipped
        val onFlowCompleted = pendingOnFlowCompleted
        val onFlowDismissed = pendingOnFlowDismissed

        setContent {
            MaterialTheme {
                OnboardingFlowHost(
                    flow = flow,
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
        private var pendingOnStepViewed: ((String, Int) -> Unit)? = null
        private var pendingOnStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null
        private var pendingOnStepSkipped: ((String, Int) -> Unit)? = null
        private var pendingOnFlowCompleted: ((Map<String, Any>) -> Unit)? = null
        private var pendingOnFlowDismissed: ((String, Int) -> Unit)? = null

        fun launch(
            context: Context,
            flow: OnboardingFlowConfig,
            onStepViewed: ((String, Int) -> Unit)? = null,
            onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null,
            onStepSkipped: ((String, Int) -> Unit)? = null,
            onFlowCompleted: ((Map<String, Any>) -> Unit)? = null,
            onFlowDismissed: ((String, Int) -> Unit)? = null
        ) {
            pendingFlow = flow
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
    onStepViewed: (String, Int) -> Unit,
    onStepCompleted: (String, Int, Map<String, Any>?) -> Unit,
    onStepSkipped: (String, Int) -> Unit,
    onFlowCompleted: (Map<String, Any>) -> Unit,
    onFlowDismissed: (String, Int) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val responses = remember { mutableStateMapOf<String, Any>() }

    val progress = if (flow.steps.isNotEmpty()) {
        (currentIndex + 1).toFloat() / flow.steps.size
    } else 0f

    // Track step viewed
    LaunchedEffect(currentIndex) {
        if (currentIndex < flow.steps.size) {
            val step = flow.steps[currentIndex]
            onStepViewed(step.id, currentIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Progress bar
        if (flow.settings.show_progress) {
            LinearProgressIndicator(
                progress = { progress },
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
                IconButton(onClick = { currentIndex-- }) {
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
            IconButton(onClick = {
                if (currentIndex < flow.steps.size) {
                    val step = flow.steps[currentIndex]
                    onFlowDismissed(step.id, currentIndex)
                }
            }) {
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
            OnboardingStepView(
                step = step,
                onNext = { data ->
                    if (data != null) {
                        responses[step.id] = data
                    }
                    onStepCompleted(step.id, currentIndex, data)
                    // Advance or complete
                    if (currentIndex + 1 >= flow.steps.size) {
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                    } else {
                        currentIndex++
                    }
                },
                onSkip = {
                    onStepSkipped(step.id, currentIndex)
                    // Advance or complete
                    if (currentIndex + 1 >= flow.steps.size) {
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                    } else {
                        currentIndex++
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
fun OnboardingStepView(
    step: OnboardingStep,
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
            OnboardingStep.StepType.WELCOME -> WelcomeStep(step.config, onNext)
            OnboardingStep.StepType.QUESTION -> QuestionStep(step.config, onNext)
            OnboardingStep.StepType.VALUE_PROP -> ValuePropStep(step.config, onNext)
            OnboardingStep.StepType.CUSTOM -> CustomStep(step.config, onNext)
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
