package com.github.tvbox.osc.util.epg;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.util.FileLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * XMLTV EPG manager —— 严格精确匹配版
 *
 * 【匹配流程（不做任何字符处理）】
 *   1) 原始频道名 → 直接在 epg_data.json 的 name 变体列表里精确查找 → 得到 epgid
 *      （name 是逗号分隔的变体列表，例如
 *       "驚豔成人電影台,驚豔成人電影台 HD,驚豔成人電影台 超清,驚豔成人電影台 高清"）
 *   2) epg_data.json 找不到 → 直接把原始频道名当作 epgid
 *   3) 用 epgid 去 XMLTV 找 <display-name> 精确匹配（trim 后 equals）：
 *      · display-name 精确等于某个 requested epgid   → 命中
 *      · display-name 是 epg_data.json 里的某个变体名，
 *        且其 epgid 也在 requested 里                → 命中
 *   4) 一个 epgid 命中多个 XMLTV channel 时，programmes 全部合并
 *   5) <icon src> 作为台标 url，透明化后保存为 epgid.png
 *
 * 【不做任何名字处理】
 *   不去空格、不去横杠、不转大写、不做归一化、不推导候选
 *
 * 【增量合并】parseXmlForEpgIds 只 merge，不清空已有缓存
 * 【分批台标】每 250ms 只发 2 个下载任务
 */
public class EpgManager {
    private static final String TAG = "EpgManager";
    private static final String EPG_DIR_NAME = "epgche";
    private static final String EPG_FILE_NAME = "epg.xml";
    private static final String HASH_FILE_NAME = "epg.hash";
    private static final String LOGO_DIR_NAME = "logos";

    private static final String DYNAMIC_EPG_IDS_FILE = "dynamic_epg_ids.json";

    private static final String GITHUB_LOGO_BASE_URL =
            "https://raw.githubusercontent.com/tytestelle/logo/main/ico/logo/";

    private static final String EPG_DATA_REMOTE_URL =
            "https://raw.githubusercontent.com/tytestelle/sandiJMYG/main/epg_data/epg_data.json";

    private static final String EPG_DATA_CACHE_FILE = "epg_data_cache.json";
    private static final String EPG_DATA_HASH_FILE = "epg_data_cache.hash";

    private static final long EPG_WARMUP_DELAY_MS = 150L;

    private static EpgManager instance;

    private final Context context;
    private final OkHttpClient httpClient;
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService epgExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> iconInFlight = Collections.synchronizedSet(new HashSet<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File epgDir;
    private final File epgFile;
    private final File localHashFile;
    private final File logoDir;
    private final File dynamicEpgIdsFile;
    private final File epgDataCacheFile;
    private final File epgDataHashFile;

    private volatile String epgUrl;
    private volatile boolean parsed;
    private volatile boolean refreshRunning;
    private final List<RefreshCallback> pendingRefreshCallbacks = new ArrayList<>();

    // ===== 映射表：变体名 → epgid（直接从 epg_data.json 的 name 拆分，不做任何处理） =====
    private final Map<String, String> nameToEpgId = new HashMap<>();
    private final Map<String, String> dynamicEpgIds = new HashMap<>();

    private final Set<String> pendingEpgWarmup = Collections.synchronizedSet(new LinkedHashSet<>());

    // ===== XMLTV 解析后索引 =====
    private final Object parseLock = new Object();
    private final Map<String, List<String>> xmlChannelIdsByEpgId = new HashMap<>();
    private final Map<String, List<EpgProgram>> programsByChannelId = new HashMap<>();
    private final Map<String, List<EpgProgram>> programsByEpgId = new HashMap<>();
    private final Map<String, String> iconUrlByEpgId = new HashMap<>();
    private final Set<String> loadedEpgIds = new HashSet<>();

    private volatile boolean epgDataRefreshRunning = false;
    private volatile int iconWarmupGeneration = 0;

    private static final String LOGO_PREFS = "logo_settings";
    private static final String LOGO_SOURCE_KEY = "xmltv_logo_source";
    public static final String LOGO_SOURCE_EPG = "EPG";
    public static final String LOGO_SOURCE_GITHUB = "GITHUB";

    public static String getLogoSource(Context context) {
        if (context == null) return LOGO_SOURCE_EPG;
        String value = context.getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                .getString(LOGO_SOURCE_KEY, LOGO_SOURCE_EPG);
        return LOGO_SOURCE_GITHUB.equals(value) ? LOGO_SOURCE_GITHUB : LOGO_SOURCE_EPG;
    }

    public static void setLogoSource(Context context, String source) {
        if (context == null) return;
        String value = LOGO_SOURCE_GITHUB.equals(source) ? LOGO_SOURCE_GITHUB : LOGO_SOURCE_EPG;
        context.getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                .edit().putString(LOGO_SOURCE_KEY, value).apply();
    }

    public static synchronized EpgManager getInstance(Context context) {
        if (instance == null) instance = new EpgManager(context.getApplicationContext());
        return instance;
    }

    private EpgManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build();

        epgDir = new File(context.getFilesDir(), EPG_DIR_NAME);
        if (!epgDir.exists()) epgDir.mkdirs();
        epgFile = new File(epgDir, EPG_FILE_NAME);
        localHashFile = new File(epgDir, HASH_FILE_NAME);
        logoDir = new File(context.getFilesDir(), LOGO_DIR_NAME);
        if (!logoDir.exists()) logoDir.mkdirs();
        dynamicEpgIdsFile = new File(context.getFilesDir(), DYNAMIC_EPG_IDS_FILE);
        epgDataCacheFile = new File(context.getFilesDir(), EPG_DATA_CACHE_FILE);
        epgDataHashFile = new File(context.getFilesDir(), EPG_DATA_HASH_FILE);

        epgUrl = normalizeEpgUrl(EpgSettings.getEpgUrl(context));
        if (TextUtils.isEmpty(epgUrl)) loadDefaultEpgUrl();

        loadEpgDataMap();
        loadDynamicEpgIds();

        refreshEpgDataFromRemote();
    }

    // ==================================================================
    // epg_data.json 加载：变体名 → epgid 精确映射
    // ==================================================================
    private void loadEpgDataMap() {
        synchronized (nameToEpgId) {
            nameToEpgId.clear();

            InputStream is = null;
            String source;
            try {
                if (epgDataCacheFile != null && epgDataCacheFile.exists() && epgDataCacheFile.length() > 0) {
                    is = new FileInputStream(epgDataCacheFile);
                    source = "本地缓存";
                } else {
                    is = context.getAssets().open("epg_data.json");
                    source = "内置 assets";
                }
                JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray epgs = root.getAsJsonArray("epgs");
                if (epgs != null) {
                    for (int i = 0; i < epgs.size(); i++) {
                        JsonObject item = epgs.get(i).getAsJsonObject();
                        if (!item.has("epgid") || !item.has("name")) continue;
                        String epgid = item.get("epgid").getAsString().trim();
                        String names = item.get("name").getAsString();
                        if (epgid.isEmpty() || names == null) continue;
                        for (String variant : names.split(",")) {
                            String key = variant == null ? "" : variant.trim();
                            if (key.isEmpty()) continue;
                            nameToEpgId.put(key, epgid);
                        }
                    }
                }
                FileLogger.write(TAG, "加载 epg_data.json 成功[" + source + "]，变体=" + nameToEpgId.size());
            } catch (Exception e) {
                FileLogger.write(TAG, "加载 epg_data.json 失败[" + source + "]，尝试回退 assets", e);
                if (epgDataCacheFile != null && epgDataCacheFile.exists()) {
                    try { epgDataCacheFile.delete(); } catch (Exception ignored) { }
                    try (InputStream fallback = context.getAssets().open("epg_data.json")) {
                        JsonObject root = JsonParser.parseReader(new InputStreamReader(
                                fallback, StandardCharsets.UTF_8)).getAsJsonObject();
                        JsonArray epgs = root.getAsJsonArray("epgs");
                        if (epgs != null) {
                            for (int i = 0; i < epgs.size(); i++) {
                                JsonObject item = epgs.get(i).getAsJsonObject();
                                if (!item.has("epgid") || !item.has("name")) continue;
                                String epgid = item.get("epgid").getAsString().trim();
                                String names = item.get("name").getAsString();
                                for (String variant : names.split(",")) {
                                    String key = variant == null ? "" : variant.trim();
                                    if (!key.isEmpty()) nameToEpgId.put(key, epgid);
                                }
                            }
                        }
                    } catch (Exception ignored) { }
                }
            } finally {
                if (is != null) try { is.close(); } catch (Exception ignored) { }
            }
        }
    }

    // ==================================================================
    // 远程同步 epg_data.json
    // ==================================================================
    public void refreshEpgDataFromRemote() {
        if (epgDataRefreshRunning) return;
        epgDataRefreshRunning = true;
        epgExecutor.execute(() -> {
            try {
                final String hashUrl = EPG_DATA_REMOTE_URL + ".hash";
                String remoteHash = fetchRemoteHash(hashUrl);
                if (TextUtils.isEmpty(remoteHash)) return;
                String localHash = readText(epgDataHashFile);
                if (remoteHash.equals(localHash) && epgDataCacheFile.exists() && epgDataCacheFile.length() > 0) return;
                Request request = new Request.Builder().url(EPG_DATA_REMOTE_URL)
                        .header("Cache-Control", "no-cache").get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    byte[] data = response.body().bytes();
                    if (data == null || data.length == 0) return;
                    JsonObject remoteRoot;
                    try {
                        remoteRoot = JsonParser.parseReader(new InputStreamReader(
                                new ByteArrayInputStream(data), StandardCharsets.UTF_8)).getAsJsonObject();
                    } catch (Exception e) { return; }
                    if (remoteRoot.getAsJsonArray("epgs") == null) return;

                    File tmp = new File(context.getFilesDir(), EPG_DATA_CACHE_FILE + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(data); out.flush();
                        try { out.getFD().sync(); } catch (Exception ignored) { }
                    }
                    if (epgDataCacheFile.exists()) epgDataCacheFile.delete();
                    if (!tmp.renameTo(epgDataCacheFile)) { copyFile(tmp, epgDataCacheFile); tmp.delete(); }
                    writeTextAtomically(epgDataHashFile, remoteHash);
                    FileLogger.write(TAG, "远程 epg_data.json 已更新，size=" + data.length);
                    loadEpgDataMap();
                    pruneDynamicEpgIds();
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "远程 epg_data.json 刷新异常", e);
            } finally {
                epgDataRefreshRunning = false;
            }
        });
    }

    private void pruneDynamicEpgIds() {
        boolean changed = false;
        synchronized (dynamicEpgIds) {
            java.util.Iterator<Map.Entry<String, String>> it = dynamicEpgIds.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                String staticHit;
                synchronized (nameToEpgId) { staticHit = nameToEpgId.get(e.getKey()); }
                if (staticHit != null) { it.remove(); changed = true; }
            }
        }
        if (changed) saveDynamicEpgIds();
    }

    // ==================================================================
    // 动态映射文件
    // ==================================================================
    private void loadDynamicEpgIds() {
        synchronized (dynamicEpgIds) {
            dynamicEpgIds.clear();
            if (!dynamicEpgIdsFile.exists() || dynamicEpgIdsFile.length() == 0) return;
            try (InputStream is = new FileInputStream(dynamicEpgIdsFile)) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isJsonNull()) continue;
                    if (!e.getValue().isJsonPrimitive()) continue;
                    String key = e.getKey().trim();
                    String value = e.getValue().getAsString();
                    if (!key.isEmpty() && !TextUtils.isEmpty(value)) dynamicEpgIds.put(key, value.trim());
                }
            } catch (Exception e) { FileLogger.write(TAG, "加载动态 EPG 映射失败", e); }
        }
    }

    private void saveDynamicEpgIds() {
        synchronized (dynamicEpgIds) {
            try {
                JsonObject root = new JsonObject();
                for (Map.Entry<String, String> e : dynamicEpgIds.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    root.addProperty(e.getKey(), e.getValue());
                }
                File tmp = new File(context.getFilesDir(), DYNAMIC_EPG_IDS_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try { out.getFD().sync(); } catch (Exception ignored) { }
                }
                if (dynamicEpgIdsFile.exists()) dynamicEpgIdsFile.delete();
                if (!tmp.renameTo(dynamicEpgIdsFile)) { copyFile(tmp, dynamicEpgIdsFile); tmp.delete(); }
            } catch (Exception e) { FileLogger.write(TAG, "保存动态 EPG 映射失败", e); }
        }
    }

    /**
     * 从原始频道名找 epgid（不做任何处理）：
     *   1) nameToEpgId（epg_data.json 的 name 变体）精确查
     *   2) dynamicEpgIds 精确查
     *   3) 都没有 → 返回 null
     */
    private String getEpgIdByChannelName(String channelName) {
        if (TextUtils.isEmpty(channelName)) return null;
        synchronized (nameToEpgId) {
            String v = nameToEpgId.get(channelName);
            if (v != null) return v;
        }
        synchronized (dynamicEpgIds) {
            return dynamicEpgIds.get(channelName);
        }
    }

    public String resolveEpgId(String originalChannelName) {
        return getEpgIdByChannelName(originalChannelName);
    }

    // ==================================================================
    // URL 管理
    // ==================================================================
    public synchronized void setEpgUrl(String url) {
        epgUrl = normalizeEpgUrl(url);
        if (!TextUtils.isEmpty(epgUrl)) EpgSettings.saveEpgUrl(context, epgUrl);
    }

    private String normalizeEpgUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        int suffix = value.indexOf('$');
        if (suffix > 0) value = value.substring(0, suffix).trim();
        return value;
    }

    private void loadDefaultEpgUrl() {
        try (InputStream is = context.getAssets().open("configuration.json")) {
            JsonObject config = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject configuration = config.getAsJsonObject("Configuration");
            if (configuration == null || !configuration.has("EPG_URLS")) return;
            String epgUrls = configuration.get("EPG_URLS").getAsString();
            if (TextUtils.isEmpty(epgUrls)) return;
            for (String part : epgUrls.split("\\|\\|")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                int idx = trimmed.lastIndexOf('$');
                String url = idx > 0 ? trimmed.substring(0, idx).trim() : trimmed;
                if (!url.isEmpty()) { setEpgUrl(url); return; }
            }
        } catch (Exception e) { FileLogger.write(TAG, "加载默认 EPG URL 失败", e); }
    }

    // ==================================================================
    // 下载 / 刷新 XMLTV
    // ==================================================================
    public void refreshEpg(RefreshCallback callback) {
        final String url = epgUrl;
        if (TextUtils.isEmpty(url)) {
            if (callback != null) callback.onError("EPG URL 未设置");
            return;
        }
        synchronized (this) {
            if (callback != null) pendingRefreshCallbacks.add(callback);
            if (refreshRunning) return;
            refreshRunning = true;
        }
        new AsyncTask<Void, Void, RefreshResult>() {
            @Override protected RefreshResult doInBackground(Void... ignored) { return refreshFromHash(url); }
            @Override protected void onPostExecute(RefreshResult result) {
                List<RefreshCallback> callbacks;
                synchronized (EpgManager.this) {
                    refreshRunning = false;
                    callbacks = new ArrayList<>(pendingRefreshCallbacks);
                    pendingRefreshCallbacks.clear();
                }
                for (RefreshCallback cb : callbacks) {
                    try {
                        if (result.success) cb.onSuccess();
                        else cb.onError(result.message);
                    } catch (Exception ignored) { }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private RefreshResult refreshFromHash(String url) {
        try {
            if (!epgDir.exists() && !epgDir.mkdirs()) return RefreshResult.error("无法创建 EPG 缓存目录");
            final String hashUrl = url + ".hash";
            String remoteHash = fetchRemoteHash(hashUrl);
            String localHash = readText(localHashFile);

            if (!TextUtils.isEmpty(remoteHash) && epgFile.exists() && remoteHash.equals(localHash)) {
                return RefreshResult.ok();
            }
            if (TextUtils.isEmpty(remoteHash) && epgFile.exists()) return RefreshResult.ok();

            File tmp = new File(epgDir, EPG_FILE_NAME + ".tmp");
            downloadXmlToFile(url, tmp);
            if (!tmp.exists() || tmp.length() == 0) return RefreshResult.error("EPG 文件下载为空");

            if (!tmp.renameTo(epgFile)) { copyFile(tmp, epgFile); tmp.delete(); }
            if (!TextUtils.isEmpty(remoteHash)) writeTextAtomically(localHashFile, remoteHash);

            // XML 文件已更新 → 整体清空缓存
            synchronized (parseLock) {
                xmlChannelIdsByEpgId.clear();
                programsByChannelId.clear();
                programsByEpgId.clear();
                iconUrlByEpgId.clear();
                loadedEpgIds.clear();
                parsed = false;
            }
            FileLogger.write(TAG, "EPG 下载成功，等待按需懒加载");
            return RefreshResult.ok();
        } catch (Exception e) {
            FileLogger.write(TAG, "EPG 刷新异常", e);
            if (epgFile.exists()) return ensureParsed();
            return RefreshResult.error("EPG 刷新失败");
        }
    }

    private String fetchRemoteHash(String hashUrl) {
        try {
            Request request = new Request.Builder().url(hashUrl).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return "";
                return response.body().string().trim();
            }
        } catch (Exception e) { return ""; }
    }

    private void downloadXmlToFile(String url, File target) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("HTTP " + response.code());
            try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
        if (isGzip(target)) {
            File xmlTmp = new File(epgDir, EPG_FILE_NAME + ".unzipped.tmp");
            try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(target));
                 OutputStream out = new FileOutputStream(xmlTmp)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (target.exists()) target.delete();
            if (!xmlTmp.renameTo(target)) copyFile(xmlTmp, target);
            if (xmlTmp.exists()) xmlTmp.delete();
        }
    }

    private boolean isGzip(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            int a = in.read(), b = in.read();
            return a == 0x1f && b == 0x8b;
        }
    }

    private RefreshResult ensureParsed() {
        if (!epgFile.exists() || epgFile.length() == 0) return RefreshResult.error("没有 EPG 缓存文件");
        return RefreshResult.ok();
    }

    // ==================================================================
    // 核心：解析 XMLTV，把匹配到的 channel 归入对应 epgid
    // 匹配规则全部是 trim 后精确匹配，不做任何字符处理
    // ==================================================================
    private boolean parseXmlForEpgIds(File file, Set<String> requestedEpgIds) {
        if (requestedEpgIds == null || requestedEpgIds.isEmpty()) return true;

        synchronized (parseLock) {
            try {
                final Map<String, List<EpgProgram>> newPrograms = new HashMap<>();
                final Map<String, List<String>> idsByEpgId = new HashMap<>();
                final Map<String, String> iconUrlByEpgIdNew = new HashMap<>();
                final Set<String> wantedXmlChannelIds = new HashSet<>();

                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                XmlPullParser parser = factory.newPullParser();
                try (InputStream input = new FileInputStream(file)) {
                    parser.setInput(input, null);
                    String channelId = null;
                    ArrayList<String> displayNames = new ArrayList<>();
                    String icon = null;
                    int event = parser.getEventType();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String tag = parser.getName();
                        if (event == XmlPullParser.START_TAG) {
                            if ("channel".equals(tag)) {
                                channelId = safeText(parser.getAttributeValue(null, "id"));
                                displayNames.clear();
                                icon = null;
                            } else if ("display-name".equals(tag) && channelId != null) {
                                String displayName = safeText(readElementText(parser));
                                if (!displayName.isEmpty()) displayNames.add(displayName);
                            } else if ("icon".equals(tag) && channelId != null && TextUtils.isEmpty(icon)) {
                                icon = safeText(parser.getAttributeValue(null, "src"));
                            }
                        } else if (event == XmlPullParser.END_TAG && "channel".equals(tag)) {
                            if (!TextUtils.isEmpty(channelId)) {
                                String matchedEpgId = matchChannelToEpgId(displayNames, requestedEpgIds);
                                if (matchedEpgId != null) {
                                    List<String> ids = idsByEpgId.get(matchedEpgId);
                                    if (ids == null) { ids = new ArrayList<>(); idsByEpgId.put(matchedEpgId, ids); }
                                    if (!ids.contains(channelId)) ids.add(channelId);
                                    wantedXmlChannelIds.add(channelId);
                                    if (!TextUtils.isEmpty(icon) && !iconUrlByEpgIdNew.containsKey(matchedEpgId)) {
                                        iconUrlByEpgIdNew.put(matchedEpgId, icon);
                                    }
                                    FileLogger.write(TAG, "XMLTV 命中: epgid=[" + matchedEpgId
                                            + "] displayName=" + displayNames + " channelId=[" + channelId + "]");
                                }
                            }
                            channelId = null;
                            displayNames.clear();
                            icon = null;
                        }
                        event = parser.next();
                    }
                }

                parser = factory.newPullParser();
                try (InputStream input = new FileInputStream(file)) {
                    parser.setInput(input, null);
                    String programChannel = null;
                    String programStart = null;
                    String programStop = null;
                    String title = "";
                    String desc = "";
                    int event = parser.getEventType();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String tag = parser.getName();
                        if (event == XmlPullParser.START_TAG) {
                            if ("programme".equals(tag)) {
                                programChannel = safeText(parser.getAttributeValue(null, "channel"));
                                programStart = safeText(parser.getAttributeValue(null, "start"));
                                programStop = safeText(parser.getAttributeValue(null, "stop"));
                                title = "";
                                desc = "";
                            } else if ("title".equals(tag) && programChannel != null) {
                                title = readElementText(parser);
                            } else if ("desc".equals(tag) && programChannel != null) {
                                desc = readElementText(parser);
                            }
                        } else if (event == XmlPullParser.END_TAG && "programme".equals(tag)) {
                            if (!TextUtils.isEmpty(programChannel)
                                    && wantedXmlChannelIds.contains(programChannel)
                                    && !TextUtils.isEmpty(programStart)
                                    && !TextUtils.isEmpty(programStop)) {
                                Date startDate = parseXmltvTime(programStart);
                                Date stopDate = parseXmltvTime(programStop);
                                if (startDate != null && stopDate != null && stopDate.after(startDate)) {
                                    List<EpgProgram> list = newPrograms.get(programChannel);
                                    if (list == null) { list = new ArrayList<>(); newPrograms.put(programChannel, list); }
                                    list.add(new EpgProgram(title, desc, startDate, stopDate));
                                }
                            }
                            programChannel = null; programStart = null; programStop = null;
                            title = ""; desc = "";
                        }
                        event = parser.next();
                    }
                }

                // ===== 增量合并 =====
                for (Map.Entry<String, List<EpgProgram>> e : newPrograms.entrySet()) {
                    programsByChannelId.put(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, List<String>> entry : idsByEpgId.entrySet()) {
                    String epgid = entry.getKey();
                    List<String> existing = xmlChannelIdsByEpgId.get(epgid);
                    if (existing == null) { existing = new ArrayList<>(); xmlChannelIdsByEpgId.put(epgid, existing); }
                    for (String id : entry.getValue()) {
                        if (!existing.contains(id)) existing.add(id);
                    }
                }
                for (Map.Entry<String, List<String>> entry : idsByEpgId.entrySet()) {
                    String epgid = entry.getKey();
                    List<String> allChannelIds = xmlChannelIdsByEpgId.get(epgid);
                    if (allChannelIds == null || allChannelIds.isEmpty()) continue;
                    List<EpgProgram> merged = new ArrayList<>();
                    for (String id : allChannelIds) {
                        List<EpgProgram> list = programsByChannelId.get(id);
                        if (list != null) merged.addAll(list);
                    }
                    Collections.sort(merged, (a, b) -> {
                        if (a == null || a.start == null) return 1;
                        if (b == null || b.start == null) return -1;
                        return a.start.compareTo(b.start);
                    });
                    programsByEpgId.put(epgid, merged);
                    FileLogger.write(TAG, "EPG汇总(增量): epgid=[" + epgid
                            + "] channelIds=" + allChannelIds
                            + " programmes=" + merged.size()
                            + " dates=" + buildProgramDateKeys(merged));
                }
                for (Map.Entry<String, String> e : iconUrlByEpgIdNew.entrySet()) {
                    if (!iconUrlByEpgId.containsKey(e.getKey())) iconUrlByEpgId.put(e.getKey(), e.getValue());
                }
                loadedEpgIds.addAll(requestedEpgIds);
                parsed = !programsByEpgId.isEmpty() || !xmlChannelIdsByEpgId.isEmpty();

                FileLogger.write(TAG, "按需增量解析完成: 请求=" + requestedEpgIds.size()
                        + "，本次命中=" + idsByEpgId.size()
                        + "，累计epgid=" + loadedEpgIds.size()
                        + "，累计节目=" + programsByEpgId.size());
                return true;
            } catch (Exception e) {
                FileLogger.write(TAG, "按需解析失败", e);
                return false;
            }
        }
    }

    /**
     * 对单个 XMLTV channel 的所有 display-name 尝试匹配到 requestedEpgIds 里的某个 epgid。
     * 全部为 trim 后精确匹配，不做任何字符处理。
     */
    private String matchChannelToEpgId(List<String> displayNames, Set<String> requestedEpgIds) {
        if (displayNames == null || displayNames.isEmpty()) return null;

        // 规则 1：display-name 精确等于某个 requested epgid
        for (String dn : displayNames) {
            String dnTrim = dn.trim();
            if (requestedEpgIds.contains(dnTrim)) return dnTrim;
        }

        // 规则 2：display-name 精确等于 epg_data.json 里某变体名，且其 epgid 在 requested 里
        for (String dn : displayNames) {
            String dnTrim = dn.trim();
            String epgid = nameToEpgId.get(dnTrim);
            if (epgid != null && requestedEpgIds.contains(epgid)) return epgid;
        }

        // 规则 3：display-name 精确等于动态映射的 key，且其值在 requested 里
        for (String dn : displayNames) {
            String dnTrim = dn.trim();
            synchronized (dynamicEpgIds) {
                String epgid = dynamicEpgIds.get(dnTrim);
                if (epgid != null && requestedEpgIds.contains(epgid)) return epgid;
            }
        }

        return null;
    }

    // ==================================================================
    // 分组预热
    // ==================================================================
    private final Runnable epgWarmupTask = new Runnable() {
        @Override public void run() {
            List<String> names;
            synchronized (pendingEpgWarmup) {
                if (pendingEpgWarmup.isEmpty()) return;
                names = new ArrayList<>(pendingEpgWarmup);
                pendingEpgWarmup.clear();
            }
            loadChannelGroup(names, null);
        }
    };

    private void requestEpgWarmUp(String channelName) {
        if (TextUtils.isEmpty(channelName)) return;
        if (parsed) {
            String epgid = getEpgIdByChannelName(channelName);
            if (!TextUtils.isEmpty(epgid)) {
                synchronized (parseLock) {
                    if (loadedEpgIds.contains(epgid)) return;
                }
            }
        }
        pendingEpgWarmup.add(channelName);
        mainHandler.removeCallbacks(epgWarmupTask);
        mainHandler.postDelayed(epgWarmupTask, EPG_WARMUP_DELAY_MS);
    }

    private void scheduleIconWarmup(List<String> channelNames) {
        final int gen = ++iconWarmupGeneration;
        final List<String> list = new ArrayList<>();
        for (String name : channelNames) if (!TextUtils.isEmpty(name)) list.add(name);
        if (list.isEmpty()) return;
        mainHandler.post(new Runnable() {
            int index = 0;
            @Override public void run() {
                if (gen != iconWarmupGeneration) return;
                int end = Math.min(index + 2, list.size());
                for (int i = index; i < end; i++) doLoadProcessedChannelIcon(list.get(i), null);
                index = end;
                if (index < list.size()) mainHandler.postDelayed(this, 250L);
            }
        });
    }

    /**
     * 加载指定频道列表的 EPG。
     * 直接用原始频道名去 nameToEpgId 精确查 epgid；查不到就用原始名当 epgid。
     */
    public void loadChannelGroup(final List<String> channelNames, final Runnable onComplete) {
        if (channelNames == null || channelNames.isEmpty()) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }

        final Set<String> requestedEpgIds = new HashSet<>();
        for (String name : channelNames) {
            if (TextUtils.isEmpty(name)) continue;
            String epgid = getEpgIdByChannelName(name);
            if (TextUtils.isEmpty(epgid)) epgid = name;   // 直接用原始名当 epgid
            requestedEpgIds.add(epgid);
        }

        if (requestedEpgIds.isEmpty() || !epgFile.exists() || epgFile.length() == 0) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }

        epgExecutor.execute(() -> {
            try {
                synchronized (parseLock) {
                    if (!(parsed && loadedEpgIds.containsAll(requestedEpgIds))) {
                        parseXmlForEpgIds(epgFile, requestedEpgIds);
                    }
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "当前频道组 EPG 懒加载失败", e);
            } finally {
                if (onComplete != null) mainHandler.post(onComplete);
                scheduleIconWarmup(channelNames);
            }
        });
    }

    public void preloadGroupResources(List<String> channelNames, Runnable onComplete) {
        loadChannelGroup(channelNames, onComplete);
    }

    // ==================================================================
    // 查询接口
    // ==================================================================
    public List<EpgProgram> getProgramsForChannel(String channelName) {
        List<EpgProgram> result = new ArrayList<>();
        if (TextUtils.isEmpty(channelName) || !parsed) return result;
        String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) epgid = channelName;
        synchronized (parseLock) {
            List<String> ids = xmlChannelIdsByEpgId.get(epgid);
            if (ids == null || ids.isEmpty()) return result;
            List<EpgProgram> all = programsByEpgId.get(epgid);
            if (all != null) result.addAll(all);
        }
        return result;
    }

    public String getEpgIdByChannelNameForLogo(String channelName) {
        String epgid = getEpgIdByChannelName(channelName);
        return TextUtils.isEmpty(epgid) ? channelName : epgid;
    }

    public String getChannelIconUrlForLogo(String channelName) {
        return getChannelIconUrl(channelName);
    }

    public String getChannelIconUrl(String channelName) {
        if (!parsed || TextUtils.isEmpty(channelName)) return "";
        String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) epgid = channelName;
        synchronized (parseLock) {
            String icon = iconUrlByEpgId.get(epgid);
            return TextUtils.isEmpty(icon) ? "" : icon;
        }
    }

    public File getProcessedChannelIconFile(String channelName) {
        return null;
    }

    // ==================================================================
    // 台标：本地 → EPG → GitHub
    // ==================================================================
    public void loadProcessedChannelIcon(final String channelName, final IconCallback callback) {
        requestEpgWarmUp(channelName);
        doLoadProcessedChannelIcon(channelName, callback);
    }

    private void doLoadProcessedChannelIcon(final String channelName, final IconCallback callback) {
        if (TextUtils.isEmpty(channelName)) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        String mapped = getEpgIdByChannelName(channelName);
        final String epgid = TextUtils.isEmpty(mapped) ? channelName : mapped;

        final File target = new File(logoDir, epgid + ".png");

        if (target.exists() && target.length() > 0) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(target));
            return;
        }
        if (!iconInFlight.add(epgid)) {
            if (callback != null) {
                final Runnable[] checker = new Runnable[1];
                checker[0] = () -> {
                    if (target.exists() && target.length() > 0) callback.onIcon(target);
                    else if (iconInFlight.contains(epgid)) mainHandler.postDelayed(checker[0], 120);
                    else callback.onIcon(null);
                };
                mainHandler.postDelayed(checker[0], 120);
            }
            return;
        }

        iconExecutor.execute(() -> {
            File result = null;
            try {
                String epgIconUrl;
                synchronized (parseLock) { epgIconUrl = iconUrlByEpgId.get(epgid); }
                if (!TextUtils.isEmpty(epgIconUrl)) {
                    result = downloadEpgIconAndSave(epgid, epgIconUrl, target);
                }
                if (result == null) {
                    String githubUrl = GITHUB_LOGO_BASE_URL + epgid + ".png";
                    result = downloadGithubIconAndSave(epgid, githubUrl, target);
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "台标加载异常: epgid=[" + epgid + "]", e);
            } finally {
                iconInFlight.remove(epgid);
                final File finalResult = result;
                if (callback != null) mainHandler.post(() -> callback.onIcon(finalResult));
            }
        });
    }

    private File downloadEpgIconAndSave(String epgid, String url, File target) {
        Bitmap bitmap = null;
        Bitmap transparent = null;
        File tmp = null;
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                byte[] data = response.body().bytes();
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return null;
                transparent = makeTransparent(bitmap);
                if (transparent == null) return null;
                if (!logoDir.exists() && !logoDir.mkdirs() && !logoDir.isDirectory()) return null;
                tmp = File.createTempFile("logo_", ".png", logoDir);
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    if (!transparent.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("PNG 写入失败");
                    out.flush();
                    try { out.getFD().sync(); } catch (Exception ignored) { }
                }
                if (!tmp.exists() || tmp.length() <= 0) throw new java.io.IOException("临时 PNG 未生成");
                return installTmpFile(tmp, target) ? target : null;
            }
        } catch (Exception e) {
            if (tmp != null && tmp.exists()) tmp.delete();
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        }
    }

    private File downloadGithubIconAndSave(String epgid, String url, File target) {
        File tmp = null;
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                byte[] data = response.body().bytes();
                if (data == null || data.length == 0) return null;
                Bitmap probe = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (probe == null) return null;
                probe.recycle();
                if (!logoDir.exists() && !logoDir.mkdirs() && !logoDir.isDirectory()) return null;
                tmp = File.createTempFile("logo_gh_", ".png", logoDir);
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    out.write(data); out.flush();
                    try { out.getFD().sync(); } catch (Exception ignored) { }
                }
                if (!tmp.exists() || tmp.length() <= 0) throw new java.io.IOException("临时 PNG 未生成");
                return installTmpFile(tmp, target) ? target : null;
            }
        } catch (Exception e) {
            if (tmp != null && tmp.exists()) tmp.delete();
            return null;
        }
    }

    private boolean installTmpFile(File tmp, File target) {
        try {
            File backup = new File(logoDir, target.getName() + ".bak");
            if (backup.exists()) backup.delete();
            boolean movedOld = target.exists() && target.renameTo(backup);
            boolean installed = tmp.renameTo(target);
            if (!installed) { copyFile(tmp, target); installed = target.exists() && target.length() > 0; }
            if (!installed) { if (movedOld && !target.exists()) backup.renameTo(target); return false; }
            if (backup.exists()) backup.delete();
            if (tmp.exists()) tmp.delete();
            return true;
        } catch (Exception e) {
            if (tmp != null && tmp.exists()) tmp.delete();
            return false;
        }
    }

    public interface IconCallback { void onIcon(File file); }

    public void clearLogoSourceCache() {
        if (!logoDir.exists()) return;
        File[] files = logoDir.listFiles();
        if (files == null) return;
        for (File f : files) { if (f == null) continue; try { f.delete(); } catch (Exception ignored) { } }
    }

    // ==================================================================
    // 日期 / 节目查询
    // ==================================================================
    public List<Date> getAvailableDatesForChannel(String channelName) {
        List<Date> result = new ArrayList<>();
        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p == null || p.start == null || p.stop == null) continue;
            addAllProgramDates(result, p.start, p.stop, tz);
        }
        Collections.sort(result);
        return result;
    }

    private void addAllProgramDates(List<Date> dates, Date start, Date stop, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTime(start);
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance(tz);
        end.setTime(new Date(Math.max(start.getTime(), stop.getTime() - 1L)));
        end.set(Calendar.HOUR_OF_DAY, 0); end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0); end.set(Calendar.MILLISECOND, 0);
        while (!cal.after(end)) { addDate(dates, cal.getTime()); cal.add(Calendar.DAY_OF_MONTH, 1); }
    }

    private void addDate(List<Date> dates, Date value) {
        if (value == null) return;
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String key = day.format(value);
        for (Date d : dates) if (key.equals(day.format(d))) return;
        try { Date parsedDay = day.parse(key); if (parsedDay != null) dates.add(parsedDay); } catch (Exception ignored) { }
    }

    public List<EpgProgram> getAllProgramsForChannel(String channelName) {
        return getProgramsForChannel(channelName);
    }

    public List<EpgProgram> getProgramsForChannelOnDate(String channelName, Date date) {
        List<EpgProgram> result = new ArrayList<>();
        if (date == null) return result;
        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        Calendar startCal = Calendar.getInstance(tz);
        startCal.setTime(date);
        startCal.set(Calendar.HOUR_OF_DAY, 0); startCal.set(Calendar.MINUTE, 0);
        startCal.set(Calendar.SECOND, 0); startCal.set(Calendar.MILLISECOND, 0);
        long dayStart = startCal.getTimeInMillis();
        long dayEnd = dayStart + TimeUnit.DAYS.toMillis(1);
        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p == null || p.start == null || p.stop == null) continue;
            if (p.start.getTime() < dayEnd && p.stop.getTime() > dayStart) result.add(p);
        }
        Collections.sort(result, (a, b) -> a.start.compareTo(b.start));
        return result;
    }

    public boolean isEpgParsed() { return parsed && !programsByEpgId.isEmpty(); }

    public List<Date> getAllAvailableDates() {
        List<Date> result = new ArrayList<>();
        if (!parsed) return result;
        synchronized (parseLock) {
            for (List<EpgProgram> list : programsByChannelId.values()) {
                if (list == null) continue;
                for (EpgProgram p : list) {
                    if (p == null || p.start == null || p.stop == null) continue;
                    addAllProgramDates(result, p.start, p.stop, TimeZone.getTimeZone("GMT+8:00"));
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    public EpgProgram getCurrentProgram(String channelName) {
        Date now = new Date();
        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p.start != null && p.stop != null && !now.before(p.start) && now.before(p.stop)) return p;
        }
        return null;
    }

    public EpgProgram getNextProgram(String channelName) {
        Date now = new Date();
        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p.start != null && p.start.after(now)) return p;
        }
        return null;
    }

    // ==================================================================
    // 台标透明化
    // ==================================================================
    private Bitmap makeTransparent(Bitmap src) {
        if (src == null) return null;
        final int width = src.getWidth(), height = src.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setHasAlpha(true);
        int[] pixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int spread = max - min;
            if (max >= 235 && spread <= 20) { pixels[i] = Color.argb(0, r, g, b); continue; }
            if (max >= 215 && spread <= 35) { pixels[i] = Color.argb(0, r, g, b); continue; }
            if (max >= 195 && spread <= 50) { pixels[i] = Color.argb(0, r, g, b); continue; }
            if (max >= 175 && spread <= 60) {
                int alpha = (int) (100 + (max - 175) * 120 / 20.0);
                alpha = Math.max(60, Math.min(230, alpha));
                pixels[i] = Color.argb(alpha, r, g, b); continue;
            }
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        result.setHasAlpha(true);
        return result;
    }

    // ==================================================================
    // 工具
    // ==================================================================
    private String buildProgramDateKeys(List<EpgProgram> programs) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (programs == null) return "[]";
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        for (EpgProgram p : programs) {
            if (p == null || p.start == null || p.stop == null) continue;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00"));
            c.setTime(p.start);
            Calendar end = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00"));
            end.setTime(p.stop);
            while (!c.after(end)) { keys.add(day.format(c.getTime())); c.add(Calendar.DAY_OF_MONTH, 1); if (keys.size() > 31) break; }
        }
        return keys.toString();
    }

    private static String readElementText(XmlPullParser parser) throws Exception {
        StringBuilder sb = new StringBuilder();
        final int startDepth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == startDepth)) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                String text = parser.getText();
                if (text != null) sb.append(text);
            }
            event = parser.next();
        }
        return sb.toString();
    }

    private static String safeText(String value) { return value == null ? "" : value.trim(); }

    private Date parseXmltvTime(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 14) return null;
        try {
            String main = value.substring(0, 14);
            String zone = value.length() >= 19 ? value.substring(15).trim() : "+0800";
            if (zone.isEmpty()) zone = "+0800";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
            sdf.setLenient(false);
            return sdf.parse(main + " " + zone);
        } catch (Exception first) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                return sdf.parse(value.substring(0, 14));
            } catch (Exception second) { return null; }
        }
    }

    private static String readText(File file) {
        if (!file.exists()) return "";
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8").trim();
        } catch (Exception e) { return ""; }
    }

    private static void writeTextAtomically(File target, String text) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) copyFile(tmp, target);
        if (tmp.exists()) tmp.delete();
    }

    private static void copyFile(File source, File target) throws Exception {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private static class RefreshResult {
        final boolean success;
        final String message;
        private RefreshResult(boolean success, String message) { this.success = success; this.message = message; }
        static RefreshResult ok() { return new RefreshResult(true, ""); }
        static RefreshResult error(String msg) { return new RefreshResult(false, msg); }
    }

    public interface RefreshCallback {
        void onSuccess();
        void onError(String msg);
    }

    public static class EpgProgram {
        public String title, description;
        public Date start, stop;
        public EpgProgram(String title, String description, Date start, Date stop) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.start = start;
            this.stop = stop;
        }
    }
}
