package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Surah
import com.example.ui.theme.GoldAccent

val SURAH_BADGE_SIZE = 24.dp
val SURAH_PLAY_BUTTON_SIZE = 34.dp

@Composable
fun SurahNumberBadge(
    surahId: Int,
    isActive: Boolean,
    shape: Shape = RoundedCornerShape(7.dp),
    showBorder: Boolean = true,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fixedTextSize = with(density) { 9.5.dp.toSp() }
    val fixedLineHeight = with(density) { 11.dp.toSp() }

    Box(
        modifier = modifier
            .requiredSize(SURAH_BADGE_SIZE)
            .clip(shape)
            .background(
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = shape
            )
            .then(
                if (showBorder) {
                    Modifier.border(
                        width = 1.dp,
                        color = if (isActive) GoldAccent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = shape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$surahId",
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = fixedTextSize,
                lineHeight = fixedLineHeight,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both
                ),
                letterSpacing = 0.sp,
                fontFeatureSettings = "tnum",
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.dp)
                .testTag("surah_badge_text_$surahId")
        )
    }
}

@Composable
fun SurahItemCard(
    surah: Surah,
    isPlaying: Boolean,
    isCurrent: Boolean,
    isAvailableForReciter: Boolean,
    onCardClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onCardClick)
            .testTag("surah_card_${surah.id}"),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrent) 3.dp else 1.dp,
        shape = RoundedCornerShape(14.dp),
        border = if (isCurrent) {
            BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Surah Number Badge (1..114, unified 24dp fixed badge)
            SurahNumberBadge(
                surahId = surah.id,
                isActive = isCurrent,
                shape = RoundedCornerShape(7.dp),
                showBorder = true,
                modifier = Modifier.testTag("surah_badge_${surah.id}")
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Surah Name (clean single-line Arabic title matching visual reference)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "سورة ${surah.name}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("surah_name_${surah.id}")
                )

                if (!isAvailableForReciter) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(غير مسجلة للقارئ)",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Uniform compact Play / Pause circular icon button
            IconButton(
                onClick = onPlayClick,
                enabled = isAvailableForReciter,
                modifier = Modifier
                    .requiredSize(SURAH_PLAY_BUTTON_SIZE)
                    .background(
                        color = if (isCurrent && isPlaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    )
                    .testTag("play_button_${surah.id}")
            ) {
                Icon(
                    imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCurrent && isPlaying) "إيقاف مؤقت" else "تشغيل",
                    tint = if (isCurrent && isPlaying) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
