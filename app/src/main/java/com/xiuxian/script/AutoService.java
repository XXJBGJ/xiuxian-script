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
        execRoot("input keyevent KEYCODE_MOVE_END");
        for (int i = 0; i < 24; i++) {
            execRoot("input keyevent KEYCODE_DEL");
        }
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
            String tmp = "/data/local/tmp/xiuxian_shot.png";
            String own = getFilesDir().getAbsolutePath() + "/_shot.png";
            execRootOut("screencap -p " + tmp + " && cat " + tmp + " > " + own
                    + " && chmod 666 " + own);
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeFile(own);
            if (bmp == null) {
                Logger.w("截屏失败（需要 root 权限）");
                return false;
            }
            sW = bmp.getWidth();
            sH = bmp.getHeight();
            sPix = new int[sW * sH];
            bmp.getPixels(sPix, 0, sW, 0, 0, sW, sH);
            bmp.recycle();
            return true;
        } catch (Throwable t) {
            Logger.w("截屏异常: " + t.getClass().getSimpleName());
            return false;
        }
    }

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

    /** 某一行指定颜色的占比 */
    private double rowRatio(int y, int mode) {
        int from = (int) (sW * 0.12);
        int to = (int) (sW * 0.88);
        int hit = 0;
        int tot = 0;
        for (int x = from; x < to; x += 2) {
            int c = sPix[y * sW + x];
            boolean ok = mode == 0 ? isGold(c) : isLight(c);
            if (ok) {
                hit++;
            }
            tot++;
        }
        return tot == 0 ? 0 : (double) hit / tot;
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
        java.util.List<int[]> lights = colorBands(1, 0.55, (int) (sH * 0.025));
        java.util.List<int[]> fields = new java.util.ArrayList<int[]>();
        for (int[] b : lights) {
            int h = b[1] - b[0] + 1;
            if (b[1] < btn[0] && h <= sH * 0.12) {
                fields.add(b);
            }
        }
        if (fields.size() < 2) {
            Logger.w("按钮上方只识别到 " + fields.size() + " 个输入框");
            return null;
        }
        int[] passB = fields.get(fields.size() - 1);
        int[] userB = fields.get(fields.size() - 2);
        int cx = sW / 2;
        int[] r = new int[]{
                cx, (userB[0] + userB[1]) / 2,
                cx, (passB[0] + passB[1]) / 2,
                cx, (btn[0] + btn[1]) / 2
        };
        Logger.i("自动定位(" + sW + "x" + sH + ")：账号 y=" + r[1]
                + "  密码 y=" + r[3] + "  登录 y=" + r[5]);
        return r;
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
                Logger.i("识别到图「" + tplName + "」");
                return clickPx(bestX + tw / 2, bestY + th / 2);
            }
            return false;
        } catch (Throwable t) {
            Logger.e("找图异常: " + t);
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
            case "login": {
                // 填账号 -> 填密码 -> 点登录。
                // 定位顺序：颜色自动识别（适配任意分辨率）-> 界面文字 -> 固定比例坐标。
                if (curUser == null || curUser.length() == 0) {
                    Logger.w("当前没有账号，跳过登录");
                    break;
                }
                Logger.i("登录: 账号 " + curUser);

                int[] ui = null;
                if (s.optBoolean("autoLocate", true)) {
                    for (int t = 0; t < 3 && ui == null && running; t++) {
                        ui = locateLoginUi();
                        if (ui == null) {
                            sleep(1200);
                        }
                    }
                }

                boolean okU = false;
                if (ui != null) {
                    clickPx(ui[0], ui[1]);
                    okU = true;
                } else if (s.optBoolean("byText", true)) {
                    okU = clickField(new String[]{"请输入账号", "账号", "帐号", "手机号"});
                }
                if (!okU) {
                    Logger.i("改用比例坐标点账号框");
                    clickRatio((float) s.optDouble("userX", 0.5),
                            (float) s.optDouble("userY", 0.349));
                }
                sleep(s.optInt("tapDelay", 700));
                inputText(curUser, true);
                sleep(400);

                boolean okP = false;
                if (ui != null) {
                    clickPx(ui[2], ui[3]);
                    okP = true;
                } else if (s.optBoolean("byText", true)) {
                    okP = clickField(new String[]{"请输入密码", "密码"});
                }
                if (!okP) {
                    Logger.i("改用比例坐标点密码框");
                    clickRatio((float) s.optDouble("passX", 0.5),
                            (float) s.optDouble("passY", 0.451));
                }
                sleep(s.optInt("tapDelay", 700));
                inputText(curPass, true);
                sleep(400);

                boolean clicked = false;
                if (ui != null) {
                    clickPx(ui[4], ui[5]);
                    clicked = true;
                }
                if (!clicked) {
                    String tpl = s.optString("tplLogin", "");
                    if (tpl.length() > 0) {
                        clicked = findImage(tpl, s.optDouble("tplTolerance", 0.82));
                    }
                }
                if (!clicked) {
                    clicked = clickByKeyword(new String[]{"登录", "登 录", "进入游戏"});
                }
                if (!clicked) {
                    Logger.i("没识别到登录按钮，按比例坐标点击");
                    clickRatio((float) s.optDouble("btnX", 0.5),
                            (float) s.optDouble("btnY", 0.554));
                }
                sleep(s.optInt("waitAfter", 6000));
                Logger.i("登录动作完成");
                break;
            }
            case "findimg":
            case "findimage": {
                String tpl = s.optString("tpl", s.optString("text", ""));
                boolean hit = findImage(tpl, s.optDouble("tolerance", 0.82));
                Logger.i("找图「" + tpl + "」: " + (hit ? "命中并点击" : "未命中"));
                break;
            }
            case "findtext": {
                String key = s.optString("text", s.optString("msg", ""));
                boolean hit = clickByKeyword(key.split("\\|"));
                Logger.i("找字「" + key + "」: " + (hit ? "命中并点击" : "未命中"));
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
