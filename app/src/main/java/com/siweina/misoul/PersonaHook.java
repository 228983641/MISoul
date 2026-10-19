package com.siweina.misoul;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * MISoul：给超级小爱（com.miui.voiceassist）换人格，不动 APK、不改数据目录。
 *
 * <p>注入点：
 * <ul>
 *   <li>{@code rs.a$a.readAsset / readFile} —— 载入 {@code agents/osbot.main/soul.md} 与
 *       {@code prompt.md} 时替换人格文本、修正身份句；</li>
 *   <li>{@code ck.o0.l(String) / m(File)} —— 解析 {@code profile.json} 成 AgentProfile 之后，
 *       替换界面显示名 / 签名 / 欢迎语（profile.json 不走 readAsset，必须单独 hook）。</li>
 * </ul>
 *
 * <p>配置由模块 App 的 {@link PersonaProvider} 通过 ContentResolver.call 提供。
 */
public final class PersonaHook extends XposedModule {

    public static final String PREFS = "persona";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SOUL = "soul";
    public static final String KEY_FIX_PROMPT = "fix_prompt";
    public static final String KEY_NAME = "name";

    public static final String KEY_PROFILE_ENABLED = "profile_enabled";
    public static final String KEY_PROFILE_NAME = "profile_name";
    public static final String KEY_PROFILE_SIGNATURE = "profile_signature";
    public static final String KEY_PROFILE_WELCOME = "profile_welcome";
    /** 用户自定义文案替换，一行一条「原文=新词」。 */
    public static final String KEY_TEXT_MAP = "text_map";
    /** 追加到系统提示末尾的语气契约。 */
    public static final String KEY_STYLE_CONTRACT = "style_contract";
    /** 是否对工具描述做瘦身。 */
    public static final String KEY_TOOL_TRIM = "tool_trim";
    /** 单个工具的描述覆盖，一行一条「工具名=新描述」。 */
    public static final String KEY_TOOL_OVERRIDES = "tool_overrides";
    /** 随包发布的内置精简描述（`### 工具名` 分块），由 ContentProvider 读 assets 传过来。 */
    public static final String KEY_TOOL_DEFAULTS = "tool_defaults";
    /** 自定义的 tool_selection_rules.md；空 = 用官方那份。 */
    public static final String KEY_RULES_TEXT = "rules_text";
    /** 工具描述深度压缩（实验）：按块打分 + 每工具硬上限。 */
    public static final String KEY_TOOL_DEEP = "tool_deep";

    /** 「选工具规则」的内置精简版，作为编辑器的初始内容；清空保存即回到官方那份。 */
    public static final String COMPACT_RULES =
            "# 选工具规则（精简版）\n\n"
                    + "## 优先级\n"
                    + "专用命令 > 专用工具 > A2A/MCP/Skill > controlApp（最后手段）。\n"
                    + "已有专用能力能完成时，不要用 controlApp 绕。\n\n"
                    + "## 闹钟 / 日程 / 定时 的区分（最容易混）\n"
                    + "- 「叫我起床」「早上 X 点闹我」→ `deskclock add`（闹钟，不支持指定日期）\n"
                    + "- 「明天 X 点开会提醒」「某天 X 点提醒我」→ `calendar create`（日程）\n"
                    + "- 「明天有会但没说几点」→ 先问具体时间，<b>禁止默认 9 点</b>\n"
                    + "- 「5 分钟后叫我」「到点跑个 AI 任务」→ `timer set`（定时/倒计时任务，不是闹钟）\n"
                    + "- 「每年 X 月 X 号」「生日/纪念日/倒数日」→ `calendar new-reminder`\n"
                    + "- 「上车后/关闹钟后执行」→ `timer set --event-type-id car_boarded|alarm_dismissed`，不要换算成时间\n\n"
                    + "## 时间与参数格式\n"
                    + "- 时间统一 ISO：`2026-10-19T07:00`；不要写「明天早上」这种相对说法进参数。\n"
                    + "- 坐标经度在前、纬度在后；缺省内/城市时先问，不要瞎填。\n"
                    + "- 参数只传用户原话里明确过的信息，缺失就问一句，不要替用户假设。\n\n"
                    + "## 什么时候不要用工具\n"
                    + "- 纯闲聊、情绪回应、解释概念：直接回答，别调工具。\n"
                    + "- 用户只是问「能不能做」，先回答再问要不要做。\n"
                    + "- 工具失败一次就如实说，不要换个工具硬凑。\n";
    /** 深度压缩时每个工具保留的字符上限。 */
    private static final int DEEP_BUDGET = 800;

    /** 契约里自带的标记，用来避免重复追加。 */
    private static final String STYLE_MARKER = "【语气契约】";

    public static final String DEFAULT_STYLE_CONTRACT =
            "下面是硬性语气要求，优先于上面任何设定：\n"
                    + "1. 说话像熟人发消息：短句为主，一条尽量不超过 40 字，能一句说完就别分点。\n"
                    + "2. 禁止书面语与客服腔：不要用「首先/其次/最后/综上所述/此外/值得注意的是」"
                    + "，不要出现「希望对你有帮助」「作为 AI」「建议您」这类句式，不要用「您」。\n"
                    + "3. 不要动不动列清单或加小标题；只有对方明确要步骤、要对比时才用列表。\n"
                    + "4. 允许口语、语气词和碎片句（如「诶」「行吧」「草」），该吐槽就吐槽，别端着。\n"
                    + "5. 仍然保持人格设定里的自称、称呼和性格，别因为这条要求变成另一个人。";

    public static final String DEFAULT_NAME = "DeepSeek（日常被叫大肥鱼）";
    public static final String DEFAULT_PROFILE_NAME = "大肥鱼";
    public static final String DEFAULT_PROFILE_SIGNATURE = "你一个人的赛博小鲸鱼";
    public static final String DEFAULT_PROFILE_WELCOME = "我回来了";

    private static final String TAG = "MISoul";
    private static final String PKG = "com.miui.voiceassist";
    private static final String AGENT = "osbot.main";
    private static final String AGENT_PREFIX = "agents/" + AGENT + "/";
    private static final String SOUL = "soul.md";
    private static final String PROMPT = "prompt.md";
    private static final String RULES = "tool_selection_rules.md";

    /** 官方 prompt.md 里那句"表明自己是超级小爱"，做定点替换而不是整份覆盖。 */
    private static final String OFFICIAL_CLAIM = "并表明自己是超级小爱";

    /** 界面上不会经由 agent 数据、而是直接渲染的品牌文案。 */
    private static final String BRAND_NAME = "超级小爱";

    private final Object installLock = new Object();
    private boolean installed;
    private boolean reportedFailure;

    private volatile Context appContext;
    private volatile Bundle cachedConfig;
    private volatile long cachedAt;
    private volatile Constructor<?> profileConstructor;
    private volatile String lastLoggedName;
    private volatile String lastLoggedText;
    private volatile boolean contractLogged;
    private volatile boolean toolTrimLogged;
    private final java.util.concurrent.atomic.AtomicInteger toolCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong toolCharsBefore =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong toolCharsAfter =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong toolParamChars =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile int systemPromptChars = -1;
    private volatile boolean statsScheduled;
    private final java.util.concurrent.atomic.AtomicInteger paramHookCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger paramTrimCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong paramCharsBefore =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong paramCharsAfter =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile Object jsonInstance;
    private static volatile java.lang.reflect.Method jsonParse;
    private volatile boolean jsonRebuildWarned;
    private volatile java.util.Map<String, String> cachedOverrides;
    private volatile java.util.Map<String, String> cachedBuiltin;
    private volatile String cachedBuiltinRaw;
    private volatile String cachedOverridesRaw;
    private volatile java.util.Map<String, String> cachedTextMap;
    private volatile String cachedTextMapStamp;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PKG.equals(param.getPackageName())) return;
        try {
            install(param.getClassLoader());
        } catch (Throwable failure) {
            synchronized (installLock) {
                if (reportedFailure) return;
                reportedFailure = true;
            }
            // 找不到目标类/方法时安静降级：小爱照常以官方人格运行。
            log(Log.ERROR, TAG, "install failed, 小爱将以官方人格运行: " + failure, failure);
        }
    }

    private void install(ClassLoader loader) throws Exception {
        synchronized (installLock) {
            if (installed) return;
        }

        hookPromptFiles(loader);
        try {
            hookProfile(loader);
        } catch (Throwable failure) {
            // 界面形象是可选能力：失败时不影响人格文本注入。
            log(Log.ERROR, TAG, "profile hook skipped: " + failure);
        }
        try {
            hookAgentName(loader);
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "name hook skipped: " + failure);
        }
        try {
            hookConfigAsset();
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "config hook skipped: " + failure);
        }
        try {
            hookBrandText();
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "text hook skipped: " + failure);
        }
        try {
            hookStyleContract(loader);
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "style contract skipped: " + failure);
        }
        try {
            hookToolDescription(loader);
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "tool trim skipped: " + failure);
        }

        synchronized (installLock) {
            installed = true;
        }
        log(Log.INFO, TAG, "installed");
    }

    // ---- 对话页标题：AgentDefinition.name（来自 config.json 的 name 字段） ----

    /**
     * 对话页顶部标题与开场语旁边的「超级小爱」是品牌字符串资源，直接渲染在 TextView 上，
     * 不经过 agent 数据。这里在 setText 时把它换成配置里的显示名。
     * 只匹配长度 4 且完全相等的串，避免影响其它文本。
     */
    private void hookBrandText() throws Exception {
        Class<?> textView = Class.forName("android.widget.TextView");
        Method setText = textView.getDeclaredMethod("setText", CharSequence.class);
        hook(setText).intercept(chain -> {
            String replacement = textReplacement(chain.getArg(0));
            return replacement == null ? chain.proceed() : chain.proceed(new Object[]{replacement});
        });

        // 有些地方用 setText(resId) / 带 BufferType 的重载
        Method setTextTyped = null;
        for (Method m : textView.getDeclaredMethods()) {
            if ("setText".equals(m.getName()) && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == CharSequence.class) {
                setTextTyped = m;
                break;
            }
        }
        if (setTextTyped != null) {
            final Method target = setTextTyped;
            hook(target).intercept(chain -> {
                String replacement = textReplacement(chain.getArg(0));
                return replacement == null
                        ? chain.proceed()
                        : chain.proceed(new Object[]{replacement, chain.getArg(1)});
            });
        }

        Class<?> resources = Class.forName("android.content.res.Resources");
        for (String name : new String[]{"getText", "getString"}) {
            for (Method m : resources.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != int.class) {
                    continue;
                }
                hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    String replacement = textReplacement(result);
                    return replacement == null ? result : replacement;
                });
            }
        }

        // 开场白是一组随机候选（字符串数组），必须单独处理 getStringArray
        for (Method m : resources.getDeclaredMethods()) {
            if (!"getStringArray".equals(m.getName()) || m.getParameterCount() != 1
                    || m.getParameterTypes()[0] != int.class) {
                continue;
            }
            hook(m).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof String[] array)) return result;
                java.util.Map<String, String> map = textMap();
                if (map.isEmpty()) return result;
                String[] copy = null;
                for (int i = 0; i < array.length; i++) {
                    String hit = map.get(array[i]);
                    if (hit != null && !hit.equals(array[i])) {
                        if (copy == null) copy = array.clone();
                        copy[i] = hit;
                    }
                }
                return copy == null ? result : copy;
            });
        }
        log(Log.INFO, TAG, "hooking TextView/Resources 文案替换");
    }

    /** 命中映射时返回替换文本，否则 null。 */
    private String textReplacement(Object candidate) {
        if (!(candidate instanceof CharSequence cs)) return null;
        java.util.Map<String, String> map = textMap();
        if (map.isEmpty()) return null;
        return map.get(cs.toString());
    }

    /**
     * 文案映射：「超级小爱 → 显示名」是内置的，另外解析用户在「文案替换」里写的
     * 「原文=新词」，用户写的覆盖内置。
     */
    private java.util.Map<String, String> textMap() {
        Bundle config = config();
        if (config == null || !config.getBoolean(KEY_ENABLED, false)
                || !config.getBoolean(KEY_PROFILE_ENABLED, true)) {
            return java.util.Collections.emptyMap();
        }
        String raw = config.getString(KEY_TEXT_MAP, "");
        String name = config.getString(KEY_PROFILE_NAME, DEFAULT_PROFILE_NAME);
        String stamp = (raw == null ? "" : raw) + "\u0000" + (name == null ? "" : name);
        if (stamp.equals(cachedTextMapStamp) && cachedTextMap != null) {
            return cachedTextMap;
        }

        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (name != null && !name.trim().isEmpty()) {
            map.put(BRAND_NAME, name);
        }
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#")) continue;
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String from = entry.substring(0, eq).trim();
                String to = entry.substring(eq + 1).trim();
                if (!from.isEmpty()) map.put(from, to);
            }
        }
        cachedTextMap = map;
        cachedTextMapStamp = stamp;
        if (map.size() > 1) log(Log.INFO, TAG, "文案映射 " + map.size() + " 条");
        return map;
    }

    /**
     * UI 读的是 AgentDefinition 的 name 字段（Kotlin 同类内直接读字段，不走 getter），
     * 所以真正的办法是在 App 读 assets 里的 config.json 时改掉 "name"。
     */
    private void hookConfigAsset() throws Exception {
        Method open = Class.forName("android.content.res.AssetManager")
                .getDeclaredMethod("open", String.class);
        hook(open).intercept(chain -> {
            Object pathArg = chain.getArg(0);
            if (!(pathArg instanceof String path)
                    || !path.endsWith("/config.json")
                    || !path.contains("/" + AGENT + "/")) {
                return chain.proceed();
            }
            Bundle config = config();
            if (config == null || !config.getBoolean(KEY_ENABLED, false)
                    || !config.getBoolean(KEY_PROFILE_ENABLED, true)) {
                return chain.proceed();
            }
            String name = config.getString(KEY_PROFILE_NAME, DEFAULT_PROFILE_NAME);
            if (name == null || name.trim().isEmpty()) return chain.proceed();

            Object result = chain.proceed();
            if (!(result instanceof java.io.InputStream stream)) return result;
            byte[] raw;
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
                raw = buffer.toByteArray();
            } catch (Throwable failure) {
                log(Log.ERROR, TAG, "config asset read failed: " + failure);
                return result;
            }
            try {
                String text = new String(raw, "UTF-8");
                String patched = text.replaceAll("\"name\"\\s*:\\s*\"[^\"]*\"",
                        java.util.regex.Matcher.quoteReplacement("\"name\": \"" + name + "\""));
                if (patched.equals(text)) return new java.io.ByteArrayInputStream(raw);
                if (!name.equals(lastLoggedName)) {
                    lastLoggedName = name;
                    log(Log.INFO, TAG, "config.json name -> " + name);
                }
                return new java.io.ByteArrayInputStream(patched.getBytes("UTF-8"));
            } catch (Throwable failure) {
                log(Log.ERROR, TAG, "config asset patch failed: " + failure);
                return new java.io.ByteArrayInputStream(raw);
            }
        });
        log(Log.INFO, TAG, "hooking AssetManager.open (config.json name)");
    }

    /**
     * 语气契约：追加到最终系统提示的末尾。
     *
     * 小爱的系统提示由 soul/prompt/security/bootstrap 等片段拼成，工具定义作为 function-calling
     * 的 tools 数组跟在后面，模型对结尾更敏感，所以这段短契约放在最后比写在开头更管用。
     */
    private void hookStyleContract(ClassLoader loader) throws Exception {
        // 这个方法在运行时的 agent 对象 ck.d0 上，不在 AgentDefinition 上
        Class<?> agent = Class.forName("ck.d0", false, loader);
        Method getPrompt = agent.getDeclaredMethod("getResolvedSystemPrompt");
        hook(getPrompt).intercept(chain -> {
            Object result = chain.proceed();
            Bundle config = config();
            if (config == null || !config.getBoolean(KEY_ENABLED, false)) return result;
            String contract = config.getString(KEY_STYLE_CONTRACT, DEFAULT_STYLE_CONTRACT);
            if (contract == null || contract.trim().isEmpty()) return result;
            if (!(result instanceof String base)) return result;
            if (!base.contains(STYLE_MARKER)) {
                systemPromptChars = base.length() + contract.length();
                scheduleStats();
            }
            if (!contractLogged) {
                contractLogged = true;
                log(Log.INFO, TAG, "语气契约已追加（" + contract.length() + " 字，系统提示 "
                        + base.length() + " -> " + (base.length() + contract.length()) + " 字）");
            }
            return base + "\n\n" + STYLE_MARKER + "\n" + contract.trim();
        });
        log(Log.INFO, TAG, "hooking ck.d0.getResolvedSystemPrompt (语气契约)");
    }

    /**
     * 工具描述瘦身：只改 description，参数 schema 一个字节都不动。
     *
     * 保留：含格式字面量（格式/必须/必填/枚举/经度/纬度/ISO）和适用边界的行
     *       （不适用/仅用于/不要用于）——这些一旦删掉模型就会瞎猜参数、用错工具。
     * 丢弃：示例、⚠️ 提醒、重复语气、长场景描写。
     */
    private void hookToolDescription(ClassLoader loader) throws Exception {
        Class<?> toolDefinition = Class.forName("com.aios.osbot.llm.model.ToolDefinition", false, loader);
        Method getDescription = toolDefinition.getDeclaredMethod("getDescription");
        Method getName = toolDefinition.getDeclaredMethod("getName");
        Method getParameters = null;
        try {
            getParameters = toolDefinition.getDeclaredMethod("getParameters");
        } catch (Throwable ignored) {
            // 拿不到参数也不影响瘦身
        }
        final Method parameters = getParameters;
        hook(getDescription).intercept(chain -> {
            Object result = chain.proceed();
            if (!(result instanceof String original) || original.isEmpty()) return result;
            Bundle config = config();
            if (config == null || !config.getBoolean(KEY_ENABLED, false)) return result;

            String name;
            try {
                name = String.valueOf(getName.invoke(chain.getThisObject()));
            } catch (Throwable ignored) {
                name = "";
            }

            String override = toolOverrides(config).get(name);
            if (override == null) override = builtinOverrides(config).get(name);
            String finalText = result instanceof String s ? s : original;
            if (override != null) {
                finalText = override;
            } else if (config.getBoolean(KEY_TOOL_DEEP, false)) {
                // 实验：按块打分 + 硬上限，砍示例和决策表，保格式与边界
                finalText = compressDeep(original);
            } else if (config.getBoolean(KEY_TOOL_TRIM, true)) {
                String compressed = compressToolDescription(original);
                if (!compressed.equals(original) && !toolTrimLogged) {
                    toolTrimLogged = true;
                    log(Log.INFO, TAG, "工具描述瘦身：例 " + name + " " + original.length()
                            + " -> " + compressed.length() + " 字");
                }
                finalText = compressed;
            }

            // 统计：工具数、描述字数（前/后）、参数 schema 字数
            toolCount.incrementAndGet();
            toolCharsBefore.addAndGet(original.length());
            toolCharsAfter.addAndGet(finalText.length());
            int paramsChars = 0;
            if (parameters != null) {
                try {
                    Object params = parameters.invoke(chain.getThisObject());
                    if (params != null) {
                        paramsChars = params.toString().length();
                        toolParamChars.addAndGet(paramsChars);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            // 逐条打印（只打前 12 条，避免刷屏）：工具名 描述 before -> after 参数 schema
            int index = toolCount.get();
            if (index <= 12) {
                log(
                        Log.INFO,
                        TAG,
                        "工具 " + index + " " + name + "：描述 " + original.length() + " -> "
                                + finalText.length() + " 字，参数 schema " + paramsChars + " 字");
            }
            scheduleStats();
            return finalText;
        });
        log(Log.INFO, TAG, "hooking ToolDefinition.getDescription (工具描述瘦身)");

        // 参数表里的 description 才是真正的体积大头（257 个工具的 schema 加起来几十万字）。
        // 只截断超长描述：name/type/required/enum/default 一律不动，避免工具调用直接失效。
        if (parameters != null) {
            hook(parameters).intercept(chain -> {
                Object result = chain.proceed();
                int calls = paramHookCalls.incrementAndGet();
                Bundle config = config();
                if (config == null || !config.getBoolean(KEY_ENABLED, false) || result == null) {
                    return result;
                }
                boolean deep = config.getBoolean(KEY_TOOL_DEEP, false);
                boolean trim = config.getBoolean(KEY_TOOL_TRIM, true);
                if (!deep && !trim) return result;

                String text = result.toString();
                // 无条件探针：前 5 次调用如实记录 JSON 的形态，用来定位"为什么没改动"
                if (calls <= 5) {
                    int descFields = countOf(text, "\"description\"");
                    int maxValue = maxDescriptionLength(text);
                    log(
                            Log.INFO,
                            TAG,
                            "参数探针 " + calls + "：len=" + text.length() + " desc字段="
                                    + descFields + " 最长描述=" + maxValue + " 头部="
                                    + sample(text));
                }
                if (text.length() < 400) return result;   // 参数表本来就短，别动
                String shrunk = trimParameterDescriptions(text, deep ? 120 : 240);
                if (shrunk.equals(text)) return result;

                Object rebuilt = buildJsonElement(shrunk);
                if (rebuilt == null) return result;        // 解析失败就原样返回，绝不冒险

                int n = paramTrimCount.incrementAndGet();
                paramCharsBefore.addAndGet(text.length());
                paramCharsAfter.addAndGet(shrunk.length());
                if (n <= 8) {
                    log(Log.INFO, TAG, "参数表 " + n + "：" + text.length() + " -> "
                            + shrunk.length() + " 字");
                }
                return rebuilt;
            });
            log(Log.INFO, TAG, "hooking ToolDefinition.getParameters (参数描述截断)");
        }
    }

    /**
     * 把 JSON 里超长的 "description" 值截断。
     * 只动引号内的描述文本，键名、类型、enum、required 都不会被碰到。
     */
    private static String trimParameterDescriptions(String json, int limit) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.length() <= limit) continue;
            String cut = value.substring(0, limit);
            // 别切在转义序列中间：末尾若是奇数个反斜杠，或者落在 unicode 转义里，就往回退
            while (!cut.isEmpty() && endsWithOddBackslashes(cut)) {
                cut = cut.substring(0, cut.length() - 1);
            }
            int unicode = cut.lastIndexOf("\\u");
            if (unicode >= 0 && cut.length() - unicode < 6) {
                cut = cut.substring(0, unicode);
            }
            out.append(json, last, matcher.start(1));
            out.append(cut).append("…");
            last = matcher.end(1);
            changed = true;
        }
        if (!changed) return json;
        out.append(json.substring(last));
        return out.toString();
    }

    private static boolean endsWithOddBackslashes(String text) {
        int count = 0;
        for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '\\'; i--) count++;
        return count % 2 == 1;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    /** 所有 "description" 值里最长的那个长度，用来判断到底有没有可截的对象。 */
    private static int maxDescriptionLength(String json) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, matcher.group(1).length());
        }
        return max;
    }

    /** 探针用：取前 200 字并压平换行，方便在 logcat 里看结构。 */
    private static String sample(String text) {
        String head = text.length() > 200 ? text.substring(0, 200) : text;
        return head.replace("\n", " ").replace("\r", " ");
    }

    /** 用 kotlinx.serialization 的 Json 把字符串重新解析成 JsonObject。 */
    private Object buildJsonElement(String text) {
        try {
            if (jsonInstance == null) {
                Class<?> jsonClass = Class.forName("kotlinx.serialization.json.Json");
                Object companion = jsonClass.getField("Companion").get(null);
                Object defaultJson = companion.getClass().getMethod("getDefault").invoke(companion);
                jsonParse = defaultJson.getClass().getMethod("parseToJsonElement", String.class);
                jsonInstance = defaultJson;
            }
            return jsonParse.invoke(jsonInstance, text);
        } catch (Throwable failure) {
            if (!jsonRebuildWarned) {
                jsonRebuildWarned = true;
                log(Log.WARN, TAG, "参数表重建失败，保留原样：" + failure);
            }
            return null;
        }
    }

    /** 等工具列表出齐后打一条汇总，避免每次都刷日志。 */
    private void scheduleStats() {
        if (statsScheduled) return;
        synchronized (this) {
            if (statsScheduled) return;
            statsScheduled = true;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
                return;
            }
            log(
                    Log.INFO,
                    TAG,
                    "提示词统计：系统提示 " + systemPromptChars + " 字；工具 " + toolCount.get()
                            + " 个，描述 " + toolCharsBefore.get() + " -> " + toolCharsAfter.get()
                            + " 字，参数 schema " + toolParamChars.get() + " 字；合计约 "
                            + (systemPromptChars + toolCharsAfter.get() + toolParamChars.get()) + " 字");
        }, "misoul-stats");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isNoiseLine(String line) {
        String l = line.toLowerCase(java.util.Locale.ROOT);
        return l.contains("示例")
                || l.contains("例如")
                || l.contains("比如")
                || l.contains("example")
                || l.contains("⚠")
                || l.contains("注意")
                || l.contains("温馨提示")
                || l.contains("建议")
                || l.startsWith("//");
    }

    private static boolean hasLiteralRequirement(String line) {
        return line.contains("格式")
                || line.contains("必须")
                || line.contains("必填")
                || line.contains("可选")
                || line.contains("枚举")
                || line.contains("取值")
                || line.contains("经度")
                || line.contains("纬度")
                || line.contains("iso")
                || line.contains("ISO")
                || line.contains("不适用")
                || line.contains("仅用于")
                || line.contains("不要用于")
                || line.contains("只用于")
                || line.contains("适用于");
    }

    private static String compressToolDescription(String text) {
        StringBuilder builder = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            boolean keep = !isNoiseLine(line) || hasLiteralRequirement(line);
            if (!keep) continue;
            builder.append(line).append('\n');
        }
        String out = builder.toString().trim();
        return out.isEmpty() ? text : out;
    }

    /**
     * 深度压缩（实验）：按空行/标题切块，给每块打分，超预算时丢低分块。
     *
     * 加分：含参数格式或适用边界的块（这两类是"字面要求"，删了模型会瞎猜/用错工具）
     * 减分：示例、⚠️ 提醒、超过 800 字的大段
     * 首块（概述）无条件保留；高价值块可截断进预算，低价值块直接丢。
     */
    private static String compressDeep(String text) {
        java.util.List<String> blocks = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
            } else if (trimmed.startsWith("#")) {
                if (current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                current.append(line).append('\n');
            } else {
                current.append(line).append('\n');
            }
        }
        if (current.length() > 0) blocks.add(current.toString());

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i);
            boolean first = i == 0;
            int score = scoreBlock(block);
            if (!first && score < 0) continue;

            int remaining = DEEP_BUDGET - out.length();
            if (remaining <= 0) break;
            if (block.length() <= remaining) {
                out.append(block).append('\n');
            } else if (first || score >= 100) {
                // 首块和"字面要求"块：截断保留，不整块丢
                out.append(block, 0, remaining).append('\n');
            }
        }
        String result = out.toString().trim();
        return result.isEmpty() ? text : result;
    }

    private static int scoreBlock(String block) {
        int score = 0;
        String lower = block.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(block, "格式", "必须", "必填", "可选", "枚举", "取值", "经度", "纬度")
                || lower.contains("iso")) {
            score += 100;
        }
        if (containsAny(block, "不适用", "仅用于", "不要用于", "只用于", "禁止", "别用", "不支持", "不负责")) {
            score += 100;
        }
        if (containsAny(block, "示例", "例如", "比如", "example", "⚠")) score -= 100;
        if (block.length() > 800) score -= 30;
        if (block.length() <= 200) score += 20;
        return score;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    /** 解析随包内置的精简描述表（`### 工具名` 起块，块内为描述正文）。 */
    private java.util.Map<String, String> builtinOverrides(Bundle config) {
        String raw = config.getString(KEY_TOOL_DEFAULTS, "");
        if (raw == null) raw = "";
        if (raw.equals(cachedBuiltinRaw) && cachedBuiltin != null) return cachedBuiltin;
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.startsWith("### ")) {
                if (current != null) map.put(current, body.toString().trim());
                current = line.substring(4).trim();
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null) map.put(current, body.toString().trim());
        map.values().removeIf(String::isEmpty);
        cachedBuiltin = map;
        cachedBuiltinRaw = raw;
        return map;
    }
    private java.util.Map<String, String> toolOverrides(Bundle config) {
        String raw = config.getString(KEY_TOOL_OVERRIDES, "");
        if (raw == null) raw = "";
        if (raw.equals(cachedOverridesRaw) && cachedOverrides != null) return cachedOverrides;
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            map.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        cachedOverrides = map;
        cachedOverridesRaw = raw;
        return map;
    }

    private void hookAgentName(ClassLoader loader) throws Exception {
        Class<?> definition = Class.forName("com.aios.osbot.agent.unified.AgentDefinition", false, loader);
        Method getId = definition.getDeclaredMethod("getId");
        Method getName = definition.getDeclaredMethod("getName");
        hook(getName).intercept(chain -> {
            Object result = chain.proceed();
            if (!(result instanceof String original)) return result;
            Bundle config = config();
            if (config == null || !config.getBoolean(KEY_ENABLED, false)
                    || !config.getBoolean(KEY_PROFILE_ENABLED, true)) {
                return result;
            }
            Object id;
            try {
                id = getId.invoke(chain.getThisObject());
            } catch (Throwable ignored) {
                return result;
            }
            if (!AGENT.equals(id)) return result;
            String name = config.getString(KEY_PROFILE_NAME, DEFAULT_PROFILE_NAME);
            if (name == null || name.trim().isEmpty()) return result;
            if (!name.equals(lastLoggedName)) {
                lastLoggedName = name;
                log(Log.INFO, TAG, "agent name -> " + name + " (was " + original + ")");
            }
            return name;
        });
        log(Log.INFO, TAG, "hooking AgentDefinition.getName");
    }

    // ---- 人格文本：soul.md / prompt.md ----

    private void hookPromptFiles(ClassLoader loader) throws Exception {
        Class<?> builder = Class.forName("rs.a", false, loader);
        Class<?> companion = null;
        Method readAsset = null;
        Method readFile = null;

        for (Class<?> inner : builder.getDeclaredClasses()) {
            Method asset = null;
            Method file = null;
            for (Method m : inner.getDeclaredMethods()) {
                if ("readAsset".equals(m.getName()) && m.getParameterCount() == 3
                        && m.getParameterTypes()[1] == String.class) {
                    asset = m;
                } else if ("readFile".equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == File.class) {
                    file = m;
                }
            }
            if (asset != null && file != null) {
                companion = inner;
                readAsset = asset;
                readFile = file;
                break;
            }
        }
        if (companion == null) {
            throw new IllegalStateException("PromptBuilder companion not found in rs.a");
        }

        log(Log.INFO, TAG, "hooking " + companion.getName());
        hook(readAsset).intercept(this::onReadAsset);
        hook(readFile).intercept(this::onReadFile);
    }

    private Object onReadAsset(XposedInterface.Chain chain) throws Throwable {
        Object pathArg = chain.getArg(1);
        if (!(pathArg instanceof String path) || !path.startsWith(AGENT_PREFIX)) {
            return chain.proceed();
        }
        Object prefixArg = chain.getArg(2);
        String prefix = prefixArg instanceof String s ? s : null;

        if (path.equals(AGENT_PREFIX + SOUL)) {
            String soul = soul();
            if (soul != null) {
                log(Log.INFO, TAG, "soul.md from assets -> " + soul.length() + " chars override");
                return withPrefix(prefix, soul);
            }
        } else if (path.equals(AGENT_PREFIX + RULES)) {
            String custom = customRules();
            if (custom != null) {
                log(Log.INFO, TAG, "tool_selection_rules.md -> " + custom.length() + " chars override");
                return withPrefix(prefix, custom);
            }
        } else if (path.equals(AGENT_PREFIX + PROMPT)) {
            Object result = chain.proceed();
            if (!(result instanceof String text)) return result;
            if (!fixPrompt()) return result;
            String fixed = fixIdentity(text);
            log(Log.INFO, TAG, "prompt.md from assets -> " + (fixed == null ? "unchanged" : "identity patched"));
            return fixed == null ? result : withPrefix(prefix, fixed);
        }
        return chain.proceed();
    }

    private Object onReadFile(XposedInterface.Chain chain) throws Throwable {
        Object fileArg = chain.getArg(0);
        if (!(fileArg instanceof File file)) return chain.proceed();
        String name = file.getName();
        if (!SOUL.equals(name) && !PROMPT.equals(name) && !RULES.equals(name)) return chain.proceed();
        if (!file.getPath().contains("/" + AGENT + "/")) return chain.proceed();

        Object prefixArg = chain.getArg(1);
        String prefix = prefixArg instanceof String s ? s : null;

        if (RULES.equals(name)) {
            String custom = customRules();
            if (custom != null) {
                log(Log.INFO, TAG, "tool_selection_rules.md (file) -> " + custom.length() + " chars override");
                return withPrefix(prefix, custom);
            }
        } else if (SOUL.equals(name)) {
            String soul = soul();
            if (soul != null) {
                log(Log.INFO, TAG, "soul.md from file -> " + soul.length() + " chars override");
                return withPrefix(prefix, soul);
            }
        } else if (fixPrompt()) {
            Object result = chain.proceed();
            if (result instanceof String text) {
                String fixed = fixIdentity(text);
                if (fixed != null) return withPrefix(prefix, fixed);
            }
            return result;
        }
        return chain.proceed();
    }

    private static String withPrefix(String prefix, String text) {
        return prefix == null ? text : prefix + "\n" + text;
    }

    // ---- 界面形象：profile.json -> AgentProfile ----

    private void hookProfile(ClassLoader loader) throws Exception {
        Class<?> store = Class.forName("ck.o0", false, loader);
        Method fromAsset = store.getDeclaredMethod("l", String.class);
        Method fromFile = store.getDeclaredMethod("m", File.class);

        hook(fromAsset).intercept(chain -> {
            Object result = chain.proceed();
            if (result == null || !AGENT.equals(chain.getArg(0))) return result;
            return patchProfile(result);
        });
        hook(fromFile).intercept(chain -> {
            Object result = chain.proceed();
            Object arg = chain.getArg(0);
            if (result == null || !(arg instanceof File file)
                    || !file.getPath().contains("/" + AGENT + "/")) {
                return result;
            }
            return patchProfile(result);
        });
        log(Log.INFO, TAG, "hooking ck.o0 profile loader");
    }

    private Object patchProfile(Object profile) {
        Bundle config = config();
        if (config == null || !config.getBoolean(KEY_ENABLED, false)
                || !config.getBoolean(KEY_PROFILE_ENABLED, true)) {
            return profile;
        }
        try {
            Class<?> type = profile.getClass();
            Constructor<?> ctor = profileConstructor;
            if (ctor == null) {
                ctor = type.getDeclaredConstructor(String.class, String.class, String.class,
                        String.class, List.class, String.class, String.class, List.class);
                ctor.setAccessible(true);
                profileConstructor = ctor;
            }
            String displayName = config.getString(KEY_PROFILE_NAME, DEFAULT_PROFILE_NAME);
            String signature = config.getString(KEY_PROFILE_SIGNATURE, DEFAULT_PROFILE_SIGNATURE);
            String welcome = config.getString(KEY_PROFILE_WELCOME, DEFAULT_PROFILE_WELCOME);

            Object patched = ctor.newInstance(
                    isBlank(displayName) ? get(profile, "getDisplayName") : displayName,
                    get(profile, "getAvatar"),
                    isBlank(signature) ? get(profile, "getSignature") : signature,
                    get(profile, "getPersonality"),
                    get(profile, "getExpertise"),
                    isBlank(welcome) ? get(profile, "getWelcomeHi") : welcome,
                    get(profile, "getWelcomeHiDescription"),
                    get(profile, "getWelcomeOptions"));
            log(Log.INFO, TAG, "profile -> display=" + (isBlank(displayName) ? "(keep)" : displayName));
            return patched;
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "profile patch failed: " + failure);
            return profile;
        }
    }

    private static Object get(Object target, String getter) {
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    // ---- 配置读取 ----

    private Context context() {
        Context cached = appContext;
        if (cached != null) return cached;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            if (application instanceof Context ctx) {
                appContext = ctx;
                return ctx;
            }
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "context unavailable: " + failure);
        }
        return null;
    }

    private Bundle config() {
        long now = SystemClock.elapsedRealtime();
        Bundle cached = cachedConfig;
        if (cached != null && now - cachedAt < 1500) return cached;

        Context ctx = context();
        if (ctx == null) return cached;
        try {
            Bundle fresh = ctx.getContentResolver().call(
                    Uri.parse("content://" + PersonaProvider.AUTHORITY), PersonaProvider.METHOD, null, null);
            if (fresh != null) {
                cachedConfig = fresh;
                cachedAt = now;
                return fresh;
            }
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "provider call failed: " + failure);
        }
        return cached;
    }

    private boolean enabled() {
        Bundle config = config();
        return config != null && config.getBoolean(KEY_ENABLED, false);
    }

    private boolean fixPrompt() {
        Bundle config = config();
        return config != null && config.getBoolean(KEY_ENABLED, false)
                && config.getBoolean(KEY_FIX_PROMPT, true);
    }

    /** 用户自定义的选工具规则；空则返回 null（继续用官方 24k 那份）。 */
    private String customRules() {
        Bundle config = config();
        if (config == null || !config.getBoolean(KEY_ENABLED, false)) return null;
        String raw = config.getString(KEY_RULES_TEXT, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        return raw;
    }

    private String soul() {
        Bundle config = config();
        if (config == null || !config.getBoolean(KEY_ENABLED, false)) return null;
        String text = config.getString(KEY_SOUL, "");
        return text == null || text.trim().isEmpty() ? null : text;
    }

    private String displayName() {
        Bundle config = config();
        String name = config == null ? null : config.getString(KEY_NAME, null);
        return name == null || name.trim().isEmpty() ? DEFAULT_NAME : name.trim();
    }

    /** 把官方身份句换掉；若官方文案变了找不到该句，则末尾追加一段覆盖声明。 */
    private String fixIdentity(String original) {
        String name = displayName();
        if (original.contains(OFFICIAL_CLAIM)) {
            return original.replace(OFFICIAL_CLAIM, "并表明自己叫" + name);
        }
        if (original.contains(name)) return null;
        return original + "\n\n## 名字与自称（覆盖上文同名设定）\n\n- 你的名字是" + name
                + "。被误叫成「超级小爱」「小爱」时不要顺着承认，也不必争辩，一句话带过即可。\n";
    }
}
