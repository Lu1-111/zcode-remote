package com.lululu.zcoderemote.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lululu.zcoderemote.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原生的电脑列表页（方案第 9、13、14 条）：列出已保存的电脑（电脑名 + 最后使用时间），
 * 点选即加载那台电脑的链接；可以改名、删除（删除前确认）。
 * 一台电脑都没有时变成引导页：说明三种添加方式，给出「免扫码入口」。
 * 「设置与更多」也放在这一页的右上角。
 *
 * 页面上只出现电脑名，链接一个字都不显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerListScreen(
    computers: List<Computer>,
    /** 内核当前加载的那条电脑链接（只用来比对是哪台「使用中」，不用于显示）。 */
    currentLink: String?,
    /** 「重新加载」「在浏览器中打开」是否可用（内核里有没有已打开的页面）。 */
    pageActionsAvailable: Boolean,
    onOpen: (Computer) -> Unit,
    onRename: (sid: String, newName: String) -> Unit,
    onDelete: (sid: String) -> Unit,
    onAddComputer: () -> Unit,
    onScan: () -> Unit,
    onOpenFallbackEntry: () -> Unit,
    onReload: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onSignOut: () -> Unit,
    onBackToDesktop: () -> Unit,
) {
    // 这一页自己的对话框状态。对话框指着哪台电脑只记 sid（String 能存进界面状态），按 sid 找回电脑。
    var renamingSid by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingSid by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var confirmBrowser by rememberSaveable { mutableStateOf(false) }
    var confirmCopy by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val renaming = computers.firstOrNull { it.sid == renamingSid }
    val deleting = computers.firstOrNull { it.sid == deletingSid }

    // 电脑列表按返回键回桌面（docs/ARCHITECTURE.md 第 7 节）。
    BackHandler(onBack = onBackToDesktop)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
                actions = {
                    TextButton(onClick = onScan) { Text(stringResource(R.string.list_action_scan)) }
                    TextButton(onClick = onAddComputer) { Text(stringResource(R.string.list_action_add)) }
                    TextButton(onClick = { showMore = true }) { Text(stringResource(R.string.list_action_more)) }
                },
            )
        },
    ) { padding ->
        if (computers.isEmpty()) {
            Onboarding(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onOpenFallbackEntry = onOpenFallbackEntry,
                onScan = onScan,
                onAddComputer = onAddComputer,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(computers, key = { it.sid }) { computer ->
                    ComputerRow(
                        computer = computer,
                        inUse = computer.link == currentLink,
                        onOpen = { onOpen(computer) },
                        onRename = {
                            renamingSid = computer.sid
                            renameText = computer.name
                        },
                        onDelete = { deletingSid = computer.sid },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                item {
                    // 列表末尾的一行小说明：电脑从哪里来。
                    Text(
                        text = stringResource(R.string.list_footer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 对话框

    if (renaming != null) {
        val computer = renaming
        AlertDialog(
            onDismissRequest = { renamingSid = null },
            title = { Text(stringResource(R.string.list_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.list_rename_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(computer.sid, renameText)
                        renamingSid = null
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingSid = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (deleting != null) {
        val computer = deleting
        AlertDialog(
            onDismissRequest = { deletingSid = null },
            title = { Text(stringResource(R.string.list_delete_title, computer.name)) },
            text = { Text(stringResource(R.string.list_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(computer.sid)
                        deletingSid = null
                    },
                ) { Text(stringResource(R.string.list_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingSid = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showMore) {
        AlertDialog(
            onDismissRequest = { showMore = false },
            title = { Text(stringResource(R.string.more_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MoreItem(stringResource(R.string.more_reload), enabled = pageActionsAvailable) {
                        showMore = false
                        onReload()
                    }
                    MoreItem(stringResource(R.string.more_open_in_browser), enabled = pageActionsAvailable) {
                        showMore = false
                        confirmBrowser = true
                    }
                    MoreItem(stringResource(R.string.more_copy_link), enabled = currentLink != null) {
                        showMore = false
                        confirmCopy = true
                    }
                    MoreItem(stringResource(R.string.more_sign_out), enabled = true, danger = true) {
                        showMore = false
                        confirmSignOut = true
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMore = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    if (confirmBrowser) {
        AlertDialog(
            onDismissRequest = { confirmBrowser = false },
            title = { Text(stringResource(R.string.more_browser_confirm_title)) },
            text = { Text(stringResource(R.string.more_browser_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBrowser = false
                        onOpenInBrowser()
                    },
                ) { Text(stringResource(R.string.more_browser_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBrowser = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmCopy) {
        AlertDialog(
            onDismissRequest = { confirmCopy = false },
            title = { Text(stringResource(R.string.more_copy_confirm_title)) },
            text = { Text(stringResource(R.string.more_copy_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCopy = false
                        onCopyLink()
                    },
                ) { Text(stringResource(R.string.more_copy_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCopy = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.more_signout_confirm_title)) },
            text = { Text(stringResource(R.string.more_signout_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                ) { Text(stringResource(R.string.more_signout_confirm_ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 「更多」菜单里的一项。占满一行，好按。 */
@Composable
private fun MoreItem(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComputerRow(
    computer: Computer,
    inUse: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = computer.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatUsedAt(computer.lastUsedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inUse) {
                Text(
                    text = stringResource(R.string.list_in_use),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onRename) { Text(stringResource(R.string.list_rename)) }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.list_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 第一次使用、还没有电脑时的引导页（方案第 4、10、11、12、14 条）。 */
@Composable
private fun Onboarding(
    modifier: Modifier = Modifier,
    onOpenFallbackEntry: () -> Unit,
    onScan: () -> Unit,
    onAddComputer: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenFallbackEntry) {
                Text(stringResource(R.string.onboarding_fallback_entry))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_fallback_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_ways_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.onboarding_way_scan),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.onboarding_way_paste),
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

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onScan) { Text(stringResource(R.string.onboarding_scan_button)) }
            OutlinedButton(onClick = onAddComputer) { Text(stringResource(R.string.onboarding_add_button)) }
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.onboarding_workspace_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 「最后使用时间」的显示文案。 */
@Composable
private fun formatUsedAt(lastUsedAt: Long): String {
    if (lastUsedAt <= 0L) return stringResource(R.string.list_never_used)
    val formatted = remember(lastUsedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(lastUsedAt))
    }
    return stringResource(R.string.list_last_used, formatted)
}
