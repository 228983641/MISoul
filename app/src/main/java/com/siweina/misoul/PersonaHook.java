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
    private volatile java.util.Map<String, String> cachedOverrides;
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
            if (base.contains(STYLE_MARKER)) return result;
            if (!contractLogged) {
                contractLogged = true;
                log(Log.INFO, TAG, "语气契约已追加（" + contract.length() + " 字）");
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
            if (override != null) return override;
            if (!config.getBoolean(KEY_TOOL_TRIM, true)) return result;

            String compressed = compressToolDescription(original);
            if (!compressed.equals(original) && !toolTrimLogged) {
                toolTrimLogged = true;
                log(Log.INFO, TAG, "工具描述瘦身：例 " + name + " " + original.length()
                        + " -> " + compressed.length() + " 字");
            }
            return compressed;
        });
        log(Log.INFO, TAG, "hooking ToolDefinition.getDescription (工具描述瘦身)");
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
        if (!SOUL.equals(name) && !PROMPT.equals(name)) return chain.proceed();
        if (!file.getPath().contains("/" + AGENT + "/")) return chain.proceed();

        Object prefixArg = chain.getArg(1);
        String prefix = prefixArg instanceof String s ? s : null;

        if (SOUL.equals(name)) {
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
