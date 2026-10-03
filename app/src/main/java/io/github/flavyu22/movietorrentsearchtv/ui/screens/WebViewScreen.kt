package io.github.flavyu22.movietorrentsearchtv.ui.screens

import android.os.SystemClock
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackIntentHelper
import io.github.flavyu22.movietorrentsearchtv.security.MagnetLinkValidator
import io.github.flavyu22.movietorrentsearchtv.ui.locals.LocalTranslationStrings
import java.net.URI
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Restricted in-app browser for HTTPS source pages, with a D-pad cursor for TV.
 * Untrusted schemes and insecure redirects are blocked. Magnet links are validated
 * and require a separate, explicit confirmation before leaving the WebView.
 */
@Composable
fun WebViewScreen(
    url: String,
    onBack: () -> Unit,
    onMagnetPlayed: () -> Unit = {},
) {
    val context = LocalContext.current
    val strings = LocalTranslationStrings.current
    val safeInitialUrl = remember(url) { validateHttpsUrl(url) }
    var cursor by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pendingMagnet by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val cursorStep = 40f

    if (safeInitialUrl == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    fun moveCursor(deltaX: Float, deltaY: Float) {
        val maxX = (viewportSize.width - 1).coerceAtLeast(0).toFloat()
        val maxY = (viewportSize.height - 1).coerceAtLeast(0).toFloat()
        cursor = Offset(
            x = (cursor.x + deltaX).coerceIn(0f, maxX),
            y = (cursor.y + deltaY).coerceIn(0f, maxY),
        )
    }

    fun moveCursorOrScroll(deltaX: Float, deltaY: Float) {
        val previous = cursor
        moveCursor(deltaX, deltaY)
        if (cursor == previous) {
            webViewInstance?.scrollBy((deltaX * 3).roundToInt(), (deltaY * 3).roundToInt())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { newSize ->
                if (viewportSize == IntSize.Zero) {
                    cursor = Offset(newSize.width / 2f, newSize.height / 2f)
                }
                viewportSize = newSize
                moveCursor(0f, 0f)
            }
            .focusRequester(focusRequester)
            .semantics {
                contentDescription = strings.browserContentDescription
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        moveCursorOrScroll(0f, -cursorStep)
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveCursorOrScroll(0f, cursorStep)
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursorOrScroll(-cursorStep, 0f)
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursorOrScroll(cursorStep, 0f)
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            webViewInstance?.dispatchClick(cursor.x, cursor.y)
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_PAGE_UP,
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                    AndroidKeyEvent.KEYCODE_BUTTON_L1 -> {
                        webViewInstance?.scrollBy(0, -(viewportSize.height * 0.8f).roundToInt())
                        true
                    }

                    AndroidKeyEvent.KEYCODE_PAGE_DOWN,
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                    AndroidKeyEvent.KEYCODE_BUTTON_R1 -> {
                        webViewInstance?.scrollBy(0, (viewportSize.height * 0.8f).roundToInt())
                        true
                    }

                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { browserContext ->
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                WebView(browserContext).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    settings.apply {
                        javaScriptEnabled = false
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        mediaPlaybackRequiresUserGesture = true
                        setGeolocationEnabled(false)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            safeBrowsingEnabled = true
                        }
                        userAgentString = "$userAgentString MovieTorrentSearchTV/${BuildConfig.VERSION_NAME}"
                    }
                    CookieManager.getInstance().setAcceptCookie(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val requestedUrl = request?.url?.toString() ?: return blockedResponse()
                            return if (validateHttpsUrl(requestedUrl) == null) {
                                blockedResponse()
                            } else {
                                super.shouldInterceptRequest(view, request)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val requestedUrl = request?.url?.toString() ?: return true
                            MagnetLinkValidator.validate(requestedUrl)?.let { validatedMagnet ->
                                if (request.hasGesture()) pendingMagnet = validatedMagnet
                                return true
                            }
                            return validateHttpsUrl(requestedUrl) == null
                        }
                    }
                    webViewInstance = this
                    loadUrl(safeInitialUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { releasedWebView ->
                if (webViewInstance === releasedWebView) webViewInstance = null
                releasedWebView.stopLoading()
                releasedWebView.onPause()
                releasedWebView.webViewClient = WebViewClient()
                releasedWebView.loadUrl("about:blank")
                releasedWebView.clearHistory()
                releasedWebView.removeAllViews()
                releasedWebView.destroy()
            },
        )

        Box(
            Modifier
                .offset { IntOffset(cursor.x.roundToInt(), cursor.y.roundToInt()) }
                .size(12.dp)
                .background(Color.White, CircleShape)
                .border(1.dp, Color.Black, CircleShape)
                .clearAndSetSemantics { },
        )
    }

    pendingMagnet?.let { magnet ->
        val dismissFocus = remember { FocusRequester() }
        LaunchedEffect(magnet) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { dismissFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { pendingMagnet = null },
            title = { Text(strings.magnetConfirmTitle) },
            text = { Text(strings.magnetConfirmMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingMagnet = null
                        // Arm the two-minute playback auto-exit, same as the other
                        // playback entry points, so resources are always freed.
                        onMagnetPlayed()
                        PlaybackIntentHelper.playFallback(
                            context,
                            magnet,
                            strings.magnetSources,
                            isMagnet = true,
                            strings = strings,
                        )
                    },
                ) {
                    Text(strings.yes)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingMagnet = null },
                    modifier = Modifier.focusRequester(dismissFocus),
                ) {
                    Text(strings.cancel)
                }
            },
        )
    }

    BackHandler {
        val webView = webViewInstance
        if (webView?.canGoBack() == true) webView.goBack() else onBack()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webViewInstance) {
        val browser = webViewInstance
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> browser?.onResume()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> browser?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            browser?.onResume()
        } else {
            browser?.onPause()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            browser?.onPause()
        }
    }
    LaunchedEffect(pendingMagnet) {
        if (pendingMagnet == null) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
}

private fun WebView.dispatchClick(x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(downTime, downTime + 50L, MotionEvent.ACTION_UP, x, y, 0)
    try {
        dispatchTouchEvent(down)
        dispatchTouchEvent(up)
    } finally {
        down.recycle()
        up.recycle()
    }
}

internal fun validateHttpsUrl(value: String): String? {
    val candidate = value.trim()
    if (candidate.length !in 1..2_048 || candidate.any(Char::isISOControl)) return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        uri.host.isNullOrBlank() ||
        uri.userInfo != null ||
        uri.port !in listOf(-1, 443)
    ) return null
    val host = uri.host.lowercase(Locale.ROOT)
    if (host == "localhost" ||
        host.endsWith(".localhost") ||
        host.endsWith(".local") ||
        host.endsWith(".lan") ||
        host.endsWith(".internal") ||
        host.endsWith(".home") ||
        host.endsWith(".arpa") ||
        ':' in host ||
        IPV4_LITERAL.matches(host) ||
        '.' !in host
    ) return null
    return uri.toASCIIString()
}

private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    Charsets.UTF_8.name(),
    ByteArrayInputStream(byteArrayOf()),
)
