package com.xiuxian.script;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 日志浮窗的显示设置：勾选显示哪些内容，以及字号、边框、透明度。
 * 全部存在本机，改完立即生效。
 */
public class FloatConfig {

    private static final String SP = "float_cfg";

    /** 日志内容开关（顺序与设置面板一致） */
    public boolean showLoop = false;        // 循环次数
    public boolean showLoopGap = false;     // 循环的间隔时间
    public boolean showCurStep = false;     // 当前步骤
    public boolean showCurNote = true;      // 当前步骤的备注
    public boolean showCurWait = true;      // 当前步骤的等待时间
    public boolean showCurTimes = false;    // 当前步骤内的执行次数和间隔时间
    public boolean showNextStep = true;     // 下个步骤
    public boolean showNextNote = true;     // 下个步骤的备注

    /** 字号（sp），默认 9 */
    public int fontSize = 9;
    /** 边框粗细/圆角（dp），默认 6 */
    public int borderSize = 6;
    /** 透明度 0~100，默认 10 表示不透明度很低（很透明） */
    public int alpha = 10;

    public static FloatConfig load(Context c) {
        FloatConfig f = new FloatConfig();
        if (c == null) {
            return f;
        }
        try {
            SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
            f.showLoop = sp.getBoolean("showLoop", f.showLoop);
            f.showLoopGap = sp.getBoolean("showLoopGap", f.showLoopGap);
            f.showCurStep = sp.getBoolean("showCurStep", f.showCurStep);
            f.showCurNote = sp.getBoolean("showCurNote", f.showCurNote);
            f.showCurWait = sp.getBoolean("showCurWait", f.showCurWait);
            f.showCurTimes = sp.getBoolean("showCurTimes", f.showCurTimes);
            f.showNextStep = sp.getBoolean("showNextStep", f.showNextStep);
            f.showNextNote = sp.getBoolean("showNextNote", f.showNextNote);
            f.fontSize = sp.getInt("fontSize", f.fontSize);
            f.borderSize = sp.getInt("borderSize", f.borderSize);
            f.alpha = sp.getInt("alpha", f.alpha);
        } catch (Throwable ignored) {
        }
        return f;
    }

    public void save(Context c) {
        if (c == null) {
            return;
        }
        try {
            SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            e.putBoolean("showLoop", showLoop);
            e.putBoolean("showLoopGap", showLoopGap);
            e.putBoolean("showCurStep", showCurStep);
            e.putBoolean("showCurNote", showCurNote);
            e.putBoolean("showCurWait", showCurWait);
            e.putBoolean("showCurTimes", showCurTimes);
            e.putBoolean("showNextStep", showNextStep);
            e.putBoolean("showNextNote", showNextNote);
            e.putInt("fontSize", fontSize);
            e.putInt("borderSize", borderSize);
            e.putInt("alpha", alpha);
            e.commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 背景不透明度：面板上的「浮窗透明度」数值越大越不透明。
     * 面板取 0~20，映射到 0x00~0xFF，默认 10 -> 约 80% 不透明。
     */
    public int bgAlpha() {
        int a = alpha;
        if (a < 0) {
            a = 0;
        }
        if (a > 20) {
            a = 20;
        }
        int v = (int) (a / 20f * 0xFF);
        if (v < 0x33) {
            v = 0x33;
        }
        return v;
    }
}
