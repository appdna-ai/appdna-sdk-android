package ai.appdna.sdk.onboarding

import android.util.Log
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
// ContentScale removed — no image loading dependency
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Avatar uses initial-based circle (no coil dependency)
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * SPEC-090: Interactive chat step Composable.
 * Renders a multi-turn AI chat with webhooks, quick replies, typing indicator, and turn limits.
 */
@Composable
fun ChatStepComposable(
    step: OnboardingStep,
    flowId: String,
    onNext: (Map<String, Any>) -> Unit,
    onSkip: () -> Unit,
    // SPEC-401-A — saved transcript for back-nav restore. Mirrors
    // iOS `ChatStepView.savedTranscript`. When the renderer was
    // already completed once, the orchestrator passes the prior
    // `responses[step.id]` map back in so the bubble history is
    // rebuilt instead of replaying auto-messages from scratch.
    savedTranscript: Map<String, Any>? = null,
) {
    val chatConfig = step.config.chat_config ?: return
    val style = chatConfig.style
    val persona = chatConfig.persona
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    // SPEC-401-A R4 — host View for haptic feedback on sendMessage.
    val hostView = androidx.compose.ui.platform.LocalView.current

    // SPEC-401-A — restore from savedTranscript on first compose.
    // We seed initial state with the saved values so LaunchedEffect
    // never plays auto-messages when restoring.
    val initialMessages: List<ChatMessage> = remember(savedTranscript) {
        @Suppress("UNCHECKED_CAST")
        (savedTranscript?.get("transcript") as? List<Map<String, Any>>)?.map { m ->
            val role = (m["role"] as? String) ?: "ai"
            val content = (m["content"] as? String).orEmpty()
            val msgId = (m["id"] as? String) ?: java.util.UUID.randomUUID().toString()
            val ts = (m["timestamp"] as? String)?.let { ts ->
                runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrNull()
            } ?: System.currentTimeMillis()
            ChatMessage(
                id = msgId,
                role = if (role == "user") ChatRole.USER else ChatRole.AI,
                content = content,
                timestamp = ts,
            )
        } ?: emptyList()
    }
    val initialUserTurnCount: Int = remember(savedTranscript) {
        (savedTranscript?.get("user_turn_count") as? Number)?.toInt()
            ?: initialMessages.count { it.role == ChatRole.USER }
    }
    val didRestore = savedTranscript != null && initialMessages.isNotEmpty()

    // State
    var messages by remember { mutableStateOf(initialMessages) }
    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var userTurnCount by remember { mutableIntStateOf(initialUserTurnCount) }
    var isCompleted by remember { mutableStateOf(didRestore) }
    var currentRating by remember { mutableStateOf<Int?>(null) }
    // SPEC-401-A R5 — only seed turn-0 quick replies synchronously
    // when there are no auto-messages to play. iOS calls
    // `loadQuickReplies(forTurn: 0)` AFTER `playAutoMessages`
    // completes (or immediately when no auto-messages); Android
    // previously seeded them at composition so they appeared while
    // the AI was still "typing" the welcome sequence.
    val initialQuickReplies = if (didRestore || chatConfig.auto_messages.isNullOrEmpty()) {
        chatConfig.quick_replies?.filter { (it.show_at_turn ?: 0) == 0 } ?: emptyList()
    } else emptyList()
    var dynamicQuickReplies by remember { mutableStateOf(initialQuickReplies) }
    var webhookData by remember { mutableStateOf(mutableMapOf<String, Any>()) }
    val startTime = remember { System.currentTimeMillis() }
    var showSoftLimitWarning by remember { mutableStateOf(false) }

    val maxTurns = chatConfig.resolvedMaxTurns
    val minTurns = chatConfig.resolvedMinTurns
    val turnsRemaining = maxOf(0, maxTurns - userTurnCount)
    val canSendMessage = inputText.isNotBlank() && !isTyping && !isCompleted

    // Colors
    fun hex(value: String?, fallback: String): Color {
        val v = value ?: fallback
        return try { Color(android.graphics.Color.parseColor(v)) } catch (_: Exception) { Color.Gray }
    }
    val aiBubbleBg = hex(style?.ai_bubble_bg, "#1E293B")
    val aiBubbleTextColor = hex(style?.ai_bubble_text, "#E2E8F0")
    val userBubbleBg = hex(style?.user_bubble_bg, "#6366F1")
    val userBubbleTextColor = hex(style?.user_bubble_text, "#FFFFFF")
    val inputBgColor = hex(style?.input_bg, "#1E293B")
    val inputTextColor = hex(style?.input_text, "#E2E8F0")
    val inputBorderColor = hex(style?.input_border, "#334155")
    val sendBtnColor = hex(style?.send_button_color, "#6366F1")
    val qrBgColor = hex(style?.quick_reply_bg, "#334155")
    val qrTextColor = hex(style?.quick_reply_text, "#E2E8F0")
    val typingDotColor = hex(style?.typing_indicator_color, "#6366F1")

    // Auto-messages on appear — SPEC-401-A skip when we restored
    // a prior transcript so the user does not see the welcome
    // sequence replay over the saved bubbles.
    LaunchedEffect(Unit) {
        if (didRestore) return@LaunchedEffect
        val autoMsgs = chatConfig.auto_messages?.filter { it.turn == 0 } ?: emptyList()
        for ((i, autoMsg) in autoMsgs.withIndex()) {
            // SPEC-401-A — match iOS cadence: silent for `delay_ms`,
            // then `isTyping=true` for ~0.8s, then message. Android
            // previously held isTyping for the full delay, so dots
            // ran noticeably longer (especially for early messages
            // where delay defaults to 500/1700/2900ms).
            val delayMs = autoMsg.delay_ms ?: (500 + i * 1200)
            delay(delayMs.toLong())
            isTyping = true
            delay(800)
            isTyping = false
            messages = messages + ChatMessage(id = autoMsg.id, role = ChatRole.AI, content = autoMsg.content, media = autoMsg.media)
        }
        // SPEC-401-A R5 — load turn-0 quick replies AFTER auto-messages
        // finish playing. Mirrors iOS ChatStepView.swift:614-637 which
        // calls loadQuickReplies(forTurn: 0) at the end of
        // playAutoMessages. Without this the QR chips appeared while
        // the welcome sequence was still typing.
        if (autoMsgs.isNotEmpty()) {
            dynamicQuickReplies = chatConfig.quick_replies?.filter { (it.show_at_turn ?: 0) == 0 } ?: emptyList()
        }
    }

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // SPEC-401-A R4 — sendMessage haptic feedback. Mirrors iOS
        // HapticEngine.trigger(.light) at ChatStepView.swift:432.
        runCatching {
            ai.appdna.sdk.core.HapticEngine.trigger(
                hostView,
                ai.appdna.sdk.core.HapticType.LIGHT,
            )
        }
        val userMsg = ChatMessage(id = "msg_u$userTurnCount", role = ChatRole.USER, content = trimmed)
        messages = messages + userMsg
        inputText = ""
        userTurnCount++
        dynamicQuickReplies = emptyList()
        focusManager.clearFocus()

        // Track message sent
        ai.appdna.sdk.AppDNA.track("chat_message_sent", mapOf(
            "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount, "message_length" to trimmed.length
        ))

        if (!chatConfig.isHardLimit && turnsRemaining == 1) showSoftLimitWarning = true

        // Check turn actions
        chatConfig.turn_actions?.filter { it.turn == userTurnCount }?.forEach { action ->
            when (action.type) {
                "rating_prompt" -> currentRating = 0
                "auto_message" -> {
                    val content = (action.config?.get("content") as? String) ?: return@forEach
                    scope.launch {
                        delay(500)
                        messages = messages + ChatMessage(id = "action_$userTurnCount", role = ChatRole.AI, content = content)
                    }
                }
            }
        }

        isTyping = true
        scope.launch {
            try {
                val response = fireWebhook(
                    webhook = chatConfig.webhook,
                    flowId = flowId,
                    stepId = step.id,
                    messages = messages,
                    turn = userTurnCount - 1,
                    maxTurns = maxTurns,
                    remaining = turnsRemaining,
                    rating = currentRating,
                    context = webhookData.toMap(),
                )
                isTyping = false
                if (response == null) {
                    // SPEC-401-A — surface non-2xx HTTP / null webhook
                    // response as a system message in the transcript +
                    // event-track. iOS ChatStepView.swift:496-507 does the
                    // same; Android was silent on HTTP errors which made
                    // failed webhooks invisible to the user.
                    val errMsg = chatConfig.webhook?.error_text ?: "Sorry, something went wrong."
                    messages = messages + ChatMessage(id = "err_$userTurnCount", role = ChatRole.SYSTEM, content = errMsg)
                    ai.appdna.sdk.AppDNA.track("chat_webhook_error", mapOf(
                        "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount,
                        "error" to "http_non_2xx_or_null",
                    ))
                } else {
                    ai.appdna.sdk.AppDNA.track("chat_message_received", mapOf(
                        "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount,
                        "message_count" to (response.messages?.size ?: 0)
                    ))
                    // Add AI messages
                    response.messages?.forEachIndexed { i, msg ->
                        messages = messages + ChatMessage(id = "msg_a${userTurnCount}_$i", role = ChatRole.AI, content = msg.content ?: "", media = msg.media)
                    }
                    // Update quick replies
                    response.quick_replies?.let { dynamicQuickReplies = it }
                    // Merge data
                    response.data?.forEach { (k, v) -> webhookData[k] = v }
                    // Force complete
                    if (response.force_complete == true || response.action == "reply_and_complete") {
                        val completionMsg = response.completion_message ?: chatConfig.completion_message?.content
                        if (completionMsg != null) {
                            messages = messages + ChatMessage(id = "completion", role = ChatRole.AI, content = completionMsg)
                        }
                        isCompleted = true
                        ai.appdna.sdk.AppDNA.track("chat_completed", mapOf(
                            "flow_id" to flowId, "step_id" to step.id, "user_turn_count" to userTurnCount,
                            "total_messages" to messages.size, "completion_reason" to "ai_completed",
                            "duration_ms" to (System.currentTimeMillis() - startTime).toInt()
                        ))
                    }
                    // Max turns
                    if (turnsRemaining <= 0 && !isCompleted) {
                        chatConfig.completion_message?.content?.let {
                            messages = messages + ChatMessage(id = "completion", role = ChatRole.AI, content = it)
                        }
                        isCompleted = true
                        ai.appdna.sdk.AppDNA.track("chat_completed", mapOf(
                            "flow_id" to flowId, "step_id" to step.id, "user_turn_count" to userTurnCount,
                            "total_messages" to messages.size, "completion_reason" to "max_turns",
                            "duration_ms" to (System.currentTimeMillis() - startTime).toInt()
                        ))
                    }
                }
            } catch (e: Exception) {
                isTyping = false
                val errMsg = chatConfig.webhook?.error_text ?: "Sorry, something went wrong."
                messages = messages + ChatMessage(id = "err_$userTurnCount", role = ChatRole.SYSTEM, content = errMsg)
                ai.appdna.sdk.AppDNA.track("chat_webhook_error", mapOf(
                    "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount, "error" to (e.message ?: "unknown")
                ))
            }
        }
    }

    fun buildTranscript(reason: String): Map<String, Any> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val transcript = messages.filter { it.role != ChatRole.SYSTEM }.map { msg ->
            mapOf("role" to if (msg.role == ChatRole.USER) "user" else "ai", "content" to msg.content, "id" to msg.id, "timestamp" to iso.format(Date(msg.timestamp)))
        }
        val result = mutableMapOf<String, Any>(
            "transcript" to transcript,
            "user_turn_count" to userTurnCount,
            "total_message_count" to messages.size,
            "completion_reason" to reason,
            "duration_ms" to (System.currentTimeMillis() - startTime).toInt()
        )
        currentRating?.let { result["rating"] = it }
        if (webhookData.isNotEmpty()) result["webhook_data"] = webhookData
        return result
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(hex(style?.background_color, "#0F172A"))
    ) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            step.config.title?.let { Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (persona?.name != null && persona.role != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // SPEC-401-A — render `persona.avatar_url` when set, else
                    // fall back to the initial-based circle. Mirrors iOS
                    // ChatStepView.swift:149,173 which loads via BundledAsyncImage.
                    val initial = persona?.name?.firstOrNull()?.toString() ?: "A"
                    val avatarUrl = persona?.avatar_url?.takeIf { it.isNotBlank() }
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(hex(style?.ai_bubble_bg, "#1E293B")),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarUrl != null) {
                            ai.appdna.sdk.core.NetworkImage(
                                url = avatarUrl,
                                modifier = Modifier.size(28.dp).clip(CircleShape),
                                contentDescription = persona.name,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Text(initial, color = hex(style?.ai_bubble_text, "#E2E8F0"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("${persona.name} - ${persona.role}", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    ChatRole.AI -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            // SPEC-401-A — load persona.avatar_url for AI message
                            // bubbles, fallback to initial circle.
                            val msgAvatarUrl = persona?.avatar_url?.takeIf { it.isNotBlank() }
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(aiBubbleBg), contentAlignment = Alignment.Center) {
                                if (msgAvatarUrl != null) {
                                    ai.appdna.sdk.core.NetworkImage(
                                        url = msgAvatarUrl,
                                        modifier = Modifier.size(32.dp).clip(CircleShape),
                                        contentDescription = persona?.name,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                } else {
                                    Text((persona?.name?.take(1) ?: "A"), color = aiBubbleTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            // SPEC-401-A — render text + optional inline image
                            // media bubble below it. iOS draws an image
                            // BundledAsyncImage when `media.type == "image"`
                            // and a non-empty url; Android previously dropped
                            // the media field on the floor.
                            Column(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (msg.content.isNotBlank()) {
                                    Text(
                                        msg.content, color = aiBubbleTextColor, fontSize = 14.sp,
                                        modifier = Modifier.background(aiBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                                    )
                                }
                                msg.media?.takeIf { it.type == "image" && !it.url.isNullOrBlank() }?.let { media ->
                                    ai.appdna.sdk.core.NetworkImage(
                                        url = media.url!!,
                                        contentDescription = media.alt_text,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    )
                                }
                            }
                            Spacer(Modifier.width(40.dp))
                        }
                    }
                    ChatRole.USER -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Spacer(Modifier.width(40.dp))
                            Text(
                                msg.content, color = userBubbleTextColor, fontSize = 14.sp,
                                modifier = Modifier.background(userBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                            )
                        }
                    }
                    ChatRole.SYSTEM -> {
                        Text(msg.content, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Typing indicator
            if (isTyping) {
                item {
                    // SPEC-401-A — same persona avatar fallback as message bubbles.
                    val typingAvatarUrl = persona?.avatar_url?.takeIf { it.isNotBlank() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(aiBubbleBg), contentAlignment = Alignment.Center) {
                            if (typingAvatarUrl != null) {
                                ai.appdna.sdk.core.NetworkImage(
                                    url = typingAvatarUrl,
                                    modifier = Modifier.size(32.dp).clip(CircleShape),
                                    contentDescription = persona?.name,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Text((persona?.name?.take(1) ?: "A"), color = aiBubbleTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // SPEC-401-A — animated typing indicator (3 dots
                        // pulsing alpha 0.3 → 1.0 with 0.2s stagger).
                        // Mirrors iOS ChatStepView.swift:223 0.6s easing curve.
                        // Static dots looked broken — pulsing tells the user
                        // the assistant is actively responding.
                        val infiniteTx = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.background(aiBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                        ) {
                            for (dotIndex in 0..2) {
                                val alpha by infiniteTx.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1.0f,
                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                        animation = androidx.compose.animation.core.tween(
                                            durationMillis = 600,
                                            delayMillis = dotIndex * 200,
                                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                        ),
                                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                                    ),
                                    label = "typing_dot_$dotIndex",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(typingDotColor.copy(alpha = alpha)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // SPEC-070-A G.9 — chat rating widget. Shown when a `rating_prompt`
        // turn-action set `currentRating = 0`. Mirrors iOS ChatStepView.swift:309
        // 5-star row with `sendRatingEvent` emit. The composable uses a
        // simple unicode star (★/☆) instead of Material icons to avoid
        // pulling icon deps inside the chat composable.
        // SPEC-401-A — rating prompt only visible while currentRating == 0
        // (the "shown but unanswered" sentinel). Once user taps a star,
        // currentRating > 0 → row hides. Mirrors iOS ChatStepView.swift:71.
        currentRating?.takeIf { it == 0 }?.let { ratingState ->
            // SPEC-401-A — honour style.rating_star_color (was hardcoded
            // amber #FBBF24). Mirrors iOS ChatStepView.swift:315.
            val starColor = hex(style?.rating_star_color, "#FBBF24")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                for (star in 1..5) {
                    val filled = ratingState >= star
                    Text(
                        text = if (filled) "★" else "☆", // ★ vs ☆
                        color = if (filled) starColor else Color(0xFF94A3B8),
                        fontSize = 24.sp,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clickable {
                                currentRating = star
                                ai.appdna.sdk.AppDNA.track("chat_rating_submitted", mapOf(
                                    "flow_id" to flowId,
                                    "step_id" to step.id,
                                    "rating" to star,
                                    "turn" to userTurnCount,
                                ))
                            }
                    )
                }
            }
        }

        // Quick replies
        if (dynamicQuickReplies.isNotEmpty() && !isCompleted && !isTyping) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // SPEC-401-A — `style.quick_reply_border` config now applied
                // to OutlinedButton border. Mirrors iOS ChatStepView.swift:251
                // 1px stroke with `qrBorder` color. Was using Material3
                // default border, ignoring the config.
                val qrBorderColor = style?.quick_reply_border?.takeIf { it.isNotBlank() }?.let { hex(it, "#475569") } ?: qrBgColor
                items(dynamicQuickReplies, key = { it.id }) { qr ->
                    OutlinedButton(
                        onClick = {
                            ai.appdna.sdk.AppDNA.track("chat_quick_reply_tapped", mapOf(
                                "flow_id" to flowId, "step_id" to step.id, "quick_reply_id" to qr.id, "turn" to userTurnCount
                            ))
                            sendMessage(qr.text)
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = qrBgColor, contentColor = qrTextColor),
                        border = androidx.compose.foundation.BorderStroke(1.dp, qrBorderColor),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(qr.text, fontSize = 12.sp)
                    }
                }
            }
        }

        // Soft limit warning
        if (showSoftLimitWarning) {
            Text("You have 1 message remaining", color = Color(0xFFF59E0B), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }

        // Input or CTA
        if (isCompleted || (turnsRemaining <= 0 && chatConfig.isHardLimit)) {
            // SPEC-401-A R3 — fire chat_completed (reason=max_turns) the
            // first time the hard-limit branch triggers. iOS does this in
            // ChatStepView.swift:107-109 via `.onAppear { completeChat(...) }`
            // as a safety net when turnsRemaining<=0 && hardLimit but
            // isCompleted hasn't been set (e.g. saved transcript with
            // stale state). Android previously rendered the CTA without
            // ever tracking the completion event for this code path.
            LaunchedEffect(turnsRemaining, chatConfig.isHardLimit, isCompleted) {
                if (!isCompleted && turnsRemaining <= 0 && chatConfig.isHardLimit) {
                    isCompleted = true
                    ai.appdna.sdk.AppDNA.track("chat_completed", mapOf(
                        "flow_id" to flowId,
                        "step_id" to step.id,
                        "reason" to "max_turns",
                        "turns" to userTurnCount,
                        "duration_ms" to (System.currentTimeMillis() - startTime),
                    ))
                }
            }
            // Completion CTA — styling mirrors the normal CTA button block.
            // Any unset field in completion_button falls back to the chat
            // theme's user bubble colors so existing flows render unchanged.
            val btn = chatConfig.completion_button
            val variant = btn?.variant ?: "primary"
            val resolvedBg = btn?.bg_color?.takeIf { it.isNotEmpty() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: userBubbleBg
            val resolvedText = btn?.text_color?.takeIf { it.isNotEmpty() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: userBubbleTextColor
            val radius = (btn?.button_corner_radius ?: 14.0).dp
            // SPEC-401-A — match iOS height behaviour. iOS uses
            // height=nil → natural padding(14×2)≈46pt fallback when
            // button_height is not configured (ChatStepView.swift:354,
            // 374-375). Android previously forced 52.dp default making
            // the CTA consistently taller. Use Modifier.heightIn that
            // honours button_height when set, otherwise lets the
            // intrinsic content+padding determine height.
            val heightModifier = btn?.button_height
                ?.let { androidx.compose.ui.Modifier.height(it.dp) }
                ?: androidx.compose.ui.Modifier.defaultMinSize(minHeight = 46.dp)
            val fontSize = (btn?.style?.font_size ?: 17.0).sp
            val weight = when ((btn?.style?.font_weight ?: 600.0).toInt()) {
                400 -> FontWeight.Normal
                500 -> FontWeight.Medium
                700 -> FontWeight.Bold
                else -> FontWeight.SemiBold
            }
            val label = chatConfig.completion_cta_text ?: step.config.cta_text ?: "Continue"

            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (variant) {
                    "text" -> {
                        TextButton(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier)
                        ) { Text(label, color = resolvedBg, fontWeight = weight, fontSize = fontSize) }
                    }
                    "outline" -> {
                        OutlinedButton(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier),
                            shape = RoundedCornerShape(radius),
                            border = androidx.compose.foundation.BorderStroke(2.dp, resolvedBg)
                        ) { Text(label, color = resolvedBg, fontWeight = weight, fontSize = fontSize) }
                    }
                    "secondary" -> {
                        Button(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier),
                            shape = RoundedCornerShape(radius),
                            colors = ButtonDefaults.buttonColors(containerColor = resolvedBg.copy(alpha = 0.15f))
                        ) { Text(label, color = resolvedText, fontWeight = weight, fontSize = fontSize) }
                    }
                    else -> { // primary
                        Button(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier),
                            shape = RoundedCornerShape(radius),
                            colors = ButtonDefaults.buttonColors(containerColor = resolvedBg)
                        ) { Text(label, color = resolvedText, fontWeight = weight, fontSize = fontSize) }
                    }
                }
                if (step.config.skip_enabled == true) {
                    TextButton(onClick = onSkip) { Text("Skip", color = Color.Gray, fontSize = 12.sp) }
                }
            }
        } else {
            // Input bar
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    // SPEC-401-A — enforce input_max_length truncation.
                    // ChatModels declared the field but the UI ignored it.
                    onValueChange = { input ->
                        val maxLen = chatConfig.input_max_length
                        inputText = if (maxLen != null && input.length > maxLen) input.take(maxLen) else input
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(chatConfig.input_placeholder ?: "Type your message...", fontSize = (style?.input_font_size ?: 14).sp, color = inputTextColor.copy(alpha = 0.5f)) },
                    textStyle = LocalTextStyle.current.copy(fontSize = (style?.input_font_size ?: 14).sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBgColor,
                        unfocusedContainerColor = inputBgColor,
                        focusedBorderColor = inputBorderColor,
                        unfocusedBorderColor = inputBorderColor,
                        focusedTextColor = inputTextColor,
                        unfocusedTextColor = inputTextColor,
                        cursorColor = sendBtnColor
                    ),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSendMessage) sendMessage(inputText) })
                )
                IconButton(onClick = { sendMessage(inputText) }, enabled = canSendMessage) {
                    Text("↑", fontSize = 24.sp, color = if (canSendMessage) sendBtnColor else Color.Gray.copy(alpha = 0.3f))
                }
            }
        }
    }
}

// ── Webhook ──

private data class WebhookResponse(
    val action: String?,
    val messages: List<ChatWebhookMessage>?,
    val quick_replies: List<ChatQuickReply>?,
    val data: Map<String, Any>?,
    val force_complete: Boolean?,
    val completion_message: String?
)

private data class ChatWebhookMessage(
    val content: String?,
    val media: ChatMedia?,
    val delay_ms: Int?
)

/**
 * SPEC-070-A A.31 — fire chat webhook off the Main thread.
 *
 * The body is wrapped in `withContext(Dispatchers.IO)` because callers run on
 * the Compose `rememberCoroutineScope` (Main) and we do blocking
 * `URL.openConnection() + outputStream + bufferedReader().readText()`.
 * Without the wrap, every chat turn produces a `NetworkOnMainThreadException`
 * (or worse, a frozen UI). Mirrors the `executeWebhook` IO wrap in
 * `OnboardingActivity.executeWebhook` (SPEC-083 P1).
 */
@Suppress("UNCHECKED_CAST")
private suspend fun fireWebhook(
    webhook: StepHookConfig?,
    flowId: String,
    stepId: String,
    messages: List<ChatMessage>,
    turn: Int,
    maxTurns: Int,
    remaining: Int,
    rating: Int? = null,
    context: Map<String, Any> = emptyMap(),
): WebhookResponse? = withContext(Dispatchers.IO) {
    webhook ?: return@withContext null
    val url = webhook.webhook_url ?: return@withContext null

    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val msgsJson = JSONArray().apply {
        messages.filter { it.role != ChatRole.SYSTEM }.forEach { msg ->
            put(JSONObject().apply {
                put("role", if (msg.role == ChatRole.USER) "user" else "ai")
                put("content", msg.content)
                put("id", msg.id)
                put("timestamp", iso.format(Date(msg.timestamp)))
            })
        }
    }

    val body = JSONObject().apply {
        put("event", "chat_message")
        put("flow_id", flowId)
        put("step_id", stepId)
        put("app_id", ai.appdna.sdk.AppDNA.getCurrentAppId() ?: "")
        put("user_id", ai.appdna.sdk.AppDNA.getCurrentUserId() ?: "")
        put("conversation", JSONObject().apply {
            put("turn", turn)
            put("messages", msgsJson)
            put("user_message", messages.lastOrNull { it.role == ChatRole.USER }?.content ?: "")
            put("max_turns", maxTurns)
            put("remaining_turns", remaining)
        })
        // SPEC-070-A audit Round 2 finding 2: include `rating`, `context`,
        // and `responses` keys to match iOS `ChatStepView.swift:454-470`
        // ChatWebhookRequest. AI/webhook backends key off these for
        // chat satisfaction surveys + accumulated webhook_data carry-over.
        if (rating != null) put("rating", rating)
        if (context.isNotEmpty()) {
            put("context", JSONObject(context as Map<*, *>))
        }
        // `responses` mirrors iOS — top-level accumulated step responses.
        // Read from SessionDataStore which the host onboarding accumulator
        // writes into via `mergeData`.
        ai.appdna.sdk.core.SessionDataStore.instance?.let { sds ->
            val accumulated = sds.getSessionData("onboarding.responses") as? Map<*, *>
            if (accumulated != null && accumulated.isNotEmpty()) {
                put("responses", JSONObject(accumulated))
            }
        }
    }

    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        webhook.headers?.forEach { (k, v) -> setRequestProperty(k, v) }
        connectTimeout = (webhook.timeout_ms ?: 15000)
        readTimeout = (webhook.timeout_ms ?: 15000)
        doOutput = true
    }

    try {
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) {
            Log.e("ChatWebhook", "HTTP ${conn.responseCode}")
            return@withContext null
        }
        val respText = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(respText)

        val respMessages = json.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val mediaJson = m.optJSONObject("media")
                val media = mediaJson?.let { ChatMedia(type = it.optString("type", "image"), url = it.optString("url", null), alt_text = it.optString("alt_text", null)) }
                ChatWebhookMessage(content = m.optString("content", null), media = media, delay_ms = if (m.has("delay_ms")) m.getInt("delay_ms") else null)
            }
        }

        val respQRs = json.optJSONArray("quick_replies")?.let { arr ->
            (0 until arr.length()).map { i ->
                val q = arr.getJSONObject(i)
                ChatQuickReply(id = q.optString("id", "qr_$i"), text = q.optString("text", ""), show_at_turn = if (q.has("show_at_turn")) q.getInt("show_at_turn") else null)
            }
        }

        val respData = json.optJSONObject("data")?.let { d ->
            val map = mutableMapOf<String, Any>()
            d.keys().forEach { k -> map[k] = d.get(k) }
            map
        }

        WebhookResponse(
            action = json.optString("action", null),
            messages = respMessages,
            quick_replies = respQRs,
            data = respData,
            force_complete = if (json.has("force_complete")) json.getBoolean("force_complete") else null,
            completion_message = json.optString("completion_message", null)
        )
    } finally {
        conn.disconnect()
    }
}
