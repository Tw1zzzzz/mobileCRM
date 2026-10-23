package com.pulse.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.pulse.android.ui.main.MainScreen
import com.pulse.android.ui.theme.Palette
import com.pulse.core.PulseCore
import com.pulse.core.model.UserRole
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Shared setup for Compose UI tests on Robolectric. Tests run with
 * `@Config(application = android.app.Application::class, sdk = [34])`: the real `PulseApplication` (WorkManager,
 * Keystore, Health Connect) never starts, and SDK 35+ sandboxes need Java 21 while the project runs on 17.
 */

/** A core over a fresh temp dir with in-memory secrets; signed in to the demo of [role] when given. */
fun testCore(role: UserRole?): PulseCore {
    val core = PulseCore(
        files = JvmFileSystem(Files.createTempDirectory("pulse-ui").toFile()),
        secrets = MemorySecretStore(),
        http = HttpClient(OkHttp),
        background = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        health = null
    )
    if (role != null) runBlocking { core.session.switchToDemo(role) }
    return core
}

/** The signed-in app as `MainActivity` shows it, opened on a debug route (`DebugRoutes`) when given. */
fun ComposeContentTestRule.setMain(core: PulseCore, route: String? = null) {
    val user = requireNotNull(core.session.user.value) { "setMain needs a signed-in core" }
    setContent {
        MaterialTheme(colorScheme = darkColorScheme(primary = Palette.cyan, background = Palette.background, surface = Palette.card)) {
            MainScreen(core, user, debugRoute = route)
        }
    }
}

/** iOS minimum touch target; applies to controls without visible text. */
private const val MIN_ICON_TARGET_DP = 44f

/**
 * Every clickable or toggleable control has something for TalkBack to read (text or description), and icon-only
 * controls are at least 44 × 44dp. Fails with one line per problem.
 */
fun SemanticsNodeInteractionsProvider.assertAccessible() {
    val controls = onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()
    val problems = controls.mapNotNull { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty() +
            node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val density = node.layoutInfo.density.density
        val width = node.touchBoundsInRoot.width / density
        val height = node.touchBoundsInRoot.height / density
        when {
            text.isBlank() && description.isBlank() -> "unlabeled control at ${node.boundsInRoot} near «${nearbyText(node)}»"
            text.isBlank() && (width < MIN_ICON_TARGET_DP || height < MIN_ICON_TARGET_DP) ->
                "«$description»: touch area ${width.toInt()}×${height.toInt()}dp < ${MIN_ICON_TARGET_DP.toInt()}dp"
            else -> null
        }
    }
    assertTrue(problems.isEmpty(), "Accessibility problems:\n" + problems.joinToString("\n"))
}

private fun nearbyText(node: SemanticsNode): String {
    var current: SemanticsNode? = node.parent
    while (current != null) {
        current.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.let { return it.text }
        current.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }?.let { return it }
        current = current.parent
    }
    return "?"
}
