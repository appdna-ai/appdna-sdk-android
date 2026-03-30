package ai.appdna.sdk.onboarding

import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onSkip: () -> Unit
) {
    val chatConfig = step.config.chat_config ?: return
    val style = chatConfig.style
    val persona = chatConfig.persona
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // State
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var userTurnCount by remember { mutableIntStateOf(0) }
    var isCompleted by remember { mutableStateOf(false) }
    var currentRating by remember { mutableStateOf<Int?>(null) }
    var dynamicQuickReplies by remember { mutableStateOf(chatConfig.quick_replies?.filter { (it.show_at_turn ?: 0) == 0 } ?: emptyList()) }
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

    // Auto-messages on appear
    LaunchedEffect(Unit) {
        val autoMsgs = chatConfig.auto_messages?.filter { it.turn == 0 } ?: emptyList()
        for ((i, autoMsg) in autoMsgs.withIndex()) {
            val delayMs = autoMsg.delay_ms ?: (500 + i * 1200)
            isTyping = true
            delay(delayMs.toLong())
            isTyping = false
            messages = messages + ChatMessage(id = autoMsg.id, role = ChatRole.AI, content = autoMsg.content, media = autoMsg.media)
        }
    }

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

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
                val response = fireWebhook(chatConfig.webhook, flowId, step.id, messages, userTurnCount - 1, maxTurns, turnsRemaining)
                isTyping = false
                if (response != null) {
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
                    // Avatar: initial-based circle
                    persona?.name?.firstOrNull()?.let { initial ->
                        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(hex(style?.ai_bubble_bg, "#1E293B")), contentAlignment = Alignment.Center) {
                            Text(initial.toString(), color = hex(style?.ai_bubble_text, "#E2E8F0"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            // Avatar
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(aiBubbleBg), contentAlignment = Alignment.Center) {
                                Text((persona?.name?.take(1) ?: "A"), color = aiBubbleTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                msg.content, color = aiBubbleTextColor, fontSize = 14.sp,
                                modifier = Modifier.weight(1f, fill = false).background(aiBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                            )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(aiBubbleBg), contentAlignment = Alignment.Center) {
                            Text((persona?.name?.take(1) ?: "A"), color = aiBubbleTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.background(aiBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                        ) {
                            repeat(3) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(typingDotColor.copy(alpha = 0.6f)))
                            }
                        }
                    }
                }
            }
        }

        // Quick replies
        if (dynamicQuickReplies.isNotEmpty() && !isCompleted && !isTyping) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
            // Completion CTA
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = userBubbleBg)
                ) {
                    Text(chatConfig.completion_cta_text ?: step.config.cta_text ?: "Continue", color = userBubbleTextColor, fontWeight = FontWeight.Bold)
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
                    onValueChange = { inputText = it },
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

@Suppress("UNCHECKED_CAST")
private suspend fun fireWebhook(
    webhook: StepHookConfig?,
    flowId: String,
    stepId: String,
    messages: List<ChatMessage>,
    turn: Int,
    maxTurns: Int,
    remaining: Int
): WebhookResponse? {
    webhook ?: return null
    val url = webhook.webhook_url ?: return null

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
            return null
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

        return WebhookResponse(
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
