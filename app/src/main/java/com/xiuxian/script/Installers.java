package com.xiuxian.script;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装包库：从 GitHub Releases 自动列出所有历史版本 APK，点一下就能下载。
 *
 * 为什么这样做，而不是把 APK 塞进 App 里：
 *   单个游戏包就 26MB 以上，几个版本塞进去 App 会变成几百 MB，
 *   每次改脚本都要重新打包上传，换手机下载一次要等很久。
 *   放在 Releases 里当「版本库」，App 只读清单，点哪个下哪个，
 *   既省体积又不会丢版本 —— 这正是「换手机随时下载不同版本」的需求。
 *
 * 注意：仓库若是 public，任何人都能读到这个清单；若是 private，
 * 没登录就读不到，需要改成 public 或手动填链接。
 */
public class Installers {

    /** 版本库所在仓库 */
    public static final String OWNER = "XXJBGJ";
    public static final String REPO = "xiuxian-script";

    public static class Item {
        public String tag = "";      // 版本标签，如 v1.0.4
        public String title = "";    // 发布标题
        public String name = "";     // 资产文件名，如 天途-1.0.4.apk
        public String url = "";      // 下载直链
        public long size = 0;        // 字节
        public String published = "";// 发布时间

        public String sizeText() {
            if (size <= 0) {
                return "";
            }
            double mb = size / 1048576.0;
            if (mb >= 1) {
                return String.format(java.util.Locale.US, "%.1f MB", mb);
            }
            return (size / 1024) + " KB";
        }
    }

    public interface Callback {
        void onResult(List<Item> items, String error);
    }

    /**
     * 读取 Releases 清单。网络请求放子线程，回调切回主线程。
     */
    public static void fetch(final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Item> out = new ArrayList<Item>();
                String err = null;
                HttpURLConnection conn = null;
                try {
                    String api = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases";
                    URL u = new URL(api);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "xiuxian-script");
                    int code = conn.getResponseCode();
                    if (code == 404) {
                        err = "仓库不存在或为私有（需登录才能读取）";
                    } else if (code != 200) {
                        err = "GitHub 返回 " + code;
                    } else {
                        InputStream is = conn.getInputStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                        JSONArray arr = new JSONArray(sb.toString());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject rel = arr.getJSONObject(i);
                            String tag = rel.optString("tag_name", "");
                            String title = rel.optString("name", "");
                            String pub = rel.optString("published_at", "");
                            if (pub.length() >= 10) {
                                pub = pub.substring(0, 10);
                            }
                            JSONArray assets = rel.optJSONArray("assets");
                            if (assets == null) {
                                continue;
                            }
                            for (int j = 0; j < assets.length(); j++) {
                                JSONObject a = assets.getJSONObject(j);
                                String nm = a.optString("name", "");
                                if (!nm.toLowerCase().endsWith(".apk")) {
                                    continue;
                                }
                                Item it = new Item();
                                it.tag = tag;
                                it.title = title;
                                it.name = nm;
                                it.url = a.optString("browser_download_url", "");
                                it.size = a.optLong("size", 0);
                                it.published = pub;
                                out.add(it);
                            }
                        }
                    }
                } catch (Throwable t) {
                    err = "网络失败: " + t.getClass().getSimpleName();
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                final List<Item> res = out;
                final String e = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(res, e);
                    }
                });
            }
        }).start();
    }
}
