package com.siweina.misoul;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 桌面图标即 manifest 里的 .LauncherAlias，隐藏与否只看这个组件的 enabled 状态，
 * 不再另存一份开关，避免两份状态不一致。
 */
public final class LauncherIcon {

    /** 拨号密令：*#*#7685#*#*（S=7 O=6 U=8 L=5）。必须与 manifest 里 receiver 的 host 一致。 */
    public static final String SECRET_CODE = "7685";

    private LauncherIcon() {
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".LauncherAlias");
    }

    public static boolean isHidden(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(component(context))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    public static void setHidden(Context context, boolean hidden) {
        // 用 ENABLED 而不是 DEFAULT：HyperOS 桌面在 DEFAULT 下不会把图标放回来。
        // DONT_KILL_APP：否则切换开关会把当前页面一起杀掉。
        context.getPackageManager().setComponentEnabledSetting(
                component(context),
                hidden
                        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }
}
