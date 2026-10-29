package com.pulse.android.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.pulse.core.model.AuthFieldId
import com.pulse.core.model.AuthValidator
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.android.ui.components.contentWidth
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.core.auth.SessionStore
import com.pulse.core.model.AccessType
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.TeamRole
import com.pulse.core.model.UserRole
import kotlinx.coroutines.launch

internal object AuthStyle {
    val card = Color(0xFF0B1222)
    val field = Color(0xFF1C2333)
    val placeholder = Color(0xFF7B8599)
    val roleBorder = Color(0xFF2A3346)
    val button = Brush.horizontalGradient(listOf(Color(0xFF0A6CFF), Color(0xFF00C8FF)))
}

/** What the form card shows. */
private enum class AuthStep { LOGIN, REGISTER, VERIFY, RESET }

/**
 * Вход и регистрация: градиентный хиро-блок и карточка формы (как на iOS). A remote build ([SessionStore.requireBetaTerms])
 * adds the beta-terms consent, the emailed-code step and the password reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(session: SessionStore) {
    val remote = session.requireBetaTerms
    var register by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    var resetEmail by rememberSaveable { mutableStateOf("") }
    var legal by remember { mutableStateOf<LegalDocument?>(null) }
    val verifying by session.pendingVerification.collectAsStateWithLifecycle()
    val resetPending by session.pendingReset.collectAsStateWithLifecycle()
    val step = when {
        verifying != null -> AuthStep.VERIFY
        resetting || resetPending != null -> AuthStep.RESET
        register -> AuthStep.REGISTER
        else -> AuthStep.LOGIN
    }
    // «Изменить почту» returns to a filled form: the forms keep their fields while the code step is shown.
    val forms = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Color(0xFF070C1A))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF00BFFF), Color(0xFF0A6CFF).copy(alpha = 0.9f), Color(0xFF0A2C7A).copy(alpha = 0.6f), Color.Transparent),
                    center = Offset(size.width * 0.1f, size.height * 0.05f),
                    radius = 520.dp.toPx()
                )
            )
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Hero()
            Column(
                Modifier.contentWidth().clip(RoundedCornerShape(24.dp)).background(AuthStyle.card)
                    .border(1.dp, Palette.stroke, RoundedCornerShape(24.dp)).padding(horizontal = 22.dp, vertical = 28.dp)
            ) {
                Crossfade(step, label = "auth") { shown ->
                    when (shown) {
                        AuthStep.LOGIN -> forms.SaveableStateProvider(shown.name) {
                            val forgot: (String) -> Unit = { email ->
                                resetEmail = email
                                resetting = true
                            }
                            LoginCard(session, onRegister = { register = true }, onForgotPassword = forgot.takeIf { remote })
                        }
                        AuthStep.REGISTER -> forms.SaveableStateProvider(shown.name) {
                            RegistrationCard(session, onLegal = { legal = it }) { register = false }
                        }
                        AuthStep.VERIFY -> VerifyEmailCard(session, verifying.orEmpty())
                        AuthStep.RESET -> ResetCard(session, resetEmail, resetPending) {
                            session.cancelReset()
                            resetting = false
                        }
                    }
                }
            }
        }
    }
    legal?.let { document ->
        PulseSheet({ legal = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
                Text(document.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(document.text, color = Palette.textSecondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable
private fun Hero() {
    Column(Modifier.contentWidth().padding(horizontal = 8.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MonitorHeart, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text("Pulse", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("ТВОЯ ФОРМА —\nТВОЯ ПОБЕДА", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
        Text("Сон, восстановление и статистика\nкиберспортсмена в одном приложении", color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp)
    }
}

/** [onForgotPassword] (remote builds only) gets the email typed so far. */
@Composable
private fun LoginCard(session: SessionStore, onRegister: () -> Unit, onForgotPassword: ((String) -> Unit)?) {
    val scope = rememberCoroutineScope()
    val error by session.errorMessage.collectAsStateWithLifecycle()
    val loading by session.isLoading.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var demoMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { session.clearError() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Вход", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        AuthField("Email", email, { email = it }, keyboard = KeyboardType.Email)
        AuthField("Пароль", password, { password = it }, isPassword = true, ime = ImeAction.Done) { scope.launch { session.login(email, password) } }
        onForgotPassword?.let { onForgot ->
            TextLink("Забыли пароль?", Modifier.align(Alignment.End)) { onForgot(email) }
        }
        error?.let { ErrorBanner(it) }
        GradientButton("Войти", Modifier.padding(top = 8.dp), isLoading = loading, brush = AuthStyle.button) {
            scope.launch { session.login(email, password) }
        }
        SwitchLink("Нет аккаунта?", "Зарегистрироваться", onRegister)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("БЕЗ РЕГИСТРАЦИИ", color = AuthStyle.placeholder, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Box {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Palette.cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).pressable { demoMenu = true },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.PlayCircle, null, tint = Palette.cyan, modifier = Modifier.size(18.dp))
                    Text("  Посмотреть демо", color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(demoMenu, onDismissRequest = { demoMenu = false }) {
                    listOf(UserRole.TEAM_PLAYER, UserRole.SOLO_PLAYER, UserRole.STAFF).forEach { role ->
                        DropdownMenuItem(text = { Text(role.title) }, onClick = {
                            demoMenu = false
                            scope.launch { session.switchToDemo(role) }
                        })
                    }
                }
            }
            Text("Демо-аккаунт с примерами данных. Твои записи хранятся отдельно.", color = AuthStyle.placeholder, fontSize = 12.sp)
        }
    }
}

/** Keeps the registration form across rotation and process death — without the passwords. */
internal val RegistrationFormSaver: Saver<RegistrationForm, List<String>> = Saver(
    save = {
        listOf(it.nickname, it.email, it.faceitURL, it.teamCode, it.teamName, it.accessType.name, it.teamRole.name, "${it.acceptedTerms}",
            "${it.acceptedPrivacy}", "${it.acceptedBetaTerms}")
    },
    restore = { saved ->
        RegistrationForm(
            nickname = saved[0], email = saved[1], faceitURL = saved[2], teamCode = saved[3], teamName = saved[4],
            accessType = AccessType.entries.firstOrNull { it.name == saved[5] } ?: AccessType.SOLO,
            teamRole = TeamRole.entries.firstOrNull { it.name == saved[6] } ?: TeamRole.PLAYER,
            acceptedTerms = saved[7].toBoolean(), acceptedPrivacy = saved[8].toBoolean(),
            // Saved before the beta-terms consent existed: nine entries.
            acceptedBetaTerms = saved.getOrNull(9).toBoolean()
        )
    }
)

@Composable
private fun RegistrationCard(session: SessionStore, onLegal: (LegalDocument) -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    val error by session.errorMessage.collectAsStateWithLifecycle()
    val loading by session.isLoading.collectAsStateWithLifecycle()
    var form by rememberSaveable(stateSaver = RegistrationFormSaver) { mutableStateOf(RegistrationForm()) }
    val remote = session.requireBetaTerms
    LaunchedEffect(Unit) { session.clearError() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Регистрация", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        Segmented(AccessType.entries.map { it.title }, form.accessType.ordinal) {
            form = form.copy(accessType = AccessType.entries[it])
            session.clearError()
        }
        AnimatedVisibility(form.accessType == AccessType.TEAM) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TeamRole.entries.forEach { role ->
                        val selected = form.teamRole == role
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp)).background(AuthStyle.card)
                                .border(1.dp, if (selected) Palette.cyan else AuthStyle.roleBorder, RoundedCornerShape(12.dp))
                                .pressable { form = form.copy(teamRole = role) },
                            contentAlignment = Alignment.Center
                        ) { Text(role.title, color = if (selected) Color.White else AuthStyle.placeholder, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
                Text(teamHint(form.isTeamStaff, remote), color = if (form.isTeamStaff && !remote) Palette.orange else AuthStyle.placeholder,
                    fontSize = 13.sp)
            }
        }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val last = form.fields().last()
            fun ime(id: AuthFieldId) = if (id == last) ImeAction.Done else ImeAction.Next
            AuthField("Имя", form.nickname, { form = form.copy(nickname = it.take(AuthValidator.NICKNAME_MAX_LENGTH)) }, ime = ime(AuthFieldId.NAME))
            AuthField("Email", form.email, { form = form.copy(email = it) }, keyboard = KeyboardType.Email, ime = ime(AuthFieldId.EMAIL))
            AuthField("Пароль", form.password, { form = form.copy(password = it) }, isPassword = true, ime = ime(AuthFieldId.PASSWORD))
            AuthField("Повторите пароль", form.confirmPassword, { form = form.copy(confirmPassword = it) }, isPassword = true,
                ime = ime(AuthFieldId.CONFIRM))
            AuthField("Ссылка Faceit", form.faceitURL, { form = form.copy(faceitURL = it) }, keyboard = KeyboardType.Uri, ime = ime(AuthFieldId.FACEIT))
            if (form.isTeamPlayer) AuthField("Код команды", form.teamCode, { form = form.copy(teamCode = it) }, ime = ime(AuthFieldId.TEAM_CODE))
            if (form.isTeamStaff) AuthField("Название команды", form.teamName, { form = form.copy(teamName = it.take(AuthValidator.TEAM_NAME_MAX_LENGTH)) }, ime = ime(AuthFieldId.TEAM_NAME))
        }
        // Consents before the button: «Создать аккаунт» is the last thing to press, not the first error to read.
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Consent("Я принимаю условия ", "Пользовательского соглашения", form.acceptedTerms, { onLegal(LegalDocument.TERMS) }) {
                form = form.copy(acceptedTerms = it)
            }
            Consent("Я принимаю ", "политику конфиденциальности", form.acceptedPrivacy, { onLegal(LegalDocument.PRIVACY) }) {
                form = form.copy(acceptedPrivacy = it)
            }
            if (remote) {
                Consent("Я принимаю ", "условия бета-тестирования", form.acceptedBetaTerms, { onLegal(LegalDocument.BETA_TERMS) }) {
                    form = form.copy(acceptedBetaTerms = it)
                }
            }
        }
        error?.let { ErrorBanner(it) }
        GradientButton("Создать аккаунт", Modifier.padding(top = 10.dp), isLoading = loading, brush = AuthStyle.button) {
            scope.launch { session.register(form) }
        }
        SwitchLink("Уже есть аккаунт?", "Войти", onLogin)
    }
}

/** Under the team role: on the server a staff member creates the team; on the phone both wait for the server. */
private fun teamHint(isStaff: Boolean, remote: Boolean): String = when {
    remote && isStaff -> "После регистрации вы получите код команды — отправьте его игрокам."
    remote -> "Код команды выдаёт стафф."
    isStaff -> "Кабинет стаффа с данными игроков откроется вместе с сервером команды. Сейчас его можно посмотреть в демо на экране входа."
    else -> "Код команды сохранится в профиле — привяжем тебя к команде, когда подключится сервер."
}

@Composable
/**
 * [name] sits inside the empty field and moves above the text once something is typed, so two filled password fields
 * still say which is which. «Далее» ([ime] Next) moves to the next field; Done closes the keyboard or runs [onDone].
 */
internal fun AuthField(
    name: String, value: String, onChange: (String) -> Unit, isPassword: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next, onDone: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(name) },
        singleLine = true,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboard, imeAction = ime),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus(); onDone?.invoke() }),
        trailingIcon = if (isPassword) {
            {
                Box(Modifier.size(Metrics.minTouchTarget).pressable { visible = !visible }, contentAlignment = Alignment.Center) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Скрыть пароль" else "Показать пароль",
                        tint = AuthStyle.placeholder)
                }
            }
        } else null,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AuthStyle.field, unfocusedContainerColor = AuthStyle.field,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Palette.cyan,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedLabelColor = Palette.cyan, unfocusedLabelColor = AuthStyle.placeholder
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AuthStyle.field).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, title ->
            val isSelected = index == selected
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp))
                    .then(if (isSelected) Modifier.background(AuthStyle.button) else Modifier).pressable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) { Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Consent(prefix: String, link: String, checked: Boolean, onLink: () -> Unit, onCheck: (Boolean) -> Unit) {
    // FlowRow: with large fonts the link wraps under the checkbox instead of breaking its words.
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        // Box and prefix are one checkbox for TalkBack; the document link is a separate control.
        Row(
            Modifier.heightIn(min = 44.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onCheck),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(if (checked) Palette.cyan else Color.Transparent)
                    .border(1.5.dp, if (checked) Palette.cyan else AuthStyle.placeholder, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) { if (checked) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
            Text(prefix, color = AuthStyle.placeholder, fontSize = 14.sp)
        }
        Text(link, color = Palette.cyan, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.heightIn(min = 44.dp).wrapContentHeight().clickable(onClickLabel = "Открыть", role = Role.Button, onClick = onLink))
    }
}

@Composable
private fun SwitchLink(prompt: String, action: String, onClick: () -> Unit) {
    // With large fonts the link moves to its own line instead of breaking «Зарегистрироваться» in half.
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
        Text("$prompt ", color = AuthStyle.placeholder, fontSize = 15.sp)
        Text(action, color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable(onClick = onClick))
    }
}

/** A standalone cyan link («Забыли пароль?», «Изменить почту»), tall enough to hit. */
@Composable
internal fun TextLink(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(title, color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.heightIn(min = 44.dp).wrapContentHeight().pressable(onClick = onClick))
}
