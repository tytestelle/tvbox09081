package com.github.tvbox.osc.util.epg;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.text.TextUtils;

import com.github.tvbox.osc.util.FileLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

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
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * XMLTV EPG manager.
 *
 * EPG source of truth is the downloaded XMLTV file in files/epgche/epg.xml.
 * The remote <epg-url>.hash is checked first. Only when the hash changes (or
 * the local XML is missing) is the XML downloaded again. Playback never waits
 * for EPG network I/O: refresh and icon downloads run in background threads.
 *
 * 台标加载策略（顺序回退）：
 *   1) 本地 logos/{epgid}.png 存在 → 直接返回
 *   2) EPG 文件里该 epgid 有 icon 地址 → 下载 → 透明化 → 保存为 {epgid}.png
 *   3) EPG 没有或下载失败 → 到 GitHub 仓库下载 {epgid}.png
 *        （GitHub 仓库本身已是透明 PNG，原样保存，不再透明化）
 *   4) 全部失败 → 返回 null，调用方不显示台标
 *
 * EPG ID 解析策略（按优先级）：
 *   1) assets/epg_data.json 的静态映射；
 *   2) 运行期回填的动态映射 dynamic_epg_ids.json（等价于把新条目写进
 *      epg_data.json，下次启动直接命中）；
 *   3) 仍未命中时用“候选名”去 XMLTV 的 display-name 里做精确匹配，候选规则：
 *        - 第一个候选：原始频道名称本身
 *        - 若名称以“台”结尾且长度 > 1，追加第二个候选：去掉末尾“台”
 *      任一个候选在 XMLTV 命中，就把 原始频道名 → 该候选 回填到
 *      dynamic_epg_ids.json；
 *   4) 全部未命中 → 视为 XMLTV 中确实没有该频道，不做处理。
 *
 * 分组切换自动预热策略：
 *   UI 侧在分组切换时通常会为每个频道调 loadProcessedChannelIcon 加载台标。
 *   本类利用这一入口：凡是 loadProcessedChannelIcon 被调用，如果当前频道
 *   所属的 EPG 分组尚未解析，就把该频道名暂存到 pendingEpgWarmup，150ms
 *   内聚合所有频道名后异步调用一次 loadChannelGroup，解析整个分组的
 *   XMLTV，从而使 getProgramsForChannel 立刻有数据。同时 loadChannelGroup
 *   完成后又反过来为整个分组的频道预热台标，两边互为兜底。
 */
public class EpgManager {
    private static final String TAG = "EpgManager";
    private static final String EPG_DIR_NAME = "epgche";
    private static final String EPG_FILE_NAME = "epg.xml";
    private static final String HASH_FILE_NAME = "epg.hash";
    private static final String LOGO_DIR_NAME = "logos";

    /**
     * 运行期回填的 EPG ID 映射，语义上等价于“往 epg_data.json 里补条目”。
     * assets 里的 epg_data.json 是只读的，无法直接写回，所以用独立文件承载。
     * 静态映射优先级高于动态映射。
     */
    private static final String DYNAMIC_EPG_IDS_FILE = "dynamic_epg_ids.json";

    /** GitHub 台标仓库基础地址（与参考实现保持一致）。 */
    private static final String GITHUB_LOGO_BASE_URL =
            "https://raw.githubusercontent.com/tytestelle/logo/main/ico/logo/";

    /** 分组切换时聚合 EPG 预热的延迟窗口（毫秒）。 */
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

    private volatile String epgUrl;
    private volatile boolean parsing;
    private volatile boolean parsed;
    private volatile boolean refreshRunning;
    private final List<RefreshCallback> pendingRefreshCallbacks = new ArrayList<>();

    // epg_data.json: every variant name maps to one shared epgid.
    private final Map<String, String> nameToEpgId = new HashMap<>();

    // 动态回填映射：频道名 -> 从 XMLTV 里精确匹配到的候选 epgid。
    private final Map<String, String> dynamicEpgIds = new HashMap<>();

    // 分组切换时，由 loadProcessedChannelIcon 收集的待预热频道名。
    private final Set<String> pendingEpgWarmup = Collections.synchronizedSet(new LinkedHashSet<>());

    // Parsed XMLTV indexes. Access is synchronized through parseLock.
    private final Object parseLock = new Object();
    private final Map<String, ChannelInfo> channelsByDisplayName = new HashMap<>();
    private final Map<String, ChannelInfo> channelsById = new HashMap<>();
    private final Map<String, List<EpgProgram>> programsByChannelId = new HashMap<>();
    private final Map<String, List<String>> xmlChannelIdsByEpgId = new HashMap<>();
    private final Map<String, List<EpgProgram>> programsByEpgId = new HashMap<>();
    private final Map<String, String> iconUrlByEpgId = new HashMap<>();
    private final Set<String> loadedEpgIds = new HashSet<>();

    // 保留旧的偏好设置键，兼容外部调用，但新的加载链路不再依赖它。
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

        epgUrl = normalizeEpgUrl(EpgSettings.getEpgUrl(context));
        if (TextUtils.isEmpty(epgUrl)) loadDefaultEpgUrl();
        loadEpgDataMap();
        loadDynamicEpgIds();
    }

    // ======================== epg_data.json ========================
    private void loadEpgDataMap() {
        synchronized (nameToEpgId) {
            nameToEpgId.clear();
            try (InputStream is = context.getAssets().open("epg_data.json")) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray epgs = root.getAsJsonArray("epgs");
                if (epgs == null) return;
                for (int i = 0; i < epgs.size(); i++) {
                    JsonObject item = epgs.get(i).getAsJsonObject();
                    if (!item.has("epgid") || !item.has("name")) continue;
                    String epgid = item.get("epgid").getAsString().trim();
                    String names = item.get("name").getAsString();
                    if (epgid.isEmpty() || names == null) continue;
                    for (String variant : names.split(",")) {
                        String key = variant == null ? "" : variant.trim();
                        if (!key.isEmpty()) nameToEpgId.put(key, epgid);
                    }
                }
                FileLogger.write(TAG, "加载 epg_data.json 成功，共 " + nameToEpgId.size() + " 个频道变种");
            } catch (Exception e) {
                FileLogger.write(TAG, "加载 epg_data.json 失败", e);
            }
        }
    }

    // ======================== 动态 EPG ID 映射 ========================
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
                    if (!key.isEmpty() && !TextUtils.isEmpty(value)) {
                        dynamicEpgIds.put(key, value.trim());
                    }
                }
                FileLogger.write(TAG, "加载动态 EPG 映射成功，共 " + dynamicEpgIds.size() + " 条");
            } catch (Exception e) {
                FileLogger.write(TAG, "加载动态 EPG 映射失败", e);
            }
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
                if (!tmp.renameTo(dynamicEpgIdsFile)) {
                    copyFile(tmp, dynamicEpgIdsFile);
                    tmp.delete();
                }
                FileLogger.write(TAG, "动态 EPG 映射已保存，共 " + dynamicEpgIds.size() + " 条");
            } catch (Exception e) {
                FileLogger.write(TAG, "保存动态 EPG 映射失败", e);
            }
        }
    }

    /**
     * 从频道名推导候选 EPG ID。
     * 规则（按顺序）：
     *   ① 原始频道名称本身；
     *   ② 若名称以“台”结尾且长度 > 1，去掉末尾“台”。
     *
     * 仅在 epg_data.json 与 dynamic_epg_ids.json 均未命中时使用。
     */
    private List<String> deriveEpgIdCandidates(String channelName) {
        List<String> candidates = new ArrayList<>();
        if (TextUtils.isEmpty(channelName)) return candidates;
        String name = channelName.trim();
        if (name.isEmpty()) return candidates;

        // ① 原始名称始终作为候选
        candidates.add(name);

        // ② 末尾带“台”时，去掉“台”作为第二个候选
        if (name.length() > 1 && name.endsWith("台")) {
            String without = name.substring(0, name.length() - 1).trim();
            if (!without.isEmpty() && !without.equals(name)) {
                candidates.add(without);
            }
        }
        return candidates;
    }

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

    // ======================== 默认 EPG URL ========================
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
                if (!url.isEmpty()) {
                    setEpgUrl(url);
                    return;
                }
            }
        } catch (Exception e) {
            FileLogger.write(TAG, "加载默认 EPG URL 失败", e);
        }
    }

    // ======================== HASH + 下载 ========================
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
            @Override protected RefreshResult doInBackground(Void... ignored) {
                return refreshFromHash(url);
            }

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
            if (!epgDir.exists() && !epgDir.mkdirs()) {
                return RefreshResult.error("无法创建 EPG 缓存目录");
            }

            final String hashUrl = url + ".hash";
            FileLogger.write(TAG, "EPG检查开始: url=" + url + ", hash=" + hashUrl);
            String remoteHash = fetchRemoteHash(hashUrl);
            String localHash = readText(localHashFile);
            FileLogger.write(TAG, "EPG hash: remote=" + (TextUtils.isEmpty(remoteHash) ? "<empty>" : remoteHash) + ", local=" + (TextUtils.isEmpty(localHash) ? "<empty>" : localHash));

            if (!TextUtils.isEmpty(remoteHash) && epgFile.exists() && remoteHash.equals(localHash)) {
                FileLogger.write(TAG, "EPG hash 未变化，直接使用本地文件（不解析全量 XML）: " + epgFile.getAbsolutePath());
                return RefreshResult.ok();
            }

            if (TextUtils.isEmpty(remoteHash) && epgFile.exists()) {
                FileLogger.write(TAG, "EPG hash 获取失败，继续使用已有 EPG 本地文件（不全量解析）");
                return RefreshResult.ok();
            }

            FileLogger.write(TAG, "EPG hash 已变化或本地文件不存在，开始下载 XMLTV: " + url);
            File tmp = new File(epgDir, EPG_FILE_NAME + ".tmp");
            downloadXmlToFile(url, tmp);
            if (!tmp.exists() || tmp.length() == 0) return RefreshResult.error("EPG 文件下载为空");

            if (!tmp.renameTo(epgFile)) {
                copyFile(tmp, epgFile);
                tmp.delete();
            }
            if (!TextUtils.isEmpty(remoteHash)) writeTextAtomically(localHashFile, remoteHash);

            synchronized (parseLock) {
                channelsByDisplayName.clear();
                channelsById.clear();
                programsByChannelId.clear();
                programsByEpgId.clear();
                xmlChannelIdsByEpgId.clear();
                iconUrlByEpgId.clear();
                loadedEpgIds.clear();
                parsed = false;
            }
            FileLogger.write(TAG, "EPG 下载成功，等待按频道组懒加载: " + epgFile.getAbsolutePath());
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
        } catch (Exception e) {
            FileLogger.write(TAG, "获取 EPG hash 失败: " + hashUrl, e);
            return "";
        }
    }

    private void downloadXmlToFile(String url, File target) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
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
        if (!epgFile.exists() || epgFile.length() == 0) {
            return RefreshResult.error("没有 EPG 缓存文件");
        }
        return RefreshResult.ok();
    }

    private boolean parseXmlForEpgIds(File file, Set<String> requestedEpgIds) {
        if (requestedEpgIds == null || requestedEpgIds.isEmpty()) return true;
        synchronized (parseLock) {
            try {
                final Map<String, ChannelInfo> newByName = new HashMap<>();
                final Map<String, ChannelInfo> newById = new HashMap<>();
                final Map<String, List<EpgProgram>> newPrograms = new HashMap<>();
                final Map<String, List<String>> idsByEpgId = new HashMap<>();
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
                                String matchedEpgId = null;
                                for (String displayName : displayNames) {
                                    String mappedEpgId = getEpgIdByChannelName(displayName);
                                    if (mappedEpgId != null && requestedEpgIds.contains(mappedEpgId)) {
                                        matchedEpgId = mappedEpgId;
                                        break;
                                    }
                                    if (requestedEpgIds.contains(displayName)) {
                                        matchedEpgId = displayName;
                                        break;
                                    }
                                }
                                if (matchedEpgId != null) {
                                    ChannelInfo info = new ChannelInfo(channelId, matchedEpgId, icon);
                                    newById.put(channelId, info);
                                    List<String> ids = idsByEpgId.get(matchedEpgId);
                                    if (ids == null) {
                                        ids = new ArrayList<>();
                                        idsByEpgId.put(matchedEpgId, ids);
                                    }
                                    if (!ids.contains(channelId)) ids.add(channelId);
                                    ChannelInfo representative = newByName.get(matchedEpgId);
                                    if (representative == null || (TextUtils.isEmpty(representative.iconUrl) && !TextUtils.isEmpty(icon))) {
                                        newByName.put(matchedEpgId, info);
                                    }
                                    wantedXmlChannelIds.add(channelId);
                                    FileLogger.write(TAG, "XMLTV 精确命中: epgid=[" + matchedEpgId
                                            + "] displayName=[" + displayNames + "] -> channel id=[" + channelId + "] icon=[" + icon + "]");
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
                                    if (list == null) {
                                        list = new ArrayList<>();
                                        newPrograms.put(programChannel, list);
                                    }
                                    list.add(new EpgProgram(title, desc, startDate, stopDate));
                                }
                            }
                            programChannel = null;
                            programStart = null;
                            programStop = null;
                            title = "";
                            desc = "";
                        }
                        event = parser.next();
                    }
                }

                final Map<String, List<EpgProgram>> newProgramsByEpgId = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : idsByEpgId.entrySet()) {
                    String epgid = entry.getKey();
                    List<EpgProgram> merged = new ArrayList<>();
                    for (String id : entry.getValue()) {
                        List<EpgProgram> list = newPrograms.get(id);
                        if (list != null) merged.addAll(list);
                    }
                    Collections.sort(merged, (a, b) -> {
                        if (a == null || a.start == null) return 1;
                        if (b == null || b.start == null) return -1;
                        return a.start.compareTo(b.start);
                    });
                    newProgramsByEpgId.put(epgid, merged);
                    FileLogger.write(TAG, "EPG完整汇总: epgid=[" + epgid + "] channelIds=" + entry.getValue()
                            + " programmes=" + merged.size() + " dates=" + buildProgramDateKeys(merged));
                }

                channelsByDisplayName.clear();
                channelsByDisplayName.putAll(newByName);
                channelsById.clear();
                channelsById.putAll(newById);
                programsByChannelId.clear();
                programsByChannelId.putAll(newPrograms);
                programsByEpgId.clear();
                programsByEpgId.putAll(newProgramsByEpgId);
                iconUrlByEpgId.clear();
                for (Map.Entry<String, ChannelInfo> e : newByName.entrySet()) {
                    if (e.getValue() != null && !TextUtils.isEmpty(e.getValue().iconUrl)) iconUrlByEpgId.put(e.getKey(), e.getValue().iconUrl);
                }
                xmlChannelIdsByEpgId.clear();
                for (Map.Entry<String, List<String>> entry : idsByEpgId.entrySet()) {
                    xmlChannelIdsByEpgId.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                loadedEpgIds.clear();
                loadedEpgIds.addAll(requestedEpgIds);
                parsed = !newById.isEmpty() || requestedEpgIds.isEmpty();

                int count = 0;
                for (String epgid : newByName.keySet()) {
                    ChannelInfo info = newByName.get(epgid);
                    if (info == null) continue;
                    List<EpgProgram> list = newPrograms.get(info.channelId);
                    if (list != null) count += list.size();
                }
                FileLogger.write(TAG, "按频道组完整懒解析完成: requested=" + requestedEpgIds.size()
                        + ", channels=" + newById.size() + ", epgid=" + newByName.size()
                        + ", programmes=" + count);
                return true;
            } catch (Exception e) {
                FileLogger.write(TAG, "按频道组解析失败", e);
                return false;
            }
        }
    }

    // ======================== 分组 EPG 预热入口 ========================

    /**
     * 主线程上延迟执行的聚合任务：把短时间内收集到的频道名合并成一次
     * loadChannelGroup 调用，避免 UI 逐个频道触发时把同一分组解析多次。
     */
    private final Runnable epgWarmupTask = new Runnable() {
        @Override
        public void run() {
            List<String> names;
            synchronized (pendingEpgWarmup) {
                if (pendingEpgWarmup.isEmpty()) return;
                names = new ArrayList<>(pendingEpgWarmup);
                pendingEpgWarmup.clear();
            }
            FileLogger.write(TAG, "EPG自动预热触发: 频道数=" + names.size());
            loadChannelGroup(names, null);
        }
    };

    /**
     * 由 loadProcessedChannelIcon 触发的 EPG 预热请求。
     * 仅当该频道所属分组尚未解析时才加入待预热集合。
     */
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

    /**
     * 主动调用：解析整个频道分组的 EPG（分组切换时用）。
     * 解析完成后会为整个分组的频道触发台标预热（不影响 EPG 本身）。
     */
    public void loadChannelGroup(final List<String> channelNames, final Runnable onComplete) {
        if (channelNames == null || channelNames.isEmpty()) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }

        final Set<String> requested = new HashSet<>();
        // 记录“候选 -> 一组原始频道名”，解析成功后用于回填动态映射。
        final Map<String, Set<String>> derivedToOriginal = new HashMap<>();

        for (String name : channelNames) {
            if (TextUtils.isEmpty(name)) continue;
            String epgid = getEpgIdByChannelName(name);
            if (!TextUtils.isEmpty(epgid)) {
                requested.add(epgid);
                continue;
            }
            // epg_data.json 与 dynamic_epg_ids.json 均未命中 → 从频道名推导候选。
            List<String> candidates = deriveEpgIdCandidates(name);
            if (!candidates.isEmpty()) {
                for (String cand : candidates) {
                    requested.add(cand);
                    Set<String> originals = derivedToOriginal.get(cand);
                    if (originals == null) {
                        originals = new HashSet<>();
                        derivedToOriginal.put(cand, originals);
                    }
                    originals.add(name);
                }
                FileLogger.write(TAG, "EPG候选推导: name=[" + name + "] 候选=" + candidates);
            }
        }

        if (requested.isEmpty() || !epgFile.exists() || epgFile.length() == 0) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }

        epgExecutor.execute(() -> {
            final List<String[]> backfill = new ArrayList<>();
            try {
                synchronized (parseLock) {
                    if (!(loadedEpgIds.equals(requested) && parsed)) {
                        parseXmlForEpgIds(epgFile, requested);
                    }
                    // 只有真正命中 XMLTV display-name 的候选才会被回填。
                    for (Map.Entry<String, Set<String>> e : derivedToOriginal.entrySet()) {
                        String candidate = e.getKey();
                        if (xmlChannelIdsByEpgId.containsKey(candidate)) {
                            for (String originalName : e.getValue()) {
                                backfill.add(new String[]{originalName, candidate});
                            }
                        }
                    }
                }

                if (!backfill.isEmpty()) {
                    boolean changed = false;
                    synchronized (dynamicEpgIds) {
                        for (String[] pair : backfill) {
                            String originalName = pair[0];
                            String candidate = pair[1];
                            if (originalName == null || candidate == null) continue;
                            String existing = dynamicEpgIds.get(originalName);
                            if (candidate.equals(existing)) continue;
                            dynamicEpgIds.put(originalName, candidate);
                            changed = true;
                            FileLogger.write(TAG, "EPG映射回填: name=[" + originalName + "] -> epgid=[" + candidate + "]");
                        }
                    }
                    if (changed) saveDynamicEpgIds();
                } else if (!derivedToOriginal.isEmpty()) {
                    FileLogger.write(TAG, "EPG候选未命中XMLTV，不做处理: 候选=" + derivedToOriginal.keySet());
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "当前频道组 EPG 懒加载失败", e);
            } finally {
                if (onComplete != null) mainHandler.post(onComplete);
                // EPG 解析完成后，反过来为整个分组的频道预热台标（不触发 EPG 预热）。
                for (String name : channelNames) {
                    if (TextUtils.isEmpty(name)) continue;
                    doLoadProcessedChannelIcon(name, null);
                }
            }
        });
    }

    public void preloadGroupResources(List<String> channelNames, Runnable onComplete) {
        loadChannelGroup(channelNames, onComplete);
    }

    public List<EpgProgram> getProgramsForChannel(String channelName) {
        List<EpgProgram> result = new ArrayList<>();
        if (TextUtils.isEmpty(channelName)) return result;
        if (!parsed) return result;
        String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) {
            FileLogger.write(TAG, "EPG严格映射失败① 原始频道名未命中 epg_data.json/dynamic_epg_ids.json: name=[" + channelName + "]");
            return result;
        }
        synchronized (parseLock) {
            List<String> ids = xmlChannelIdsByEpgId.get(epgid);
            if (ids == null || ids.isEmpty()) {
                FileLogger.write(TAG, "EPG映射失败② epgid 未精确命中 XMLTV display-name: name=[" + channelName + "] epgid=[" + epgid + "]");
                return result;
            }
            List<EpgProgram> all = programsByEpgId.get(epgid);
            if (all != null) result.addAll(all);
            FileLogger.write(TAG, "EPG严格映射完成: name=[" + channelName + "] -> epgid=[" + epgid
                    + "] -> channel ids=" + ids
                    + " -> 全部programme=" + result.size() + " -> 日期=" + buildProgramDateKeys(result));
        }
        return result;
    }

    public String getEpgIdByChannelNameForLogo(String channelName) {
        return getEpgIdByChannelName(channelName);
    }

    public String getChannelIconUrlForLogo(String channelName) {
        return getChannelIconUrl(channelName);
    }

    /**
     * 只读地返回 EPG 中该频道对应的 icon 地址（不触发下载）。
     */
    public String getChannelIconUrl(String channelName) {
        if (!parsed || TextUtils.isEmpty(channelName)) return "";
        String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) return "";
        synchronized (parseLock) {
            String icon = iconUrlByEpgId.get(epgid);
            return TextUtils.isEmpty(icon) ? "" : icon;
        }
    }

    public File getProcessedChannelIconFile(String channelName) {
        return null;
    }

    // ======================== 台标：本地 → EPG → GitHub ========================

    /**
     * 公开入口：加载台标。同时作为“分组切换时自动预热整个分组 EPG”的触发点——
     * UI 侧在分组切换时为每个频道调它加载台标，此处收集频道名并聚合触发
     * loadChannelGroup，从而让整个分组的节目预告也自动加载。
     */
    public void loadProcessedChannelIcon(final String channelName, final IconCallback callback) {
        // 分组切换时 UI 会为每个频道调这里，顺便请求 EPG 预热。
        requestEpgWarmUp(channelName);
        doLoadProcessedChannelIcon(channelName, callback);
    }

    /**
     * 内部方法：只做台标下载，不触发 EPG 预热。
     * loadChannelGroup 完成后会调这里为整个分组预热台标，形成双向兜底。
     */
    private void doLoadProcessedChannelIcon(final String channelName, final IconCallback callback) {
        if (TextUtils.isEmpty(channelName)) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        final String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) {
            FileLogger.write(TAG, "台标加载失败：频道未在 epg_data.json/dynamic_epg_ids.json 命中: name=[" + channelName + "]");
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        final File target = new File(logoDir, epgid + ".png");

        if (target.exists() && target.length() > 0) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(target));
            return;
        }

        if (!iconInFlight.add(epgid)) {
            if (callback != null) {
                final Runnable[] checker = new Runnable[1];
                checker[0] = () -> {
                    if (target.exists() && target.length() > 0) {
                        callback.onIcon(target);
                    } else if (iconInFlight.contains(epgid)) {
                        mainHandler.postDelayed(checker[0], 120);
                    } else {
                        callback.onIcon(null);
                    }
                };
                mainHandler.postDelayed(checker[0], 120);
            }
            return;
        }

        iconExecutor.execute(() -> {
            File result = null;
            try {
                String epgIconUrl;
                synchronized (parseLock) {
                    epgIconUrl = iconUrlByEpgId.get(epgid);
                }
                if (!TextUtils.isEmpty(epgIconUrl)) {
                    FileLogger.write(TAG, "台标尝试来源=EPG: epgid=[" + epgid + "] url=[" + epgIconUrl + "]");
                    result = downloadEpgIconAndSave(epgid, epgIconUrl, target);
                    if (result != null) {
                        FileLogger.write(TAG, "台标来源命中=EPG: epgid=[" + epgid + "] -> " + target.getAbsolutePath());
                    } else {
                        FileLogger.write(TAG, "EPG 台标下载/处理失败，回退 GitHub: epgid=[" + epgid + "]");
                    }
                } else {
                    FileLogger.write(TAG, "EPG 中无台标地址，直接尝试 GitHub: epgid=[" + epgid + "]");
                }

                if (result == null) {
                    String githubUrl = GITHUB_LOGO_BASE_URL + epgid + ".png";
                    FileLogger.write(TAG, "台标尝试来源=GitHub: epgid=[" + epgid + "] url=[" + githubUrl + "]");
                    result = downloadGithubIconAndSave(epgid, githubUrl, target);
                    if (result != null) {
                        FileLogger.write(TAG, "台标来源命中=GitHub: epgid=[" + epgid + "] -> " + target.getAbsolutePath());
                    }
                }

                if (result == null) {
                    FileLogger.write(TAG, "台标最终未获取: epgid=[" + epgid + "] name=[" + channelName + "]");
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "台标加载异常: epgid=[" + epgid + "]", e);
            } finally {
                iconInFlight.remove(epgid);
                final File finalResult = result;
                if (callback != null) {
                    mainHandler.post(() -> callback.onIcon(finalResult));
                }
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
                if (!response.isSuccessful() || response.body() == null) {
                    FileLogger.write(TAG, "EPG 台标下载失败 HTTP " + response.code() + ": " + url);
                    return null;
                }
                byte[] data = response.body().bytes();
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) {
                    FileLogger.write(TAG, "EPG 台标解码失败: " + url);
                    return null;
                }
                transparent = makeTransparent(bitmap);
                if (transparent == null) {
                    FileLogger.write(TAG, "EPG 台标透明化失败: " + url);
                    return null;
                }

                if (!logoDir.exists() && !logoDir.mkdirs() && !logoDir.isDirectory()) {
                    FileLogger.write(TAG, "无法创建台标目录: " + logoDir.getAbsolutePath());
                    return null;
                }
                tmp = File.createTempFile("logo_", ".png", logoDir);
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    if (!transparent.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new java.io.IOException("PNG 写入失败");
                    }
                    out.flush();
                    try { out.getFD().sync(); } catch (Exception ignored) { }
                }
                if (!tmp.exists() || tmp.length() <= 0) throw new java.io.IOException("临时 PNG 未生成");

                return installTmpFile(tmp, target) ? target : null;
            }
        } catch (Exception e) {
            FileLogger.write(TAG, "EPG 台标下载/处理异常: epgid=[" + epgid + "] url=[" + url + "]", e);
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
                if (!response.isSuccessful() || response.body() == null) {
                    FileLogger.write(TAG, "GitHub 台标下载失败 HTTP " + response.code() + ": " + url);
                    return null;
                }
                byte[] data = response.body().bytes();
                if (data == null || data.length == 0) {
                    FileLogger.write(TAG, "GitHub 台标数据为空: " + url);
                    return null;
                }

                Bitmap probe = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (probe == null) {
                    FileLogger.write(TAG, "GitHub 台标解码校验失败（可能不是图片）: " + url);
                    return null;
                }
                probe.recycle();

                if (!logoDir.exists() && !logoDir.mkdirs() && !logoDir.isDirectory()) {
                    FileLogger.write(TAG, "无法创建台标目录: " + logoDir.getAbsolutePath());
                    return null;
                }
                tmp = File.createTempFile("logo_gh_", ".png", logoDir);
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    out.write(data);
                    out.flush();
                    try { out.getFD().sync(); } catch (Exception ignored) { }
                }
                if (!tmp.exists() || tmp.length() <= 0) throw new java.io.IOException("临时 PNG 未生成");

                FileLogger.write(TAG, "GitHub 台标已下载（跳过透明化）: epgid=[" + epgid + "] size=" + data.length);
                return installTmpFile(tmp, target) ? target : null;
            }
        } catch (Exception e) {
            FileLogger.write(TAG, "GitHub 台标下载异常: epgid=[" + epgid + "] url=[" + url + "]", e);
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
            if (!installed) {
                copyFile(tmp, target);
                installed = target.exists() && target.length() > 0;
            }
            if (!installed) {
                if (movedOld && !target.exists()) backup.renameTo(target);
                FileLogger.write(TAG, "台标安装失败: " + target.getAbsolutePath());
                return false;
            }
            if (backup.exists()) backup.delete();
            if (tmp.exists()) tmp.delete();
            return true;
        } catch (Exception e) {
            FileLogger.write(TAG, "台标安装异常: " + target.getAbsolutePath(), e);
            if (tmp != null && tmp.exists()) tmp.delete();
            return false;
        }
    }

    public interface IconCallback {
        void onIcon(File file);
    }

    public void clearLogoSourceCache() {
        if (!logoDir.exists()) return;
        File[] files = logoDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null) continue;
            try { f.delete(); } catch (Exception ignored) { }
        }
        FileLogger.write(TAG, "台标缓存已清空: " + logoDir.getAbsolutePath());
    }

    // ======================== EPG 日期/节目查询 ========================

    public List<Date> getAvailableDatesForChannel(String channelName) {
        List<Date> result = new ArrayList<>();
        List<EpgProgram> programs = getProgramsForChannel(channelName);
        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        for (EpgProgram p : programs) {
            if (p == null || p.start == null || p.stop == null) continue;
            addAllProgramDates(result, p.start, p.stop, tz);
        }
        Collections.sort(result);
        if (!result.isEmpty()) {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day.setTimeZone(tz);
            StringBuilder dates = new StringBuilder();
            for (Date d : result) { if (dates.length() > 0) dates.append(','); dates.append(day.format(d)); }
            FileLogger.write(TAG, "频道全部EPG日期: name=[" + channelName + "] programmes=" + programs.size() + " dates=" + dates);
        } else {
            FileLogger.write(TAG, "频道无EPG日期: name=[" + channelName + "] programmes=" + programs.size());
        }
        return result;
    }

    private void addAllProgramDates(List<Date> dates, Date start, Date stop, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTime(start);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance(tz);
        end.setTime(new Date(Math.max(start.getTime(), stop.getTime() - 1L)));
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        while (!cal.after(end)) {
            addDate(dates, cal.getTime());
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private void addDate(List<Date> dates, Date value) {
        if (value == null) return;
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String key = day.format(value);
        for (Date d : dates) if (key.equals(day.format(d))) return;
        try {
            Date parsedDay = day.parse(key);
            if (parsedDay != null) dates.add(parsedDay);
        } catch (Exception ignored) { }
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
        startCal.set(Calendar.HOUR_OF_DAY, 0);
        startCal.set(Calendar.MINUTE, 0);
        startCal.set(Calendar.SECOND, 0);
        startCal.set(Calendar.MILLISECOND, 0);
        long dayStart = startCal.getTimeInMillis();
        long dayEnd = dayStart + TimeUnit.DAYS.toMillis(1);

        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p == null || p.start == null || p.stop == null) continue;
            if (p.start.getTime() < dayEnd && p.stop.getTime() > dayStart) result.add(p);
        }
        Collections.sort(result, (a, b) -> a.start.compareTo(b.start));
        SimpleDateFormat dayLog = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayLog.setTimeZone(tz);
        FileLogger.write(TAG, "按日期读取EPG: name=[" + channelName + "] date=[" + dayLog.format(date) + "] programmes=" + result.size());
        return result;
    }

    public boolean isEpgParsed() {
        return parsed && !channelsById.isEmpty();
    }

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

    // ======================== 台标透明化 ========================

    private Bitmap makeTransparent(Bitmap src) {
        if (src == null) return null;
        final int width = src.getWidth(), height = src.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setHasAlpha(true);

        int[] pixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        int transparent = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int spread = max - min;

            if (max >= 235 && spread <= 20) {
                pixels[i] = Color.argb(0, r, g, b); transparent++; continue;
            }
            if (max >= 215 && spread <= 35) {
                pixels[i] = Color.argb(0, r, g, b); transparent++; continue;
            }
            if (max >= 195 && spread <= 50) {
                pixels[i] = Color.argb(0, r, g, b); transparent++; continue;
            }
            if (max >= 175 && spread <= 60) {
                int alpha = (int) (100 + (max - 175) * 120 / 20.0);
                alpha = Math.max(60, Math.min(230, alpha));
                pixels[i] = Color.argb(alpha, r, g, b); transparent++; continue;
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height);
        result.setHasAlpha(true);

        int tl = Color.alpha(pixels[0]);
        int tr = Color.alpha(pixels[width - 1]);
        int bl = Color.alpha(pixels[(height - 1) * width]);
        int br = Color.alpha(pixels[height * width - 1]);
        FileLogger.write(TAG, "台标透明化完成V7: " + width + "x" + height
                + " transparent=" + transparent + " 四角alpha=[" + tl + "," + tr + "," + bl + "," + br + "]");
        return result;
    }

    // ======================== 工具 ========================
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
            while (!c.after(end)) {
                keys.add(day.format(c.getTime()));
                c.add(Calendar.DAY_OF_MONTH, 1);
                if (keys.size() > 31) break;
            }
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
            } catch (Exception second) {
                FileLogger.write(TAG, "时间解析失败: " + value);
                return null;
            }
        }
    }

    private static String readText(File file) {
        if (!file.exists()) return "";
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeTextAtomically(File target, String text) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
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

    private static class ChannelInfo {
        final String channelId;
        final String displayName;
        final String iconUrl;
        ChannelInfo(String channelId, String displayName, String iconUrl) {
            this.channelId = channelId;
            this.displayName = displayName;
            this.iconUrl = iconUrl;
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
