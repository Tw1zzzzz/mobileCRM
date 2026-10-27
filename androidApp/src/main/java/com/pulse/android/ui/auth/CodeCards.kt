package com.pulse.android.ui.auth

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientButton
import com.pulse.core.auth.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The server lets a new code go out a minute after the previous one. */
private const val RESEND_DELAY_MS = 60_000L
private const val CODE_LENGTH = 6
private const val MS_PER_SECOND = 1_000L

/** After sign-up (or a sign-in with an unverified address): the 6-digit code from the email signs in. */
@Composable
fun VerifyEmailCard(session: SessionStore, email: String) {
    val scope = rememberCoroutineScope()
    val error by session.errorMessage.collectAsStateWithLifecycle()
    val loading by session.isLoading.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    val submit = { scope.launch { session.verifyEmail(code) } }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardTitle("Подтвердите почту")
        SentTo(email)
        CodeField(code, { code = it }, ImeAction.Done) { submit() }
        error?.let { ErrorBanner(it) }
        GradientButton("Подтвердить", Modifier.padding(top = 8.dp), isLoading = loading, brush = AuthStyle.button) { submit() }
        Column(Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
            ResendLink(session, loading)
            TextLink("Изменить почту") { session.cancelVerification() }
        }
    }
}

/**
 * «Забыли пароль?»: the email first, then — once [pendingEmail] is set — the code and the new password, which signs in.
 * [initialEmail] is what was typed on the sign-in card.
 */
@Composable
fun ResetCard(session: SessionStore, initialEmail: String, pendingEmail: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val error by session.errorMessage.collectAsStateWithLifecycle()
    val loading by session.isLoading.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { session.clearError() }
    val requestCode = { scope.launch { session.requestPasswordReset(email) } }
    val changePassword = { scope.launch { session.confirmPasswordReset(code, password, confirm) } }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardTitle("Сброс пароля")
        if (pendingEmail == null) {
            AuthField("Email", email, { email = it }, keyboard = KeyboardType.Email, ime = ImeAction.Done) { requestCode() }
            error?.let { ErrorBanner(it) }
            GradientButton("Получить код", Modifier.padding(top = 8.dp), isLoading = loading, brush = AuthStyle.button) { requestCode() }
        } else {
            SentTo(pendingEmail)
            CodeField(code, { code = it }, ImeAction.Next)
            AuthField("Новый пароль", password, { password = it }, isPassword = true)
            AuthField("Повторите пароль", confirm, { confirm = it }, isPassword = true, ime = ImeAction.Done) { changePassword() }
            error?.let { ErrorBanner(it) }
            GradientButton("Сменить пароль", Modifier.padding(top = 8.dp), isLoading = loading, brush = AuthStyle.button) { changePassword() }
        }
        TextLink("Назад ко входу", Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally), onClick = onBack)
    }
}

@Composable
private fun CardTitle(title: String) {
    Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SentTo(email: String) {
    Text("Мы отправили 6-значный код на $email", color = AuthStyle.placeholder, fontSize = 15.sp)
}

/** Digits only, at most six; the number keyboard. */
@Composable
private fun CodeField(code: String, onChange: (String) -> Unit, ime: ImeAction, onDone: (() -> Unit)? = null) {
    AuthField("Код из письма", code, { typed -> onChange(typed.filter(Char::isDigit).take(CODE_LENGTH)) },
        keyboard = KeyboardType.NumberPassword, ime = ime, onDone = onDone)
}

/**
 * «Отправить код ещё раз», or the seconds left until it may be sent. The deadline is on the monotonic clock and saved,
 * so rotating the phone does not restart the minute.
 */
@Composable
private fun ResendLink(session: SessionStore, loading: Boolean) {
    val scope = rememberCoroutineScope()
    var deadline by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime() + RESEND_DELAY_MS) }
    var secondsLeft by remember { mutableIntStateOf(secondsUntil(deadline)) }
    LaunchedEffect(deadline) {
        secondsLeft = secondsUntil(deadline)
        while (secondsLeft > 0) {
            val untilNextSecond = (deadline - SystemClock.elapsedRealtime()) % MS_PER_SECOND
            delay(if (untilNextSecond > 0) untilNextSecond else MS_PER_SECOND)
            secondsLeft = secondsUntil(deadline)
        }
    }
    if (secondsLeft > 0) {
        val time = "${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}"
        Text("Отправить ещё раз через $time", color = AuthStyle.placeholder, fontSize = 15.sp,
            modifier = Modifier.heightIn(min = 44.dp).wrapContentHeight())
    } else {
        TextLink("Отправить код ещё раз") {
            if (!loading) scope.launch {
                session.resendCode()
                if (session.errorMessage.value == null) deadline = SystemClock.elapsedRealtime() + RESEND_DELAY_MS
            }
        }
    }
}

private fun secondsUntil(deadline: Long): Int =
    ((deadline - SystemClock.elapsedRealtime() + MS_PER_SECOND - 1) / MS_PER_SECOND).coerceAtLeast(0).toInt()
