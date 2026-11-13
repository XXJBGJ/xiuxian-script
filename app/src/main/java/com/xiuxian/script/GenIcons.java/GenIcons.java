package com.xiuxian.script;

/**
 * 兜底占位：正常构建时 tools/gen_icons.py 会用真实内容覆盖本文件
 * （从每个游戏 APK 的清单里读启动图标）。即使那一步失败，这里也能编译通过，
 * 只是回退到 BundledGameIcons 里内置的图标。
 */
public final class GenIcons {

    private GenIcons() {
    }

    public static String get(String key) {
        return null;
    }
}
