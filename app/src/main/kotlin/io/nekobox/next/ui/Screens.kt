package io.nekobox.next.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import io.nekobox.next.R
import io.nekobox.next.data.ProfileRepository
import io.nekobox.next.db.NodeEntity
import io.nekobox.next.engine.config.formatRate
import io.nekobox.next.engine.model.ByeDpiPresets
import io.nekobox.next.engine.model.ByeDpiSpec
import io.nekobox.next.engine.model.NodeSpecs
import io.nekobox.next.engine.model.PerAppMode
import io.nekobox.next.engine.model.RouteRule
import io.nekobox.next.engine.model.RunMode
import io.nekobox.next.session.LogBuffer
import io.nekobox.next.session.Phase
import io.nekobox.next.session.SessionStore
import io.nekobox.next.settings.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@Composable
fun NekoRoot(onConnect: () -> Unit) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsState()
    NekoTheme(dynamic = settings.dynamicColor) {
        val nav = rememberNavController()
        var dest by remember { mutableStateOf(TopDest.Home) }
        val topLevel = mapOf("home" to TopDest.Home, "nodes" to TopDest.Nodes, "route" to TopDest.Route, "more" to TopDest.More)
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                    composable("home") { HomeScreen(onConnect) { nav.navigate("nodes") } }
                    composable("nodes") { NodesScreen(nav) }
                    composable("editor/{id}") { entry -> EditorScreen(entry.arguments?.getString("id"), nav) }
                    composable("groups") { GroupsScreen(nav) }
                    composable("route") { RouteScreen() }
                    composable("more") { MoreScreen(nav) }
                    composable("logs") { LogsScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("dashboard") { DashboardScreen() }
                    composable("backup") { BackupScreen() }
                    composable("assets") { AssetsScreen() }
                    composable("about") { AboutScreen() }
                }
                FloatingNav(
                    current = dest,
                    onSelect = {
                        dest = it
                        nav.navigate(when (it) {
                            TopDest.Home -> "home"
                            TopDest.Nodes -> "nodes"
                            TopDest.Route -> "route"
                            TopDest.More -> "more"
                        }) { launchSingleTop = true }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                LaunchedEffect(nav) {
                    nav.currentBackStackEntryFlow.collect { entry ->
                        topLevel[entry.destination.route]?.let { dest = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onConnect: () -> Unit, onOpenNodes: () -> Unit) {
    val vm: HomeViewModel = hiltViewModel()
    val state by vm.ui.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    val button = 250.dp
    Column(
        Modifier.fillMaxSize().padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(when (state.phase) {
                Phase.Idle -> R.string.status_idle
                Phase.Connecting -> R.string.status_connecting
                Phase.Connected -> R.string.status_connected
            }),
            style = MaterialTheme.typography.displaySmallEmphasized,
        )
        Spacer(Modifier.height(28.dp))
        ConnectButton(state.phase, {
            if (state.phase == Phase.Connected || state.phase == Phase.Connecting) vm.disconnect() else onConnect()
        }, button)
        Spacer(Modifier.height(16.dp))
        Text(
            "↑ ${formatRate(state.upload)}    ↓ ${formatRate(state.download)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(22.dp))
        Surface(onClick = { sheet = true }, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProtocolAvatar(state.protocol, Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(state.nodeName.ifBlank { stringResource(R.string.node_none) }, style = MaterialTheme.typography.titleMedium)
                    Text(state.latency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (sheet) NodePickerSheet(state.nodes, { sheet = false }) { group, node ->
        vm.select(group, node)
        sheet = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodePickerSheet(nodes: List<NodeEntity>, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (nodes.isEmpty()) {
            Text(stringResource(R.string.empty_nodes), modifier = Modifier.padding(24.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(nodes, key = { it.id }) { node ->
                ListItem(
                    headlineContent = { Text(node.name) },
                    supportingContent = { Text(node.protocol) },
                    leadingContent = { ProtocolAvatar(node.protocol) },
                    modifier = Modifier.clickable { onPick(node.groupId, node.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NodesScreen(nav: NavHostController) {
    val vm: NodesViewModel = hiltViewModel()
    val groups by vm.groups.collectAsState()
    val groupId by vm.groupId.collectAsState()
    val nodes by vm.nodes.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || groupId == null) return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
        scope.launch { vm.importText(groupId!!, text) }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
        Column(Modifier.fillMaxSize()) {
            if (groups.isEmpty()) {
                Text(stringResource(R.string.empty_nodes), modifier = Modifier.padding(24.dp))
            } else {
                ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }, modifier = Modifier.padding(12.dp)) {
                    groups.forEach { group ->
                        toggleableItem(
                            checked = group.id == groupId,
                            label = group.name,
                            onCheckedChange = { vm.groupId.value = group.id },
                        )
                    }
                }
                if (nodes.isEmpty()) {
                    Text(stringResource(R.string.empty_nodes), modifier = Modifier.padding(24.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(nodes, key = { it.id }) { node ->
                        ListItem(
                            headlineContent = { Text(node.name) },
                            supportingContent = { Text(node.latencyMs?.let { "$it ms" } ?: "—") },
                            leadingContent = { ProtocolAvatar(node.protocol) },
                            trailingContent = {
                                TextButton(onClick = { nav.navigate("editor/${node.id}") }) { Text(stringResource(R.string.save)) }
                            },
                            modifier = Modifier.clickable { scope.launch { vm.select(node.groupId, node.id) } },
                        )
                    }
                }
            }
            HorizontalFloatingToolbar(expanded = true, modifier = Modifier.padding(12.dp)) {
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.LeadingButton(onClick = { scope.launch { vm.measure(false) } }) {
                            Text(stringResource(R.string.action_urltest))
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.TrailingButton(onClick = { speedMenu = true }) {
                            androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                    },
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_urltest)) }, onClick = { speedMenu = false; scope.launch { vm.measure(true) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_tcping)) }, onClick = { speedMenu = false; scope.launch { vm.measure(false) } })
                }
                TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.action_sort)) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sort_name)) }, onClick = { sortMenu = false; scope.launch { vm.sort(false) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sort_latency)) }, onClick = { sortMenu = false; scope.launch { vm.sort(true) } })
                }
                TextButton(onClick = { scope.launch { vm.refresh() } }) { Text(stringResource(R.string.action_update)) }
            }
        }
        FloatingActionButtonMenu(
            expanded = menu,
            button = {
                ToggleFloatingActionButton(checked = menu, onCheckedChange = { menu = it }) {
                    androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 88.dp),
        ) {
            FloatingActionButtonMenuItem(onClick = { menu = false; nav.navigate("editor/new") }, text = { Text(stringResource(R.string.action_add)) }, icon = { androidx.compose.material3.Icon(Icons.Filled.Add, null) })
            FloatingActionButtonMenuItem(onClick = {
                menu = false
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                if (clip.isNotBlank() && groupId != null) scope.launch { vm.importText(groupId!!, clip) }
            }, text = { Text(stringResource(R.string.action_clipboard)) }, icon = { androidx.compose.material3.Icon(Icons.Filled.Subject, null) })
            FloatingActionButtonMenuItem(onClick = { menu = false; nav.navigate("editor/scan") }, text = { Text(stringResource(R.string.action_scan)) }, icon = { androidx.compose.material3.Icon(Icons.Filled.Info, null) })
            FloatingActionButtonMenuItem(onClick = { menu = false; openFile.launch(arrayOf("*/*")) }, text = { Text(stringResource(R.string.action_file)) }, icon = { androidx.compose.material3.Icon(Icons.Filled.Storage, null) })
        }
    }
}


@Composable
private fun EditorScreen(id: String?, nav: NavHostController) {
    val vm: EditorViewModel = hiltViewModel()
    LaunchedEffect(id) { vm.load(id) }
    val state by vm.state
    if (id == "scan") {
        ScanPaste { text ->
            vm.importRaw(text)
            nav.popBackStack()
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(state.name, { vm.state.value = state.copy(name = it) }, label = { Text(stringResource(R.string.field_name)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.protocol), style = MaterialTheme.typography.titleMedium)
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            NodeSpecs.protocols.forEach { protocol ->
                toggleableItem(checked = state.protocol == protocol, label = protocol, onCheckedChange = { vm.switch(protocol) })
            }
        }
        if (state.protocol == "byedpi") {
            ByeDpiPresets.all.forEach { preset ->
                TextButton(onClick = { vm.applyPreset(preset) }) {
                    Text(when (preset) {
                        ByeDpiPresets.MILD -> stringResource(R.string.preset_mild)
                        ByeDpiPresets.SPLIT -> stringResource(R.string.preset_split)
                        ByeDpiPresets.DISORDER -> stringResource(R.string.preset_disorder)
                        ByeDpiPresets.FAKE -> stringResource(R.string.preset_fake)
                        else -> stringResource(R.string.preset_custom)
                    })
                }
            }
        }
        state.fields.forEach { (key, value) ->
            if (key == "protocol" || key == "detourNodeId") return@forEach
            OutlinedTextField(
                value = value,
                onValueChange = { vm.edit(key, it) },
                label = { Text(fieldLabel(key)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = if (key.endsWith("Port") || key == "mtu") KeyboardType.Number else KeyboardType.Text),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save { nav.popBackStack() } }, modifier = Modifier.height(ButtonDefaults.ExtraLargeContainerHeight)) {
                Text(stringResource(R.string.save))
            }
            if (state.existing) {
                TextButton(onClick = { vm.delete { nav.popBackStack() } }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun fieldLabel(key: String): String = when (key) {
    "server" -> stringResource(R.string.field_server)
    "serverPort" -> stringResource(R.string.field_port)
    "password" -> stringResource(R.string.field_password)
    "uuid" -> stringResource(R.string.field_uuid)
    "hosts" -> stringResource(R.string.hosts)
    "rawArgs" -> stringResource(R.string.preset_custom)
    "preset" -> stringResource(R.string.protocol)
    else -> key
}

@Composable
private fun ScanPaste(onText: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4)
        Button(onClick = { if (text.isNotBlank()) onText(text) }) { Text(stringResource(R.string.confirm)) }
    }
}

@Composable
private fun GroupsScreen(nav: NavHostController) {
    val vm: NodesViewModel = hiltViewModel()
    val groups by vm.groups.collectAsState()
    val subs by vm.subs.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.groups_title), style = MaterialTheme.typography.headlineSmallEmphasized)
        groups.forEach { Text(it.name + " · " + it.type) }
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) })
        Button(onClick = { if (name.isNotBlank()) vm.addGroup(name) }) { Text(stringResource(R.string.add_group)) }
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.subscription_url)) }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.addSubscription(name.ifBlank { "订阅" }, url) }) { Text(stringResource(R.string.add_subscription)) }
        subs.forEach { Text("${it.name}  ${it.lastError.orEmpty()}") }
        TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.confirm)) }
    }
}

@Composable
private fun RouteScreen() {
    val vm: RouteViewModel = hiltViewModel()
    var page by remember { mutableStateOf(0) }
    val rules by vm.rules.collectAsState()
    val selected by vm.packages.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0).map {
            it.packageName to pm.getApplicationLabel(it).toString()
        }.sortedBy { it.second }
    }
    val rulesLabel = stringResource(R.string.route_rules)
    val appsLabel = stringResource(R.string.route_apps)
    val offLabel = stringResource(R.string.per_app_off)
    val allowLabel = stringResource(R.string.per_app_allow)
    val denyLabel = stringResource(R.string.per_app_deny)
    Column(Modifier.fillMaxSize().padding(16.dp).padding(bottom = 96.dp)) {
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            toggleableItem(checked = page == 0, label = rulesLabel, onCheckedChange = { page = 0 })
            toggleableItem(checked = page == 1, label = appsLabel, onCheckedChange = { page = 1 })
        }
        if (page == 0) {
            if (rules.isEmpty()) Text(stringResource(R.string.empty_rules), modifier = Modifier.padding(top = 24.dp))
            LazyColumn {
                items(rules) { rule ->
                    ListItem(headlineContent = { Text(rule.configJson) })
                }
            }
            var suffix by remember { mutableStateOf("") }
            OutlinedTextField(suffix, { suffix = it }, label = { Text(stringResource(R.string.domain_suffix)) })
            Button(onClick = { vm.addSuffix(suffix); suffix = "" }) { Text(stringResource(R.string.add_rule)) }
        } else {
            ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                toggleableItem(checked = settings.perAppMode == PerAppMode.OFF, label = offLabel, onCheckedChange = { vm.mode(PerAppMode.OFF) })
                toggleableItem(checked = settings.perAppMode == PerAppMode.ALLOW, label = allowLabel, onCheckedChange = { vm.mode(PerAppMode.ALLOW) })
                toggleableItem(checked = settings.perAppMode == PerAppMode.DENY, label = denyLabel, onCheckedChange = { vm.mode(PerAppMode.DENY) })
            }
            LazyColumn {
                items(apps) { app ->
                    ListItem(
                        headlineContent = { Text(app.second) },
                        supportingContent = { Text(app.first) },
                        trailingContent = {
                            Switch(checked = app.first in selected, onCheckedChange = { vm.toggle(app.first, it) })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize().padding(16.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_more), style = MaterialTheme.typography.headlineSmallEmphasized)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreTile(stringResource(R.string.more_logs), Icons.Filled.Subject, { nav.navigate("logs") }, Modifier.weight(1f))
            MoreTile(stringResource(R.string.more_settings), Icons.Filled.Settings, { nav.navigate("settings") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreTile(stringResource(R.string.more_dashboard), Icons.Filled.Dashboard, { nav.navigate("dashboard") }, Modifier.weight(1f))
            MoreTile(stringResource(R.string.more_backup), Icons.Filled.Restore, { nav.navigate("backup") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreTile(stringResource(R.string.more_assets), Icons.Filled.Storage, { nav.navigate("assets") }, Modifier.weight(1f))
            MoreTile(stringResource(R.string.more_about), Icons.Filled.Info, { nav.navigate("about") }, Modifier.weight(1f))
        }
        TextButton(onClick = { nav.navigate("groups") }) { Text(stringResource(R.string.groups_title)) }
    }
}

@Composable
private fun LogsScreen() {
    val vm: LogViewModel = hiltViewModel()
    val lines by vm.lines.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp).padding(bottom = 96.dp)) {
        TextButton(onClick = vm::clear) { Text(stringResource(R.string.clear_logs)) }
        if (lines.isEmpty()) Text(stringResource(R.string.empty_logs))
        LazyColumn { items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScreen() {
    val vm: SettingsViewModel = hiltViewModel()
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val vpnLabel = stringResource(R.string.mode_vpn)
        val proxyLabel = stringResource(R.string.mode_proxy)
        Text(stringResource(R.string.more_settings), style = MaterialTheme.typography.headlineSmallEmphasized)
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            toggleableItem(checked = s.mode == RunMode.VPN, label = vpnLabel, onCheckedChange = { vm.update { copy(mode = RunMode.VPN) } })
            toggleableItem(checked = s.mode == RunMode.PROXY, label = proxyLabel, onCheckedChange = { vm.update { copy(mode = RunMode.PROXY) } })
        }
        Text(stringResource(R.string.tun_stack))
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            listOf("gvisor", "system", "mixed").forEach { stack ->
                toggleableItem(checked = s.tunStack == stack, label = stack, onCheckedChange = { vm.update { copy(tunStack = stack) } })
            }
        }
        OutlinedTextField(s.mtu.toString(), { raw -> raw.toIntOrNull()?.let { vm.update { copy(mtu = it) } } }, label = { Text(stringResource(R.string.mtu)) })
        SettingSwitch(stringResource(R.string.ipv6), s.ipv6) { vm.update { copy(ipv6 = it) } }
        SettingSwitch(stringResource(R.string.fakedns), s.fakeDns) { vm.update { copy(fakeDns = it) } }
        OutlinedTextField(s.dnsRemote, { vm.update { copy(dnsRemote = it) } }, label = { Text(stringResource(R.string.dns_remote)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.dnsDirect, { vm.update { copy(dnsDirect = it) } }, label = { Text(stringResource(R.string.dns_direct)) }, modifier = Modifier.fillMaxWidth())
        SettingSwitch(stringResource(R.string.boot), s.bootStart) { vm.update { copy(bootStart = it) } }
        SettingSwitch(stringResource(R.string.dynamic_color), s.dynamicColor) { vm.update { copy(dynamicColor = it) } }
        OutlinedTextField(s.globalOverride, { vm.update { copy(globalOverride = it) } }, label = { Text(stringResource(R.string.override_json)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(value, onChange)
    }
}

@Composable
private fun DashboardScreen() {
    val session by hiltViewModel<HomeViewModel>().ui.collectAsState()
    Column(Modifier.padding(24.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.more_dashboard), style = MaterialTheme.typography.headlineSmallEmphasized)
        if (session.phase != Phase.Connected) {
            Text(stringResource(R.string.dashboard_offline))
        } else {
            Text("↑ ${formatRate(session.upload)}")
            Text("↓ ${formatRate(session.download)}")
            Text(if (session.native) "sing-box" else stringResource(R.string.core_missing))
        }
    }
}

@Composable
private fun BackupScreen() {
    val vm: BackupViewModel = hiltViewModel()
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export { text -> context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            vm.import(text)
        }
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { export.launch("nekobox-backup.json") }, modifier = Modifier.height(ButtonDefaults.ExtraLargeContainerHeight)) { Text(stringResource(R.string.export_backup)) }
        Button(onClick = { import.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.import_backup)) }
    }
}

@Composable
private fun AssetsScreen() {
    val vm: RouteViewModel = hiltViewModel()
    val sets by vm.ruleSets.collectAsState()
    var tag by remember { mutableStateOf("geosite") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.more_assets), style = MaterialTheme.typography.headlineSmallEmphasized)
        sets.forEach { Text("${it.tag}  ${it.url}") }
        OutlinedTextField(tag, { tag = it }, label = { Text("tag") })
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.addRuleSet(tag, url) }) { Text(stringResource(R.string.confirm)) }
    }
}

@Composable
private fun AboutScreen() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMediumEmphasized)
        Text(stringResource(R.string.about_body))
    }
}

private fun Modifier.size(dp: androidx.compose.ui.unit.Dp) = this.then(Modifier.height(dp).width(dp))

data class HomeUi(
    val phase: Phase = Phase.Idle,
    val upload: Long = 0,
    val download: Long = 0,
    val native: Boolean = false,
    val nodeName: String = "",
    val protocol: String = "byedpi",
    val latency: String = "",
    val nodes: List<NodeEntity> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    session: SessionStore,
    private val repo: ProfileRepository,
    private val controller: SessionController,
) : ViewModel() {
    val ui = repo.settings.flatMapLatest { settings ->
        val nodesFlow = settings.selectedGroupId?.let { repo.nodes(it) } ?: flowOf(emptyList())
        combine(session.state, nodesFlow) { s, nodes ->
            val current = nodes.firstOrNull { it.id == settings.selectedNodeId }
            HomeUi(
                phase = s.phase,
                upload = s.uploadBps,
                download = s.downloadBps,
                native = s.nativeCore,
                nodeName = current?.name.orEmpty(),
                protocol = current?.protocol ?: "byedpi",
                latency = current?.latencyMs?.let { "$it ms" } ?: "",
                nodes = nodes,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    fun disconnect() = controller.disconnect()
    fun select(group: String, node: String) { viewModelScope.launch { controller.select(group, node) } }
}

@HiltViewModel
class NodesViewModel @Inject constructor(
    private val repo: ProfileRepository,
    private val controller: SessionController,
) : ViewModel() {
    val groups = repo.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subs = repo.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val groupId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val nodes = groupId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.nodes(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repo.ensureSeeded()
            val settings = repo.currentSettings()
            groupId.value = settings.selectedGroupId
        }
    }

    fun select(group: String, node: String) { viewModelScope.launch { controller.select(group, node) } }
    fun importText(group: String, text: String) { viewModelScope.launch { repo.importText(group, text) } }
    fun measure(url: Boolean) { viewModelScope.launch { groupId.value?.let { controller.measure(it, url) } } }
    fun sort(byLatency: Boolean) { viewModelScope.launch { groupId.value?.let { repo.sortGroup(it, byLatency) } } }
    fun refresh() { viewModelScope.launch { repo.refreshAll() } }
    fun addGroup(name: String) { viewModelScope.launch { groupId.value = repo.addGroup(name, "selector") } }
    fun addSubscription(name: String, url: String) { viewModelScope.launch { groupId.value?.let { repo.addSubscription(name, url, it) } } }
}

data class EditorState(
    val id: String = "",
    val name: String = "",
    val protocol: String = "vless",
    val fields: Map<String, String> = emptyMap(),
    val existing: Boolean = false,
    val groupId: String = "",
)

@HiltViewModel
class EditorViewModel @Inject constructor(private val repo: ProfileRepository) : ViewModel() {
    val state = androidx.compose.runtime.mutableStateOf(EditorState())
    fun load(id: String?) {
        viewModelScope.launch {
            val settings = repo.currentSettings()
            if (id == null || id == "new" || id == "scan") {
                state.value = fieldsOf("vless", "", settings.selectedGroupId.orEmpty(), false)
                return@launch
            }
            val entity = repo.findNode(id) ?: return@launch
            val spec = NodeSpecs.decode(entity.configJson)
            state.value = EditorState(
                id = entity.id,
                name = entity.name,
                protocol = entity.protocol,
                fields = fields(NodeSpecs.toJsonObject(spec)),
                existing = true,
                groupId = entity.groupId,
            )
        }
    }
    fun switch(protocol: String) { state.value = fieldsOf(protocol, state.value.name, state.value.groupId, state.value.existing).copy(id = state.value.id) }
    fun edit(key: String, value: String) { state.value = state.value.copy(fields = state.value.fields + (key to value)) }
    fun applyPreset(preset: String) {
        val args = ByeDpiPresets.args(preset, state.value.fields["rawArgs"].orEmpty())
        state.value = state.value.copy(fields = state.value.fields + ("preset" to preset) + ("rawArgs" to args))
    }
    fun save(done: () -> Unit) {
        viewModelScope.launch {
            val spec = NodeSpecs.fromJsonObject(buildJsonObject {
                put("protocol", JsonPrimitive(state.value.protocol))
                state.value.fields.forEach { (k, v) ->
                    when {
                        k == "hosts" -> put(k, kotlinx.serialization.json.buildJsonArray {
                            v.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) }
                        })
                        k.endsWith("Port") || k == "mtu" || k == "alterId" || k.endsWith("Mbps") -> put(k, JsonPrimitive(v.toIntOrNull() ?: 0))
                        v == "true" || v == "false" -> put(k, JsonPrimitive(v.toBoolean()))
                        else -> put(k, JsonPrimitive(v))
                    }
                }
            })
            val id = state.value.id.ifBlank { java.util.UUID.randomUUID().toString() }
            val group = state.value.groupId.ifBlank { repo.currentSettings().selectedGroupId.orEmpty() }
            repo.saveNode(id, group, state.value.name.ifBlank { state.value.protocol }, spec, null)
            done()
        }
    }
    fun delete(done: () -> Unit) { viewModelScope.launch { if (state.value.id.isNotBlank()) repo.deleteNode(state.value.id); done() } }
    fun importRaw(text: String) { viewModelScope.launch { repo.currentSettings().selectedGroupId?.let { repo.importText(it, text) } } }
    private fun fieldsOf(protocol: String, name: String, group: String, existing: Boolean): EditorState {
        return EditorState("", name, protocol, fields(NodeSpecs.toJsonObject(NodeSpecs.default(protocol))), existing, group)
    }
    private fun fields(obj: JsonObject) = obj.mapValues { (_, value) ->
        when (value) {
            is kotlinx.serialization.json.JsonArray -> value.joinToString(",") { it.jsonPrimitive.content }
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            else -> value.toString()
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: ProfileRepository) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    fun update(block: AppSettings.() -> AppSettings) { viewModelScope.launch { repo.updateSettings { it.block() } } }
}

@HiltViewModel
class LogViewModel @Inject constructor(private val logs: LogBuffer) : ViewModel() {
    val lines = logs.flow
    fun clear() = logs.clear()
}

@HiltViewModel
class RouteViewModel @Inject constructor(private val repo: ProfileRepository) : ViewModel() {
    val rules = repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val packages = repo.packages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val ruleSets = repo.ruleSets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    fun addSuffix(suffix: String) { viewModelScope.launch { if (suffix.isNotBlank()) repo.saveRule(RouteRule(domainSuffix = listOf(suffix), outbound = "bypass")) } }
    fun toggle(pkg: String, on: Boolean) { viewModelScope.launch { repo.togglePackage(pkg, on) } }
    fun mode(value: PerAppMode) { viewModelScope.launch { repo.updateSettings { it.copy(perAppMode = value) } } }
    fun addRuleSet(tag: String, url: String) { viewModelScope.launch { repo.saveRuleSet(tag, url) } }
}

@HiltViewModel
class BackupViewModel @Inject constructor(private val repo: ProfileRepository) : ViewModel() {
    fun export(write: (String) -> Unit) { viewModelScope.launch { write(repo.exportBackup()) } }
    fun import(text: String) { viewModelScope.launch { repo.importBackup(text) } }
}
