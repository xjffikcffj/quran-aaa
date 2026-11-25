package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.model.SleepTimerOption

object SleepTimerConfig {
    /**
     * Returns the ordered list of sleep timer options for the dialog.
     * In release builds, the diagnostic test option (SECONDS_20_TEST) is hidden.
     * The "عند نهاية السورة" option is always available.
     */
    fun getDialogOptions(showTestOption: Boolean = BuildConfig.DEBUG): List<Pair<SleepTimerOption, String>> {
        return buildList {
            add(SleepTimerOption.END_OF_SURAH to SleepTimerOption.END_OF_SURAH.label)
            add(SleepTimerOption.MINUTES_5 to SleepTimerOption.MINUTES_5.label)
            add(SleepTimerOption.MINUTES_10 to SleepTimerOption.MINUTES_10.label)
            add(SleepTimerOption.MINUTES_15 to SleepTimerOption.MINUTES_15.label)
            add(SleepTimerOption.MINUTES_20 to SleepTimerOption.MINUTES_20.label)
            add(SleepTimerOption.MINUTES_30 to SleepTimerOption.MINUTES_30.label)
            add(SleepTimerOption.MINUTES_45 to SleepTimerOption.MINUTES_45.label)
            add(SleepTimerOption.HOUR_1 to SleepTimerOption.HOUR_1.label)
            if (showTestOption) {
                add(SleepTimerOption.SECONDS_20_TEST to SleepTimerOption.SECONDS_20_TEST.label)
            }
            add(SleepTimerOption.OFF to SleepTimerOption.OFF.label)
        }
    }
}

@Composable
fun SleepTimerDialog(
    currentOption: SleepTimerOption,
    onOptionSelected: (SleepTimerOption) -> Unit,
    onDismiss: () -> Unit,
    showTestOption: Boolean = BuildConfig.DEBUG
) {
    val options = SleepTimerConfig.getDialogOptions(showTestOption)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "مؤقت النوم",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            )
        },
        text = {
            Column {
                Text(
                    text = "حدد متى ترغب في إيقاف التلاوة تلقائياً لمساعدتك على الاسترخاء والنوم:",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                options.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOptionSelected(option)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp)
                            .testTag("sleep_timer_${option.name}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (currentOption == option),
                            onClick = {
                                onOptionSelected(option)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (currentOption == option) FontWeight.Bold else FontWeight.Normal,
                                color = if (currentOption == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_sleep_dialog")
            ) {
                Text("إغلاق")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
