package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PlayerState
import com.example.model.SleepTimerOption
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.GoldAccent
import java.util.Locale

@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    onBarClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentSurah = playerState.currentSurah

    AnimatedVisibility(
        visible = currentSurah != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        if (currentSurah == null) return@AnimatedVisibility

        val sleepLabel = when {
            playerState.sleepTimerOption == SleepTimerOption.END_OF_SURAH -> "نهاية السورة"
            playerState.sleepTimerSecondsRemaining > 0L -> {
                val mins = playerState.sleepTimerSecondsRemaining / 60L
                val secs = playerState.sleepTimerSecondsRemaining % 60L
                String.format(Locale.US, "%02d:%02d", mins, secs)
            }
            else -> null
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBarClick)
                .testTag("mini_player_bar"),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        ) {
            Column {
                LinearProgressIndicator(
                    progress = { playerState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = GoldAccent,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Compact Surah Number Badge
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${currentSurah.id}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Title, Reciter & Sleep Timer
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "سورة ${currentSurah.name}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (playerState.isPlayingOffline) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "دون إنترنت",
                                    tint = EmeraldLight,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            if (sleepLabel != null) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = GoldAccent,
                                    modifier = Modifier.testTag("mini_player_sleep_timer")
                                ) {
                                    Text(
                                        text = sleepLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = playerState.currentReciter?.name ?: "القارئ",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Exactly 3 controls in fixed physical LTR direction:
                    // Left: |◀ (Previous Surah), Center: Play/Pause, Right: ▶| (Next Surah)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = onPrevClick,
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("mini_player_prev")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "السورة السابقة",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            if (playerState.isBuffering) {
                                Box(
                                    modifier = Modifier.size(34.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = onPlayPauseClick,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .testTag("mini_player_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playerState.isPlaying) "إيقاف مؤقت" else "تشغيل",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onNextClick,
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("mini_player_next")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "السورة التالية",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
