package com.xiuxian.script;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：模拟器开机后自动把无障碍和悬浮窗权限补回去。
 *
 * 雷电多开每次关机再开，无障碍服务有可能被系统从启用列表里摘掉，
 * 表现就是「每次都要重新打开一次」。这里在开机广播里再自愈一遍，
 * 做到开机即就绪，用户完全不用管。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? "" : intent.getAction();
            boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                    || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
            if (!boot) {
                return;
            }
            Logger.init(context);
            Logger.i("开机广播收到，开始恢复权限…");
            boolean ok = RootGrant.ensureAll(context);
            Logger.i("开机自愈" + (ok ? "成功：无障碍与悬浮窗均已就绪" : "未完全成功，进入 App 可再次尝试"));
        } catch (Throwable t) {
            // 广播里绝不能抛异常，否则会被系统判为崩溃
        }
    }
}
