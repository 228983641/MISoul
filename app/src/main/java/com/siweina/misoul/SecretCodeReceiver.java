package com.siweina.misoul;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 桌面图标被隐藏后，拨号 *#*#7685#*#* 从这里回到设置页。
 * manifest 里已经按 action + 密令过滤，能到这里的都是自己人。
 */
public final class SecretCodeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Entry.open(context);
    }
}
