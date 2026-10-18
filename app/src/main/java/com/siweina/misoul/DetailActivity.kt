package com.siweina.misoul

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 二级页面：像 HyperMusicCover 那样做成独立 Activity，转场和返回（含预测性返回）都交给系统，
 * 不在 Compose 里自己仿造页面栈。
 */
class DetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val field = Field.from(intent.getStringExtra(EXTRA_FIELD))
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                DetailScreen(field, onDone = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_FIELD = "field"

        fun intent(context: Context, field: Field): android.content.Intent =
            android.content.Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_FIELD, field.id)
    }
}

enum class Field(
    val id: String,
    val title: String,
    val hint: String,
    val prefKey: String,
    val multiline: Boolean,
) {
    Name("name", "自称", "写进 prompt.md 的身份修正句", PersonaHook.KEY_NAME, false),
    Soul("soul", "人格全文", "整份替换 soul.md 的人格设定", PersonaHook.KEY_SOUL, true),
    ProfileName("profile_name", "显示名", "界面上助手叫什么", PersonaHook.KEY_PROFILE_NAME, false),
    Signature("signature", "签名", "显示名下面那行字", PersonaHook.KEY_PROFILE_SIGNATURE, false),
    Welcome("welcome", "欢迎语", "新对话的开场白", PersonaHook.KEY_PROFILE_WELCOME, false),
    TextMap(
        "text_map",
        "文案替换",
        "一行一条「原文=新词」，只匹配完全相同的整句",
        PersonaHook.KEY_TEXT_MAP,
        true,
    );

    companion object {
        fun from(id: String?): Field = entries.firstOrNull { it.id == id } ?: Name
    }
}

@Composable
private fun DetailScreen(field: Field, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PersonaHook.PREFS, Context.MODE_PRIVATE) }
    val fallback = when (field) {
        Field.Name -> PersonaHook.DEFAULT_NAME
        Field.Soul -> readAsset(context, "soul.default.md")
        Field.ProfileName -> PersonaHook.DEFAULT_PROFILE_NAME
        Field.Signature -> PersonaHook.DEFAULT_PROFILE_SIGNATURE
        Field.Welcome -> PersonaHook.DEFAULT_PROFILE_WELCOME
        Field.TextMap -> ""
    }
    var value by remember {
        mutableStateOf(prefs.getString(field.prefKey, null) ?: fallback)
    }
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = field.title,
                subtitle = field.hint,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    TextButton(
                        "保存",
                        {
                            prefs.edit().putString(field.prefKey, value).apply()
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            onDone()
                        },
                    )
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (field.multiline) {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        label = "soul.md 全文",
                        modifier = Modifier.fillMaxSize(),
                        minLines = 18,
                        singleLine = false,
                    )
                } else {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        label = field.title,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
    }
}

private fun readAsset(context: Context, name: String): String =
    runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
