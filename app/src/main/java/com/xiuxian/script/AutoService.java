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
    /** 配置结构版本：流程步骤改版时 +1，旧配置会自动升级 */
    public static final int CFG_VERSION = 2;

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
    /** 当前步骤预计结束时间戳（真实耗时模式下为 0，仅历史兼容） */
    public volatile long pCurEnd = 0L;
    /** 当前步骤已耗时毫秒（实时刷新，悬浮窗用它显示已经花掉的时间） */
    public volatile long pCurElapsedMs = 0L;
    /** 当前步骤预估耗时毫秒（仅作参考，不再用来倒计时） */
    public volatile long pEstMs = 0L;
    /** 当前步骤内的实时子状态，如「等待界面加载」「点击登录按钮」 */
    public volatile String pPhase = "";
    /** 最近一次运行的每一步真实耗时记录（供导出与调整等待时间） */
    public volatile String pTimingReport = "";
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
            o.put("version", CFG_VERSION);
            o.put("appName", "天途");
            o.put("packageName", "");
            o.put("loop", 1);
            o.put("perAccount", true);
            // ---- 9 步流程：每一步都能在悬浮窗上看到，卡在哪一步一眼可见 ----
            JSONArray steps = new JSONArray();

            // 1. 打开游戏（启动后原地等界面加载）
            steps.put(new JSONObject()
                    .put("action", "launch")
                    .put("waitAfter", 9000)
                    .put("note", "按包名启动游戏，等登录界面出现"));

            // 2~4. 登录拆成三段：输入账号 / 输入密码 / 点登录
            steps.put(new JSONObject()
                    .put("action", "inputuser")
                    .put("autoLocate", true)
                    .put("byText", true)
                    .put("userX", 0.5)
                    .put("userY", 0.349)
                    .put("userX2", 0.5)
                    .put("note", "识别账号框并填入账号"));
            steps.put(new JSONObject()
                    .put("action", "inputpass")
                    .put("autoLocate", true)
                    .put("byText", true)
                    .put("passX", 0.5)
                    .put("passY", 0.451)
                    .put("note", "识别密码框并填入密码"));
            steps.put(new JSONObject()
                    .put("action", "loginbtn")
                    .put("autoLocate", true)
                    .put("byText", true)
                    .put("btnX", 0.5)
                    .put("btnY", 0.554)
                    .put("note", "点登录按钮并确认已离开登录界面"));

            // 5. 确定（有的渠道登录后会弹确认框，没有就跳过）
            steps.put(new JSONObject()
                    .put("action", "confirm")
                    .put("ms", 2500)
                    .put("poll", 400)
                    .put("note", "登录后如弹出「确定」就点掉，没有则跳过"));

            // 6. 选服页 -> 进入游戏
            steps.put(new JSONObject()
                    .put("action", "entergame")
                    .put("retry", 4)
                    .put("poll", 700)
                    .put("waitAfter", 1500)
                    .put("note", "在选服页点「进入游戏」"));

            // 7~9. 三个常见弹窗逐个关
            steps.put(new JSONObject()
                    .put("action", "closepopup")
                    .put("label", "奇货居弹窗")
                    .put("ms", 12000)
                    .put("poll", 600)
                    .put("note", "点右上角白色 X 关掉奇货居弹窗"));
            steps.put(new JSONObject()
                    .put("action", "closepopup")
                    .put("label", "离线收益弹窗")
                    .put("ms", 12000)
                    .put("poll", 600)
                    .put("note", "点右上角白色 X 关掉离线收益弹窗"));
            steps.put(new JSONObject()
                    .put("action", "closepopup")
                    .put("label", "域外凶兽弹窗")
                    .put("ms", 12000)
                    .put("poll", 600)
                    .put("note", "点右上角白色 X 关掉域外凶兽弹窗"));

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
        // 【构建 66】服务被系统回收/重启时不要终止脚本。
        // 原来这里把 running 置 false，导致点「进入游戏」时系统重启无障碍服务后，
        // 工作线程直接退出，第 5 步（关弹窗到主界面）永远不执行。
        // 现在只断开单例引用，工作线程继续跑，点击自动走 root tap 兜底。
        instance = null;
        Logger.w("无障碍服务已关闭（脚本继续运行，等待服务重连）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        Logger.w("无障碍服务已销毁（脚本继续运行，等待服务重连）");
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
            // 流程步骤改版：旧配置（5 步）自动升级成新版 9 步，用户不用手动改
            if (o.optInt("version", 1) < CFG_VERSION) {
                JSONObject d = defaultConfig();
                d.put("loop", Math.max(1, o.optInt("loop", 1)));
                d.put("perAccount", o.optBoolean("perAccount", true));
                String oldPkg = o.optString("packageName", "");
                if (oldPkg != null && oldPkg.length() > 0) {
                    d.put("packageName", oldPkg);
                }
                writeText(f, d.toString(2));
                Logger.i("配置已升级到 v" + CFG_VERSION + "（流程改为 9 步）");
                return d;
            }
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
        pTimingReport = "";
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
                        long __t0 = System.currentTimeMillis();
                        execStep(cfg, s, j + 1);
                        long __cost = System.currentTimeMillis() - __t0;
                        pCurElapsedMs = __cost;
                        pCurEnd = System.currentTimeMillis();
                        recordTiming(j + 1, steps.length(), s, __cost);
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
                        long __t0 = System.currentTimeMillis();
                        execStep(cfg, s, j + 1);
                        long __cost = System.currentTimeMillis() - __t0;
                        pCurElapsedMs = __cost;
                        pCurEnd = System.currentTimeMillis();
                        recordTiming(j + 1, steps.length(), s, __cost);
                    }
                }
                Logger.i("账号 " + a.user + " 的流程已走完，开始跑勾选的日常任务");
                try {
                    runDailyTasks();
                } catch (Throwable t) {
                    Logger.w("日常任务异常: " + t);
                }
                Logger.i("账号 " + a.user + " 的日常任务已处理完，准备切下一个账号");
                sleep(800);
            }
            // 【构建 66】流程跑完后再扫一遍弹窗，避免结束后一直停在
            // 奇货居 / 离线收益 / 域外凶兽 这类活动弹窗上（只点右上角 X）
            try {
                for (int pi = 0; pi < 6; pi++) {
                    Thread.sleep(700);
                    if (!dismissPopups()) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
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
     * 取屏幕上真实的输入框控件（返回按 y 从上到下排好序）。
     *
     * 为什么必须有这个方法：颜色法对天途这种「白底登录卡」完全不可靠——
     * 账号框与卡片底色几乎同色，推断出来的 y 常常落到密码框那一行或两框之间的空白，
     * 于是账号被填进密码框，密码又填进同一行，表现就是「只填了密码、账号没填」。
     * 这里直接读控件树的 bounds，坐标就是真实像素，和分辨率 / DPI 无关。
     */
    private java.util.List<UiNode> editableNodes() {
        final java.util.List<UiNode> out = new java.util.ArrayList<UiNode>();
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> all =
                        new java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo>();
                collectNodes(root, all, 0);
                for (android.view.accessibility.AccessibilityNodeInfo n : all) {
                    CharSequence cls = n.getClassName();
                    if (cls == null || cls.toString().indexOf("EditText") < 0) {
                        continue;
                    }
                    android.graphics.Rect r = new android.graphics.Rect();
                    n.getBoundsInScreen(r);
                    if (r.width() <= 0 || r.height() <= 0) {
                        continue;
                    }
                    // 太窄的（搜索小框之类）不算登录框
                    if (r.width() < sW * 0.2) {
                        continue;
                    }
                    UiNode u = new UiNode();
                    u.text = nodeText(n);
                    u.cls = cls.toString();
                    u.left = r.left;
                    u.top = r.top;
                    u.right = r.right;
                    u.bottom = r.bottom;
                    out.add(u);
                }
            }
        } catch (Throwable t) {
            Logger.w("读取输入框控件异常: " + t.getClass().getSimpleName());
        }
        java.util.Collections.sort(out, new java.util.Comparator<UiNode>() {
            @Override
            public int compare(UiNode a, UiNode b) {
                return a.top - b.top;
            }
        });
        return out;
    }

    /** 从界面层级 XML 里挑出输入框，按 y 排序（无障碍树拿不到时的备用通道） */
    private java.util.List<UiNode> editableFromDump() {
        java.util.List<UiNode> out = new java.util.ArrayList<UiNode>();
        for (UiNode n : dumpUi()) {
            if (n.cls != null && n.cls.indexOf("EditText") >= 0
                    && (n.right - n.left) >= sW * 0.2) {
                out.add(n);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<UiNode>() {
            @Override
            public int compare(UiNode a, UiNode b) {
                return a.top - b.top;
            }
        });
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
        if ("inputuser".equals(a) || "inputpass".equals(a)) {
            // 定位 + 等键盘 + 填字 + 回读校验
            return 3500;
        }
        if ("loginbtn".equals(a)) {
            return 4500;
        }
        if ("confirm".equals(a)) {
            return 1200;
        }
        if ("closepopup".equals(a)) {
            return Math.max(1500, s.optInt("ms", 8000));
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
            pCurElapsedMs = 0L;
            pEstMs = cost;
            pCurEnd = 0L;
            pPhase = "";
            Logger.i("[进度] " + pCurName + " | " + note);
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
            case "inputuser": return "输入账号";
            case "inputpass": return "输入密码";
            case "loginbtn": return "登录";
            case "confirm": return "确定";
            case "closepopup": return "关闭弹窗";
            case "entergame": return "点击进入游戏";
            case "closepopups": return "关闭弹窗直到主界面";
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
            case "inputuser": return "定位账号输入框并填入当前账号";
            case "inputpass": return "定位密码输入框并填入当前密码";
            case "loginbtn": return "点登录按钮，确认已离开登录界面";
            case "confirm": return "有「确定」按钮就点，没有则跳过";
            case "closepopup": return "关闭" + s.optString("label", "弹窗");
            case "findimg":
            case "findimage": return "在屏幕上找模板图并点击";
            case "findtext": return "在界面文字里找关键词并点击";
            case "back": return "按一次返回";
            case "entergame": return "在选服页点「进入游戏」";
            case "closepopups": return "奇货居/离线收益等弹窗逐个关到主界面";
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

    /** 能读到这些字样说明已经站在游戏主界面上（弹窗都清干净了） */
    private static final String[] MAIN_UI_MARK =
            {"行囊", "背包", "修仙", "境界", "灵根", "设置", "邮件", "商城", "任务", "好友"};

    /** 关闭按钮关键字（原生弹窗会给按钮起名） */
    private static final String[] CLOSE_KEYS =
            {"关闭", "关 闭", "Close", "close", "取消", "确定", "x", "X"};

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

        // 无障碍层拿不到按钮时，用颜色兜底（公告的「确定」是橙色实心块）。
        // 不再要求先命中 hasMark：天途这类界面把公告正文画在画布里，控件树里
        // 读不到「公告」二字，一旦被 hasMark 挡住，公告就永远点不掉。
        // 安全前提：登录卡还挂在屏幕上时绝不按颜色乱点（否则会把「登录」当「确定」）。
        if (!loginCardStill()) {
            // 【构建 66】优先找右上角关闭按钮 X（奇货居 / 离线收益 / 域外凶兽 共用）。
            // 这类活动弹窗中间是橙色「前往」，按颜色点中部会直接进入活动或卡死，
            // 所以只要识别到 X，就只点关闭按钮，别的位置一律不点。
            try {
                if (shot()) {
                    int[] cp = closeButtonOnFrame();
                    if (cp != null) {
                        Logger.i("弹窗右上角有关闭按钮，只点 X (" + cp[0] + "," + cp[1] + ")");
                        return clickPx(cp[0], cp[1]);
                    }
                }
            } catch (Throwable ignored) {
            }
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
        // 【构建 66】只要屏幕右上角存在关闭按钮 X，就一律不按颜色点中部。
        // 奇货居 / 域外凶兽 弹窗中间的「前往」是橙色实心条，会被误判成公告的
        // 「确定」点掉，结果直接进入对应活动；离线收益中间按钮是灰色，点了无响应会卡死。
        if (closeButtonOnFrame() != null) {
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
     * 进游戏后再守一会儿，专门接住「晚刷出来」的公告/活动弹窗。
     * 刷出来一个就点掉一个；连续 1.2s 没有新弹窗且已等够 2s 就提前结束，
     * 不会把每一步都拖成固定死等。
     */
    private int settlePopups(int maxMs) {
        long t0 = System.currentTimeMillis();
        long end = t0 + Math.max(0, maxMs);
        int n = 0;
        long quiet = 0;
        while (running && System.currentTimeMillis() < end) {
            if (dismissPopups()) {
                n++;
                quiet = 0;
                sleep(400);
            } else {
                quiet += 250;
                if (quiet >= 1200 && System.currentTimeMillis() - t0 >= 2000) {
                    break;
                }
                sleep(250);
            }
        }
        return n;
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
    // =================================================================
    // 进入游戏 + 弹窗清理（构建 60）
    // -----------------------------------------------------------------
    // 通关流程：login（已填账号密码并点登录）
    //        -> entergame（服务器选择页点「进入游戏」）
    //        -> closepopups（奇货居 / 离线收益 / 其它弹窗，逐个关到主界面为止）
    // =================================================================

    /** 点「进入游戏」：先按文字点，再按底部暖色块点，都没命中就重试几次 */
    private void doEnterGame(JSONObject s) {
        int retry = Math.max(1, s.optInt("retry", 4));
        long t0 = System.currentTimeMillis();
        for (int i = 1; i <= retry && running; i++) {
            pPhase = "正在点「进入游戏」(第" + i + "次)";
            if (clickEnterGameOnce()) {
                pPhase = "已进入游戏，等待界面稳定";
                Logger.i("点「进入游戏」成功（第 " + i + " 次尝试，耗时 "
                        + (System.currentTimeMillis() - t0) + " ms）");
                sleep(s.optInt("waitAfter", 1500));
                return;
            }
            sleep(s.optInt("poll", 700));
        }
        Logger.w("没有点到「进入游戏」，继续往下走");
    }

    private boolean clickEnterGameOnce() {
        // 1) 控件文字优先
        if (clickTextKeyword(new String[]{"进入游戏", "进入遊戲", "点击进入", "开始游戏", "进入"})) {
            return true;
        }
        // 2) 底部那条暖色实心横条（选服页的「进入游戏」就是这种块）
        if (shot()) {
            int[] p = warmBarInBand(0.70, 0.99, 0.16, 0.88);
            if (p != null) {
                Logger.i("按暖色块点「进入游戏」 (" + p[0] + "," + p[1] + ")");
                return clickPx(p[0], p[1]);
            }
        }
        return false;
    }

    /** 在指定纵向区间里找最长的一条暖色横条，返回它的中心点 */
    private int[] warmBarInBand(double y0r, double y1r, double minWr, double maxWr) {
        if (sPix == null) {
            return null;
        }
        int y0 = (int) (sH * y0r);
        int y1 = (int) (sH * y1r);
        int bestY = -1, bestRun = 0, bestCx = 0;
        for (int y = Math.max(0, y0); y < Math.min(sH, y1); y++) {
            int run = 0, sumX = 0, runRow = 0, sumRow = 0;
            for (int x = 0; x < sW; x++) {
                if (isWarm(sPix[y * sW + x])) {
                    run++;
                    sumX += x;
                    if (run > runRow) {
                        runRow = run;
                        sumRow = sumX;
                    }
                } else {
                    run = 0;
                    sumX = 0;
                }
            }
            if (runRow > bestRun) {
                bestRun = runRow;
                bestY = y;
                bestCx = sumRow / Math.max(1, runRow);
            }
        }
        if (bestRun >= sW * minWr && bestRun <= sW * maxWr) {
            return new int[]{bestCx, bestY};
        }
        return null;
    }

    /** 暖色：偏橙 / 金 / 米黄的按钮底色 */
    private boolean isWarm(int c) {
        int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
        return r >= 195 && g >= 140 && b <= 185 && (r - b) >= 55 && r >= g - 12;
    }

    /** 点掉弹窗、直到确认站上主界面为止 */
    private void closePopupsToMain(JSONObject s) {
        int maxMs = Math.max(2000, s.optInt("maxMs", 30000));
        int poll = Math.max(300, s.optInt("poll", 700));
        long t0 = System.currentTimeMillis();
        int closed = 0;
        while (running && System.currentTimeMillis() - t0 < maxMs) {
            pPhase = "确认主界面(已关" + closed + "个弹窗)";
            if (mainUiReady()) {
                pPhase = "已到主界面";
                Logger.i("已进入游戏主界面（关掉 " + closed + " 个弹窗，耗时 "
                        + (System.currentTimeMillis() - t0) + " ms）");
                return;
            }
            if (closeOnePopup(s)) {
                closed++;
                pPhase = "已关闭第" + closed + "个弹窗";
                Logger.i("已关闭第 " + closed + " 个弹窗，继续确认主界面");
                sleep(poll);
            } else {
                sleep(poll);
            }
        }
        Logger.w("等主界面超时（已关 " + closed + " 个弹窗），可能还停在弹窗上");
    }

    /**
     * 判定是否已经在游戏主界面。
     * 顺序：能读到「行囊」等底部标签 -> 视为主界面；
     *       读不到（Unity 画面通常在控件树里没有文字）就看画面：
     *       右上角还有白色 X（弹窗）、或整屏被半透明黑压暗，都不算主界面。
     */
    private boolean mainUiReady() {
        if (textHasAny(MAIN_UI_MARK)) {
            return true;
        }
        if (!shot()) {
            return false;
        }
        if (closeButtonOnFrame() != null) {
            return false;
        }
        return !dimOverlay();
    }

    /** 控件树里能否读到这些字样中的任意一个 */
    private boolean textHasAny(String[] keys) {
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
                if (t == null || t.length() == 0) {
                    continue;
                }
                for (String k : keys) {
                    if (t.contains(k)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 按下文字点一个控件（无障碍树 -> 层级 XML 两条通道） */
    private boolean clickTextKeyword(String[] keys) {
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
                    boolean hit = false;
                    for (String k : keys) {
                        if (t.contains(k)) {
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
                    if (n.isClickable() && n.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        Logger.i("按文字点击「" + t + "」");
                        return true;
                    }
                    Logger.i("按文字点击「" + t + "」(" + r.centerX() + "," + r.centerY() + ")");
                    return clickPx(r.centerX(), r.centerY());
                }
            }
        } catch (Throwable ignored) {
        }
        for (UiNode n : dumpUi()) {
            if (n.text == null || n.text.length() == 0) {
                continue;
            }
            boolean hit = false;
            for (String k : keys) {
                if (n.text.contains(k)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                Logger.i("按文字点击「" + n.text + "」(" + n.cx() + "," + n.cy() + ")");
                return clickPx(n.cx(), n.cy());
            }
        }
        return false;
    }

    /**
     * 关掉当前这个弹窗。
     * 奇货居、离线收益以及其它弹窗的关闭按钮都是右上角同样的白色 X，
     * 所以不区分是哪个弹窗，统一按「白色 X」定位；识别不到才用位置兜底。
     */
    private boolean closeOnePopup(JSONObject s) {
        // 1) 原生弹窗直接给按钮起了名字
        if (clickTextKeyword(CLOSE_KEYS)) {
            return true;
        }
        // 2) 右上角白色 X（奇货居 / 离线收益 / 其它弹窗共用这一种按钮）
        //    老逻辑只取「最大白块」，弹窗插画里的灯笼、十字装饰常被当成按钮，
        //    点下去毫无反应，于是卡在这一步。现在改成：按「像 X + 靠右上」排候选，
        //    最多试 3 个，并且点完回看该位置的白 X 是否真的消失，没消失就换下一个。
        if (shot()) {
            java.util.List<int[]> cands = closeButtonCandidates();
            int tried = 0;
            for (int i = 0; i < cands.size() && running && tried < 3; i++) {
                int[] p = cands.get(i);
                tried++;
                Logger.i("识别到弹窗关闭按钮候选 " + tried + " (" + p[0] + "," + p[1] + ")");
                clickPx(p[0], p[1]);
                sleep(Math.max(250, s.optInt("poll", 700) / 2));
                if (whiteCrossGoneAt(p[0], p[1])) {
                    Logger.i("关闭按钮点中，X 已消失 (" + p[0] + "," + p[1] + ")");
                    return true;
                }
                Logger.w("该候选点点了没反应，换下一个候选");
            }
            if (tried > 0) {
                Logger.w("候选点全试过，X 仍在，继续轮询");
            }
        }
        // 3) 配了模板图就再用模板兜一次
        String tpl = s.optString("tplClose", "");
        if (tpl.length() > 0 && findImage(tpl, 0.82)) {
            return true;
        }
        // 4) 最后兜底：只有在「确实有遮罩」时才按右上角位置点一下，
        //    避免在主界面上乱点右上角误触游戏功能
        if (sPix != null && dimOverlay()) {
            int fx = (int) (sW * 0.905);
            int fy = (int) (sH * 0.215);
            Logger.w("没识别到关闭按钮但屏幕有遮罩，按右上角兜底点 (" + fx + "," + fy + ")");
            return clickPx(fx, fy);
        }
        return false;
    }

    // ============ 分步登录 / 确定 / 逐弹窗关闭（供 9 步流程使用） ============

    /**
     * 分步登录：phase=1 只填账号，2 只填密码，3 只点登录按钮。
     * 拆开是为了让悬浮窗进度能显示「输入账号 → 输入密码 → 登录」，
     * 每一步都有真实耗时，卡在哪一步一眼就能看出来。
     */
    private void doLoginPhase(JSONObject s, int phase) {
        if (curUser == null) {
            curUser = "";
        }
        if (curPass == null) {
            curPass = "";
        }
        if (phase == 1 && curUser.length() == 0) {
            Logger.w("当前没有账号，跳过输入账号");
            return;
        }
        int[] ui = null;
        if (s.optBoolean("autoLocate", true)) {
            int[] waits = {0, 250, 350, 500, 700, 900, 1200};
            for (int i = 0; i < waits.length && running; i++) {
                if (waits[i] > 0) {
                    sleep(waits[i]);
                }
                pPhase = (phase == 3 ? "定位登录按钮" : "定位登录界面")
                        + "(第" + (i + 1) + "次探测)";
                ui = locateLoginUi();
                if (ui != null) {
                    break;
                }
            }
        }
        if (!running) {
            return;
        }
        java.util.List<UiNode> inputs = null;
        if (s.optBoolean("byText", true)) {
            inputs = editableNodes();
            if (inputs.size() < 2) {
                java.util.List<UiNode> alt = editableFromDump();
                if (alt.size() > inputs.size()) {
                    inputs = alt;
                }
            }
        }
        int userX = ui == null ? -1 : ui[0];
        int userY = ui == null ? -1 : ui[1];
        int passX = ui == null ? -1 : ui[2];
        int passY = ui == null ? -1 : ui[3];
        int btnX = ui == null ? -1 : ui[4];
        int btnY = ui == null ? -1 : ui[5];
        if (inputs != null && inputs.size() >= 2) {
            UiNode a = inputs.get(0);
            UiNode b = inputs.get(inputs.size() - 1);
            userX = a.cx();
            userY = a.cy();
            passX = b.cx();
            passY = b.cy();
            Logger.i("控件定位输入框：账号(" + userX + "," + userY + ")"
                    + "  密码(" + passX + "," + passY + ")");
        }

        if (phase == 1) {
            boolean ok = false;
            if (userY > 0) {
                ok = clickPx(userX, userY);
            }
            if (!ok) {
                Logger.i("改用比例坐标点账号框");
                ok = clickRatio((float) s.optDouble("userX", 0.5),
                        (float) s.optDouble("userY", 0.349));
            }
            pPhase = "填写账号";
            waitKeyboard(s.optInt("tapDelay", 900));
            Logger.i("账号填入: " + (typeInto(curUser, false) ? "成功" : "失败"));
            return;
        }
        if (phase == 2) {
            boolean ok = false;
            if (passY > 0) {
                ok = clickPx(passX, passY);
            }
            if (!ok) {
                Logger.i("改用比例坐标点密码框");
                ok = clickRatio((float) s.optDouble("passX", 0.5),
                        (float) s.optDouble("passY", 0.451));
            }
            pPhase = "填写密码";
            waitKeyboard(s.optInt("tapDelay", 900));
            Logger.i("密码填入: " + (typeInto(curPass, true) ? "成功" : "失败"));
            return;
        }

        // phase == 3：点登录按钮，点完必须验证真的离开了登录界面
        boolean loggedIn = false;
        if (btnY > 0) {
            Logger.i("按金色块点登录(" + btnX + "," + btnY + ")");
            clickPx(btnX, btnY);
            loggedIn = waitLoginGone(1200);
        }
        if (!loggedIn && running && btnY > 0) {
            Logger.i("无障碍点击没生效，改用 root 注入点登录");
            clickPxRoot(btnX, btnY);
            loggedIn = waitLoginGone(1500);
        }
        if (!loggedIn && running && clickLoginByText()) {
            loggedIn = waitLoginGone(1500);
        }
        if (!loggedIn && running) {
            Logger.i("改用比例坐标点登录按钮");
            clickRatio((float) s.optDouble("btnX", 0.5),
                    (float) s.optDouble("btnY", 0.554));
            loggedIn = waitLoginGone(1500);
        }
        Logger.i("登录按钮处理完成: " + (loggedIn ? "已离开登录界面" : "未确认离开，继续下一步"));
    }

    /** 点掉「确定」类按钮（渠道确认框），没有就跳过 */
    private void doConfirm(JSONObject s) {
        int cap = Math.max(500, s.optInt("ms", 2500));
        int poll = Math.max(200, s.optInt("poll", 400));
        long t0 = System.currentTimeMillis();
        while (running && System.currentTimeMillis() - t0 < cap) {
            if (clickTextKeyword(POPUP_OK)) {
                Logger.i("已点「确定」类按钮");
                sleep(Math.max(200, s.optInt("waitAfter", 500)));
                return;
            }
            sleep(poll);
        }
        Logger.i("没有「确定」按钮，跳过本步");
    }

    /** 关掉某一个指定弹窗：等出现 -> 点 X -> 确认消失 */
    private void closeNamedPopup(JSONObject s) {
        String label = s.optString("label", "弹窗");
        int cap = Math.max(1500, s.optInt("ms", 12000));
        int poll = Math.max(300, s.optInt("poll", 600));
        long t0 = System.currentTimeMillis();
        while (running && System.currentTimeMillis() - t0 < cap) {
            pPhase = "关闭" + label;
            if (closeOnePopup(s)) {
                sleep(poll);
                Logger.i(label + " 已关闭（耗时 " + (System.currentTimeMillis() - t0) + " ms）");
                pPhase = label + "已关闭";
                return;
            }
            sleep(poll);
        }
        if (running) {
            Logger.w(label + " 等待 " + cap + "ms 没找到关闭按钮，可能没有弹出，继续下一步");
        }
    }

    /** 在当前这一帧像素里找右上角的白色 X，返回中心点（找不到返回 null） */
    private int[] closeButtonOnFrame() {
        if (sPix == null) {
            return null;
        }
        int x0 = (int) (sW * 0.55), x1 = sW - 1;
        int y0 = (int) (sH * 0.04), y1 = (int) (sH * 0.46);
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (w <= 4 || h <= 4) {
            return null;
        }
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int base = (y0 + y) * sW + x0;
            int off = y * w;
            for (int x = 0; x < w; x++) {
                mask[off + x] = isWhiteX(sPix[base + x]);
            }
        }
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int bestArea = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int area = 0, minX = w, maxX = -1, minY = h, maxY = -1;
            while (sp > 0) {
                int cur = stack[--sp];
                int cx = cur % w, cy = cur / w;
                area++;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = cy + dy;
                    if (ny < 0 || ny >= h) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx;
                        if (nx < 0 || nx >= w) {
                            continue;
                        }
                        int ni = ny * w + nx;
                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true;
                            stack[sp++] = ni;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            // 像个小方块的才算按钮：太小是噪点，太大是别的白块，细长的是文字笔画
            if (bw < sW * 0.035 || bw > sW * 0.16) continue;
            if (bh < sW * 0.035 || bh > sW * 0.16) continue;
            if (bw > bh * 2 || bh > bw * 2) continue;
            if (area < bw * bh * 0.15) continue;
            if (area > bestArea) {
                bestArea = area;
                bx0 = minX;
                by0 = minY;
                bx1 = maxX;
                by1 = maxY;
            }
        }
        if (bestArea == 0) {
            return null;
        }
        return new int[]{x0 + (bx0 + bx1) / 2, y0 + (by0 + by1) / 2};
    }

    /**
     * 关闭按钮候选点，越像「右上角的白色 X」排得越前。
     *
     * 打分依据（都是像素特征，不依赖具体游戏）：
     *   1) 位置：越靠右上分越高（弹窗关闭按钮基本都在右上）；
     *   2) 形状：接近正方形，笔画填充率 0.12~0.5（X 是细笔画，实心灯笼填充率高）；
     *   3) 大小：边长在屏宽的 3.5%~13% 之间。
     */
    private java.util.List<int[]> closeButtonCandidates() {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        if (sPix == null) {
            return out;
        }
        int x0 = (int) (sW * 0.45), x1 = sW - 1;
        int y0 = (int) (sH * 0.02), y1 = (int) (sH * 0.45);
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (w <= 4 || h <= 4) {
            return out;
        }
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int base = (y0 + y) * sW + x0;
            int off = y * w;
            for (int x = 0; x < w; x++) {
                mask[off + x] = isWhiteX(sPix[base + x]);
            }
        }
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        java.util.List<double[]> scored = new java.util.ArrayList<double[]>();
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int area = 0, minX = w, maxX = -1, minY = h, maxY = -1;
            while (sp > 0) {
                int cur = stack[--sp];
                int cx = cur % w, cy = cur / w;
                area++;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = cy + dy;
                    if (ny < 0 || ny >= h) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx;
                        if (nx < 0 || nx >= w) {
                            continue;
                        }
                        int ni = ny * w + nx;
                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true;
                            stack[sp++] = ni;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (bw < sW * 0.030 || bw > sW * 0.130) continue;
            if (bh < sW * 0.030 || bh > sW * 0.130) continue;
            if (bw > bh * 1.8 || bh > bw * 1.8) continue;
            double fill = area / (double) (bw * bh);
            if (fill < 0.12 || fill > 0.55) continue;
            int gx = x0 + (minX + maxX) / 2;
            int gy = y0 + (minY + maxY) / 2;
            double nx = gx / (double) sW;       // 0..1 越右越好
            double ny = gy / (double) sH;       // 0..1 越上越好
            double score = nx * 2.2 + (1.0 - ny) * 1.4;
            if (nx > 0.72) score += 0.6;        // 右上角再奖励
            if (ny < 0.18) score += 0.4;
            if (fill >= 0.15 && fill <= 0.40) score += 0.5;   // 细笔画更像 X
            scored.add(new double[]{score, gx, gy});
        }
        // 简单排序：分数高的排前面
        for (int i = 1; i < scored.size(); i++) {
            double[] cur = scored.get(i);
            int j = i - 1;
            while (j >= 0 && scored.get(j)[0] < cur[0]) {
                scored.set(j + 1, scored.get(j));
                j--;
            }
            scored.set(j + 1, cur);
        }
        for (int i = 0; i < scored.size() && i < 5; i++) {
            out.add(new int[]{(int) scored.get(i)[1], (int) scored.get(i)[2]});
        }
        return out;
    }

    /** 点完之后回看：那个位置的白 X 是不是真的消失了 */
    private boolean whiteCrossGoneAt(int x, int y) {
        if (!shot()) {
            return false;
        }
        int r = Math.max(8, (int) (sW * 0.022));
        int white = 0, hit = 0;
        for (int yy = Math.max(0, y - r); yy <= Math.min(sH - 1, y + r); yy++) {
            int base = yy * sW;
            for (int xx = Math.max(0, x - r); xx <= Math.min(sW - 1, x + r); xx++) {
                hit++;
                if (isWhiteX(sPix[base + xx])) {
                    white++;
                }
            }
        }
        return hit > 0 && white * 100 / hit < 3;
    }

    /** 近白、低饱和（关闭按钮的白色 X 笔画） */
    private boolean isWhiteX(int c) {
        int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return mn >= 200 && (mx - mn) <= 34;
    }

    /** 顶部横条是不是被半透明黑遮罩压暗了（弹窗弹出时会有这层遮罩） */
    private boolean dimOverlay() {
        if (sPix == null) {
            return false;
        }
        long sum = 0;
        int n = 0;
        int xa = (int) (sW * 0.06), xb = (int) (sW * 0.94);
        for (int i = 0; i < 3; i++) {
            int y = (int) (sH * (0.04 + i * 0.025));
            if (y < 0 || y >= sH) {
                continue;
            }
            for (int x = xa; x < xb; x += 4) {
                int c = sPix[y * sW + x];
                sum += ((c >> 16 & 0xFF) * 30 + (c >> 8 & 0xFF) * 59 + (c & 0xFF) * 11) / 100;
                n++;
            }
        }
        if (n == 0) {
            return false;
        }
        return (sum / n) < 95;
    }

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
                pPhase = "等待登录界面加载(第" + (i + 1) + "次探测)";
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

        // ---------- 输入框用真实控件坐标（颜色推断只作兜底）----------
        // 这一步是「账号没填、只填了密码」的根因修复：
        // 以前账号 / 密码的 y 全靠颜色横条推断，白底卡上必然算偏，
        // 两个坐标会落到同一行（密码框），于是账号被写进密码框、密码又覆盖一次。
        int userX = (ui == null ? -1 : ui[0]);
        int userY = (ui == null ? -1 : ui[1]);
        int passX = (ui == null ? -1 : ui[2]);
        int passY = (ui == null ? -1 : ui[3]);
        java.util.List<UiNode> inputs = null;
        if (s.optBoolean("byText", true)) {
            inputs = editableNodes();
            if (inputs.size() < 2) {
                java.util.List<UiNode> alt = editableFromDump();
                if (alt.size() > inputs.size()) {
                    inputs = alt;
                }
            }
        }
        if (inputs != null && inputs.size() >= 2) {
            UiNode a = inputs.get(0);
            UiNode b = inputs.get(inputs.size() - 1);
            userX = a.cx();
            userY = a.cy();
            passX = b.cx();
            passY = b.cy();
            Logger.i("控件定位输入框：账号(" + userX + "," + userY + ")"
                    + "  密码(" + passX + "," + passY + ")");
        } else {
            Logger.w("控件树里没有拿到 2 个输入框，仍按颜色/比例坐标兜底");
        }

        // ---------- 账号框 ----------
        boolean okU = false;
        if (userY > 0) {
            okU = clickPx(userX, userY);
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
        pPhase = "填写账号";
        Logger.i("账号框点击: " + (okU ? "成功" : "失败"));
        waitKeyboard(s.optInt("tapDelay", 900));
        Logger.i("账号填入: " + (typeInto(curUser, false) ? "成功" : "失败"));

        // ---------- 密码框 ----------
        boolean okP = false;
        if (passY > 0) {
            okP = clickPx(passX, passY);
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
        pPhase = "填写密码";
        Logger.i("密码框点击: " + (okP ? "成功" : "失败"));
        waitKeyboard(s.optInt("tapDelay", 900));
        Logger.i("密码填入: " + (typeInto(curPass, true) ? "成功" : "失败"));

        // ---------- 填完核验：账号框里必须真的有账号 ----------
        // 天途这类界面填错框是高频问题，这里直接回读最上面那个输入框，
        // 里面不是本次账号就重新点它补填一次，避免「空着账号去点登录」。
        if (s.optBoolean("byText", true)) {
            java.util.List<UiNode> chk = editableNodes();
            if (chk.size() >= 2) {
                String t0 = chk.get(0).text == null ? "" : chk.get(0).text.trim();
                if (!t0.contains(curUser)) {
                    Logger.w("账号框里没有本账号（读到「" + t0 + "」），重新点账号框补填");
                    clickPx(chk.get(0).cx(), chk.get(0).cy());
                    waitKeyboard(700);
                    typeInto(curUser, false);
                    Logger.i("账号补填完成");
                } else {
                    Logger.i("账号框校验通过");
                }
            }
        }

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

        // 公告/开服活动常常比登录卡晚一两拍才刷出来。
        // 以前 waitEnterGame 一返回就立刻结束流程，公告还挂在屏幕上没人点，
        // 看起来就是「界面刷新出公告、但脚本没有点确定」。这里再守一段。
        int latePopups = settlePopups(s.optInt("popupGrace", 6000));
        if (latePopups > 0) {
            Logger.i("进游戏后又点掉 " + latePopups + " 个公告/活动弹窗");
        }
        Logger.i("登录流程总耗时 " + (System.currentTimeMillis() - tStart) + " ms");
    }

    private void execStep(JSONObject cfg, JSONObject s, int index) {
        String action = s.optString("action", "").trim();
        Logger.i("步骤 " + index + ": " + action);
        pPhase = actionName(action);
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
                    break;
                }
                int after = s.optInt("waitAfter", 0);
                if (after > 0) {
                    pPhase = "等待游戏界面加载";
                    sleep(after);
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
            case "entergame": {
                doEnterGame(s);
                break;
            }
            case "closepopups": {
                closePopupsToMain(s);
                break;
            }
            case "inputuser": {
                doLoginPhase(s, 1);
                break;
            }
            case "inputpass": {
                doLoginPhase(s, 2);
                break;
            }
            case "loginbtn": {
                doLoginPhase(s, 3);
                break;
            }
            case "confirm": {
                doConfirm(s);
                break;
            }
            case "closepopup": {
                closeNamedPopup(s);
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
            if (pCurStart > 0L) {
                pCurElapsedMs = System.currentTimeMillis() - pCurStart;
            }
        }
    }

    /** 记录每一步的真实耗时，并打印到日志（便于据此调整等待时间） */
    private void recordTiming(int stepNo, int total, JSONObject s, long costMs) {
        try {
            String action = s == null ? "" : s.optString("action", "").trim();
            String note = s == null ? "" : s.optString("note", s.optString("desc", ""));
            if (note == null || note.isEmpty()) {
                note = actionNote(action, s);
            }
            String line = "第 " + stepNo + "/" + total + " 步 " + actionName(action)
                    + (note.isEmpty() ? "" : "（" + note + "）")
                    + " 实际耗时 " + fmtSec(costMs);
            Logger.i("[耗时] " + line);
            String rep = pTimingReport;
            if (rep == null) {
                rep = "";
            }
            pTimingReport = rep + line + "\n";
        } catch (Throwable ignored) {
        }
    }

    /** 毫秒 -> 便于阅读的秒数 */
    public static String fmtSec(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    /** 导出最近一次运行的每一步实际耗时（悬浮球长按面板里可查看/复制） */
    public String timingReport() {
        String r = pTimingReport;
        return (r == null || r.isEmpty()) ? "还没有运行记录" : r;
    }

    /** 清空耗时记录 */
    public void clearTiming() {
        pTimingReport = "";
    }

    // ===================== 日常任务：按天途分步流程执行 =====================

    /** 天途流程配置的基准分辨率（所有 config 都按 540x960 抓的坐标） */
    private static final int FLOW_BASE_W = 540;
    private static final int FLOW_BASE_H = 960;

    /** 任务名和流程名对不上的，在这里给个别名 */
    private static final String[][] FLOW_ALIAS = {
            {"建造", "建设"},
            {"仙盟事务", "仙盟事务"},
            {"天宫幻境", "天宫幻境"},
            {"慕仙境", "慕仙境"},
    };

    /** 把内置的识别模板与流程解包到 App 私有目录（tpl/ 与 flows/），只解一次 */
    private void ensureFlowPack() {
        try {
            java.io.File root = BundledFlows.ensure(this);
            java.io.File tpl = new java.io.File(root, "tpl");
            String[] names = tpl.list();
            Logger.i("识别模板就绪: " + (names == null ? 0 : names.length) + " 个");
        } catch (Throwable t) {
            Logger.w("流程资源解包失败: " + t);
        }
    }

    /** 任务名 → 流程文件名；按关键字匹配，匹配不到返回 null */
    private String flowAssetFor(String task) {
        if (task == null || task.length() == 0) {
            return null;
        }
        String key = task.trim();
        for (int i = 0; i < FLOW_ALIAS.length; i++) {
            if (FLOW_ALIAS[i][0].equals(key)) {
                key = FLOW_ALIAS[i][1];
                break;
            }
        }
        try {
            String[] list = new java.io.File(getFilesDir(), "flows").list();
            if (list == null) {
                return null;
            }
            String fuzzy = null;
            for (int i = 0; i < list.length; i++) {
                String fn = list[i];
                int dash = fn.indexOf('-');
                String base = fn.substring(dash + 1).replace(".config.json", "");
                if (base.equals(key)) {
                    return fn;
                }
                if (fuzzy == null && (base.contains(key) || key.contains(base))) {
                    fuzzy = fn;
                }
            }
            return fuzzy;
        } catch (Throwable t) {
            return null;
        }
    }

    private String readFlowFile(String name) {
        try {
            java.io.InputStream in = new java.io.FileInputStream(
                    new java.io.File(new java.io.File(getFilesDir(), "flows"), name));
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在屏幕（或指定区域内）找模板图。region 按 540x960 基准给，传 null 表示全屏。
     * tap=true 时命中就点匹配中心。
     */
    private boolean findImageRegion(String tplName, double threshold, int[] region, boolean tap) {
        try {
            java.io.File tf = new java.io.File(getFilesDir(), "tpl/" + tplName);
            if (!tf.exists()) {
                Logger.w("模板不存在: " + tplName);
                return false;
            }
            android.graphics.Bitmap tb = android.graphics.BitmapFactory.decodeFile(tf.getAbsolutePath());
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
            if (!shot() || sPix == null) {
                Logger.w("截图失败，跳过识图");
                return false;
            }
            if (tw > sW || th > sH) {
                Logger.w("模板比屏幕大，跳过: " + tplName);
                return false;
            }
            int[] sg = new int[sW * sH];
            for (int i = 0; i < sPix.length; i++) {
                int c = sPix[i];
                sg[i] = ((c >> 16 & 0xFF) * 77 + (c >> 8 & 0xFF) * 151 + (c & 0xFF) * 28) >> 8;
            }
            int x0 = 0;
            int y0 = 0;
            int x1 = sW - tw;
            int y1 = sH - th;
            if (region != null && region.length >= 4) {
                x0 = Math.max(0, region[0] * sW / FLOW_BASE_W);
                y0 = Math.max(0, region[1] * sH / FLOW_BASE_H);
                x1 = Math.min(sW - tw, (region[0] + region[2]) * sW / FLOW_BASE_W);
                y1 = Math.min(sH - th, (region[1] + region[3]) * sH / FLOW_BASE_H);
            }
            if (x1 < x0) {
                x1 = x0;
            }
            if (y1 < y0) {
                y1 = y0;
            }
            int stepPx = tw > 120 ? 4 : 3;
            double best = -1;
            int bx = 0;
            int by = 0;
            for (int y = y0; y <= y1; y += stepPx) {
                for (int x = x0; x <= x1; x += stepPx) {
                    long sum = 0;
                    int cnt = 0;
                    for (int ty = 0; ty < th; ty += 3) {
                        int srow = (y + ty) * sW + x;
                        int trow = ty * tw;
                        for (int tx = 0; tx < tw; tx += 3) {
                            sum += Math.abs(sg[srow + tx] - tg[trow + tx]);
                            cnt++;
                        }
                    }
                    double score = 1.0 - (sum / (double) Math.max(1, cnt)) / 255.0;
                    if (score > best) {
                        best = score;
                        bx = x;
                        by = y;
                    }
                }
            }
            boolean hitOk = best >= threshold;
            Logger.i("识图 " + tplName + " 相似度 " + String.format("%.3f", best)
                    + (hitOk ? " 命中" : " 未命中"));
            if (hitOk && tap) {
                clickPx(bx + tw / 2, by + th / 2);
            }
            return hitOk;
        } catch (Throwable t) {
            Logger.w("识图异常: " + t);
            return false;
        }
    }

    /** 按 540x960 基准坐标点一下 */
    private void tapFlow(int x, int y, int count, int pressMs) {
        int sx = x * sW / FLOW_BASE_W;
        int sy = y * sH / FLOW_BASE_H;
        for (int i = 0; i < Math.max(1, count); i++) {
            clickPx(sx, sy);
            if (pressMs > 0 && i + 1 < count) {
                sleep(pressMs);
            }
        }
    }

    /** 执行一个流程文件；返回 true 表示正常走到结束 */
    private boolean runFlowAsset(String asset) {
        String json = readFlowFile(asset);
        if (json == null) {
            Logger.w("流程读取失败: " + asset);
            return false;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray arr = root.optJSONArray("steps");
            if (arr == null || arr.length() == 0) {
                Logger.w("流程没有步骤: " + asset);
                return false;
            }
            java.util.HashMap<Integer, org.json.JSONObject> map =
                    new java.util.HashMap<Integer, org.json.JSONObject>();
            int first = Integer.MAX_VALUE;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject st = arr.optJSONObject(i);
                if (st == null) {
                    continue;
                }
                int id = st.optInt("id", i + 1);
                map.put(id, st);
                if (id < first) {
                    first = id;
                }
            }
            int cur = first;
            int guard = 0;
            while (map.containsKey(cur) && guard++ < 600) {
                org.json.JSONObject st = map.get(cur);
                String type = st.optString("type", "");
                int wait = st.optInt("wait", 300);
                int next = cur + 1;
                if ("ifimg".equals(type)) {
                    String tpl = st.optString("tpl", "");
                    double thresh = st.optDouble("thresh", 0.9);
                    boolean tapMatch = st.optBoolean("tap_match", false);
                    int[] region = null;
                    org.json.JSONArray rg = st.optJSONArray("region");
                    if (rg != null && rg.length() >= 4) {
                        region = new int[]{rg.optInt(0), rg.optInt(1), rg.optInt(2), rg.optInt(3)};
                    }
                    boolean hitOk = findImageRegion(tpl, thresh, region, tapMatch);
                    next = hitOk ? st.optInt("true", cur + 1) : st.optInt("false", cur + 1);
                } else if ("click".equals(type)) {
                    tapFlow(st.optInt("x", 0), st.optInt("y", 0),
                            st.optInt("count", 1), st.optInt("press_ms", 25));
                } else if ("goto".equals(type)) {
                    next = st.optInt("to", cur + 1);
                } else if ("swipe".equals(type)) {
                    int x1 = st.optInt("x1", 0) * sW / FLOW_BASE_W;
                    int y1 = st.optInt("y1", 0) * sH / FLOW_BASE_H;
                    int x2 = st.optInt("x2", 0) * sW / FLOW_BASE_W;
                    int y2 = st.optInt("y2", 0) * sH / FLOW_BASE_H;
                    swipePx(x1, y1, x2, y2, st.optInt("dur", 300));
                } else if ("log".equals(type)) {
                    Logger.i("[流程] " + st.optString("desc", ""));
                } else if ("end".equals(type)) {
                    break;
                } else if ("ifcolor".equals(type) || "ifnotcolor".equals(type)) {
                    // 取色判断本轮未做，统一走 true 分支，避免流程卡死
                    next = st.optInt("true", cur + 1);
                } else {
                    next = cur + 1;
                }
                if (wait > 0) {
                    sleep(wait);
                }
                if (Thread.currentThread().isInterrupted()) {
                    Logger.w("流程被中断: " + asset);
                    return false;
                }
                if (next == cur) {
                    // 自我重试的步骤，最多连续跑 60 次，防止死循环
                    guard += 1;
                }
                cur = next;
            }
            return true;
        } catch (Throwable t) {
            Logger.w("流程执行异常 " + asset + ": " + t);
            return false;
        }
    }

    /** 跑用户勾选的日常任务：按关键字逐个匹配流程并执行 */
    private void runDailyTasks() {
        android.content.SharedPreferences sp = getSharedPreferences("daily_sel", MODE_PRIVATE);
        String csv = sp.getString("custom_tasks", "");
        if (csv == null || csv.trim().length() == 0) {
            Logger.i("[日常] 没有勾选任何任务，跳过");
            return;
        }
        Logger.i("[日常] 启动，当前勾选: " + csv);
        ensureFlowPack();
        String[] items = csv.split(",");
        int total = 0;
        int done = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i].trim().length() > 0) {
                total++;
            }
        }
        int idx = 0;
        for (int i = 0; i < items.length; i++) {
            String raw = items[i].trim();
            if (raw.length() == 0) {
                continue;
            }
            idx++;
            String name = raw.substring(raw.lastIndexOf('/') + 1);
            String asset = flowAssetFor(name);
            if (asset == null) {
                Logger.w("[日常] " + name + " 暂无对应流程，跳过");
                continue;
            }
            Logger.i("[日常] 第 " + idx + "/" + total + " 个任务 " + name + " → " + asset);
            long t0 = System.currentTimeMillis();
            // 每个任务开始先回到游戏主界面附近，避免上一步残留弹窗干扰
            boolean okDone = runFlowAsset(asset);
            long cost = System.currentTimeMillis() - t0;
            Logger.i("[日常] " + name + (okDone ? " 完成" : " 中断") + "，耗时 "
                    + (cost / 1000.0) + " 秒");
            if (okDone) {
                done++;
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        Logger.i("[日常] 结束，完成 " + done + "/" + total + " 个任务");
    }


}
