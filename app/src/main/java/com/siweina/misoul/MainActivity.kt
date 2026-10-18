package com.siweina.misoul

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siweina.misoul.ui.component.liquid.IosLiquidGlassNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {
    // 从二级页（独立 Activity）返回时递增，触发首页重新读一次配置
    private val resumeTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 拨号密令的兜底通知需要通知权限（33+）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(resumeTick.intValue)
            }
        }
    }
}

private class UiState(
    val enabled: Boolean,
    val setEnabled: (Boolean) -> Unit,
    val fixPrompt: Boolean,
    val setFixPrompt: (Boolean) -> Unit,
    val profileEnabled: Boolean,
    val setProfileEnabled: (Boolean) -> Unit,
    val name: String,
    val soul: String,
    val profileName: String,
    val signature: String,
    val welcome: String,
    val open: (Field) -> Unit,
    val reset: () -> Unit,
    val iconHidden: Boolean,
    val setIconHidden: (Boolean) -> Unit,
    val pickUpdate: () -> Unit,
    val checkUpdate: () -> Unit,
    val openLink: (String) -> Unit,
    val info: List<InfoRow>,
    val xiaoaiVersion: String,
    val moduleVersion: String,
)

@Composable
private fun App(refreshTick: Int) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val prefs = remember { context.getSharedPreferences(PersonaHook.PREFS, Context.MODE_PRIVATE) }
    val defaultSoul = remember { readAsset(context, "soul.default.md") }
    val pageBg = if (dark) Color.Black else Color(0xFFF2F2F7)
    val backdrop = rememberLayerBackdrop {
        drawRect(pageBg)
        drawContent()
    }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { 4 }

    var enabled by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PersonaHook.KEY_ENABLED, false))
    }
    var fixPrompt by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PersonaHook.KEY_FIX_PROMPT, true))
    }
    var profileEnabled by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PersonaHook.KEY_PROFILE_ENABLED, true))
    }
    var confirmReset by remember { mutableStateOf(false) }
    var tabJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 底栏高亮自己记：只认「点击」和「滑完落定」，不跟 currentPage/targetPage，
    // 否则跨页滚动经过每一页时它都会动一次，玻璃每动一次就要重画。
    var selectedTab by remember { mutableIntStateOf(0) }
    var pageWidthPx by remember { mutableStateOf(0f) }
    var checkingUpdate by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var soul by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    var welcome by remember { mutableStateOf("") }

    val sync: () -> Unit = {
        name = prefs.getString(PersonaHook.KEY_NAME, null) ?: PersonaHook.DEFAULT_NAME
        soul = prefs.getString(PersonaHook.KEY_SOUL, null) ?: defaultSoul
        profileName = prefs.getString(PersonaHook.KEY_PROFILE_NAME, null)
            ?: PersonaHook.DEFAULT_PROFILE_NAME
        signature = prefs.getString(PersonaHook.KEY_PROFILE_SIGNATURE, null)
            ?: PersonaHook.DEFAULT_PROFILE_SIGNATURE
        welcome = prefs.getString(PersonaHook.KEY_PROFILE_WELCOME, null)
            ?: PersonaHook.DEFAULT_PROFILE_WELCOME
    }
    remember(refreshTick) { sync() }

    val xiaoaiVersion = remember { xiaoaiVersion(context) }
    val moduleVersion = remember { BuildConfig.VERSION_NAME }
    val info = remember(refreshTick) {
        DeviceInfo.rows(context, moduleVersion, xiaoaiVersion)
    }

    var iconHidden by rememberSaveable { mutableStateOf(LauncherIcon.isHidden(context)) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) { UpdateInstaller.stage(context, uri) }
            when {
                file == null -> Toast.makeText(context, "读不出这个 APK", Toast.LENGTH_SHORT).show()
                !UpdateInstaller.handOff(context, file) ->
                    Toast.makeText(context, "没有应用能打开 APK", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val state = UiState(
        enabled = enabled,
        setEnabled = {
            enabled = it
            prefs.edit().putBoolean(PersonaHook.KEY_ENABLED, it).apply()
        },
        fixPrompt = fixPrompt,
        setFixPrompt = {
            fixPrompt = it
            prefs.edit().putBoolean(PersonaHook.KEY_FIX_PROMPT, it).apply()
        },
        profileEnabled = profileEnabled,
        setProfileEnabled = {
            profileEnabled = it
            prefs.edit().putBoolean(PersonaHook.KEY_PROFILE_ENABLED, it).apply()
        },
        name = name,
        soul = soul,
        profileName = profileName,
        signature = signature,
        welcome = welcome,
        open = { field -> context.startActivity(DetailActivity.intent(context, field)) },
        reset = { confirmReset = true },
        iconHidden = iconHidden,
        setIconHidden = { hidden ->
            LauncherIcon.setHidden(context, hidden)
            iconHidden = LauncherIcon.isHidden(context)
            Toast.makeText(
                context,
                if (hidden) "已隐藏，拨号 *#*#${LauncherIcon.SECRET_CODE}#*#* 打开" else "图标已恢复",
                Toast.LENGTH_SHORT,
            ).show()
        },
        pickUpdate = { pickApk.launch(arrayOf("application/vnd.android.package-archive")) },
        checkUpdate = {
            if (!checkingUpdate) {
                checkingUpdate = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        UpdateApi.check(BuildConfig.VERSION_NAME)
                    }
                    when (result) {
                        is UpdateApi.Result.UpToDate -> Toast.makeText(
                            context,
                            "已是最新版本 ${BuildConfig.VERSION_NAME}",
                            Toast.LENGTH_SHORT,
                        ).show()

                        is UpdateApi.Result.Failed -> Toast.makeText(
                            context,
                            "检查失败：${result.message}",
                            Toast.LENGTH_LONG,
                        ).show()

                        is UpdateApi.Result.Found -> {
                            Toast.makeText(
                                context,
                                "发现 ${result.release.tag}，开始下载",
                                Toast.LENGTH_SHORT,
                            ).show()
                            val file = withContext(Dispatchers.IO) {
                                UpdateApi.download(context, result.release)
                            }
                            if (file == null) {
                                Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                            } else if (!UpdateInstaller.handOff(context, file)) {
                                Toast.makeText(context, "没有应用能打开 APK", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
                    checkingUpdate = false
                }
            }
        },
        openLink = { url ->
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Toast.makeText(context, "没有浏览器能打开链接", Toast.LENGTH_SHORT).show()
            }
        },
        info = info,
        xiaoaiVersion = xiaoaiVersion,
        moduleVersion = moduleVersion,
    )

    val items = remember {
        listOf(
            NavigationItem("概览", MiuixIcons.Home),
            NavigationItem("功能", MiuixIcons.Tune),
            NavigationItem("设置", MiuixIcons.Settings),
            NavigationItem("关于", MiuixIcons.Info),
        )
    }
    val canBlur = Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()


    // 滑动（含手势拖拽）落定后才同步底栏高亮；程序化的跨页滚动不算
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.settledPage }
            .collect { page -> selectedTab = page }
    }

    Scaffold(
        bottomBar = {
            IosLiquidGlassNavigationBar(
                modifier = Modifier.padding(horizontal = 12.dp),
                items = items,
                // 高亮只认点击与「滑完落定」：跟 currentPage/targetPage 的话，
                // 跨页滚动经过每一页都会让它动一次，玻璃每动一次就要重画一遍。
                selectedIndex = selectedTab,
                onItemClick = { index ->
                    selectedTab = index
                    if (index != pagerState.currentPage) {
                        tabJob?.cancel()
                        tabJob = scope.launch {
                            val distance = index - pagerState.currentPage
                            if (kotlin.math.abs(distance) <= 1) {
                                pagerState.animateScrollToPage(
                                    index,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 1f,
                                        stiffness = 320f,
                                    ),
                                )
                            } else {
                                // animateScrollToPage 自带「预跳」优化：距离超过一页时它会先
                                // 瞬间贴到目标前一页再动画最后一步（实测 0→3 时 currentPage
                                // 5ms 内就变成 2）。要真正的多页滑动只能自己驱动 offset。
                                val pageSize = pageWidthPx
                                if (pageSize <= 0f) {
                                    pagerState.scrollToPage(index)
                                    return@launch
                                }
                                pagerState.scroll {
                                    var consumed = 0f
                                    androidx.compose.animation.core.animate(
                                        initialValue = 0f,
                                        targetValue = distance * pageSize,
                                        animationSpec = androidx.compose.animation.core.tween(
                                            durationMillis = 240 + 90 * kotlin.math.abs(distance),
                                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                        ),
                                    ) { value, _ ->
                                        scrollBy(value - consumed)
                                        consumed = value
                                    }
                                }
                            }
                        }
                    }
                },
                backdrop = backdrop,
                isBlurActive = canBlur,
            )
        },
    ) { padding ->
        val barHeight = padding.calculateBottomPadding()
        Box(Modifier.fillMaxSize().background(pageBg).layerBackdrop(backdrop)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged {
                        pageWidthPx = it.width.toFloat()
                    },
                // 四页全部预组：切换要滑过中间页，不预组就会在那页掉一帧
                beyondViewportPageCount = 3,
            ) { index ->
                when (index) {
                    0 -> OverviewPage(state, barHeight)
                    1 -> FeaturesPage(state, barHeight)
                    2 -> SettingsPage(state, barHeight)
                    else -> AboutPage(state, barHeight)
                }
            }

            OverlayDialog(
                show = confirmReset,
                title = "恢复默认人格？",
                summary = "人格全文换回内置的大肥鱼人设，其他设置不变。",
                onDismissRequest = { confirmReset = false },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", { confirmReset = false }, Modifier.weight(1f))
                    PrimaryButton(
                        "恢复",
                        {
                            prefs.edit()
                                .putString(PersonaHook.KEY_SOUL, defaultSoul)
                                .putString(PersonaHook.KEY_NAME, PersonaHook.DEFAULT_NAME)
                                .apply()
                            sync()
                            confirmReset = false
                            Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
                        },
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageColumn(bottomPadding: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
            content = content,
        )
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Text(
        title,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = if (subtitle == null) 12.dp else 4.dp),
    )
    if (subtitle != null) {
        Text(
            subtitle,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
        )
    }
}

// ---------------- 概览 ----------------

@Composable
private fun OverviewPage(state: UiState, bottomPadding: Dp) {
    PageColumn(bottomPadding) {
        PageTitle("概览")
        StatusCard(state.enabled, state.moduleVersion, state.xiaoaiVersion)
        Section("设备") {
            state.info.forEach { row -> StackedRow(row.label, row.value) }
        }
        Section("注入") {
            StackedRow("方式", "LSPosed · libxposed API 102")
            StackedRow("作用域", "com.miui.voiceassist")
            StackedRow("人格", if (state.enabled) "替换中" else "官方人格")
        }
        Spacer(Modifier.height(6.dp))
        Hint("改完直接生效；小爱里新建一次对话即可看到新人设。")
    }
}

@Composable
private fun StatusCard(enabled: Boolean, moduleVersion: String, xiaoai: String) {
    val accent = if (enabled) Color(0xFF17A34A) else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val container = if (enabled) Color(0xFFE3F7E9) else MiuixTheme.colorScheme.surfaceContainer
    Card(
        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container, contentColor = accent),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (enabled) "已激活" else "未启用",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(moduleVersion, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "小爱 $xiaoai",
                    style = MiuixTheme.textStyles.footnote1,
                    color = accent.copy(alpha = 0.75f),
                )
            }
            ApprovalArc(active = enabled, color = accent)
        }
    }
}

/** LSPosed 那个「圆环 + 对勾」的状态图标，用 Canvas 画的，不带资源。 */
@Composable
private fun ApprovalArc(active: Boolean, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(84.dp)) {
        val stroke = 8.dp.toPx()
        drawArc(
            color = color.copy(alpha = if (active) 0.95f else 0.45f),
            startAngle = -80f,
            sweepAngle = 290f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        if (active) {
            val w = size.width
            val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.28f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.68f)
                lineTo(w * 0.74f, h * 0.34f)
            }
            drawPath(
                path = path,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
    }
}

/** 标题加粗、数值灰色放下一行——LSPosed 状态卡的排法。 */
@Composable
private fun StackedRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------- 功能（人格 + 形象） ----------------

@Composable
private fun FeaturesPage(state: UiState, bottomPadding: Dp) {
    PageColumn(bottomPadding) {
        PageTitle("功能", "人格与界面形象")
        Section("人格") {
            ArrowPreference(
                title = "自称",
                summary = state.name,
                onClick = { state.open(Field.Name) },
            )
            ArrowPreference(
                title = "人格全文",
                summary = "${state.soul.length} 字 · 点按编辑",
                onClick = { state.open(Field.Soul) },
            )
        }
        Section("形象") {
            ArrowPreference(
                title = "显示名",
                summary = state.profileName.ifBlank { "未设置" },
                onClick = { state.open(Field.ProfileName) },
            )
            ArrowPreference(
                title = "签名",
                summary = state.signature.ifBlank { "未设置" },
                onClick = { state.open(Field.Signature) },
            )
            ArrowPreference(
                title = "欢迎语",
                summary = state.welcome.ifBlank { "未设置" },
                onClick = { state.open(Field.Welcome) },
            )
        }
        Section("开关") {
            SwitchPreference(
                title = "人格替换",
                summary = "关掉即回官方人格，无需卸载",
                checked = state.enabled,
                onCheckedChange = state.setEnabled,
            )
            SwitchPreference(
                title = "修正身份句",
                summary = "官方 prompt.md 要求自称「超级小爱」",
                checked = state.fixPrompt,
                onCheckedChange = state.setFixPrompt,
            )
            SwitchPreference(
                title = "界面显示名与欢迎语",
                summary = "改 config.json / profile / 品牌文案",
                checked = state.profileEnabled,
                onCheckedChange = state.setProfileEnabled,
            )
        }
        Hint("整份替换 soul.md；身份句只改 prompt.md 里那一句，其余硬规则不动。")
    }
}

// ---------------- 设置 ----------------

@Composable
private fun SettingsPage(state: UiState, bottomPadding: Dp) {
    PageColumn(bottomPadding) {
        PageTitle("设置", "入口、更新与维护")
        Section("入口") {
            SwitchPreference(
                title = "隐藏桌面图标",
                summary = "隐藏后拨号 *#*#${LauncherIcon.SECRET_CODE}#*#* 打开；LSPosed 里也能进",
                checked = state.iconHidden,
                onCheckedChange = state.setIconHidden,
            )
        }
        Section("更新") {
            ArrowPreference(
                title = "从本地 APK 更新",
                summary = "选一个 APK，交给系统安装器",
                onClick = state.pickUpdate,
            )
        }
        Section("维护") {
            ArrowPreference(
                title = "恢复默认人格",
                summary = "换回内置的大肥鱼人设",
                onClick = state.reset,
            )
        }
        Hint("日志：adb logcat | grep MISoul")
    }
}

// ---------------- 关于 ----------------

@Composable
private fun AboutPage(state: UiState, bottomPadding: Dp) {
    PageColumn(bottomPadding) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2E6CFF), Color(0xFF56C4FF), Color(0xFF78E8FF)),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "MISoul",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            state.moduleVersion,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        Section("更新") {
            ArrowPreference(
                title = "获取更新",
                summary = "检查 GitHub Releases 并交给系统安装器",
                onClick = state.checkUpdate,
            )
            ArrowPreference(
                title = "从本地 APK 安装",
                summary = "离线时用，选一个 APK 交给系统安装器",
                onClick = state.pickUpdate,
            )
        }
        Section("链接") {
            ArrowPreference(
                title = "项目地址",
                summary = "github.com/${UpdateApi.REPO}",
                onClick = { state.openLink("https://github.com/${UpdateApi.REPO}") },
            )
            ArrowPreference(
                title = "问题反馈",
                summary = "Issues · 附上日志与版本号",
                onClick = { state.openLink("https://github.com/${UpdateApi.REPO}/issues") },
            )
            ArrowPreference(
                title = "更新日志",
                summary = "Releases",
                onClick = { state.openLink("https://github.com/${UpdateApi.REPO}/releases") },
            )
        }
        Section("关于") {
            StackedRow("作用域", "com.miui.voiceassist")
            StackedRow("小爱", state.xiaoaiVersion)
            StackedRow("模块", state.moduleVersion)
        }
        Section("致谢") {
            StackedRow("界面组件", "Miuix")
            StackedRow("液态玻璃", "AndroidLiquidGlass")
            StackedRow("架构参考", "HyperMusicCover")
        }
        Hint("MISoul 只在本机内存里替换人格文本，不改小爱的任何文件。")
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    SmallTitle(title)
    Card(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun PrimaryButton(text: String, click: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        text,
        click,
        modifier,
        colors = ButtonDefaults.textButtonColors(
            color = MiuixTheme.colorScheme.primary,
            textColor = MiuixTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Start,
    )
}

private fun readAsset(context: Context, name: String): String =
    runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")

private fun xiaoaiVersion(context: Context): String =
    runCatching {
        val info = context.packageManager.getPackageInfo("com.miui.voiceassist", 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未安装")
