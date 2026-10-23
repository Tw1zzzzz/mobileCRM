package com.pulse.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
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
fun ComposeContentTestRule.setMain(core: PulseCore, route: String? = null, fontScale: Float? = null) {
    val user = requireNotNull(core.session.user.value) { "setMain needs a signed-in core" }
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.cyan, background = Palette.background, surface = Palette.card)) {
                MainScreen(core, user, debugRoute = route)
            }
        }
    }
}

/**
 * No text is cut off at its edge (clipped) or broken inside a word — what large fonts on narrow phones do to rows that
 * were laid out for the mockup's sizes — and no fit() text had to end in «…» ([unfit], collected through FitReport:
 * semantics re-lays fit() texts out at their base size, so only the hook sees the size actually drawn).
 */
fun SemanticsNodeInteractionsProvider.assertTextFits(unfit: Collection<String> = emptyList()) {
    val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
    val problems = texts.flatMap { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return@flatMap emptyList()
        val text = layout.layoutInput.text.text
        buildList {
            // Ellipsis is a deliberate cut; Clip past maxLines hides text without a trace. (didOverflowWidth is not
            // used: the paragraph is laid out at the full constraint, so it reports overflow for any short text.)
            val clipped = layout.layoutInput.overflow == TextOverflow.Clip && layout.multiParagraph.didExceedMaxLines
            if (clipped) add("clipped: «$text»")
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                    add("word broken: «${text.substring(0, end)}|${text.substring(end)}»")
                }
            }
        }
    }.distinct() + unfit.map { "cut even at 70%: «$it»" }
    assertTrue(problems.isEmpty(), "Text problems:\n" + problems.joinToString("\n"))
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
        // Layout size, not on-screen bounds: those are clipped to zero for controls scrolled out of view.
        val width = node.size.width / density
        val height = node.size.height / density
        when {
            text.isBlank() && description.isBlank() -> "unlabeled control (${node.config.getOrNull(SemanticsProperties.Role) ?: "no role"}) near «${nearbyText(node)}»"
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
