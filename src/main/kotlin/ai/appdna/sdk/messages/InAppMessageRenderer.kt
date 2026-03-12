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
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.ConfettiOverlay
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.resolveIcon
import ai.appdna.sdk.core.applyBlurBackdrop
import androidx.compose.ui.platform.LocalView
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
    // SPEC-088: Interpolate text fields via TemplateEngine before rendering
    val ctx = ai.appdna.sdk.core.TemplateEngine.buildContext()
    val e = ai.appdna.sdk.core.TemplateEngine
    val interpolated = config.content.copy(
        title = config.content.title?.let { e.interpolate(it, ctx) },
        body = config.content.body?.let { e.interpolate(it, ctx) },
        cta_text = config.content.cta_text?.let { e.interpolate(it, ctx) },
        secondary_cta_text = config.content.secondary_cta_text?.let { e.interpolate(it, ctx) },
        dismiss_text = config.content.dismiss_text?.let { e.interpolate(it, ctx) },
    )

    when (config.message_type) {
        MessageType.BANNER -> BannerMessageView(interpolated, onCTATap, onDismiss)
        MessageType.MODAL -> ModalMessageView(interpolated, onCTATap, onDismiss)
        MessageType.FULLSCREEN -> FullscreenMessageView(interpolated, onCTATap, onDismiss)
        MessageType.TOOLTIP -> TooltipMessageView(interpolated, onCTATap, onDismiss)
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

                        // CTA button with optional icon
                        content.cta_text?.let { ctaText ->
                            Button(
                                onClick = onCTATap,
                                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                shape = RoundedCornerShape(cornerRadius.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                // SPEC-085: CTA icon
                                val ctaIcon = resolveIcon(content.cta_icon)
                                if (ctaIcon != null) {
                                    IconView(ref = ctaIcon, defaultSize = 12f)
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
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
                            Text("\u2715", fontSize = 12.sp, color = Color.Gray)
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
    val currentView = LocalView.current
    var showConfetti by remember { mutableStateOf(false) }

    // SPEC-085: Haptic on appear
    LaunchedEffect(Unit) {
        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
        // Auto-trigger confetti on appear
        if (content.particle_effect?.trigger == "on_appear") {
            showConfetti = true
        }
    }

    // Backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // SPEC-085: Blur backdrop for glassmorphism
                if (content.blur_backdrop != null) {
                    Modifier.applyBlurBackdrop(content.blur_backdrop, 0f)
                } else {
                    Modifier.background(Color.Black.copy(alpha = 0.5f))
                }
            )
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
                        Text("\u2715", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                    }
                }

                // SPEC-085: Lottie/Rive hero (preferred over static image)
                if (content.lottie_url != null) {
                    LottieBlockView(
                        block = LottieBlock(
                            lottie_url = content.lottie_url,
                            autoplay = true,
                            loop = true,
                            height = 160f,
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (content.rive_url != null) {
                    RiveBlockView(
                        block = RiveBlock(
                            rive_url = content.rive_url,
                            height = 160f,
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (content.video_url != null) {
                    // SPEC-085: Video hero
                    CoreVideoBlockView(
                        block = CoreVideoBlock(
                            video_url = content.video_url,
                            video_thumbnail_url = content.video_thumbnail_url ?: content.image_url,
                            video_height = 160f,
                            video_corner_radius = 8f,
                            autoplay = true,
                            muted = true,
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    // Fallback: Optional static image
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

                // CTA button with optional icon
                content.cta_text?.let { ctaText ->
                    Button(
                        onClick = {
                            // SPEC-085: Haptic on CTA tap
                            HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
                            onCTATap()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                        shape = RoundedCornerShape((content.corner_radius ?: 12).dp),
                    ) {
                        // SPEC-085: CTA icon
                        val ctaIcon = resolveIcon(content.cta_icon)
                        if (ctaIcon != null) {
                            IconView(ref = ctaIcon, defaultSize = 18f)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(ctaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                // Secondary CTA with optional icon (Gap #18)
                content.secondary_cta_text?.let { secondaryText ->
                    TextButton(onClick = { onDismiss() }) {
                        val secondaryIcon = resolveIcon(content.secondary_cta_icon)
                        if (secondaryIcon != null) {
                            IconView(ref = secondaryIcon, defaultSize = 14f)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
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

        // SPEC-085: Confetti overlay
        if (showConfetti && content.particle_effect != null) {
            ConfettiOverlay(
                effect = content.particle_effect,
                trigger = showConfetti,
            )
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
    val currentView = LocalView.current
    var showConfetti by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (content.particle_effect?.trigger == "on_appear") {
            showConfetti = true
        }
    }

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

            // SPEC-085: Lottie/Rive/Video hero (preferred over static image)
            if (content.lottie_url != null) {
                LottieBlockView(
                    block = LottieBlock(
                        lottie_url = content.lottie_url,
                        autoplay = true,
                        loop = true,
                        height = 280f,
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else if (content.rive_url != null) {
                RiveBlockView(
                    block = RiveBlock(
                        rive_url = content.rive_url,
                        height = 280f,
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else if (content.video_url != null) {
                CoreVideoBlockView(
                    block = CoreVideoBlock(
                        video_url = content.video_url,
                        video_thumbnail_url = content.video_thumbnail_url ?: content.image_url,
                        video_height = 280f,
                        video_corner_radius = 16f,
                        autoplay = true,
                        muted = true,
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                // Fallback: Optional static image
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

            // CTA button with optional icon
            content.cta_text?.let { ctaText ->
                Button(
                    onClick = {
                        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
                        onCTATap()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    shape = RoundedCornerShape(cornerRadius.dp),
                ) {
                    val ctaIcon = resolveIcon(content.cta_icon)
                    if (ctaIcon != null) {
                        IconView(ref = ctaIcon, defaultSize = 18f)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(ctaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // Secondary CTA with optional icon (Gap #18)
            content.secondary_cta_text?.let { secondaryText ->
                TextButton(onClick = { onDismiss() }) {
                    val secondaryIcon = resolveIcon(content.secondary_cta_icon)
                    if (secondaryIcon != null) {
                        IconView(ref = secondaryIcon, defaultSize = 14f)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
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
            Text("\u2715", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
        }

        // SPEC-085: Confetti overlay
        if (showConfetti && content.particle_effect != null) {
            ConfettiOverlay(
                effect = content.particle_effect,
                trigger = showConfetti,
            )
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

                    // CTA button with optional icon
                    content.cta_text?.let { ctaText ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onCTATap,
                            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                            shape = RoundedCornerShape(cornerRadius.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            // SPEC-085: CTA icon
                            val ctaIcon = resolveIcon(content.cta_icon)
                            if (ctaIcon != null) {
                                IconView(ref = ctaIcon, defaultSize = 12f)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(ctaText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Secondary CTA with optional icon (Gap #18)
                    content.secondary_cta_text?.let { secondaryText ->
                        TextButton(onClick = { onDismiss() }) {
                            val secondaryIcon = resolveIcon(content.secondary_cta_icon)
                            if (secondaryIcon != null) {
                                IconView(ref = secondaryIcon, defaultSize = 13f)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
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
