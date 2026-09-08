package com.github.tvbox.osc.util.logo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.util.FileLogger;   // 新增
import com.github.tvbox.osc.util.epg.EpgDataLoader;
import com.github.tvbox.osc.util.EpgUtil;
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
        return f.exists() ? f : null;
    }

    private File getCacheFile(String channelName) {
        String epgId = EpgDataLoader.getEpgId(channelName);
        String fileName = epgId != null ? epgId + ".png" : channelName.replaceAll("[^a-zA-Z0-9]", "_") + ".png";
        return new File(logoDir, fileName);
    }

    private Bitmap downloadFromSources(String channelName, String fallbackUrl) {
        Bitmap result = null;
        for (LogoSource src : enabledSources) {
            switch (src) {
                case M3U:
                    String m3uUrl = m3uLogos.get(channelName);
                    if (m3uUrl != null) result = fetchBitmap(m3uUrl);
                    break;
                case GITHUB:
                    String epgId = EpgDataLoader.getEpgId(channelName);
                    String fileName = epgId != null ? epgId + ".png" : channelName.replaceAll("[^a-zA-Z0-9]", "_") + ".png";
                    String githubUrl = "https://raw.githubusercontent.com/tytestelle/logo/main/ico/logo/" + fileName;
                    result = fetchBitmap(githubUrl);
                    break;
                case EPG:
                    break; // 可扩展
            }
            if (result != null) break;
        }
        // 先使用 epg_data.json 的别名映射；直播源没有 logo 时也能稳定命中酷9式台标。
        if (result == null) {
            try {
                String[] epgInfo = EpgUtil.getEpgInfo(channelName);
                if (epgInfo != null) {
                    if (epgInfo.length > 0 && epgInfo[0] != null && !epgInfo[0].isEmpty()) {
                        result = fetchBitmap(epgInfo[0]);
                    }
                    if (result == null && epgInfo.length > 1 && epgInfo[1] != null && !epgInfo[1].isEmpty()) {
                        result = fetchBitmap("https://raw.githubusercontent.com/tytestelle/logo/main/ico/logo/" + epgInfo[1] + ".png");
                    }
                }
            } catch (Exception ignored) { }
        }
        if (result == null && fallbackUrl != null && !fallbackUrl.isEmpty() && !"false".equalsIgnoreCase(fallbackUrl)) {
            String url = fallbackUrl;
            if (url.contains("{name}")) url = url.replace("{name}", channelName == null ? "" : channelName);
            result = fetchBitmap(url);
        }
        return result;
    }

    private Bitmap fetchBitmap(String url) {
        try {
            Request request = new Request.Builder().url(url).build();
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                InputStream is = response.body().byteStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (bitmap != null) bitmap = removeBackground(bitmap);
                return bitmap;
            }
        } catch (Exception e) {
            e.printStackTrace();
            FileLogger.write("LogoManager", "下载图片异常: " + url + " - " + e.getMessage());
        }
        return null;
    }

    private Bitmap removeBackground(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int bg = pixels[0];
        int tolerance = 30;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            if (Math.abs(Color.red(p) - Color.red(bg)) < tolerance &&
                    Math.abs(Color.green(p) - Color.green(bg)) < tolerance &&
                    Math.abs(Color.blue(p) - Color.blue(bg)) < tolerance) {
                pixels[i] = Color.TRANSPARENT;
            }
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
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
