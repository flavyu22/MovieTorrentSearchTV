package io.github.flavyu22.movietorrentsearchtv.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type


import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.flavyu22.movietorrentsearchtv.ui.theme.SoftBlue
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import io.github.flavyu22.movietorrentsearchtv.util.RemoteUrlPolicy

// ─── Focus Effect Helper (simplificat) ────────────────────────────────────────
// Un singur inel ALB în jurul elementului focalizat. Fără dublu-glow colorat —
// era costisitor pe TV-uri entry-level și încurca vizual. Borderul alb simplify
// este suficient de vizibil pe orice panou TV și are cost GPU minim.
private fun Modifier.focusGlow(isFocused: Boolean, @Suppress("UNUSED_PARAMETER") color: Color) = this.drawBehind {
    if (isFocused) {
        val pad = 3.dp.toPx()
        val stroke = 3.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(-pad, -pad),
            size = Size(size.width + pad * 2f, size.height + pad * 2f),
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            style = Stroke(width = stroke)
        )
    }
}

private val MoviePosterShape = RoundedCornerShape(12.dp)
private val PosterPlaceholderBrush =
    Brush.verticalGradient(listOf(Color(0xFF222222), Color(0xFF111111)))
private val PosterFooterBrush =
    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)))

/**
 * Poster URLs can be restored from old local history as well as current APIs. Keep Coil away
 * from loopback/LAN and credential-bearing URLs even if those persisted values predate the
 * catalogue sanitizers.
 */
internal fun validatedPosterUrl(value: String?): String? =
    RemoteUrlPolicy.allowlistedHttps(
        value,
        RemoteHosts.catalogueImageHostSuffixes,
        upgradeCleartext = true,
    )

/** Picks the first poster candidate that is both non-empty and allowed by the URL policy. */
internal fun preferredPosterUrl(vararg candidates: String?): String? =
    candidates.firstNotNullOfOrNull(::validatedPosterUrl)

// ─── Netflix Header Button (Ultra-Pro) ────────────────────────────────────────
@Composable
fun NetflixHeaderButton(
    text: String,
    active: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // FOCUS SIMPLU: fără scale animat — doar culoarea/borderul schimbă la focus.

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> Color.White
                active    -> Color(0xFF444444)
                else      -> Color(0xFF222222).copy(alpha = 0.5f)
            }
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        border = if (isFocused) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        icon?.let {
            Icon(it, null, modifier = Modifier.size(20.dp), tint = if (isFocused) Color.Black else Color.White)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

// ─── Horizontal Selection Menu (Ultra-Pro) ───────────────────────────────────
@Composable
fun HorizontalSelectionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<String>,
    selectedItem: String,
    itemLabel: (String) -> String = { it },
    onItemSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val focusItem = selectedItem.takeIf { it in items } ?: items.firstOrNull()
    LaunchedEffect(expanded, focusItem, items) {
        if (expanded && focusItem != null) {
            listState.scrollToItem(items.indexOf(focusItem).coerceAtLeast(0))
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(100)) + shrinkVertically(animationSpec = tween(100))
    ) {
        Popup(
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, 80)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp).focusGlow(true, SoftBlue),
                color = Color(0xFF1A1A1A).copy(alpha = 0.98f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                tonalElevation = 8.dp
            ) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items, key = { it }) { item ->
                        val isSelected = item == selectedItem
                        val itemFocusRequester = remember { FocusRequester() }
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()

                        Surface(
                            onClick = { onItemSelected(item) },
                            interactionSource = interactionSource,
                            modifier = Modifier.focusRequester(itemFocusRequester),
                            color = when {
                                isFocused  -> Color.White
                                isSelected -> SoftBlue.copy(alpha = 0.25f)
                                else       -> Color.Transparent
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = if (isSelected && !isFocused) BorderStroke(1.5.dp, SoftBlue) else null,
                        ) {
                            Text(
                                text = itemLabel(item),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                color = when {
                                    isFocused  -> Color.Black
                                    isSelected -> SoftBlue
                                    else       -> Color.White
                                },
                                fontSize = 17.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }

                        if (item == focusItem && expanded) {
                            LaunchedEffect(item, expanded) {
                                androidx.compose.runtime.withFrameNanos { }
                                runCatching { itemFocusRequester.requestFocus() }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Movie Poster Card (Ultra-Pro with Glow) ──────────────────────────────────
@Composable
fun MoviePosterCard(
    title: String?,
    imageRequest: ImageRequest,
    isFocused: Boolean,
    scale: () -> Float,
    isSeries: Boolean = false,
    label: String = "",
    year: String? = null,
    genre: String? = null,
    quality: String? = null,
    fallbackTitle: String = "Movie",
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var imageLoadingError by remember(imageRequest) { mutableStateOf(false) }
    val hasPoster = remember(imageRequest) {
        imageRequest.data.toString().let { it.isNotBlank() && it != "null" }
    }

    val accessibilityDescription = remember(title, label, year, genre, quality, fallbackTitle) {
        listOfNotNull(
            title?.takeIf(String::isNotBlank) ?: fallbackTitle,
            label.takeIf(String::isNotBlank),
            year?.takeIf(String::isNotBlank),
            genre?.takeIf(String::isNotBlank),
            quality?.takeIf(String::isNotBlank),
                ).joinToString(", ")
    }

    // TV remote-first: make each poster an explicit, always-focusable target so
    // the LazyVerticalGrid receives D-pad focus on every card (DPAD_CENTER then
    // fires Surface.onClick). Without focusable(), some OEM TV firmware skips
    // posters and series rows become unreachable by remote.
    // FIX: pass the SHARED interactionSource — without it focusable() creates an
    // internal source, `isFocused` (collectIsFocusedAsState) never becomes true,
    // and the focus glow/border/scale never render (focus looks invisible).
    // FIX #2 (single click): on Android TV the first DPAD_CENTER/ENTER key event is
    // consumed by the focus system, so a focused card needs a *second* press to fire
    // Surface.onClick. Intercept the select key here and trigger onClick() directly
    // so a single press opens the movie/series details.
    val tvFocusable: Modifier = modifier
        .onKeyEvent { keyEvent ->
            val isSelectPress = keyEvent.type == KeyEventType.KeyUp &&
                (keyEvent.key == Key.DirectionCenter ||
                    keyEvent.key == Key.Enter ||
                    keyEvent.key == Key.NumPadEnter ||
                    keyEvent.key == Key.ButtonA ||
                    keyEvent.key == Key.ButtonSelect)
            if (isSelectPress) {
                onClick()
                true
            } else {
                false
            }
        }
        .focusable(interactionSource = interactionSource)

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = tvFocusable
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDescription
            }
            // FOCUS SIMPLU: doar border alb (fără glow dublu — mai rapid pe TV-uri
            // slabe). Borderul este desenat de Surface, deci un singur draw pass.
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
                clip = true
                shape = MoviePosterShape
            },
        shape = MoviePosterShape,
        color = Color(0xFF111111),
        border = BorderStroke(
            width = if (isFocused) 3.dp else 1.dp,
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.08f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(PosterPlaceholderBrush)
                ) {
                    if (!imageLoadingError && hasPoster) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onError = { imageLoadingError = true }
                        )
                    }

                    if (imageLoadingError || !hasPoster) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(12.dp)
                        ) {
                            Text("📽️", fontSize = 32.sp, modifier = Modifier.graphicsLayer { alpha = 0.5f })
                            Spacer(Modifier.height(8.dp))
                            if (!title.isNullOrBlank()) {
                                Text(
                                    title,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A focus change must never resize a lazy-grid item. The old variable
                        // height invalidated the whole grid layout on every D-pad movement.
                        .height(30.dp)
                        .drawBehind {
                            drawRect(brush = PosterFooterBrush)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.drawWithContent {
                                if (isFocused) drawContent()
                            },
                        )
                    }
                }
            }

            // High-End Badge
            if (label.isNotEmpty()) {
                Surface(
                    color = (if (isSeries) SoftBlue else Color(0xFFE50914)).copy(alpha = 0.9f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = label.uppercase(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

// ─── Centered Loading Indicator (Pro Shimmer Style) ──────────────────────────
@Composable
fun CenteredLoadingIndicator(
    message: String = "Loading…",
    color: Color = SoftBlue,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loader")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    contentDescription = message
                }
        ) {
            CircularProgressIndicator(
                color = color, 
                strokeWidth = 5.dp,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(message, color = color,
                 fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun TorrserverErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFFFF5252).copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            contentDescription = message
        }
    ) {
        Text(
            text = "⚠️ $message",
            color = Color(0xFFFF8A80),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun TorrserverProcessingBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            contentDescription = message
        },
        color = Color(0xFF222222),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SoftBlue.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = SoftBlue, modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Text(message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun NetflixPaginationButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) Color.White else Color(0xFF222222),
            disabledContainerColor = Color(0xFF111111)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (isFocused) Color.Black else if (enabled) Color.White else Color.Gray,
             fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NetflixDialogButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) Color.White else Color(0xFF333333)
        ),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
    }
}

// ─── Source Loading Chip (MovieDetails) ─────────────────────────────────────
@Composable
fun SourceLoadingChip(sourceName: String, resultCount: Int) {
    Surface(
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            if (resultCount == 0) {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
            contentDescription = "$sourceName: $resultCount"
        },
        color = Color(0xFF333333),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SoftBlue.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (resultCount == 0) {
                CircularProgressIndicator(
                    color = SoftBlue,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = resultCount.toString(),
                    color = SoftBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = sourceName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─── Quality Badge (MovieDetails) ───────────────────────────────────────────
@Composable
fun QualityBadge(quality: String, isFocused: Boolean) {
    val color = when (quality.uppercase()) {
        "4K", "UHD" -> Color(0xFFE50914)
        "1080P", "FHD" -> SoftBlue
        "720P", "HD" -> Color(0xFF4CAF50)
        else -> Color.Gray
    }
    
    Surface(
        color = if (isFocused) Color.White else color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp),
        border = if (isFocused) null else BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = quality.uppercase(),
            color = if (isFocused) Color.Black else color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun FocusableIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tintNormal: Color = Color.White,
    tintFocused: Color = Color.Black,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = if (isFocused) Color.White else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isFocused) tintFocused else tintNormal,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
