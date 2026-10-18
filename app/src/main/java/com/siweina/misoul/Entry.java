package com.siweina.misoul;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 拨号密令的兜底入口。
 *
 * 从广播里直接 startActivity 会被系统的后台启动限制（BAL）拦掉——日志里是
 * "Background activity launch blocked ... callingUidHasNonAppVisibleWindow: false"，
 * 只有「持有悬浮窗权限且当前有窗口」这类进程才豁免，普通 App 拿不到。
 * 所以同时发一条通知：通知的 PendingIntent 由系统拉起 Activity，不受 BAL 限制。
 */
final class Entry {

    private static final String CHANNEL = "entry";
    private static final int NOTIFICATION_ID = 0x5EED;

    private Entry() {
    }

    static void open(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // 被 BAL 拦下时不会抛异常，这里只是兜底
        }
        notifyShortcut(context);
    }

    private static void notifyShortcut(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "入口", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("桌面图标隐藏时的打开方式");
            manager.createNotificationChannel(channel);
        }

        PendingIntent pending = PendingIntent.getActivity(
                context,
                0,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        builder.setContentTitle("MISoul 设置")
                .setContentText("点这里打开（桌面图标已隐藏）")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pending)
                .setAutoCancel(true);

        try {
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // 没给通知权限时静默失败
        }
    }
}
