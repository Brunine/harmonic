package app.harmonic.music.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.harmonic.music.R
import app.harmonic.music.data.remote.AppUpdate

/**
 * "Harmonic X is available": shows the release notes; Download opens the APK link in the
 * browser, which downloads it, and opening the file installs the update over this one.
 */
@Composable
fun UpdateDialog(update: AppUpdate, onLater: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.update_available, update.version)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.update_how), style = MaterialTheme.typography.bodyMedium)
                if (update.notes.isNotEmpty()) {
                    Text(
                        // Release notes are Markdown; show them as plain text without the markup.
                        update.notes.replace("**", "").replace(Regex("(?m)^#+\\s*"), ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = update.apkUrl ?: update.pageUrl
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                onClose()
            }) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) } },
    )
}
