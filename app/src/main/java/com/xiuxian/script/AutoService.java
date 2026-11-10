package com.xiuxian.script;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务：脚本引擎本体。
 * 第一版能力：固定坐标点击、滑动、长按、返回键、桌面键、最近任务键、按应用名启动 App、按 JSON 配置跑步骤。
 * 所有坐标都以屏幕宽高的比例（0~1）存储，360x600 换到其它分辨率不用改配置。
 */
public class AutoService extends AccessibilityService {

    public static final String CONFIG_FILE = "config.json";

    private static volatile AutoService instance;

    private volatile boolean running = false;
    private Thread worker;
    private JSONObject currentConfig;

    /** 当前正在跑的账号（账号循环用） */
    private String curUser = "";
    private String curPass = "";
    private int curAccIndex = 0;
    private int accTotal = 0;

    /** 默认流程：拉起天途 -> 等 4 秒 -> 点屏幕正中 -> 等 1 秒 */
    public static JSONObject defaultConfig() {
        JSONObject o = new JSONObject();
        try {
            o.put("appName", "天途");
            o.put("packageName", "");
            o.put("loop", 1);
            JSONArray steps = new JSONArray();
            steps.put(new JSONObject().put("action", "launch"));
            steps.put(new JSONObject().put("action", "wait").put("ms", 4000));
            steps.put(new JSONObject().put("action", "click").put("x", 0.5).put("y", 0.5));
            steps.put(new JSONObject().put("action", "wait").put("ms", 1000));
            o.put("steps", steps);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static String defaultConfigText() {
        try {
            return defaultConfig().toString(2);
        } catch (Throwable t) {
            return defaultConfig().toString();
        }
    }

    public static AutoService get() {
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    public boolean isRunning() {
        return running;
    }

    // ================= 生命周期 =================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Logger.init(this);
        Logger.i("无障碍服务已连接，可以执行点击 / 滑动 / 长按");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 第一版不处理事件；第三版的中断规则轮询会挂在这里
    }

    @Override
    public void onInterrupt() {
        Logger.w("无障碍服务被系统中断");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        running = false;
        Logger.w("无障碍服务已关闭");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        running = false;
        super.onDestroy();
    }

    // ================= 屏幕尺寸 =================

    /** 返回 {宽, 高} 的真实像素 */
    @SuppressWarnings("deprecation")
    public int[] screenSize() {
        int[] size = new int[]{360, 600};
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return size;
            }
            Display d = wm.getDefaultDisplay();
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            size[0] = m.widthPixels;
            size[1] = m.heightPixels;
        } catch (Throwable t) {
            Logger.e("读取屏幕尺寸失败: " + t);
        }
        return size;
    }

    // ================= 手势 =================

    private boolean dispatch(Path path, long durationMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs)));
        boolean ok;
        try {
            ok = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    latch.countDown();
                }
            }, null);
        } catch (Throwable t) {
            Logger.e("手势派发异常: " + t);
            return false;
        }
        if (!ok) {
            Logger.e("手势派发被拒绝（无障碍服务可能未启用）");
            return false;
        }
        try {
            latch.await(durationMs + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return true;
    }

    public boolean clickPx(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(p, 60);
    }

    /** 比例坐标点击，x / y 为 0~1 */
    public boolean clickRatio(float fx, float fy) {
        int[] s = screenSize();
        int x = (int) (fx * s[0]);
        int y = (int) (fy * s[1]);
        Logger.i("点击 比例(" + fx + "," + fy + ") -> 像素(" + x + "," + y + ")");
        return clickPx(x, y);
    }

    public boolean longPressPx(int x, int y, long durationMs) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(p, Math.max(500, durationMs));
    }

    public boolean longPressRatio(float fx, float fy, long durationMs) {
        int[] s = screenSize();
        int x = (int) (fx * s[0]);
        int y = (int) (fy * s[1]);
        Logger.i("长按 " + Math.max(500, durationMs) + "ms @ 像素(" + x + "," + y + ")");
        return longPressPx(x, y, durationMs);
    }

    public boolean swipePx(int x1, int y1, int x2, int y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        Logger.i("滑动 (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ") " + durationMs + "ms");
        return dispatch(p, Math.max(100, durationMs));
    }

    public boolean swipeRatio(float fx1, float fy1, float fx2, float fy2, long durationMs) {
        int[] s = screenSize();
        return swipePx((int) (fx1 * s[0]), (int) (fy1 * s[1]),
                (int) (fx2 * s[0]), (int) (fy2 * s[1]), durationMs);
    }

    public boolean back() {
        boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
        Logger.i("返回键 " + (ok ? "成功" : "失败"));
        return ok;
    }

    public boolean home() {
        boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
        Logger.i("桌面键 " + (ok ? "成功" : "失败"));
        return ok;
    }

    public boolean recents() {
        boolean ok = performGlobalAction(GLOBAL_ACTION_RECENTS);
        Logger.i("最近任务 / 菜单键 " + (ok ? "成功" : "失败"));
        return ok;
    }

    // ================= 配置读写 =================

    public static File configFile(Context ctx) {
        return new File(ctx.getFilesDir(), CONFIG_FILE);
    }

    /** 读配置；文件不存在或损坏就写入默认配置 */
    public static JSONObject loadConfig(Context ctx) {
        File f = configFile(ctx);
        try {
            if (!f.exists()) {
                writeText(f, defaultConfigText());
                Logger.i("已生成默认配置: " + f.getAbsolutePath());
            }
            JSONObject o = new JSONObject(readText(f));
            Logger.i("配置读取成功");
            return o;
        } catch (Throwable t) {
            Logger.e("配置损坏，已恢复默认: " + t);
            try {
                writeText(f, defaultConfigText());
            } catch (Throwable ignored) {
            }
            return defaultConfig();
        }
    }

    public static boolean saveConfig(Context ctx, String json) {
        try {
            new JSONObject(json);
            writeText(configFile(ctx), json);
            Logger.i("配置已保存");
            return true;
        } catch (Throwable t) {
            Logger.e("配置保存失败（不是合法 JSON）: " + t);
            return false;
        }
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    // ================= 脚本执行 =================

    public void startScript() {
        if (running) {
            Logger.w("脚本已经在运行中");
            return;
        }
        currentConfig = loadConfig(this);
        running = true;
        int[] s = screenSize();
        Logger.i("======== 脚本开始 ========");
        Logger.i("屏幕尺寸: " + s[0] + " x " + s[1]
                + "  /  Android " + Build.VERSION.RELEASE
                + "  /  SDK " + Build.VERSION.SDK_INT);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runFlow(currentConfig);
            }
        }, "xiuxian-worker");
        worker.start();
    }

    public void stopScript() {
        if (!running) {
            Logger.w("脚本当前没有运行");
            return;
        }
        running = false;
        Logger.w("收到停止指令");
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * 跑流程。
     *
     * 账号循环：先把该游戏勾选的账号取出来，外层遍历账号、内层遍历步骤。
     * 一个账号把自己的日常 + 仙榜全部步骤走完，才切到下一个账号；
     * 没勾选的账号根本不会进这个列表，等于直接跳过。
     * 配置里写 "perAccount": false 可以关掉账号循环，退回单轮模式。
     */
    private void runFlow(JSONObject cfg) {
        try {
            int loop = Math.max(1, cfg.optInt("loop", 1));
            JSONArray steps = cfg.optJSONArray("steps");
            if (steps == null) {
                steps = new JSONArray();
            }

            java.util.List<Accounts.Acc> accs = new java.util.ArrayList<Accounts.Acc>();
            try {
                accs = Accounts.checkedList(this, Games.selectedPkg(this));
            } catch (Throwable t) {
                Logger.e("读取账号失败: " + t);
            }
            boolean perAcc = cfg.optBoolean("perAccount", true);

            if (accs.isEmpty() || !perAcc) {
                if (accs.isEmpty()) {
                    Logger.w("没有勾选任何账号，本次不切换账号，只按流程跑一轮");
                }
                Logger.i("共 " + steps.length() + " 个步骤，循环 " + loop + " 轮");
                for (int i = 0; i < loop && running; i++) {
                    Logger.i("--- 第 " + (i + 1) + "/" + loop + " 轮 ---");
                    for (int j = 0; j < steps.length() && running; j++) {
                        JSONObject s = steps.optJSONObject(j);
                        if (s == null) {
                            continue;
                        }
                        execStep(cfg, s, j + 1);
                    }
                }
                return;
            }

            accTotal = accs.size();
            Logger.i("本次共 " + accTotal + " 个勾选账号，逐个跑完再切换");
            for (int k = 0; k < accs.size() && running; k++) {
                Accounts.Acc a = accs.get(k);
                curAccIndex = k + 1;
                curUser = a.user;
                curPass = a.pass;
                Logger.i("======== 账号 " + curAccIndex + "/" + accTotal + ": " + a.user + " ========");
                for (int i = 0; i < loop && running; i++) {
                    if (loop > 1) {
                        Logger.i("--- 账号内第 " + (i + 1) + "/" + loop + " 轮 ---");
                    }
                    for (int j = 0; j < steps.length() && running; j++) {
                        JSONObject s = steps.optJSONObject(j);
                        if (s == null) {
                            continue;
                        }
                        execStep(cfg, s, j + 1);
                    }
                }
                Logger.i("账号 " + a.user + " 的流程已走完，准备切下一个账号");
                sleep(800);
            }
            Logger.i("全部 " + accTotal + " 个勾选账号已处理完毕");
        } catch (Throwable t) {
            Logger.e("脚本异常中止: " + t);
        } finally {
            running = false;
            Logger.i("======== 脚本结束 ========");
        }
    }

    /**
     * 把 {account} / {password} / {index} / {total} 换成当前账号的值。
     * 步骤里的输入文本、日志文案都可以用这些占位符。
     */
    private String resolve(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("{account}", curUser)
                .replace("{user}", curUser)
                .replace("{password}", curPass)
                .replace("{pass}", curPass)
                .replace("{index}", String.valueOf(curAccIndex))
                .replace("{total}", String.valueOf(accTotal));
    }

    /** 深度遍历找第一个可编辑的输入框 */
    private android.view.accessibility.AccessibilityNodeInfo findEditable(
            android.view.accessibility.AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 30) {
            return null;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText")) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) {
                continue;
            }
            android.view.accessibility.AccessibilityNodeInfo r = findEditable(c, depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * 往当前输入框里填文字。
     * 先试无障碍的「设置文本」，不行再退回剪贴板 + 粘贴，
     * 两条路都不依赖焦点位置，适合游戏登录框这种自绘界面之外的情况。
     */
    public boolean inputText(String text) {
        if (text == null) {
            text = "";
        }
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Logger.w("没有活动窗口，无法输入");
                return false;
            }
            android.view.accessibility.AccessibilityNodeInfo node = root.findFocus(
                    android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) {
                node = findEditable(root, 0);
            }
            if (node == null) {
                Logger.w("没有找到输入框，请先点一下输入框");
                return false;
            }
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text);
            boolean ok = node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (ok) {
                Logger.i("已填入文本（长度 " + text.length() + "）");
                return true;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Logger.w("剪贴板不可用，输入失败");
                return false;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("xiuxian", text));
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
            boolean p = node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE);
            Logger.i("设置文本被拒，改用粘贴：" + (p ? "成功" : "失败"));
            return p;
        } catch (Throwable t) {
            Logger.e("输入异常: " + t);
            return false;
        }
    }

    private void execStep(JSONObject cfg, JSONObject s, int index) {
        String action = s.optString("action", "").trim();
        Logger.i("步骤 " + index + ": " + action);
        switch (action) {
            case "launch": {
                // 优先用步骤 / 配置里写死的包名；没写就用「当前选中的游戏」的包名。
                // 全程只按包名精确启动，避免换皮游戏互相串台。
                String pkg = s.optString("packageName", cfg.optString("packageName", ""));
                if (pkg == null) {
                    pkg = "";
                }
                pkg = pkg.trim();
                boolean ok;
                if (!pkg.isEmpty()) {
                    Logger.i("按配置包名启动: " + pkg);
                    ok = AppFinder.launchByPackage(this, pkg);
                } else {
                    String selName = Games.selectedName(this);
                    String selPkg = Games.selectedPkg(this);
                    Logger.i("按当前选中游戏启动: " + selName + " -> " + selPkg);
                    ok = AppFinder.launchByPackage(this, selPkg);
                }
                if (!ok) {
                    Logger.e("启动应用失败，流程中止（请确认该包名已安装）");
                    running = false;
                }
                break;
            }
            case "wait": {
                sleep(s.optInt("ms", 1000));
                break;
            }
            case "click": {
                boolean done = false;
                if (s.has("rx")) {
                    done = clickRatio((float) s.optDouble("rx", 0.5), (float) s.optDouble("ry", 0.5));
                } else if (s.has("x")) {
                    done = clickRatio((float) s.optDouble("x", 0.5), (float) s.optDouble("y", 0.5));
                }
                if (!done) {
                    Logger.w("点击未执行（坐标缺失或被拒绝）");
                }
                break;
            }
            case "longpress": {
                long ms = s.optLong("ms", 1000);
                if (s.has("x")) {
                    longPressRatio((float) s.optDouble("x", 0.5), (float) s.optDouble("y", 0.5), ms);
                } else {
                    Logger.w("长按缺少坐标");
                }
                break;
            }
            case "swipe": {
                float x1 = (float) s.optDouble("x1", 0.5);
                float y1 = (float) s.optDouble("y1", 0.8);
                float x2 = (float) s.optDouble("x2", 0.5);
                float y2 = (float) s.optDouble("y2", 0.2);
                long ms = s.optLong("ms", 400);
                swipeRatio(x1, y1, x2, y2, ms);
                break;
            }
            case "back":
                back();
                break;
            case "home":
                home();
                break;
            case "recents":
            case "menu":
                recents();
                break;
            case "input":
            case "settext": {
                String text = resolve(s.optString("text", ""));
                if (s.has("clear") && s.optBoolean("clear", false)) {
                    inputText("");
                }
                inputText(text);
                break;
            }
            case "tapinput": {
                // 先点一下输入框让它拿到焦点，再填字
                if (s.has("x")) {
                    clickRatio((float) s.optDouble("x", 0.5), (float) s.optDouble("y", 0.5));
                    sleep(s.optInt("ms", 600));
                }
                inputText(resolve(s.optString("text", "")));
                break;
            }
            case "log": {
                Logger.i(resolve(s.optString("msg", "")));
                break;
            }
            default:
                Logger.w("未知步骤类型: " + action);
        }
    }

    /** 可被停止打断的等待 */
    private void sleep(int ms) {
        int left = Math.max(0, ms);
        while (left > 0 && running) {
            int step = Math.min(100, left);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                return;
            }
            left -= step;
        }
    }
}
