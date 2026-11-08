package com.v2ray.ang.ui.servicecheck

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding

/**
 * Screen that checks how Google and YouTube see the exit IP of every profile.
 */
class ServiceCheckActivity : BaseComponentActivity() {

    private val viewModel: ServiceCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        KeepScreenOn(uiState.isRunning)
        ServiceCheckScreen(
            uiState = uiState,
            onAction = viewModel::onAction,
            onBackClick = { finish() },
        )
    }
}

/**
 * Keeps the screen on while the check is running.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Finds the activity of a context, the context of a composition can be a wrapper.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun ServiceCheckScreen(
    uiState: ServiceCheckUiState,
    onAction: (ServiceCheckAction) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_service_check),
                onBackClick = onBackClick,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ServiceCheckControls(
                isRunning = uiState.isRunning,
                completed = uiState.completed,
                total = uiState.total,
                onAction = onAction,
            )

            uiState.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = NavigationBarsBottomPadding(),
            ) {
                items(items = uiState.rows, key = { it.guid }) { row ->
                    ServiceCheckRowItem(row)
                    ItemDivider()
                }
            }
        }
    }
}

/**
 * Start and cancel button with the progress of the running check.
 */
@Composable
private fun ServiceCheckControls(
    isRunning: Boolean,
    completed: Int,
    total: Int,
    onAction: (ServiceCheckAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRunning) {
            OutlinedButton(onClick = { onAction(ServiceCheckAction.Cancel) }) {
                Text(text = stringResource(R.string.action_cancel))
            }
        } else {
            Button(onClick = { onAction(ServiceCheckAction.Start) }) {
                Text(text = stringResource(R.string.service_check_action_start))
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        if (isRunning) {
            Text(
                text = stringResource(R.string.service_check_running),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.service_check_progress, completed, total),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * One profile with its exit IP and its Premium status.
 */
@Composable
private fun ServiceCheckRowItem(row: ServiceCheckRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = row.remark,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.service_check_label_exit_ip) + ": " +
                (row.exitIp ?: stringResource(R.string.service_check_value_none)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.service_check_label_premium) + ": " +
                premiumLabel(row),
            style = MaterialTheme.typography.bodySmall,
        )
        row.error?.let { error ->
            Text(
                text = stringResource(R.string.service_check_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Label of the Premium status of one profile.
 */
@Composable
private fun premiumLabel(row: ServiceCheckRow): String {
    val status = row.premiumStatus
    return if (status == null) {
        stringResource(R.string.service_check_value_none)
    } else {
        stringResource(status.labelRes())
    }
}
