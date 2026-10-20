package app.harmonic.music.ui.components

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.harmonic.music.R
import app.harmonic.music.playback.SleepTimer
import app.harmonic.music.ui.LocalContainer
import kotlinx.coroutines.delay

/** Remaining time as "12:34", ticking once a second while shown. */
@Composable
fun rememberSleepRemaining(state: SleepTimer.State): String? {
    if (state !is SleepTimer.State.Running) return null
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val left = ((state.endsAt - now) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(left / 60, left % 60)
}

/** Moon icon for the player: filled and tinted while a timer runs. */
@Composable
fun SleepTimerIcon() {
    val state by LocalContainer.current.sleepTimer.state.collectAsStateWithLifecycle()
    val active = state != SleepTimer.State.Off
    Icon(
        if (active) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
        stringResource(R.string.sleep_timer),
        tint = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val timer = LocalContainer.current.sleepTimer
    val state by timer.state.collectAsStateWithLifecycle()
    val remaining = rememberSleepRemaining(state)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Bedtime, null) },
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                when {
                    remaining != null -> Text(stringResource(R.string.sleep_stops_in, remaining))
                    state == SleepTimer.State.EndOfSong -> Text(stringResource(R.string.sleep_stops_end_of_song))
                }
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    ListItem(
                        headlineContent = { Text(pluralStringResource(R.plurals.minutes, minutes, minutes)) },
                        modifier = Modifier.clickable {
                            timer.startMinutes(minutes)
                            onDismiss()
                        },
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sleep_end_of_song)) },
                    modifier = Modifier.clickable {
                        timer.startEndOfSong()
                        onDismiss()
                    },
                )
            }
        },
        confirmButton = {
            if (state != SleepTimer.State.Off) {
                TextButton(onClick = {
                    timer.cancel()
                    onDismiss()
                }) { Text(stringResource(R.string.sleep_turn_off)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
