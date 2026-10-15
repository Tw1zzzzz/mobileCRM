package com.pulse.android.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.theme.Palette
import com.pulse.core.auth.SessionStore
import com.pulse.core.model.AccessType
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.TeamRole
import com.pulse.core.model.UserRole
import kotlinx.coroutines.launch

private object AuthStyle {
    val card = Color(0xFF0B1222)
    val field = Color(0xFF1C2333)
    val placeholder = Color(0xFF7B8599)
    val roleBorder = Color(0xFF2A3346)
    val button = Brush.horizontalGradient(listOf(Color(0xFF0A6CFF), Color(0xFF00C8FF)))
}

/** Вход и регистрация: градиентный хиро-блок и карточка формы (как на iOS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(session: SessionStore) {
    var register by rememberSaveable { mutableStateOf(false) }
    var legal by remember { mutableStateOf<LegalDocument?>(null) }
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
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            Hero()
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(AuthStyle.card)
                    .border(1.dp, Palette.stroke, RoundedCornerShape(24.dp)).padding(horizontal = 22.dp, vertical = 28.dp)
            ) {
                Crossfade(register, label = "auth") { isRegister ->
                    if (isRegister) RegistrationCard(session, onLegal = { legal = it }) { register = false }
                    else LoginCard(session) { register = true }
                }
            }
        }
    }
    legal?.let { document ->
        ModalBottomSheet(onDismissRequest = { legal = null }, containerColor = Palette.background) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
                Text(document.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(document.text, color = Palette.textSecondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable
private fun Hero() {
    Column(Modifier.padding(horizontal = 8.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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

@Composable
private fun LoginCard(session: SessionStore, onRegister: () -> Unit) {
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
        AuthField("Пароль", password, { password = it }, isPassword = true)
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

@Composable
private fun RegistrationCard(session: SessionStore, onLegal: (LegalDocument) -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    val error by session.errorMessage.collectAsStateWithLifecycle()
    val loading by session.isLoading.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf(RegistrationForm()) }
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
                Text(
                    if (form.isTeamStaff) "Кабинет стаффа с данными игроков откроется вместе с сервером команды. Сейчас его можно посмотреть в демо на экране входа."
                    else "Код команды сохранится в профиле — привяжем тебя к команде, когда подключится сервер.",
                    color = if (form.isTeamStaff) Palette.orange else AuthStyle.placeholder, fontSize = 13.sp
                )
            }
        }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuthField("Имя", form.nickname, { form = form.copy(nickname = it) })
            AuthField("Email", form.email, { form = form.copy(email = it) }, keyboard = KeyboardType.Email)
            AuthField("Пароль", form.password, { form = form.copy(password = it) }, isPassword = true)
            AuthField("Повторите пароль", form.confirmPassword, { form = form.copy(confirmPassword = it) }, isPassword = true)
            AuthField("Ссылка Faceit", form.faceitURL, { form = form.copy(faceitURL = it) }, keyboard = KeyboardType.Uri)
            if (form.isTeamPlayer) AuthField("Код команды", form.teamCode, { form = form.copy(teamCode = it) })
            if (form.isTeamStaff) AuthField("Название команды", form.teamName, { form = form.copy(teamName = it) })
        }
        error?.let { ErrorBanner(it) }
        GradientButton("Создать аккаунт", Modifier.padding(top = 10.dp), isLoading = loading, brush = AuthStyle.button) {
            scope.launch { session.register(form) }
        }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Consent("Я принимаю условия ", "Пользовательского соглашения", form.acceptedTerms, { onLegal(LegalDocument.TERMS) }) {
                form = form.copy(acceptedTerms = it)
            }
            Consent("Я принимаю ", "политику конфиденциальности", form.acceptedPrivacy, { onLegal(LegalDocument.PRIVACY) }) {
                form = form.copy(acceptedPrivacy = it)
            }
        }
        SwitchLink("Уже есть аккаунт?", "Войти", onLogin)
    }
}

@Composable
private fun AuthField(placeholder: String, value: String, onChange: (String) -> Unit, isPassword: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = AuthStyle.placeholder) },
        singleLine = true,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboard),
        trailingIcon = if (isPassword) {
            {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Скрыть пароль" else "Показать пароль",
                    tint = AuthStyle.placeholder, modifier = Modifier.pressable { visible = !visible })
            }
        } else null,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AuthStyle.field, unfocusedContainerColor = AuthStyle.field,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Palette.cyan,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(if (checked) Palette.cyan else Color.Transparent)
                .border(1.5.dp, if (checked) Palette.cyan else AuthStyle.placeholder, RoundedCornerShape(4.dp)).pressable { onCheck(!checked) },
            contentAlignment = Alignment.Center
        ) { if (checked) Icon(Icons.Default.Check, "Принято", tint = Color.Black, modifier = Modifier.size(16.dp)) }
        Row {
            Text(prefix, color = AuthStyle.placeholder, fontSize = 14.sp, modifier = Modifier.pressable { onCheck(!checked) })
            Text(link, color = Palette.cyan, fontSize = 14.sp, textDecoration = TextDecoration.Underline, modifier = Modifier.pressable(onClick = onLink))
        }
    }
}

@Composable
private fun SwitchLink(prompt: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
        Text("$prompt ", color = AuthStyle.placeholder, fontSize = 15.sp)
        Text(action, color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable(onClick = onClick))
    }
}
