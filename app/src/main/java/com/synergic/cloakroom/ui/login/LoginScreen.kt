package com.synergic.cloakroom.ui.login

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.R
import com.synergic.cloakroom.ui.components.ErrorBanner

/** Brand colours of the login design (teal); the rest of the app keeps its own theme. */
private object LoginColors {
    val TealLight = Color(0xFF0E8783)
    val TealDark = Color(0xFF045E5C)
    val Page = Color(0xFFF4F7F7)
    val Heading = Color(0xFF1B2526)
    val Muted = Color(0xFF6B7A7B)
    val FieldFill = Color(0xFFF5F8F8)
    val FieldBorder = Color(0xFFDCE4E5)
    val Gradient = Brush.linearGradient(listOf(TealLight, TealDark))
}

private const val SUPPORT_EMAIL = "info@synergicsoftek.in"

/** [message] explains why the user is here, for example after a forced logout. */
@Composable
fun LoginScreen(message: String?, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LightStatusBarIcons()

    Box(
        Modifier
            .fillMaxSize()
            .background(LoginColors.Page)
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Header()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 228.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = LoginColors.TealDark.copy(alpha = 0.25f), spotColor = LoginColors.TealDark.copy(alpha = 0.25f)),
            ) {
                LoginForm(
                    state = state,
                    message = message,
                    onUserId = viewModel::onUserIdChange,
                    onPassword = viewModel::onPasswordChange,
                    onLogin = viewModel::login,
                )
            }
            Footer(Modifier.padding(top = 32.dp, bottom = 24.dp))
        }
    }

    state.update?.let { update ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            title = { Text("Update required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This version of the app can no longer log in. Install " +
                            (update.versionName?.let { "version $it" } ?: "the latest version") + " and try again.",
                    )
                    update.releaseNotes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (update.downloadUrl == null) Text("Ask your admin for the new app.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                update.downloadUrl?.let { url ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }) { Text("Download update") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissUpdate) { Text("Close") } },
        )
    }
}

/** White status bar icons over the teal header; the app's usual icons come back on leaving. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

/** Hilt wraps the activity's context, so walk the wrappers to find the activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Teal header with soft circles, the app icon, title and tagline. */
@Composable
private fun Header() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(270.dp)
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(LoginColors.Gradient),
    ) {
        // Decorative circles, as in the design.
        Box(
            Modifier
                .size(260.dp)
                .offset(x = 230.dp, y = (-110).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
        )
        Box(
            Modifier
                .size(170.dp)
                .offset(x = (-70).dp, y = 170.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Train, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Railway Cloak Room",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Easy luggage Check-in & Check-out",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginUiState,
    message: String?,
    onUserId: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val canLogin = state.userId.isNotBlank() && state.password.isNotEmpty() && !state.loading

    Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp)) {
        Text("Welcome Back", color = LoginColors.Heading, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text("Sign in to your account to continue", color = LoginColors.Muted, fontSize = 15.sp)

        (state.error ?: message)?.let {
            Spacer(Modifier.height(16.dp))
            ErrorBanner(it)
        }

        Spacer(Modifier.height(24.dp))
        FieldLabel("User ID")
        LoginField(
            value = state.userId,
            onValueChange = onUserId,
            placeholder = "Enter your user ID",
            icon = Icons.Outlined.Person,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        )

        Spacer(Modifier.height(18.dp))
        FieldLabel("Password")
        LoginField(
            value = state.password,
            onValueChange = onPassword,
            placeholder = "Enter your password",
            icon = Icons.Outlined.Lock,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canLogin) onLogin() }),
            trailing = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = LoginColors.Muted,
                    )
                }
            },
        )

        Spacer(Modifier.height(28.dp))
        LoginButton(enabled = canLogin, loading = state.loading, onClick = onLogin)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LoginColors.Heading, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = LoginColors.Muted.copy(alpha = 0.8f)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = LoginColors.Muted) },
        trailingIcon = trailing,
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LoginColors.FieldFill,
            unfocusedContainerColor = LoginColors.FieldFill,
            focusedBorderColor = LoginColors.TealLight,
            unfocusedBorderColor = LoginColors.FieldBorder,
            cursorColor = LoginColors.TealLight,
            focusedTextColor = LoginColors.Heading,
            unfocusedTextColor = LoginColors.Heading,
        ),
        modifier = Modifier.fillMaxWidth().height(58.dp),
    )
}

/** Teal gradient button "Login →"; a spinner while logging in. */
@Composable
private fun LoginButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(if (enabled) 12.dp else 0.dp, shape, spotColor = LoginColors.TealLight, ambientColor = LoginColors.TealLight)
            .clip(shape)
            .background(LoginColors.Gradient)
            .alpha(if (enabled || loading) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Login", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(10.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** "DEVELOPED BY" Synergic Softek Solutions, with the support e-mail (opens the mail app). */
@Composable
private fun Footer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DEVELOPED BY", color = LoginColors.Muted, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Image(
            painter = painterResource(R.drawable.synergic_logo),
            contentDescription = "Synergic Softek Solutions Pvt. Ltd",
            modifier = Modifier.width(170.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            SUPPORT_EMAIL,
            color = LoginColors.TealLight,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))) } }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
