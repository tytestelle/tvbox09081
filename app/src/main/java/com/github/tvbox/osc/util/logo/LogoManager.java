package com.github.tvbox.osc.util.logo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.util.FileLogger;   // 新增
import com.github.tvbox.osc.util.epg.EpgManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LogoManager {
    private static LogoManager instance;
    private Context context;
    private File logoDir;
    private OkHttpClient httpClient;
    private ExecutorService executor = Executors.newFixedThreadPool(3);
    private Map<String, String> m3uLogos = new HashMap<>();
    private List<LogoSource> enabledSources = new ArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int WHITE_BG_TOLERANCE = 55;

    public enum LogoSource { M3U, GITHUB, EPG }

    public static synchronized LogoManager getInstance(Context context) {
        if (instance == null) instance = new LogoManager(context.getApplicationContext());
        return instance;
    }

    private LogoManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build();
        logoDir = new File(context.getFilesDir(), "logos");
        if (!logoDir.exists()) logoDir.mkdirs();
        loadEnabledSources();
    }

    private void loadEnabledSources() {
        SharedPreferences prefs = context.getSharedPreferences("logo_settings", Context.MODE_PRIVATE);
        String json = prefs.getString("enabled_sources", "[\"M3U\",\"GITHUB\",\"EPG\"]");
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            enabledSources.clear();
            for (int i = 0; i < arr.size(); i++) {
                String name = arr.get(i).getAsString();
                try {
                    enabledSources.add(LogoSource.valueOf(name));
                } catch (Exception e) {
                }
            }
        } catch (Exception e) {
            enabledSources.clear();
            enabledSources.add(LogoSource.M3U);
            enabledSources.add(LogoSource.GITHUB);
            enabledSources.add(LogoSource.EPG);
        }
    }

    public void setEnabledSources(List<LogoSource> sources) {
        enabledSources.clear();
        enabledSources.addAll(sources);
        JsonArray arr = new JsonArray();
        for (LogoSource s : sources) arr.add(s.name());
        context.getSharedPreferences("logo_settings", Context.MODE_PRIVATE).edit().putString("enabled_sources", arr.toString()).apply();
    }

    public void updateM3uLogos(Map<String, String> logos) {
        m3uLogos.clear();
        m3uLogos.putAll(logos);
    }

    public void downloadLogo(String channelName, String fallbackUrl, LogoCallback callback) {
        executor.execute(() -> {
            File cacheFile = getCacheFile(channelName);
            if (cacheFile.exists()) {
                ensureTransparentCache(cacheFile);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(cacheFile));
                }
                FileLogger.write("LogoManager", "台标已缓存: " + channelName);
                return;
            }
            Bitmap bitmap = downloadFromSources(channelName, fallbackUrl);
            if (bitmap != null) {
                saveBitmap(bitmap, cacheFile);
                FileLogger.write("LogoManager", "台标下载成功: " + channelName);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(cacheFile));
                }
            } else {
                FileLogger.write("LogoManager", "台标下载失败: " + channelName);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("下载失败"));
                }
            }
        });
    }

    public File getLocalLogo(String channelName) {
        File f = getCacheFile(channelName);
        if (f.exists()) { ensureTransparentCache(f); return f; }
        return null;
    }

    private File getCacheFile(String channelName) {
        String epgId = null;
        try { epgId = EpgManager.getInstance(context).getEpgIdByChannelNameForLogo(channelName); } catch (Exception ignored) { }
        String fileName = epgId != null ? epgId + ".png" : channelName.replaceAll("[^a-zA-Z0-9]", "_") + ".png";
        return new File(logoDir, fileName);
    }

    /**
     * 台标来源严格由“设置 -> 台标来源”决定。
     * XMLTV 模式统一遵循：原始频道名 -> epg_data.json name -> epgid
     * -> XMLTV display-name -> channel id -> icon src。
     * 不再使用 EpgUtil/M3U/fallback 去绕过这条映射链。
     */
    private Bitmap downloadFromSources(String channelName, String fallbackUrl) {
        try {
            String epgId = EpgManager.getInstance(context).getEpgIdByChannelNameForLogo(channelName);
            if (epgId == null || epgId.isEmpty()) {
                FileLogger.write("LogoManager", "严格台标映射失败①: name=[" + channelName + "] 未命中 epg_data.json");
                return null;
            }

            String source = EpgManager.getLogoSource(context);
            if (EpgManager.LOGO_SOURCE_GITHUB.equals(source)) {
                String fileName = epgId + ".png";
                String githubUrl = "https://raw.githubusercontent.com/tytestelle/logo/main/ico/logo/" + fileName;
                Bitmap bitmap = fetchBitmap(githubUrl, false);
                if (bitmap != null) FileLogger.write("LogoManager", "严格台标来源=GitHub: epgid=[" + epgId + "]");
                return bitmap;
            }

            String iconUrl = EpgManager.getInstance(context).getChannelIconUrlForLogo(channelName);
            if (iconUrl == null || iconUrl.isEmpty()) {
                FileLogger.write("LogoManager", "严格台标映射失败②: epgid=[" + epgId + "] 未命中 XMLTV display-name/channel id");
                return null;
            }
            Bitmap bitmap = fetchBitmap(iconUrl, true);
            if (bitmap != null) FileLogger.write("LogoManager", "严格台标来源=EPG: epgid=[" + epgId + "] icon=[" + iconUrl + "]");
            return bitmap;
        } catch (Exception e) {
            FileLogger.write("LogoManager", "严格台标获取失败: " + channelName + " - " + e.getMessage());
            return null;
        }
    }

    private Bitmap fetchBitmap(String url) {
        return fetchBitmap(url, true);
    }

    private Bitmap fetchBitmap(String url, boolean processTransparency) {
        try {
            Request request = new Request.Builder().url(url).build();
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                InputStream is = response.body().byteStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (bitmap != null && processTransparency) bitmap = removeBackground(bitmap);
                return bitmap;
            }
        } catch (Exception e) {
            e.printStackTrace();
            FileLogger.write("LogoManager", "下载图片异常: " + url + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * 强化版背景透明化：只处理“从图片四周连通到达”的背景区域。
     *
     * 与简单的“全图 RGB 距离”不同，这里不会因为台标内部存在白色文字/白色图案
     * 而把这些内容一起抠掉；同时针对 JPG/网页图片常见的纯白、灰白、米白和压缩色差，
     * 从四边自动建立多个背景色簇，再以自适应容差做边缘连通抠除。
     */
    private Bitmap removeBackground(Bitmap src) {
        if (src == null) return null;
        final int w = src.getWidth(), h = src.getHeight();
        Bitmap result = src.getConfig() == Bitmap.Config.ARGB_8888
                ? src.copy(Bitmap.Config.ARGB_8888, true)
                : src.copy(Bitmap.Config.ARGB_8888, true);
        if (result == null) return src;

        final int[] pixels = new int[w * h];
        result.getPixels(pixels, 0, w, 0, 0, w, h);
        final boolean[] seen = new boolean[w * h];
        final java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();

        // 四边密集采样。每 2~4 像素取一个，避免只取几个角导致背景颜色判断失真。
        final int step = Math.max(1, Math.min(4, Math.max(w, h) / 160));
        final java.util.ArrayList<int[]> samples = new java.util.ArrayList<>();
        for (int x = 0; x < w; x += step) {
            addBackgroundSample(pixels[x], samples);
            addBackgroundSample(pixels[(h - 1) * w + x], samples);
        }
        for (int y = 0; y < h; y += step) {
            addBackgroundSample(pixels[y * w], samples);
            addBackgroundSample(pixels[y * w + w - 1], samples);
        }
        addBackgroundSample(pixels[0], samples);
        addBackgroundSample(pixels[w - 1], samples);
        addBackgroundSample(pixels[(h - 1) * w], samples);
        addBackgroundSample(pixels[h * w - 1], samples);

        // 建立背景候选：优先选择接近中性白/灰的边缘颜色，同时允许彩色纯底。
        final java.util.ArrayList<int[]> bgColors = buildBackgroundClusters(samples);
        if (bgColors.isEmpty()) return result;

        // 从四边开始，只把“接近背景”的连通区域透明化。
        // 透明化的颜色距离采用平方距离，避免 sqrt 带来的大量开销。
        for (int x = 0; x < w; x++) {
            queue.add(x);
            if (h > 1) queue.add((h - 1) * w + x);
        }
        for (int y = 1; y < h - 1; y++) {
            queue.add(y * w);
            if (w > 1) queue.add(y * w + w - 1);
        }

        int transparent = 0;
        while (!queue.isEmpty()) {
            int idx = queue.removeFirst();
            if (idx < 0 || idx >= pixels.length || seen[idx]) continue;
            seen[idx] = true;

            int color = pixels[idx];
            if (Color.alpha(color) == 0) {
                enqueueNeighbors(queue, idx, w, h);
                continue;
            }

            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            int best = Integer.MAX_VALUE;
            for (int[] bg : bgColors) {
                int dr = r - bg[0], dg = g - bg[1], db = b - bg[2];
                int d2 = dr * dr + dg * dg + db * db;
                if (d2 < best) best = d2;
            }

            // 根据背景亮度提高白色/JPG压缩边缘的容错。
            int brightness = (r + g + b) / 3;
            int tolerance = (brightness >= 235) ? 58 : (brightness >= 200 ? 48 : 38);
            if (best <= tolerance * tolerance) {
                // 给边缘做轻微 alpha 过渡，消除白边；核心背景直接透明。
                int distance = (int) Math.sqrt(best);
                int alpha = Color.alpha(color);
                if (distance <= tolerance - 8) {
                    pixels[idx] = Color.argb(0, r, g, b);
                } else {
                    int keep = Math.max(0, Math.min(alpha, (distance - (tolerance - 8)) * alpha / 8));
                    pixels[idx] = Color.argb(keep, r, g, b);
                }
                transparent++;
                enqueueNeighbors(queue, idx, w, h);
            }
        }

        result.setPixels(pixels, 0, w, 0, 0, w, h);
        FileLogger.write("LogoManager", "透明化完成: " + w + "x" + h + ", 处理像素=" + transparent);
        return result;
    }

    private void addBackgroundSample(int color, java.util.ArrayList<int[]> samples) {
        if (Color.alpha(color) == 0) return;
        samples.add(new int[]{Color.red(color), Color.green(color), Color.blue(color)});
    }

    private java.util.ArrayList<int[]> buildBackgroundClusters(java.util.ArrayList<int[]> samples) {
        java.util.ArrayList<int[]> clusters = new java.util.ArrayList<>();
        if (samples.isEmpty()) return clusters;

        // 量化到 8 级/通道后计数，让白底 JPG 的微小色差归并到同一背景簇。
        java.util.HashMap<String, int[]> counts = new java.util.HashMap<>();
        for (int[] c : samples) {
            int qr = (c[0] / 8) * 8;
            int qg = (c[1] / 8) * 8;
            int qb = (c[2] / 8) * 8;
            String key = qr + "," + qg + "," + qb;
            int[] v = counts.get(key);
            if (v == null) counts.put(key, new int[]{qr, qg, qb, 1});
            else v[3]++;
        }

        java.util.ArrayList<int[]> ranked = new java.util.ArrayList<>(counts.values());
        java.util.Collections.sort(ranked, (a, b) -> Integer.compare(b[3], a[3]));
        int limit = Math.min(6, ranked.size());
        for (int i = 0; i < limit; i++) clusters.add(new int[]{ranked.get(i)[0], ranked.get(i)[1], ranked.get(i)[2]});
        return clusters;
    }

    private void enqueueNeighbors(java.util.ArrayDeque<Integer> q, int idx, int w, int h) {
        int x = idx % w, y = idx / w;
        if (x > 0) q.add(idx - 1);
        if (x + 1 < w) q.add(idx + 1);
        if (y > 0) q.add(idx - w);
        if (y + 1 < h) q.add(idx + w);
    }

    private void ensureTransparentCache(File file) {
        try {
            Bitmap src = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (src == null) return;
            Bitmap fixed = removeBackground(src);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fixed.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            src.recycle(); fixed.recycle();
        } catch (Exception ignored) { }
    }

    private void saveBitmap(Bitmap bitmap, File file) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
            FileLogger.write("LogoManager", "保存台标失败: " + file.getName());
        }
    }

    public interface LogoCallback {
        void onSuccess(File file);

        void onError(String msg);
    }
}
