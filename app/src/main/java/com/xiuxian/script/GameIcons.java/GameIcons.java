package com.xiuxian.script;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.util.HashMap;

/**
 * 游戏图标统一出口。
 *
 * 旧做法：BundledGameIcons 里按渠道名塞了一张预设图，跟游戏真正的启动图标没关系，
 * 所以安装包库显示的图和大家手机桌面上的图标对不上。
 *
 * 现在：构建时用 Android 官方 aapt 读每个游戏 APK 清单里登记的启动图标，
 * 生成 GenIcons，这里优先取它 —— 也就是安装后桌面上显示的那张图。
 * 万一某个包取不到，再退回原来的内置图，保证界面上不出现空白。
 */
public final class GameIcons {

    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

    private GameIcons() {
    }

    /** 按 key 取图标；key 为空或都取不到时返回 null，由调用方决定怎么兜底 */
    public static Bitmap get(String key) {
        if (key == null) {
            return null;
        }
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        Bitmap bm = fromGen(key);
        if (bm == null) {
            bm = BundledGameIcons.get(key);
        }
        if (bm != null) {
            CACHE.put(key, bm);
        }
        return bm;
    }

    private static Bitmap fromGen(String key) {
        String b64 = GenIcons.get(key);
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            return null;
        }
    }
}
