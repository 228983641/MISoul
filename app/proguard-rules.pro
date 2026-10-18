# LSPosed 通过 java_init.list 里的类名反射加载模块入口，R8 不能改名/删掉。
-keep class com.siweina.misoul.PersonaHook { *; }
-keep class com.siweina.misoul.PersonaProvider { *; }
-keep class com.siweina.misoul.MainActivity { *; }

# 运行时由 LSPosed 提供，compileOnly 不进 APK。
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# Compose / Miuix 反射与序列化相关
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn org.jetbrains.annotations.**
