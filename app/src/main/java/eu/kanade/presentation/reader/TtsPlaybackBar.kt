package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.tts.TtsError
import eu.kanade.tachiyomi.ui.reader.tts.TtsPhase
import eu.kanade.tachiyomi.ui.reader.tts.TtsPlaybackState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.asFloatingChrome

private val pillShape = RoundedCornerShape(28.dp)

/** Video-player-style speed choices for the read-aloud pill. */
val SPEED_CHOICES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private fun formatSpeed(rate: Float): String =
    if (rate == rate.toInt().toFloat()) "${rate.toInt()}" else "$rate"

@Composable
fun TtsPlaybackBar(
    state: TtsPlaybackState,
    speechRate: Float,
    onSetSpeechRate: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextSentence: () -> Unit,
    onPreviousSentence: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    chromeTone: Color? = null,
) {
    AnimatedVisibility(
        visible = state.phase != TtsPhase.Idle,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .shadow(6.dp, pillShape)
                .clip(pillShape)
                .background(
                    MaterialTheme.colorScheme
                        .surfaceColorAtElevation(3.dp)
                        .withReaderTone(chromeTone)
                        .asFloatingChrome(),
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            when (state.phase) {
                TtsPhase.Preparing, TtsPhase.LoadingPage -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(
                                if (state.phase == TtsPhase.Preparing) {
                                    MR.strings.tts_preparing
                                } else {
                                    MR.strings.tts_loading_page
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onStop) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = stringResource(MR.strings.tts_action_stop),
                            )
                        }
                    }
                }
                TtsPhase.Error -> {
                    ErrorContent(state = state, onRetry = onRetry, onStop = onStop)
                }
                else -> {
                    PlaybackContent(
                        state = state,
                        speechRate = speechRate,
                        onSetSpeechRate = onSetSpeechRate,
                        onTogglePlayPause = onTogglePlayPause,
                        onNextSentence = onNextSentence,
                        onPreviousSentence = onPreviousSentence,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackContent(
    state: TtsPlaybackState,
    speechRate: Float,
    onSetSpeechRate: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextSentence: () -> Unit,
    onPreviousSentence: () -> Unit,
    onStop: () -> Unit,
) {
    val playing = state.phase == TtsPhase.Playing || state.phase == TtsPhase.LoadingPage
    var speedMenuExpanded by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            if (state.sentenceText.isNotBlank()) {
                Text(
                    text = state.sentenceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(
                        when (state.phase) {
                            TtsPhase.Paused -> MR.strings.tts_paused
                            TtsPhase.Finished -> MR.strings.tts_finished
                            else -> MR.strings.action_read_aloud
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.sentenceCount > 0 && state.sentenceIndex >= 0) {
                Text(
                    text = "${state.sentenceIndex + 1}/${state.sentenceCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onPreviousSentence, enabled = playing || state.phase == TtsPhase.Paused) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(MR.strings.tts_action_previous_sentence),
            )
        }
        IconButton(onClick = onTogglePlayPause, enabled = playing || state.phase == TtsPhase.Paused) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(
                    if (playing) MR.strings.tts_action_pause else MR.strings.tts_action_play,
                ),
            )
        }
        IconButton(onClick = onNextSentence, enabled = playing || state.phase == TtsPhase.Paused) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(MR.strings.tts_action_next_sentence),
            )
        }
        Box {
            IconButton(onClick = { speedMenuExpanded = true }) {
                Text(
                    text = "${formatSpeed(speechRate)}x",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(
                expanded = speedMenuExpanded,
                onDismissRequest = { speedMenuExpanded = false },
            ) {
                val selectedLabel = stringResource(MR.strings.selected)
                val notSelectedLabel = stringResource(MR.strings.not_selected)
                SPEED_CHOICES.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${formatSpeed(speed)}x") },
                        onClick = {
                            speedMenuExpanded = false
                            onSetSpeechRate(speed)
                        },
                        modifier = Modifier.semantics {
                            stateDescription = if (speed == speechRate) selectedLabel else notSelectedLabel
                        },
                        trailingIcon = if (speed == speechRate) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Outlined.Stop,
                contentDescription = stringResource(MR.strings.tts_action_stop),
            )
        }
    }
}

@Composable
private fun ErrorContent(
    state: TtsPlaybackState,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = errorMessage(state.error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        IconButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(MR.strings.action_retry),
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Outlined.Stop,
                contentDescription = stringResource(MR.strings.tts_action_stop),
            )
        }
    }
}

@Composable
private fun errorMessage(error: TtsError?): String = stringResource(
    when (error) {
        TtsError.EngineError -> MR.strings.tts_error_engine
        TtsError.OcrError, null -> MR.strings.tts_error_ocr
        TtsError.NoTextFound -> MR.strings.no_results_found
        TtsError.ChapterLoadFailed -> MR.strings.tts_error_chapter_load
    },
)
