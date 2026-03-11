package ai.appdna.sdk.messages

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.NetworkImage
import ai.appdna.sdk.core.StyleEngine
import kotlinx.coroutines.delay

/**
 * SPEC-084: Complete in-app message renderer for Android.
 * Renders banner, modal, fullscreen, and tooltip message types
 * with configurable styling (text_color, button_color, corner_radius, background_color).
 */

@Composable
fun InAppMessageView(
    config: MessageConfig,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (config.message_type) {
        MessageType.BANNER -> BannerMessageView(config.content, onCTATap, onDismiss)
        MessageType.MODAL -> ModalMessageView(config.content, onCTATap, onDismiss)
        MessageType.FULLSCREEN -> FullscreenMessageView(config.content, onCTATap, onDismiss)
        MessageType.TOOLTIP -> TooltipMessageView(config.content, onCTATap, onDismiss)
    }
}

// MARK: - Banner

@Composable
private fun BannerMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTop = content.banner_position != "bottom"
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
        content.auto_dismiss_seconds?.let { seconds ->
            if (seconds > 0) {
                delay(seconds * 1000L)
                isVisible = false
                delay(300L)
                onDismiss()
            }
        }
    }

    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: Color.White
    val cornerRadius = content.corner_radius ?: 12

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(initialOffsetY = { if (isTop) -it else it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { if (isTop) -it else it }) + fadeOut(),
            modifier = Modifier.align(if (isTop) Alignment.TopCenter else Alignment.BottomCenter),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(if (isTop) PaddingValues(top = 8.dp) else PaddingValues(bottom = 8.dp)),
                shape = RoundedCornerShape(cornerRadius.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Text content
                        Column(modifier = Modifier.weight(1f)) {
                            content.title?.let {
                                Text(
                                    text = it,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor,
                                )
                            }
                            content.body?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    color = if (textColor != Color.Unspecified)
                                        textColor.copy(alpha = 0.7f)
                                    else Color.Gray,
                                )
                            }
                        }

                        // CTA button
                        content.cta_text?.let { ctaText ->
                            Button(
                                onClick = onCTATap,
                                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                shape = RoundedCornerShape(cornerRadius.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(ctaText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Dismiss button
                        IconButton(
                            onClick = {
                                isVisible = false
                                onDismiss()
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Text("✕", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    // Secondary CTA (Gap #18)
                    content.secondary_cta_text?.let { secondaryText ->
                        TextButton(
                            onClick = { onDismiss() },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = secondaryText,
                                color = if (textColor != Color.Unspecified)
                                    textColor.copy(alpha = 0.6f)
                                else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Modal

@Composable
private fun ModalMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: Color.White
    val cornerRadius = content.corner_radius ?: 20

    // Backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // Modal card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clickable(enabled = false, onClick = {}), // Prevent click-through
            shape = RoundedCornerShape(cornerRadius.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Dismiss button
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Text("✕", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                    }
                }

                // Optional image
                content.image_url?.let { imageUrl ->
                    NetworkImage(
                        url = imageUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Title
                content.title?.let {
                    Text(
                        text = it,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = textColor,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Body
                content.body?.let {
                    Text(
                        text = it,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        color = if (textColor != Color.Unspecified)
                            textColor.copy(alpha = 0.7f)
                        else Color.Gray,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // CTA button
                content.cta_text?.let { ctaText ->
                    Button(
                        onClick = onCTATap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                        shape = RoundedCornerShape((content.corner_radius ?: 12).dp),
                    ) {
                        Text(ctaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                // Secondary CTA (Gap #18)
                content.secondary_cta_text?.let { secondaryText ->
                    TextButton(onClick = { onDismiss() }) {
                        Text(
                            text = secondaryText,
                            color = if (textColor != Color.Unspecified)
                                textColor.copy(alpha = 0.6f)
                            else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }

                // Dismiss text
                content.dismiss_text?.let { dismissText ->
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(dismissText, fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

// MARK: - Fullscreen

@Composable
private fun FullscreenMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: Color.White
    val cornerRadius = content.corner_radius ?: 14

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Optional image
            content.image_url?.let { imageUrl ->
                NetworkImage(
                    url = imageUrl,
                    modifier = Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Title
            content.title?.let {
                Text(
                    text = it,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = textColor,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Body
            content.body?.let {
                Text(
                    text = it,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = if (textColor != Color.Unspecified)
                        textColor.copy(alpha = 0.7f)
                    else Color.Gray,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // CTA button
            content.cta_text?.let { ctaText ->
                Button(
                    onClick = onCTATap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    shape = RoundedCornerShape(cornerRadius.dp),
                ) {
                    Text(ctaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // Secondary CTA (Gap #18)
            content.secondary_cta_text?.let { secondaryText ->
                TextButton(onClick = { onDismiss() }) {
                    Text(
                        text = secondaryText,
                        color = if (textColor != Color.Unspecified)
                            textColor.copy(alpha = 0.6f)
                        else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                }
            }

            // Dismiss text
            content.dismiss_text?.let { dismissText ->
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(dismissText, fontSize = 14.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(36.dp)
                .background(Color.LightGray.copy(alpha = 0.5f), CircleShape),
        ) {
            Text("✕", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
        }
    }
}

// MARK: - Tooltip

@Composable
private fun TooltipMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: Color.White
    val cornerRadius = content.corner_radius ?: 12

    // Overlay with semi-transparent backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(enabled = false, onClick = {}),
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 280.dp),
                shape = RoundedCornerShape(cornerRadius.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Title
                    content.title?.let {
                        Text(
                            text = it,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = textColor,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Body
                    content.body?.let {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = if (textColor != Color.Unspecified)
                                textColor.copy(alpha = 0.7f)
                            else Color.Gray,
                        )
                    }

                    // CTA button
                    content.cta_text?.let { ctaText ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onCTATap,
                            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                            shape = RoundedCornerShape(cornerRadius.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(ctaText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Secondary CTA (Gap #18)
                    content.secondary_cta_text?.let { secondaryText ->
                        TextButton(onClick = { onDismiss() }) {
                            Text(
                                text = secondaryText,
                                color = if (textColor != Color.Unspecified)
                                    textColor.copy(alpha = 0.6f)
                                else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            // Pointer arrow (Gap #17)
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .rotate(45f)
                    .offset(y = (-6).dp)
                    .background(bgColor),
            )
        }
    }
}
