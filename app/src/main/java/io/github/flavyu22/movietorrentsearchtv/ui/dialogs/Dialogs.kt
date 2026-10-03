package io.github.flavyu22.movietorrentsearchtv.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.flavyu22.movietorrentsearchtv.model.AppStrings
import io.github.flavyu22.movietorrentsearchtv.ui.components.NetflixDialogButton
import io.github.flavyu22.movietorrentsearchtv.ui.theme.Grey

// ─── Dialog culori comune ──────────────────────────────────────────────────────
private val DialogBackground  = Color(0xFF1E1E1E)
private val DialogTitleColor  = Color.White
private val DialogTextColor   = Grey

// ─── Confirmare ieșire ─────────────────────────────────────────────────────────
@Composable
fun ExitDialog(s: AppStrings, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        title = s.exitTitle,
        message = s.exitMessage,
        confirmText = s.yes,
        dismissText = s.no,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// ─── Update disponibil ─────────────────────────────────────────────────────────
@Composable
fun UpdateDialog(s: AppStrings, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        title = s.updateTitle,
        message = s.updateMessage,
        confirmText = s.yes,
        dismissText = s.no,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// ─── Confirmare logout ─────────────────────────────────────────────────────────
@Composable
fun LogoutDialog(s: AppStrings, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        title = s.logout,
        message = s.logoutConfirm,
        confirmText = s.yes,
        dismissText = s.no,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// ─── Despre aplicație ─────────────────────────────────────────────────────────
@Composable
fun AboutDialog(s: AppStrings, onDismiss: () -> Unit) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { backFocus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            NetflixDialogButton(
                text = s.back,
                modifier = Modifier.focusRequester(backFocus),
                onClick = onDismiss,
            )
        },
        title = {
            Text(
                s.aboutTitle,
                color = DialogTitleColor,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column {
                Text(s.aboutText, color = DialogTextColor)
                Spacer(Modifier.height(16.dp))
                Text(s.developer, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        containerColor = DialogBackground,
    )
}

// ─── Dialog generic reutilizabil ───────────────────────────────────────────────
@Composable
private fun AppAlertDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { dismissFocus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { NetflixDialogButton(confirmText, onClick = onConfirm) },
        dismissButton = {
            NetflixDialogButton(
                text = dismissText,
                modifier = Modifier.focusRequester(dismissFocus),
                onClick = onDismiss,
            )
        },
        title = {
            Text(
                title,
                color = DialogTitleColor,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = { Text(message, color = DialogTextColor) },
        containerColor = DialogBackground,
    )
}

