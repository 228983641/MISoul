package com.siweina.misoul;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 更新交给系统安装器：只把 APK 以 content:// 交给设备上“打开 APK 的默认应用”，
 * 不自己开 PackageInstaller session（MIUI 上那条路会静默 reject）。
 */
public final class UpdateInstaller {

    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String DIR = "updates";

    private UpdateInstaller() {
    }

    /** 把选中的 APK 复制到 cache/updates/，返回文件；失败返回 null。 */
    public static File stage(Context context, Uri source) {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        File target = new File(dir, "app-update.apk");
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return null;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return target;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** 交给系统安装器；没有应用能处理时返回 false。 */
    public static boolean handOff(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException missing) {
            return false;
        }
    }
}
