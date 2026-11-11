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

    // ============ 步骤进度（供悬浮窗显示）============
    /** 当前步骤描述，如「第 3/12 步 · 登录」 */
    public volatile String pCurName = "";
    /** 当前步骤备注 */
    public volatile String pCurNote = "";
    /** 当前步骤开始时间戳 */
    public volatile long pCurStart = 0L;
    /** 当前步骤预计结束时间戳（用来倒计时） */
    public volatile long pCurEnd = 0L;
    /** 下一步骤描述 */
    public volatile String pNextName = "";
    /** 下一步骤备注 */
    public volatile String pNextNote = "";
    /** 下一步骤预计等待毫秒 */
    public volatile long pNextMs = 0L;
    /** 第几轮 / 共几轮（悬浮窗「循环次数」用） */
    public volatile int pLoopIdx = 0;
    public volatile int pLoopTotal = 0;
    /** 每轮之间的间隔毫秒（悬浮窗「循环的间隔时间」用） */
    public volatile long pLoopGapMs = 0L;
    /** 当前步骤内的执行次数与间隔（悬浮窗「执行次数和间隔时间」用） */
    public volatile int pStepTimes = 0;
    public volatile long pStepGapMs = 0L;

    /** 暂停标志：暂停时不推进流程，但线程不退出 */
    private volatile boolean paused = false;
    /** 从第几个步骤开始跑（0 表示从第一个开始，长按悬浮窗可改） */
    private volatile int startStepIndex = 0;

    private int curAccIndex = 0;
    private int accTotal = 0;

    /**
     * 默认流程：拉起天途 -> 等加载 -> 自动填账号密码并点登录 -> 等进游戏。
     * 定位优先用「颜色自动识别」，所以在 360x600 / 1080x1920 等任意分辨率都能用。
     */
    public static JSONObject defaultConfig() {
        JSONObject o = new JSONObject();
        try {
            o.put("appName", "天途");
            o.put("packageName", "");
            o.put("loop", 1);
            o.put("perAccount", true);
            JSONArray steps = new JSONArray();
            steps.put(new JSONObject().put("action", "launch"));
            steps.put(new JSONObject().put("action", "wait").put("ms", 9000));
            JSONObject login = new JSONObject();
            login.put("action", "login");
            login.put("autoLocate", true);
            login.put("byText", true);
            login.put("userX", 0.5);
            login.put("userY", 0.349);
            login.put("passX", 0.5);
            login.put("passY", 0.451);
            login.put("btnX", 0.5);
            login.put("btnY", 0.554);
            login.put("retry", 2);
            login.put("waitAfter", 6000);
            steps.put(login);
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

    /**
     * 派发一个手势。
     *
     * 注意：老实现只要 dispatchGesture 返回 true 就当作点击成功，
     * 但系统「取消」手势时同样会返回 true —— 于是日志写着"点击成功"，
     * 屏幕上却什么也没发生（这就是登录按钮点不动、还不报错的真正原因）。
     * 现在按回调结果判定：只有 onCompleted 才算成功。
     */
    private boolean dispatch(Path path, long durationMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = new boolean[]{false};
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs)));
        boolean ok;
        try {
            ok = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    completed[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    completed[0] = false;
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
        if (!completed[0]) {
            Logger.w("手势被系统取消（多半被悬浮窗或键盘动画打断），本轮按失败处理");
        }
        return completed[0];
    }

    public boolean clickPx(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(x + 1, y);   // 部分机型不接受零长度路径，补一个极小位移
            if (dispatch(p, 60)) {
                return true;
            }
        } catch (Throwable t) {
            Logger.w("无障碍点击异常: " + t.getClass().getSimpleName());
        }
        // 无障碍派发失败时用 root tap 兜底，保证真的点下去
        try {
            execRootOut("input tap " + x + " " + y);
            Logger.i("改用 root tap 点击 " + x + "," + y);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 用 root 注入一次真实触摸（input tap）。
     *
     * 为什么要这条通道：真机上「填字」是走 root 的 input text，能正常写进输入框，
     * 说明 root 注入是通的；而无障碍 dispatchGesture 对这种游戏窗口经常被系统
     * 静默取消 —— 日志写"点击成功"，屏幕上却毫无反应（登录按钮点不动就是这个）。
     * 所以点完要看结果，没进去就换这条 root 通道再点一次。
     */
    private boolean clickPxRoot(int x, int y) {
        try {
            String r = execRootOut("input tap " + x + " " + y + "; echo OK");
            boolean ok = r != null && r.contains("OK");
            Logger.i(ok ? ("root 点击 " + x + "," + y)
                    : ("root 点击失败(无 root?) " + x + "," + y));
            return ok;
        } catch (Throwable t) {
            Logger.w("root 点击异常: " + t.getClass().getSimpleName());
            return false;
        }
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
        paused = false;
        Logger.w("收到停止指令");
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** 暂停：线程不退出，只是不再推进步骤 */
    public void pauseScript() {
        if (!running) {
            Logger.w("脚本当前没有运行，无法暂停");
            return;
        }
        paused = true;
        Logger.w("已暂停（点「继续」接着跑）");
    }

    /** 继续 */
    public void resumeScript() {
        if (!running) {
            Logger.w("脚本当前没有运行，无法继续");
            return;
        }
        paused = false;
        Logger.w("已继续");
    }

    public boolean isPaused() {
        return paused;
    }

    /** 长按浮窗可指定从第几个步骤开始（1 起），下次启动生效 */
    public void setStartStep(int stepOneBased) {
        startStepIndex = Math.max(0, stepOneBased - 1);
        Logger.i("下次启动从第 " + (startStepIndex + 1) + " 步开始");
    }

    public int getStartStep() {
        return startStepIndex + 1;
    }

    /** 读当前步骤列表，供长按菜单列出任务名 */
    public String[] stepTitles() {
        try {
            JSONObject cfg = loadConfig(this);
            JSONArray steps = cfg.optJSONArray("steps");
            if (steps == null) {
                return new String[0];
            }
            String[] out = new String[steps.length()];
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                String a = s == null ? "" : s.optString("action", "").trim();
                String note = s == null ? "" : s.optString("note", s.optString("desc", ""));
                String nm = actionName(a);
                out[i] = (i + 1) + ". " + nm + (note == null || note.isEmpty() ? "" : (" · " + note));
            }
            return out;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** 暂停期间原地等待；停止则直接跳出 */
    private void waitWhilePaused() {
        while (paused && running) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                return;
            }
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
                pLoopTotal = loop;
                pLoopGapMs = cfg.optLong("loopGap", 0L);
                for (int i = 0; i < loop && running; i++) {
                    pLoopIdx = i + 1;
                    Logger.i("--- 第 " + (i + 1) + "/" + loop + " 轮 ---");
                    for (int j = startStepIndex; j < steps.length() && running; j++) {
                        JSONObject s = steps.optJSONObject(j);
                        if (s == null) {
                            continue;
                        }
                        waitWhilePaused();
                        if (!running) {
                            break;
                        }
                        publishProgress(steps, j, s);
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
                pLoopTotal = loop;
                pLoopGapMs = cfg.optLong("loopGap", 0L);
                for (int i = 0; i < loop && running; i++) {
                    pLoopIdx = i + 1;
                    if (loop > 1) {
                        Logger.i("--- 账号内第 " + (i + 1) + "/" + loop + " 轮 ---");
                    }
                    for (int j = startStepIndex; j < steps.length() && running; j++) {
                        JSONObject s = steps.optJSONObject(j);
                        if (s == null) {
                            continue;
                        }
                        waitWhilePaused();
                        if (!running) {
                            break;
                        }
                        publishProgress(steps, j, s);
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
            pCurName = "已停止";
            pCurNote = "";
            pCurStart = 0L;
            pCurEnd = 0L;
            pNextName = "";
            pNextNote = "";
            pNextMs = 0L;
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

    // ================= root 通道 =================

    /** 用 root 执行一条命令 */
    private boolean execRoot(String cmd) {
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = pr.waitFor();
            return code == 0;
        } catch (Throwable t) {
            Logger.w("root 命令失败: " + t.getClass().getSimpleName());
            return false;
        } finally {
            if (pr != null) {
                try {
                    pr.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 用 root 执行命令并拿到输出 */
    private String execRootOut(String cmd) {
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append((char) 10);
            }
            br.close();
            pr.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (pr != null) {
                try {
                    pr.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ================= 输入 =================

    /** 找当前有输入焦点的输入框 */
    private android.view.accessibility.AccessibilityNodeInfo focusedInput() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return null;
            }
            android.view.accessibility.AccessibilityNodeInfo n = root.findFocus(
                    android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (n != null) {
                return n;
            }
            return findEditable(root, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清空当前输入框 */
    public boolean clearInput() {
        try {
            android.view.accessibility.AccessibilityNodeInfo n = focusedInput();
            if (n != null) {
                android.os.Bundle b = new android.os.Bundle();
                b.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "");
                if (n.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        // 原来 24 次 KEYCODE_DEL 会拉起 24 个 su 进程（每次 80~150ms，最坏 3 秒以上），
        // 合成一条命令后只剩一次进程开销，清空速度提升十倍以上。
        StringBuilder cmd = new StringBuilder("input keyevent KEYCODE_MOVE_END");
        for (int i = 0; i < 24; i++) {
            cmd.append(" && input keyevent KEYCODE_DEL");
        }
        execRoot(cmd.toString());
        return true;
    }

    /**
     * 填文字，三条通道依次尝试：
     * 1) 无障碍设置文本；2) 剪贴板粘贴；3) root 的 input text。
     */
    public boolean inputText(String text) {
        if (text == null) {
            text = "";
        }
        try {
            android.view.accessibility.AccessibilityNodeInfo node = focusedInput();
            if (node != null) {
                android.os.Bundle b = new android.os.Bundle();
                b.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text);
                if (node.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                    Logger.i("已填入（无障碍通道，长度 " + text.length() + "）");
                    return true;
                }
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("xiuxian", text));
                    node.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
                    if (node.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)) {
                        Logger.i("已填入（剪贴板通道，长度 " + text.length() + "）");
                        return true;
                    }
                }
            } else {
                Logger.w("没有输入框节点，改走 root 通道");
            }
        } catch (Throwable t) {
            Logger.w("无障碍输入异常: " + t.getClass().getSimpleName());
        }
        if (text.length() == 0) {
            return false;
        }
        String esc = text.replace(" ", "%s").replace("'", "");
        boolean ok = execRoot("input text '" + esc + "'");
        Logger.i("root 通道填字: " + (ok ? "成功" : "失败") + "（长度 " + text.length() + "）");
        return ok;
    }

    /** 读当前焦点输入框里的文字（无障碍通道，不产生 shell 往返） */
    private String focusedText() {
        try {
            android.view.accessibility.AccessibilityNodeInfo n = focusedInput();
            if (n == null) {
                return null;
            }
            CharSequence t = n.getText();
            return t == null ? "" : t.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 软键盘是否已经弹出 */
    private boolean keyboardShown() {
        try {
            String o = execRootOut("dumpsys input_method | grep -m1 -E 'mInputShown|mIsInputViewShown'");
            return o != null && (o.contains("mInputShown=true") || o.contains("mIsInputViewShown=true"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 等键盘真正弹出来，而不是死等固定毫秒。
     * 键盘很快就位时立刻返回（通常 150~300ms），比固定 700ms 快一倍；
     * 慢的时候最多等到 maxMs，绝不会抢在键盘前打字。
     */
    private void waitKeyboard(int maxMs) {
        long end = System.currentTimeMillis() + Math.max(0, maxMs);
        while (running && System.currentTimeMillis() < end) {
            if (keyboardShown()) {
                sleep(80);
                return;
            }
            sleep(120);
        }
    }

    /** 回读文本是否只是一串掩码符号（账号框也可能被游戏做成密码型） */
    private static boolean isMaskedText(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\u2022' && c != '\u00b7' && c != '*' && c != '\u25cf' && c != '\u25cb') {
                return false;
            }
        }
        return true;
    }

    /**
     * 往当前焦点框填字并回读校验。
     * 框里本来就是空的时候跳过清空（省 200ms + 一次清空动作）；
     * 填完读回来核对：账号必须包含原值，密码只校验非空（密码框回读多为掩码）。
     * 校验不过就清空重填一次 —— 质量保障，不能省。
     */
    private boolean typeInto(String value, boolean isPass) {
        String before = focusedText();
        if (before == null || before.length() > 0) {
            clearInput();
            sleep(80);
        }
        boolean ok = inputText(value);
        if (!ok) {
            return false;
        }
        sleep(120);
        String after = focusedText();
        if (after != null && after.length() > 0 && isMaskedText(after)) {
            // 有些游戏登录框（天途等）把账号框也做成掩码，回读只能看到 •••••••；
            // 此时不能用「包含原值」判断，否则必然误判并白跑一次清空重填。
            if (after.length() == value.length()) {
                return ok;
            }
            // 掩码长度对不上，才继续走真实校验
        }
        if (after != null) {
            boolean good = isPass ? after.length() > 0 : after.contains(value);
            if (!good) {
                Logger.w("填入校验不通过（读到: " + after + "），清空重填一次");
                clearInput();
                sleep(80);
                ok = inputText(value);
                sleep(120);
            }
        }
        return ok;
    }

    public boolean inputText(String text, boolean clear) {
        if (clear) {
            clearInput();
            sleep(200);
        }
        return inputText(text);
    }

    // ================= 截屏 =================

    private int[] sPix;
    private int sW;
    private int sH;

    /** 截一张屏并缓存像素 */
    private boolean shot() {
        try {
            // 直接截到应用私有目录，省掉「cat 大文件回传」这一趟
            String own = getFilesDir().getAbsolutePath() + "/_shot.png";
            execRoot("screencap -p " + own + " && chmod 666 " + own);
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeFile(own);
            if (bmp == null) {
                // 私有目录不可写的极端情况，退回老通道
                String tmp = "/data/local/tmp/xiuxian_shot.png";
                execRoot("screencap -p " + tmp + " && cat " + tmp + " > " + own
                        + " && chmod 666 " + own);
                bmp = android.graphics.BitmapFactory.decodeFile(own);
            }
            if (bmp == null) {
                Logger.w("截屏失败（需要 root 权限）");
                return false;
            }
            sW = bmp.getWidth();
            sH = bmp.getHeight();
            sPix = new int[sW * sH];
            rowGoldR = null;   // 新的一帧，行扫描缓存作废
            rowLightR = null;
            bmp.getPixels(sPix, 0, sW, 0, 0, sW, sH);
            bmp.recycle();
            return true;
        } catch (Throwable t) {
            Logger.w("截屏异常: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 静默找图开关：true 时只判断「有没有」，不点击（用于轮询等待界面出现） */
    private boolean imgQuiet = false;

    private boolean isGold(int c) {
        int r = c >> 16 & 0xFF;
        int g = c >> 8 & 0xFF;
        int b = c & 0xFF;
        return r >= 175 && r <= 252 && g >= 125 && g <= 205
                && b >= 25 && b <= 150 && (r - b) >= 70 && r >= g && g >= b;
    }

    private boolean isLight(int c) {
        int r = c >> 16 & 0xFF;
        int g = c >> 8 & 0xFF;
        int b = c & 0xFF;
        return r >= 235 && g >= 232 && b >= 222;
    }

    private float[] rowGoldR;
    private float[] rowLightR;

    /**
     * 一次遍历同时算出「每行金色占比」和「每行浅色占比」。
     * 原来要分别扫两遍整屏像素，现在一遍搞定，定位阶段快一倍。
     * 金色与浅色的判定条件互斥，合并循环不改变任何结果。
     */
    private void ensureRowScans() {
        if (rowGoldR != null && rowLightR != null && rowGoldR.length == sH) {
            return;
        }
        rowGoldR = new float[sH];
        rowLightR = new float[sH];
        int from = (int) (sW * 0.12);
        int to = (int) (sW * 0.88);
        for (int y = 0; y < sH; y++) {
            int hitG = 0;
            int hitL = 0;
            int tot = 0;
            int base = y * sW;
            for (int x = from; x < to; x += 2) {
                int c = sPix[base + x];
                if (isGold(c)) {
                    hitG++;
                } else if (isLight(c)) {
                    hitL++;
                }
                tot++;
            }
            rowGoldR[y] = tot == 0 ? 0f : (float) hitG / tot;
            rowLightR[y] = tot == 0 ? 0f : (float) hitL / tot;
        }
    }

    /** 某一行指定颜色的占比（走缓存，只算一遍） */
    private double rowRatio(int y, int mode) {
        ensureRowScans();
        if (y < 0 || y >= sH) {
            return 0;
        }
        return mode == 0 ? rowGoldR[y] : rowLightR[y];
    }

    /** 提取某种颜色的横条区间 */
    private java.util.List<int[]> colorBands(int mode, double minRatio, int minH) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        int start = -1;
        for (int y = 0; y < sH; y++) {
            boolean on = rowRatio(y, mode) >= minRatio;
            if (on && start < 0) {
                start = y;
            } else if (!on && start >= 0) {
                if (y - start >= minH) {
                    out.add(new int[]{start, y - 1});
                }
                start = -1;
            }
        }
        if (start >= 0 && sH - start >= minH) {
            out.add(new int[]{start, sH - 1});
        }
        return out;
    }

    /**
     * 自动定位「账号框 / 密码框 / 登录按钮」。
     *
     * 登录按钮是界面上最大的一块金色实心条，先按颜色找出来；
     * 输入框是按钮上方的浅色横条，取离按钮最近的两条。
     * 全部用真实像素计算，所以分辨率、DPI 不同也能定位。
     */
    public int[] locateLoginUi() {
        if (!shot()) {
            return null;
        }
        // ---------- 第一步：找登录按钮（金色实心条）----------
        java.util.List<int[]> golds = colorBands(0, 0.55, (int) (sH * 0.025));
        int[] btn = null;
        int bestH = 0;
        for (int[] b : golds) {
            int h = b[1] - b[0] + 1;
            if (h > sH * 0.15) {
                continue;
            }
            if (b[0] > sH * 0.33 && h >= bestH) {
                bestH = h;
                btn = b;
            }
        }
        if (btn == null) {
            Logger.w("没找到登录按钮（金色块）");
            return null;
        }

        // ---------- 第二步：找输入框（按钮上方的浅色横条）----------
        // 关键：输入框找不找得到，都不影响返回登录按钮的坐标。
        // 天途这类白底登录卡，输入框和卡片底色接近，很容易只识别出 1 条甚至 0 条。
        // 老逻辑一旦 fields<2 就整体 return null，连已经找到的金色按钮一起丢掉，
        // 结果账号密码填完却没有点登录 —— 这就是本次修的 bug。
        java.util.List<int[]> lights = colorBands(1, 0.55, (int) (sH * 0.018));
        java.util.List<int[]> fields = new java.util.ArrayList<int[]>();
        for (int[] b : lights) {
            int h = b[1] - b[0] + 1;
            if (b[1] < btn[0] && h <= sH * 0.14 && h >= sH * 0.008) {
                fields.add(b);
            }
        }
        // 从下往上排，离按钮最近的两条最可能是密码框、账号框
        java.util.Collections.sort(fields, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return b[1] - a[1];
            }
        });

        int cx = sW / 2;
        int userY = -1;
        int passY = -1;
        if (fields.size() >= 2) {
            passY = (fields.get(0)[0] + fields.get(0)[1]) / 2;
            userY = (fields.get(1)[0] + fields.get(1)[1]) / 2;
        } else if (fields.size() == 1) {
            // 只找到一条：它更可能是密码框（更靠下），账号框往上推一个行高
            int only = (fields.get(0)[0] + fields.get(0)[1]) / 2;
            passY = only;
            userY = only - Math.max(dpPx(56), (int) (sH * 0.06));
            Logger.w("只识别到 1 个输入框，账号框按行高上推估算");
        } else {
            Logger.w("按钮上方没识别到输入框，输入框交给文字/比例坐标兜底");
        }

        int btnY = (btn[0] + btn[1]) / 2;
        int[] r = new int[]{cx, userY, cx, passY, cx, btnY};
        Logger.i("自动定位(" + sW + "x" + sH + ")：账号 y=" + userY
                + "  密码 y=" + passY + "  登录 y=" + btnY);
        return r;
    }

    /** dp 转 px（用屏幕密度粗算，这里只要一个行高量级） */
    private int dpPx(float v) {
        try {
            float d = getResources().getDisplayMetrics().density;
            return (int) (v * d + 0.5f);
        } catch (Throwable t) {
            return (int) v * 2;
        }
    }

    // ================= 识字：读界面层级 =================

    public static class UiNode {
        public String text = "";
        public String desc = "";
        public String cls = "";
        public int left, top, right, bottom;

        public int cx() {
            return (left + right) / 2;
        }

        public int cy() {
            return (top + bottom) / 2;
        }

        public String all() {
            return text + "|" + desc + "|" + cls;
        }
    }

    private static final java.util.regex.Pattern NODE_PAT =
            java.util.regex.Pattern.compile("<node\\s+[^>]*?/?>");
    private static final java.util.regex.Pattern ATTR_TXT =
            java.util.regex.Pattern.compile("text=\"([^\"]*)\"");
    private static final java.util.regex.Pattern ATTR_DESC =
            java.util.regex.Pattern.compile("content-desc=\"([^\"]*)\"");
    private static final java.util.regex.Pattern ATTR_CLS =
            java.util.regex.Pattern.compile("class=\"([^\"]*)\"");
    private static final java.util.regex.Pattern ATTR_BND =
            java.util.regex.Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");

    /** 读界面控件树（文字 + 真实屏幕坐标，与分辨率无关） */
    public java.util.List<UiNode> dumpUi() {
        java.util.List<UiNode> out = new java.util.ArrayList<UiNode>();
        String xml = execRootOut("uiautomator dump /data/local/tmp/xiuxian_ui.xml >/dev/null 2>&1; "
                + "cat /data/local/tmp/xiuxian_ui.xml");
        if (xml == null || xml.length() < 30) {
            Logger.w("界面层级读取失败（uiautomator 不可用或没 root）");
            return out;
        }
        java.util.regex.Matcher m = NODE_PAT.matcher(xml);
        while (m.find()) {
            String tag = m.group();
            UiNode n = new UiNode();
            java.util.regex.Matcher a = ATTR_TXT.matcher(tag);
            if (a.find()) {
                n.text = a.group(1);
            }
            a = ATTR_DESC.matcher(tag);
            if (a.find()) {
                n.desc = a.group(1);
            }
            a = ATTR_CLS.matcher(tag);
            if (a.find()) {
                n.cls = a.group(1);
            }
            a = ATTR_BND.matcher(tag);
            if (a.find()) {
                n.left = Integer.parseInt(a.group(1));
                n.top = Integer.parseInt(a.group(2));
                n.right = Integer.parseInt(a.group(3));
                n.bottom = Integer.parseInt(a.group(4));
            }
            if (n.right > n.left && n.bottom > n.top) {
                out.add(n);
            }
        }
        Logger.i("界面识别到 " + out.size() + " 个控件");
        return out;
    }

    private UiNode findNode(java.util.List<UiNode> nodes, String[] keys, boolean wantEditable) {
        for (UiNode n : nodes) {
            if (wantEditable && n.cls.indexOf("EditText") < 0) {
                continue;
            }
            String all = n.all();
            for (String k : keys) {
                if (k != null && k.length() > 0 && all.contains(k)) {
                    return n;
                }
            }
        }
        return null;
    }

    /** 按文字找控件并点击：无障碍 -> 界面层级 -> false */
    public boolean clickByKeyword(String[] keys) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                for (String k : keys) {
                    android.view.accessibility.AccessibilityNodeInfo n =
                            findByTextLike(root, k, 0);
                    if (n != null) {
                        android.view.accessibility.AccessibilityNodeInfo cur = n;
                        while (cur != null && !cur.isClickable()) {
                            cur = cur.getParent();
                        }
                        if (cur != null && cur.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            Logger.i("识别到「" + k + "」并点击（无障碍）");
                            return true;
                        }
                        android.graphics.Rect r = new android.graphics.Rect();
                        n.getBoundsInScreen(r);
                        if (r.width() > 0) {
                            Logger.i("识别到「" + k + "」，按坐标点击 "
                                    + r.centerX() + "," + r.centerY());
                            return clickPx(r.centerX(), r.centerY());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.w("无障碍找字异常: " + t.getClass().getSimpleName());
        }
        java.util.List<UiNode> nodes = dumpUi();
        UiNode n = findNode(nodes, keys, false);
        if (n != null) {
            Logger.i("识别到「" + n.text + n.desc + "」，坐标点击 " + n.cx() + "," + n.cy());
            return clickPx(n.cx(), n.cy());
        }
        Logger.i("界面上没识别到 " + java.util.Arrays.toString(keys));
        return false;
    }

    /** 按关键字找输入框并点它 */
    public boolean clickField(String[] keys) {
        java.util.List<UiNode> nodes = dumpUi();
        UiNode n = findNode(nodes, keys, true);
        if (n == null) {
            n = findNode(nodes, keys, false);
        }
        if (n == null) {
            Logger.i("没识别到输入框 " + java.util.Arrays.toString(keys));
            return false;
        }
        Logger.i("识别到输入框「" + n.text + n.desc + "」，坐标点击 " + n.cx() + "," + n.cy());
        return clickPx(n.cx(), n.cy());
    }

    private android.view.accessibility.AccessibilityNodeInfo findByTextLike(
            android.view.accessibility.AccessibilityNodeInfo node, String key, int depth) {
        if (node == null || depth > 40 || key == null || key.length() == 0) {
            return null;
        }
        CharSequence t = node.getText();
        if (t != null && t.toString().contains(key)) {
            return node;
        }
        CharSequence d = node.getContentDescription();
        if (d != null && d.toString().contains(key)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo r =
                    findByTextLike(node.getChild(i), key, depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ================= 识图：灰度模板匹配 =================

    /**
     * 在当前屏幕里找模板图，命中就点中心。
     * 模板放在 App 私有目录 tpl/ 下（如 tpl/login.png）。
     */
    public boolean findImage(String tplName, double threshold) {
        if (tplName == null || tplName.length() == 0) {
            return false;
        }
        try {
            java.io.File tf = new java.io.File(getFilesDir(), "tpl/" + tplName);
            if (!tf.exists()) {
                Logger.w("模板不存在: " + tf.getAbsolutePath());
                return false;
            }
            android.graphics.Bitmap tb = android.graphics.BitmapFactory.decodeFile(
                    tf.getAbsolutePath());
            if (tb == null) {
                Logger.w("模板解码失败: " + tplName);
                return false;
            }
            int tw = tb.getWidth();
            int th = tb.getHeight();
            int[] tp = new int[tw * th];
            tb.getPixels(tp, 0, tw, 0, 0, tw, th);
            int[] tg = new int[tw * th];
            for (int i = 0; i < tp.length; i++) {
                int c = tp[i];
                tg[i] = ((c >> 16 & 0xFF) * 77 + (c >> 8 & 0xFF) * 151 + (c & 0xFF) * 28) >> 8;
            }
            tb.recycle();
            if (!shot()) {
                return false;
            }
            if (tw > sW || th > sH) {
                Logger.w("模板比屏幕大，跳过");
                return false;
            }
            int[] sg = new int[sW * sH];
            for (int i = 0; i < sPix.length; i++) {
                int c = sPix[i];
                sg[i] = ((c >> 16 & 0xFF) * 77 + (c >> 8 & 0xFF) * 151 + (c & 0xFF) * 28) >> 8;
            }
            int bestX = -1;
            int bestY = -1;
            long bestScore = Long.MAX_VALUE;
            for (int y = 0; y + th <= sH; y += 3) {
                for (int x = 0; x + tw <= sW; x += 3) {
                    long diff = 0;
                    int cnt = 0;
                    for (int ty = 0; ty < th; ty += 4) {
                        int rowS = (y + ty) * sW + x;
                        int rowT = ty * tw;
                        for (int tx = 0; tx < tw; tx += 4) {
                            diff += Math.abs(sg[rowS + tx] - tg[rowT + tx]);
                            cnt++;
                        }
                    }
                    long avg = cnt == 0 ? Long.MAX_VALUE : diff / cnt;
                    if (avg < bestScore) {
                        bestScore = avg;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            long tol = (long) (255 * (1 - threshold));
            Logger.i("模板「" + tplName + "」最佳误差 " + bestScore + "（阈值 " + tol + "）");
            if (bestX >= 0 && bestScore <= tol) {
                if (imgQuiet) {
                    return true;    // 静默模式：只回报存在，不点击
                }
                Logger.i("识别到图「" + tplName + "」");
                return clickPx(bestX + tw / 2, bestY + th / 2);
            }
            return false;
        } catch (Throwable t) {
            Logger.e("找图异常: " + t);
            return false;
        }
    }

    /** 估算某一步大概要占用多久（毫秒），用于悬浮窗倒计时与「下一步约 X 秒」 */
    private long stepCostMs(JSONObject s) {
        if (s == null) {
            return 0;
        }
        String a = s.optString("action", "").trim();
        if ("wait".equals(a)) {
            return s.optInt("ms", 1000);
        }
        if ("login".equals(a)) {
            // 定位 + 两次键盘等待 + 两次填字校验 + 最多五轮"点完验证"
            return 6000L + s.optInt("tapDelay", 900) * 2 + s.optInt("waitAfter", 6000);
        }
        if ("swipe".equals(a)) {
            return s.optLong("ms", 400) + 300;
        }
        if ("longpress".equals(a)) {
            return s.optLong("ms", 1000) + 300;
        }
        if ("launch".equals(a)) {
            return 3000;
        }
        if ("findimg".equals(a) || "findimage".equals(a) || "findtext".equals(a)) {
            return 1500;
        }
        if ("input".equals(a) || "settext".equals(a) || "tapinput".equals(a)) {
            return 800 + s.optInt("ms", 0);
        }
        return 600;
    }

    /** 把「当前步骤 / 下一步骤」写进字段，悬浮窗每 400ms 读一次 */
    private void publishProgress(JSONArray steps, int j, JSONObject s) {
        try {
            int total = steps.length();
            String name = s.optString("action", "").trim();
            String note = s.optString("note", s.optString("desc", ""));
            if (note == null || note.isEmpty()) {
                note = actionNote(name, s);
            }
            long cost = stepCostMs(s);
            pCurName = "第 " + (j + 1) + "/" + total + " 步 · " + actionName(name);
            pCurNote = note;
            pCurStart = System.currentTimeMillis();
            pCurEnd = pCurStart + cost;
            // 重复执行类步骤：次数与间隔（悬浮窗「执行次数和间隔时间」用）
            pStepTimes = Math.max(0, s.optInt("times", s.optInt("repeat", 0)));
            pStepGapMs = Math.max(0L, s.optLong("interval", s.optLong("gap", 0L)));

            JSONObject nx = (j + 1 < total) ? steps.optJSONObject(j + 1) : null;
            if (nx == null) {
                pNextName = "（本账号流程结束）";
                pNextNote = "";
                pNextMs = 0L;
            } else {
                String na = nx.optString("action", "").trim();
                pNextName = "第 " + (j + 2) + "/" + total + " 步 · " + actionName(na);
                String nn = nx.optString("note", nx.optString("desc", ""));
                pNextNote = (nn == null || nn.isEmpty()) ? actionNote(na, nx) : nn;
                pNextMs = stepCostMs(nx);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 动作类型 -> 中文名 */
    private String actionName(String a) {
        switch (a) {
            case "launch": return "启动游戏";
            case "wait": return "等待";
            case "click": return "点击";
            case "longpress": return "长按";
            case "swipe": return "滑动";
            case "back": return "返回键";
            case "home": return "回桌面";
            case "recents":
            case "menu": return "多任务";
            case "input":
            case "settext": return "输入文字";
            case "tapinput": return "点框输入";
            case "login": return "登录";
            case "findimg":
            case "findimage": return "找图点击";
            case "findtext": return "找字点击";
            case "log": return "记录日志";
            default: return a.isEmpty() ? "未知步骤" : a;
        }
    }

    /** 动作类型 -> 默认备注（步骤没写 note 时用） */
    private String actionNote(String a, JSONObject s) {
        switch (a) {
            case "launch": return "按包名启动选中的游戏";
            case "wait": return "歇 " + s.optInt("ms", 1000) + " 毫秒，等界面加载";
            case "click": return "点 (" + s.optDouble("x", 0.5) + ", " + s.optDouble("y", 0.5) + ")";
            case "longpress": return "长按 " + s.optLong("ms", 1000) + " 毫秒";
            case "swipe": return "从 (" + s.optDouble("x1", 0.5) + "," + s.optDouble("y1", 0.8)
                    + ") 滑到 (" + s.optDouble("x2", 0.5) + "," + s.optDouble("y2", 0.2) + ")";
            case "login": return "识别账号框/密码框/登录按钮并提交";
            case "findimg":
            case "findimage": return "在屏幕上找模板图并点击";
            case "findtext": return "在界面文字里找关键词并点击";
            case "back": return "按一次返回";
            case "log": return "写一条日志";
            default: return "";
        }
    }

    /** 账号框 / 密码框关键字（两条通道共用） */
    private static final String[] USER_KEYS =
            {"请输入账号", "输入账号", "账号", "帐号", "手机号", "用户名"};
    private static final String[] PASS_KEYS =
            {"请输入密码", "输入密码", "密码", "验证码"};

    /** 登录按钮上不该被点到的干扰文字（「账号登录」标签页也含"登录"二字） */
    private static final String[] LOGIN_BAD = {
            "账号登录", "帐号登录", "手机登录", "自动登录", "注册", "忘记", "客服", "切换"};

    private static String nodeText(android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            return t.toString().trim();
        }
        CharSequence d = n.getContentDescription();
        return d == null ? "" : d.toString().trim();
    }

    private static void collectNodes(android.view.accessibility.AccessibilityNodeInfo n,
                                     java.util.List<android.view.accessibility.AccessibilityNodeInfo> out,
                                     int depth) {
        if (n == null || depth > 60 || out.size() > 800) {
            return;
        }
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            collectNodes(n.getChild(i), out, depth + 1);
        }
    }

    private static boolean isBadLoginText(String t) {
        if (t == null || t.length() == 0) {
            return true;
        }
        for (String b : LOGIN_BAD) {
            if (t.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点「登录」按钮（文字通道）。
     *
     * 关键坑：登录卡上方有「账号登录 / 手机登录」两个标签页，前者文字里也含"登录"。
     * 老逻辑按「包含登录」去找，第一个命中的就是这个已经选中的标签页，
     * 点下去毫无反应 —— 表现就是账号密码都填好了却永远不登录。
     * 这里先找完全等于「登录」的节点，并排除账号登录/手机登录/注册等干扰项。
     */
    private boolean clickLoginByText() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> all =
                        new java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo>();
                collectNodes(root, all, 0);
                for (int pass = 0; pass < 2; pass++) {
                    for (android.view.accessibility.AccessibilityNodeInfo n : all) {
                        String t = nodeText(n);
                        if (isBadLoginText(t)) {
                            continue;
                        }
                        boolean hit = (pass == 0) ? t.equals("登录")
                                : (t.contains("登录") || t.contains("进入游戏"));
                        if (!hit) {
                            continue;
                        }
                        android.graphics.Rect r = new android.graphics.Rect();
                        n.getBoundsInScreen(r);
                        if (r.width() <= 0 || r.height() <= 0) {
                            continue;
                        }
                        android.view.accessibility.AccessibilityNodeInfo cur = n;
                        while (cur != null && !cur.isClickable()) {
                            cur = cur.getParent();
                        }
                        if (cur != null && cur.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            Logger.i("点登录(无障碍): " + t);
                            return true;
                        }
                        Logger.i("点登录(坐标): " + t + " " + r.centerX() + "," + r.centerY());
                        return clickPx(r.centerX(), r.centerY());
                    }
                }
            }
        } catch (Throwable t) {
            Logger.w("无障碍点登录异常: " + t.getClass().getSimpleName());
        }
        java.util.List<UiNode> nodes = dumpUi();
        for (UiNode n : nodes) {
            String t = (n.text == null ? "" : n.text).trim();
            if (t.equals("登录") && !isBadLoginText(t)) {
                Logger.i("点登录(层级): " + n.cx() + "," + n.cy());
                return clickPx(n.cx(), n.cy());
            }
        }
        return false;
    }

    /** 登录卡是否仍然在屏幕上（在 = 还没进游戏） */
    private boolean loginCardStill() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> all =
                    new java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo>();
            collectNodes(root, all, 0);
            for (android.view.accessibility.AccessibilityNodeInfo n : all) {
                String t = nodeText(n);
                if (t.contains("请输入密码") || t.contains("请输入账号")
                        || t.contains("忘记账号") || t.contains("账户注册")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 点完等一下，看登录卡有没有消失 */
    private boolean waitLoginGone(int ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            if (!loginCardStill()) {
                return true;
            }
            sleep(250);
        }
        return !loginCardStill();
    }

    // ==================== 公告 / 提示类弹窗自动点「确定」 ====================

    /** 弹窗确认按钮的文字（命中任一即认为是「确定」） */
    private static final String[] POPUP_OK = {"确定", "确认", "我知道了", "知道了", "确 定", "好的"};

    /** 出现这些字样基本可判定是公告/提示弹窗，点掉不会误伤游戏主界面 */
    private static final String[] POPUP_MARK = {"公告", "通知", "提示", "维护", "更新", "致玩家"};

    /**
     * 干掉当前屏幕上的公告类弹窗，返回是否真的点过。
     *
     * 判定思路（避免误点主界面里恰好叫「确定」的按钮）：
     *   1) 屏幕上必须存在公告类标题字样，或一段较长正文（>=30 字）；
     *   2) 同时存在文字等于「确定/确认」的按钮；
     *   3) 两条同时成立才动手：先无障碍点击，其次按坐标点，最后按「橙色按钮」颜色兜底。
     */
    public boolean dismissPopups() {
        boolean hasMark = false;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> all =
                        new java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo>();
                collectNodes(root, all, 0);

                for (android.view.accessibility.AccessibilityNodeInfo n : all) {
                    String t = nodeText(n);
                    if (t == null || t.length() == 0) {
                        continue;
                    }
                    for (String m : POPUP_MARK) {
                        if (t.contains(m)) {
                            hasMark = true;
                            break;
                        }
                    }
                    // 有整段长正文，也按弹窗看待（公告正文通常就是一大段）
                    if (!hasMark && t.length() >= 30) {
                        hasMark = true;
                    }
                    if (hasMark) {
                        break;
                    }
                }

                if (hasMark) {
                    for (android.view.accessibility.AccessibilityNodeInfo n : all) {
                        String t = nodeText(n);
                        if (t == null) {
                            continue;
                        }
                        t = t.trim();
                        boolean hit = false;
                        for (String k : POPUP_OK) {
                            if (t.equals(k)) {
                                hit = true;
                                break;
                            }
                        }
                        if (!hit) {
                            continue;
                        }
                        android.graphics.Rect r = new android.graphics.Rect();
                        n.getBoundsInScreen(r);
                        if (r.width() <= 0 || r.height() <= 0) {
                            continue;
                        }
                        android.view.accessibility.AccessibilityNodeInfo cur = n;
                        while (cur != null && !cur.isClickable()) {
                            cur = cur.getParent();
                        }
                        if (cur != null && cur.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            Logger.i("点公告弹窗(无障碍): " + t);
                            return true;
                        }
                        Logger.i("点公告弹窗(坐标): " + t + " " + r.centerX() + "," + r.centerY());
                        return clickPx(r.centerX(), r.centerY());
                    }
                }
            }
        } catch (Throwable t) {
            Logger.w("处理公告弹窗异常: " + t.getClass().getSimpleName());
        }

        // 无障碍层拿不到按钮时，用颜色兜底（公告的「确定」是橙色实心块）
        if (hasMark) {
            try {
                if (popupByColor()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 颜色兜底：在屏幕中部偏下找一条橙色实心横条，点它的中心。
     * 全程按真实像素计算，所以不同分辨率/DPI 都能点到。
     */
    private boolean popupByColor() {
        if (!shot()) {
            return false;
        }
        // 公告的「确定」比登录按钮窄，占比阈值放低一些
        java.util.List<int[]> bands = colorBands(0, 0.26, (int) (sH * 0.018));
        int[] pick = null;
        for (int[] b : bands) {
            int h = b[1] - b[0] + 1;
            if (h > sH * 0.10) {
                continue;   // 太高，不是按钮
            }
            double cy = (b[0] + b[1]) / 2.0 / sH;
            if (cy < 0.38 || cy > 0.90) {
                continue;   // 只认屏幕中部偏下的按钮
            }
            if (pick == null || h > (pick[1] - pick[0] + 1)) {
                pick = b;
            }
        }
        if (pick == null) {
            return false;
        }
        int cy2 = (pick[0] + pick[1]) / 2;
        Logger.i("按颜色点公告弹窗确定(" + (sW / 2) + "," + cy2 + ")");
        if (clickPx(sW / 2, cy2)) {
            return true;
        }
        clickPxRoot(sW / 2, cy2);
        return true;
    }

    /**
     * 登录后等「真正进入游戏」。
     *
     * 需求：不再固定死等。每 200ms 看一眼——
     *   · 有公告/提示弹窗就点掉「确定」；
     *   · 弹窗点完、登录卡也消失 → 立刻返回，进入下一个步骤；
     *   · 到上限还没进 → 重新走一次「点登录」，而不是干等（识别失败就重试本步）。
     */
    private boolean waitEnterGame(JSONObject s) {
        int maxMs = s.optInt("waitAfter", 6000);
        int poll = Math.max(150, s.optInt("poll", 200));
        int maxRetry = Math.max(0, s.optInt("loginRetry", 2));
        long end = System.currentTimeMillis() + maxMs;
        long t0 = System.currentTimeMillis();
        int retried = 0;
        int popups = 0;

        while (running && System.currentTimeMillis() < end) {
            if (dismissPopups()) {
                popups++;
                sleep(poll);
                continue;
            }
            if (!loginCardStill()) {
                // 登录卡没了、也没弹窗，再补一次弹窗检查（公告常常晚一两拍才出来）
                sleep(poll);
                if (dismissPopups()) {
                    popups++;
                    sleep(poll);
                    continue;
                }
                if (!loginCardStill()) {
                    Logger.i("进入游戏: 用时 " + (System.currentTimeMillis() - t0)
                            + " ms，点掉弹窗 " + popups + " 个");
                    return true;
                }
            }
            // 登录卡还在且确实没进展 → 重试点登录（最多 loginRetry 次）
            long left = end - System.currentTimeMillis();
            if (retried < maxRetry && left > poll * 3) {
                retried++;
                Logger.w("还没进入游戏，重新点一次登录（第 " + retried + " 次）");
                if (clickLoginByText()) {
                    sleep(poll);
                } else {
                    clickRatio((float) s.optDouble("btnX", 0.5),
                            (float) s.optDouble("btnY", 0.548));
                    sleep(poll);
                }
            } else {
                sleep(poll);
            }
        }
        Logger.w("等待进入游戏超时（" + maxMs + " ms），继续按后续步骤执行");
        return false;
    }

    /**
     * 轮询等待某个条件成立，成立就立刻返回 true（用于「识别成功即下一步」）。
     *
     * @param texts  文字关键字，任一命中即算成功（可为空）
     * @param tpl    模板图名（可为空）
     * @param maxMs  最多等多久
     * @param poll   每次间隔
     */
    public boolean waitUntil(String[] texts, String tpl, int maxMs, int poll) {
        long end = System.currentTimeMillis() + Math.max(0, maxMs);
        int gap = Math.max(120, poll);
        while (running) {
            if (texts != null && texts.length > 0) {
                for (String k : texts) {
                    if (k != null && k.trim().length() > 0 && textExists(k.trim())) {
                        return true;
                    }
                }
            }
            if (tpl != null && tpl.trim().length() > 0) {
                if (findImageQuiet(tpl.trim())) {
                    return true;
                }
            }
            if (System.currentTimeMillis() >= end) {
                return false;
            }
            sleepQuiet(gap);
        }
        return false;
    }

    /** 屏幕上是否出现某段文字（只看层级，不点） */
    public boolean textExists(String key) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> all =
                    new java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo>();
            collectNodes(root, all, 0);
            for (android.view.accessibility.AccessibilityNodeInfo n : all) {
                String t = nodeText(n);
                if (t != null && t.contains(key)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 只判断有没有这张模板图，不点击（复用 findImage 的匹配，靠开关屏蔽点击） */
    private boolean findImageQuiet(String tplName) {
        imgQuiet = true;
        try {
            return findImage(tplName, 0.82);
        } catch (Throwable t) {
            return false;
        } finally {
            imgQuiet = false;
        }
    }

    /** 安静版 sleep：被停止/暂停打断也不打日志 */
    private void sleepQuiet(int ms) {
        sleep(ms);
    }

    /**
     * 自动登录：定位 -> 点账号框 -> 填账号 -> 点密码框 -> 填密码 -> 点登录。
     *
     * 提速但保质的几处：
     *   1) 定位从「固定等 1.2s × 3 次」改成自适应轮询，界面就绪时第一次就命中；
     *   2) 点完输入框不再死等 700ms，而是等键盘真正弹出（通常 150~300ms）；
     *   3) 界面层级只 dump 一次，账号框与密码框共用，省一次 uiautomator（1~2s）；
     *   4) 填完回读校验，不对就清空重填，保证最终填进去的内容正确；
     *   5) 登录按钮仍是「金色块 -> 模板图 -> 文字 -> 比例坐标 -> 回车」五级兜底。
     */
    private void doLogin(JSONObject s) {
        if (curUser == null || curUser.length() == 0) {
            Logger.w("当前没有账号，跳过登录");
            return;
        }
        Logger.i("登录开始: 账号 " + curUser);
        long tStart = System.currentTimeMillis();

        // ---------- 自适应定位 ----------
        int[] ui = null;
        if (s.optBoolean("autoLocate", true)) {
            int[] waits = {0, 250, 350, 500, 700, 900, 1200};
            for (int i = 0; i < waits.length && running; i++) {
                if (waits[i] > 0) {
                    sleep(waits[i]);
                }
                ui = locateLoginUi();
                if (ui != null) {
                    break;
                }
            }
            Logger.i("定位: " + (ui == null ? "未识别" : "已命中")
                    + "，累计 " + (System.currentTimeMillis() - tStart) + " ms");
        }
        if (!running) {
            return;
        }

        // 颜色定位失败才 dump 一次层级，两个框共用这份结果
        java.util.List<UiNode> nodes = null;
        if (ui == null && s.optBoolean("byText", true)) {
            nodes = dumpUi();
        }

        // ---------- 账号框 ----------
        boolean okU = false;
        if (ui != null && ui[1] > 0) {
            okU = clickPx(ui[0], ui[1]);
        }
        if (!okU && nodes != null) {
            UiNode n = findNode(nodes, USER_KEYS, true);
            if (n == null) {
                n = findNode(nodes, USER_KEYS, false);
            }
            if (n != null) {
                Logger.i("识别到账号框「" + n.text + n.desc + "」");
                okU = clickPx(n.cx(), n.cy());
            }
        }
        if (!okU) {
            Logger.i("改用比例坐标点账号框");
            okU = clickRatio((float) s.optDouble("userX", 0.5),
                    (float) s.optDouble("userY", 0.349));
        }
        Logger.i("账号框点击: " + (okU ? "成功" : "失败"));
        waitKeyboard(s.optInt("tapDelay", 900));
        Logger.i("账号填入: " + (typeInto(curUser, false) ? "成功" : "失败"));

        // ---------- 密码框 ----------
        boolean okP = false;
        if (ui != null && ui[3] > 0) {
            okP = clickPx(ui[2], ui[3]);
        }
        if (!okP && nodes != null) {
            UiNode n = findNode(nodes, PASS_KEYS, true);
            if (n == null) {
                n = findNode(nodes, PASS_KEYS, false);
            }
            if (n != null) {
                Logger.i("识别到密码框「" + n.text + n.desc + "」");
                okP = clickPx(n.cx(), n.cy());
            }
        }
        if (!okP) {
            Logger.i("改用比例坐标点密码框");
            okP = clickRatio((float) s.optDouble("passX", 0.5),
                    (float) s.optDouble("passY", 0.451));
        }
        Logger.i("密码框点击: " + (okP ? "成功" : "失败"));
        waitKeyboard(s.optInt("tapDelay", 900));
        Logger.i("密码填入: " + (typeInto(curPass, true) ? "成功" : "失败"));

        // ---------- 登录按钮：点完必须验证，没进去就换招再点 ----------
        boolean loggedIn = false;
        String tplLogin = s.optString("tplLogin", "");

        // 关键：填字过程会推挤界面（键盘/光标/滚动），进这一步前算好的按钮坐标
        // 到这时可能已经偏了。这里重新定位一次，用"新鲜"坐标去点。
        if (s.optBoolean("autoLocate", true)) {
            try {
                int[] fresh = locateLoginUi();
                if (fresh != null && fresh[5] > 0) {
                    Logger.i("登录前重新定位: 账号 y=" + fresh[1] + " 密码 y=" + fresh[3]
                            + " 登录 y=" + fresh[5] + "（原 " + (ui == null ? "-" : String.valueOf(ui[5])) + "）");
                    ui = fresh;
                }
            } catch (Throwable t) {
                Logger.w("登录前重新定位失败: " + t.getClass().getSimpleName());
            }
        }

        if (ui != null && ui[5] > 0) {
            Logger.i("按金色块点登录(" + ui[4] + "," + ui[5] + ")");
            clickPx(ui[4], ui[5]);
            loggedIn = waitLoginGone(1200);
        }
        if (!loggedIn && running && ui != null && ui[5] > 0) {
            Logger.i("无障碍点击没生效，改用 root 注入点登录");
            clickPxRoot(ui[4], ui[5]);
            loggedIn = waitLoginGone(1500);
        }
        if (!loggedIn && running) {
            if (clickLoginByText()) {
                loggedIn = waitLoginGone(1500);
            }
        }
        if (!loggedIn && running && tplLogin.length() > 0) {
            Logger.i("按模板图点登录");
            findImage(tplLogin, s.optDouble("tplTolerance", 0.82));
            loggedIn = waitLoginGone(1200);
        }
        if (!loggedIn && running) {
            // 录屏逐帧实测量得：登录条中心约在屏高 0.548，取色带中心偏下到 0.592。
            // 按钮只有约 60px 高，点偏 60px 就落到边缘点不动，所以两个比例都试。
            // （账户注册在更下方约 0.637，不会误触）
            double[] ys = new double[]{s.optDouble("btnY", 0.548),
                    s.optDouble("btnY2", 0.592)};
            for (double ry : ys) {
                if (loggedIn || !running) {
                    break;
                }
                int px = (int) (sW * s.optDouble("btnX", 0.5));
                int py = (int) (sH * ry);
                Logger.i("按比例 root 点登录 " + px + "," + py + " (y=" + ry + ")");
                clickPxRoot(px, py);
                loggedIn = waitLoginGone(1200);
            }
        }
        if (!loggedIn && running && s.optBoolean("enterFallback", true)) {
            Logger.i("改用回车提交登录");
            execRootOut("input keyevent 66");
            loggedIn = waitLoginGone(1500);
        }
        Logger.i("登录结果: " + (loggedIn ? "登录卡已消失，已进入游戏" : "登录卡仍在，可能账号密码有误"));
        Logger.i("登录动作完成，耗时 " + (System.currentTimeMillis() - tStart) + " ms");

        // 登录后：不再固定死等。轮询识别——公告弹窗点「确定」，识别到就立刻进入下一步，
        // 一直识别不到才重试点登录（识别失败重试本步，不空等）。
        waitEnterGame(s);
        Logger.i("登录流程总耗时 " + (System.currentTimeMillis() - tStart) + " ms");
    }

    private void execStep(JSONObject cfg, JSONObject s, int index) {
        String action = s.optString("action", "").trim();
        Logger.i("步骤 " + index + ": " + action);
        // 每步开跑前扫一眼公告/提示弹窗：有就点「确定」，免得后面每步都点不动。
        // login 与 dismisspopup 步骤自己会处理，跳过以免重复点击。
        if (cfg.optBoolean("autoPopup", true)
                && !"login".equals(action) && !"dismisspopup".equals(action)) {
            try {
                if (dismissPopups()) {
                    Logger.i("步骤前发现弹窗，已点掉，继续本步骤");
                    sleep(s.optInt("popupGap", 350));
                }
            } catch (Throwable ignored) {
            }
        }
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
                // ms 是上限；写了 until / untilImg 就变成轮询等待：
                // 一旦识别到目标界面立刻进入下一步，识别不到就一直在上限内重试，
                // 这样界面刷新出来马上就走，不用干等固定时长。
                int cap = s.optInt("ms", 1000);
                String until = s.optString("until", "").trim();
                String untilImg = s.optString("untilImg", "").trim();
                if (until.length() == 0 && untilImg.length() == 0) {
                    sleep(cap);
                    break;
                }
                long t0 = System.currentTimeMillis();
                boolean ok = waitUntil(until.split("\\|"), untilImg, cap,
                        s.optInt("poll", 250));
                Logger.i("等待" + (ok ? "命中目标界面，提前进入下一步: "
                        : "超时未识别到目标界面: ") + (System.currentTimeMillis() - t0) + " ms");
                break;
            }
            case "dismisspopup": {
                // 专门用来关公告/活动弹窗；连续关几次，防止叠了好几层
                int n = 0;
                for (int i = 0; i < 3; i++) {
                    if (!dismissPopups()) {
                        break;
                    }
                    n++;
                    sleep(s.optInt("poll", 350));
                }
                Logger.i("关闭弹窗: " + (n > 0 ? "已点掉 " + n + " 个" : "无弹窗"));
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
            case "login": {
                doLogin(s);
                break;
            }
            case "findimg":
            case "findimage": {
                String tpl = s.optString("tpl", s.optString("text", ""));
                // 未命中就按 poll 间隔重试，上限 ms（默认 3000），命中即走
                int cap = s.optInt("ms", 3000);
                int poll = Math.max(150, s.optInt("poll", 300));
                long t0 = System.currentTimeMillis();
                boolean hit = false;
                while (running) {
                    hit = findImage(tpl, s.optDouble("tolerance", 0.82));
                    if (hit || System.currentTimeMillis() - t0 >= cap) {
                        break;
                    }
                    sleep(poll);
                }
                Logger.i("找图「" + tpl + "」: " + (hit ? "命中并点击" : "未命中")
                        + "，耗时 " + (System.currentTimeMillis() - t0) + " ms");
                break;
            }
            case "findtext": {
                String key = s.optString("text", s.optString("msg", ""));
                int cap = s.optInt("ms", 3000);
                int poll = Math.max(150, s.optInt("poll", 300));
                long t0 = System.currentTimeMillis();
                boolean hit = false;
                while (running) {
                    hit = clickByKeyword(key.split("\\|"));
                    if (hit || System.currentTimeMillis() - t0 >= cap) {
                        break;
                    }
                    sleep(poll);
                }
                Logger.i("找字「" + key + "」: " + (hit ? "命中并点击" : "未命中")
                        + "，耗时 " + (System.currentTimeMillis() - t0) + " ms");
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
            if (paused) {
                // 暂停时不计时，恢复后继续把这一步等完
                waitWhilePaused();
                if (!running) {
                    return;
                }
            }
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
