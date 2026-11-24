package com.lululu.zcoderemote.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lululu.zcoderemote.R

/**
 * 添加电脑的「粘贴」方式（方案第 12 条）：输入框 + 「读取剪贴板」按钮（读取前先让用户确认）。
 * 输入的内容当场按第 10 节校验，把结论告诉用户；合法的电脑链接原样保存、原样加载。
 * 扫码入口和三种添加方式的说明也放在这一页。
 *
 * 输入框里是用户自己粘进来的链接，按设计要展示出来（用户要能核对）；
 * 电脑列表和别处的界面上仍然只显示电脑名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerScreen(
    text: String,
    onTextChange: (String) -> Unit,
    /** 用户确认后由界面层去读剪贴板；读不到（空、被拒）返回 null。 */
    readClipboard: () -> String?,
    onAddComputer: (link: String, checked: LinkCheck.Computer) -> Unit,
    onOpenEntryLink: (link: String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClipboard by rememberSaveable { mutableStateOf(false) }
    var clipboardMissed by rememberSaveable { mutableStateOf(false) }

    // 从这一页返回就是回电脑列表。
    BackHandler(onBack = onBack)

    val checked = remember(text) { RemoteLink.check(text) }
    val computerCheck = checked as? LinkCheck.Computer

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(onClick = onScan) { Text(stringResource(R.string.list_action_scan)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.add_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.add_link_label)) },
                placeholder = { Text(stringResource(R.string.add_link_placeholder)) },
            )

            // 校验结论：是什么就说是什么，不猜。
            when (checked) {
                is LinkCheck.Computer -> Text(
                    text = stringResource(R.string.add_valid_computer, RemoteLink.displayName(checked.name)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                LinkCheck.Entry -> Text(
                    text = stringResource(R.string.add_entry_link),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LinkCheck.Legacy -> Text(
                    text = stringResource(R.string.link_legacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                LinkCheck.Invalid -> if (text.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.add_invalid_link),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (clipboardMissed) {
                Text(
                    text = stringResource(R.string.add_clipboard_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { confirmClipboard = true },
                ) { Text(stringResource(R.string.add_read_clipboard)) }
                Button(
                    onClick = { computerCheck?.let { onAddComputer(text.trim(), it) } },
                    enabled = computerCheck != null,
                ) { Text(stringResource(R.string.add_save_and_connect)) }
            }

            if (checked is LinkCheck.Entry) {
                OutlinedButton(
                    onClick = { onOpenEntryLink(text.trim()) },
                ) { Text(stringResource(R.string.add_open_entry)) }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_way_scan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.onboarding_way_share),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.onboarding_assetlinks_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClipboard) {
        AlertDialog(
            onDismissRequest = { confirmClipboard = false },
            title = { Text(stringResource(R.string.add_clipboard_confirm_title)) },
            text = { Text(stringResource(R.string.add_clipboard_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClipboard = false
                        val picked = readClipboard()
                        if (picked == null) {
                            clipboardMissed = true
                        } else {
                            clipboardMissed = false
                            onTextChange(picked)
                        }
                    },
                ) { Text(stringResource(R.string.add_clipboard_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClipboard = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
