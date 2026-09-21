package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.presentation.manga.components.MarkdownRender
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * In-app release notes sheet: adaptive bottom sheet (phone) / centered dialog
 * (tablet) rendering the fetched release markdown, with a "Open on GitHub"
 * escape hatch. Replaces the browser-link "What's new" dead-end.
 */
@Composable
fun WhatsNewSheet(
    versionName: String,
    markdown: String,
    releaseLink: String,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (markdown.isNotBlank()) {
        markdown
    } else {
        stringResource(MR.strings.release_notes_unavailable)
    }

    eu.kanade.presentation.components.AdaptiveSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.medium,
                ),
        ) {
            Text(
                text = versionName,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = MaterialTheme.padding.small),
            ) {
                MarkdownRender(
                    content = content,
                    flavour = GFMFlavourDescriptor(),
                )
            }
            TextButton(
                onClick = onOpenInBrowser,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = MaterialTheme.padding.small),
            ) {
                Text(text = stringResource(MR.strings.update_check_open))
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            }
        }
    }
}
