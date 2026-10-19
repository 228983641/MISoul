package com.siweina.misoul;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 把设置页的配置交给被 hook 的小爱进程。
 *
 * <p>LSPosed 的 getRemotePreferences 在本机读不到值，因此用 ContentResolver.call。
 */
public final class PersonaProvider extends ContentProvider {

    public static final String AUTHORITY = "com.siweina.misoul.config";
    public static final String METHOD = "persona";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD.equals(method)) return null;
        SharedPreferences prefs = getContext().getSharedPreferences(PersonaHook.PREFS, 0);
        Bundle bundle = new Bundle();
        bundle.putBoolean(PersonaHook.KEY_ENABLED, prefs.getBoolean(PersonaHook.KEY_ENABLED, false));
        bundle.putBoolean(PersonaHook.KEY_FIX_PROMPT, prefs.getBoolean(PersonaHook.KEY_FIX_PROMPT, true));
        bundle.putString(PersonaHook.KEY_NAME, prefs.getString(PersonaHook.KEY_NAME, PersonaHook.DEFAULT_NAME));
        bundle.putString(PersonaHook.KEY_SOUL, prefs.getString(PersonaHook.KEY_SOUL, ""));
        bundle.putBoolean(PersonaHook.KEY_PROFILE_ENABLED,
                prefs.getBoolean(PersonaHook.KEY_PROFILE_ENABLED, true));
        bundle.putString(PersonaHook.KEY_PROFILE_NAME,
                prefs.getString(PersonaHook.KEY_PROFILE_NAME, PersonaHook.DEFAULT_PROFILE_NAME));
        bundle.putString(PersonaHook.KEY_PROFILE_SIGNATURE,
                prefs.getString(PersonaHook.KEY_PROFILE_SIGNATURE, PersonaHook.DEFAULT_PROFILE_SIGNATURE));
        bundle.putString(PersonaHook.KEY_PROFILE_WELCOME,
                prefs.getString(PersonaHook.KEY_PROFILE_WELCOME, PersonaHook.DEFAULT_PROFILE_WELCOME));
        bundle.putString(PersonaHook.KEY_TEXT_MAP, prefs.getString(PersonaHook.KEY_TEXT_MAP, ""));
        bundle.putString(PersonaHook.KEY_STYLE_CONTRACT,
                prefs.getString(PersonaHook.KEY_STYLE_CONTRACT, PersonaHook.DEFAULT_STYLE_CONTRACT));
        bundle.putBoolean(PersonaHook.KEY_TOOL_TRIM,
                prefs.getBoolean(PersonaHook.KEY_TOOL_TRIM, true));
        bundle.putString(PersonaHook.KEY_RULES_TEXT,
                prefs.getString(PersonaHook.KEY_RULES_TEXT, ""));
        bundle.putBoolean(PersonaHook.KEY_TOOL_DEEP,
                prefs.getBoolean(PersonaHook.KEY_TOOL_DEEP, false));
        bundle.putString(PersonaHook.KEY_TOOL_OVERRIDES,
                prefs.getString(PersonaHook.KEY_TOOL_OVERRIDES, ""));
        return bundle;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
