package io.github.flavyu22.movietorrentsearchtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.flavyu22.movietorrentsearchtv.model.AppStrings
import io.github.flavyu22.movietorrentsearchtv.ui.theme.AlmostBlack
import io.github.flavyu22.movietorrentsearchtv.ui.theme.Grey
import io.github.flavyu22.movietorrentsearchtv.ui.theme.SoftBlue
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AppViewModel
import kotlinx.coroutines.delay

// ─── LoginScreen ───────────────────────────────────────────────────────────────
@Composable
fun LoginScreen(
    s: AppStrings,
    onSubmit: (String, String) -> Unit,
    savedUsername: String = "",
    isSetupMode: Boolean,
    authState: AppViewModel.AuthState,
    onInputChanged: () -> Unit,
    onResetCredential: () -> Unit,
) {
    var username        by remember(savedUsername) { mutableStateOf(savedUsername) }
    var password        by remember { mutableStateOf("") }
    var confirmation    by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var localError      by remember { mutableStateOf<String?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }

    val isUsernameEmpty by remember { derivedStateOf { username.isBlank() } }
    val isPasswordEmpty by remember { derivedStateOf { password.isBlank() } }
    val isLoading = authState is AppViewModel.AuthState.Authenticating
    val isLocked = authState is AppViewModel.AuthState.Locked
    val canSubmit = !isUsernameEmpty && !isPasswordEmpty &&
        (!isSetupMode || confirmation.isNotEmpty()) && !isLocked

    val errorMessage = localError ?: when (authState) {
        AppViewModel.AuthState.InvalidCredential -> s.loginError
        AppViewModel.AuthState.InvalidInput -> s.authCredentialHint
        AppViewModel.AuthState.StorageError -> s.authResetConfirm
        is AppViewModel.AuthState.Locked ->
            s.authLockedMessage.format(authState.remainingSeconds)
        AppViewModel.AuthState.Authenticating,
        AppViewModel.AuthState.Idle -> null
    }

    val usernameFocus    = remember { FocusRequester() }
    val passwordFocus    = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    val loginButtonFocus = remember { FocusRequester() }

    LaunchedEffect(isSetupMode) {
        delay(100)
        if (isSetupMode || savedUsername.isBlank()) usernameFocus.requestFocus()
        else passwordFocus.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(AlmostBlack),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.width(460.dp), // Ajustat pentru spațiu
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(40.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── Icon ──────────────────────────────────────────────────
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SoftBlue.copy(alpha = 0.15f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = if (isSetupMode) s.authSetupTitle else s.loginTitle,
                        modifier = Modifier.size(80.dp).padding(16.dp), // Mărit de la 72dp
                        tint = SoftBlue,
                    )
                }

                Spacer(Modifier.height(32.dp))

                Text(
                    text = if (isSetupMode) s.authSetupTitle else s.loginTitle,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isSetupMode) s.authSetupMessage else s.authUnlockMessage,
                    color = Grey,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(Modifier.height(24.dp))

                // ── Username ──────────────────────────────────────────────
                LoginTextField(
                    value = username,
                    onValueChange = {
                        username = it.take(64)
                        localError = null
                        onInputChanged()
                    },
                    label = s.username,
                    leadingIcon = Icons.Default.Person,
                    imeAction = ImeAction.Next,
                    onImeAction = { passwordFocus.requestFocus() },
                    focusRequester = usernameFocus,
                    isError = errorMessage != null && username.isBlank(),
                )

                Spacer(Modifier.height(16.dp))

                // ── Password ──────────────────────────────────────────────
                LoginTextField(
                    value = password,
                    onValueChange = {
                        password = it.take(128)
                        localError = null
                        onInputChanged()
                    },
                    label = s.password,
                    leadingIcon = Icons.Default.Lock,
                    imeAction = if (isSetupMode) ImeAction.Next else ImeAction.Done,
                    keyboardType = KeyboardType.Password,
                    onImeAction = {
                        if (isSetupMode) confirmationFocus.requestFocus()
                        else if (canSubmit) loginButtonFocus.requestFocus()
                    },
                    focusRequester = passwordFocus,
                    isError = errorMessage != null && password.isBlank(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = s.password,
                                tint = Grey,
                            )
                        }
                    },
                )

                if (isSetupMode) {
                    Spacer(Modifier.height(16.dp))
                    LoginTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it.take(128)
                            localError = null
                            onInputChanged()
                        },
                        label = s.authConfirmCredential,
                        leadingIcon = Icons.Default.Lock,
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Password,
                        onImeAction = {
                            if (canSubmit) loginButtonFocus.requestFocus()
                        },
                        focusRequester = confirmationFocus,
                        isError = localError != null,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    )
                }

                // ── Mesaj eroare ──────────────────────────────────────────
                AnimatedErrorMessage(message = errorMessage)

                Spacer(Modifier.height(24.dp))

                // ── Buton Login ───────────────────────────────────────────
                LoginButton(
                    text = if (isSetupMode) s.authSetupAction else s.login,
                    isLoading = isLoading,
                    enabled = canSubmit,
                    focusRequester = loginButtonFocus,
                    onClick = {
                        localError = null
                        if (isSetupMode && password != confirmation) {
                            localError = s.authCredentialMismatch
                        } else if (password.length < 4) {
                            localError = s.authCredentialHint
                        } else {
                            onSubmit(username.trim(), password)
                        }
                    },
                )

                if (authState is AppViewModel.AuthState.StorageError) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showResetConfirmation = true }) {
                        Text(s.authResetAction)
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = s.authCredentialHint,
                    color = Grey.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(s.authResetAction) },
            text = { Text(s.authResetConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        password = ""
                        confirmation = ""
                        onResetCredential()
                    },
                ) { Text(s.yes) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(s.cancel)
                }
            },
        )
    }
}

// ─── LoginTextField ────────────────────────────────────────────────────────────
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    imeAction: ImeAction,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeAction: () -> Unit,
    focusRequester: FocusRequester,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Grey) },
        singleLine = true,
        isError = isError,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF1A1A1A),
            unfocusedContainerColor = Color(0xFF1A1A1A),
            focusedBorderColor = SoftBlue,
            unfocusedBorderColor = Color(0xFF333333),
            errorBorderColor = Color(0xFFE53935),
        ),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        leadingIcon = { Icon(leadingIcon, contentDescription = label, tint = Grey) },
        trailingIcon = trailingIcon,
        // activăm meniul contextual pentru copy/paste/select all
        enabled = true,
        readOnly = false,
    )
}

// ─── Buton login cu loading state ─────────────────────────────────────────────
@Composable
private fun LoginButton(
    text: String,
    isLoading: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = Modifier.fillMaxWidth().height(60.dp).focusRequester(focusRequester), // Mărit de la 52dp
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) Color.White else SoftBlue,
            disabledContainerColor = SoftBlue.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = if (isFocused) SoftBlue else Color.White,
                modifier = Modifier.size(28.dp), // Mărit de la 24dp
                strokeWidth = 3.dp,
            )
        } else {
            Text(
                text,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 18.sp, // Mărit de la 16sp
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─── Mesaj eroare animat ───────────────────────────────────────────────────────
@Composable
private fun AnimatedErrorMessage(message: String?) {
    // Rezervă spațiu fix pentru a evita layout shift
    Box(modifier = Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
        if (message != null) {
            Text(
                text = message,
                color = Color(0xFFE53935),
                fontSize = 13.sp,
            )
        }
    }
}
