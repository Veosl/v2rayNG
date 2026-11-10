package com.v2ray.ang.ui.main

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.ServiceCheckRunner
import com.v2ray.ang.dto.entities.ServiceCheckStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the service check report of one profile.
 *
 * The dialog only shows the data of the report, every action is sent back to the owner
 * of the screen.
 *
 * @param details The report of the profile.
 * @param onCopy The copy button of one check was pressed.
 * @param onDismiss The dialog was closed.
 */
@Composable
fun ServiceCheckDetailsDialog(
    details: ServiceCheckDetailsUiModel,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.title_service_check_details))
                Text(
                    details.remarks,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(
                        R.string.service_check_details_checked_at,
                        formatServiceCheckTime(details.timestamp)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details.error?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.service_check_details_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                details.checks.forEachIndexed { index, check ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    ServiceCheckDetailsCheck(check, onCopy)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * Shows the result of one check of the report.
 */
@Composable
private fun ServiceCheckDetailsCheck(
    check: ServiceCheckDetailsCheckUiModel,
    onCopy: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = serviceCheckLabel(check.checkId),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(serviceCheckStatusLabel(check.status)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        check.value?.takeIf { it.isNotBlank() }?.let { value ->
            ServiceCheckDetailsLine(stringResource(R.string.service_check_details_value, value))
        }
        check.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            ServiceCheckDetailsLine(stringResource(R.string.service_check_details_reason, reason))
        }
        check.httpCode?.let { code ->
            ServiceCheckDetailsLine(stringResource(R.string.service_check_details_http_code, code))
        }
        check.locationHeader?.takeIf { it.isNotBlank() }?.let { location ->
            ServiceCheckDetailsLine(stringResource(R.string.service_check_details_location, location))
        }
        val body = check.bodyPreview
        if (!body.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!check.fullBody.isNullOrEmpty()) {
            TextButton(onClick = { onCopy(check.checkId) }) {
                Text(stringResource(R.string.action_copy))
            }
        }
    }
}

/**
 * One line of the details of a check.
 */
@Composable
private fun ServiceCheckDetailsLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Formats the time of a report.
 */
@Composable
private fun formatServiceCheckTime(timestamp: Long): String {
    val context = LocalContext.current
    return remember(timestamp) {
        if (timestamp <= 0L) {
            ""
        } else {
            val locale: Locale = context.resources.configuration.locales[0]
            val skeleton = if (DateFormat.is24HourFormat(context)) "yMMMdHm" else "yMMMdhm"
            val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
            SimpleDateFormat(pattern, locale).format(Date(timestamp))
        }
    }
}

/**
 * The name of a check, in the language of the app.
 */
@Composable
private fun serviceCheckLabel(checkId: String): String =
    stringResource(
        when (checkId) {
            ServiceCheckRunner.CHECK_YOUTUBE_PREMIUM -> R.string.service_check_label_youtube_premium
            ServiceCheckRunner.CHECK_GOOGLE_SEARCH_CAPTCHA -> R.string.service_check_label_google_search_captcha
            ServiceCheckRunner.CHECK_YOUTUBE_COUNTRY -> R.string.service_check_label_youtube_country
            ServiceCheckRunner.CHECK_GOOGLE_COUNTRY -> R.string.service_check_label_google_country
            ServiceCheckRunner.CHECK_YOUTUBE_MUSIC -> R.string.service_check_label_youtube_music
            ServiceCheckRunner.CHECK_EXIT_IP -> R.string.service_check_label_exit_ip
            else -> R.string.service_check_label_unknown
        }
    )

/**
 * The name of a status, in the language of the app.
 */
private fun serviceCheckStatusLabel(status: ServiceCheckStatus): Int = when (status) {
    ServiceCheckStatus.YES -> R.string.service_check_status_yes
    ServiceCheckStatus.NO -> R.string.service_check_status_no
    ServiceCheckStatus.IPBLOCK -> R.string.service_check_status_ipblock
    ServiceCheckStatus.UNKNOWN -> R.string.service_check_status_unknown
}
