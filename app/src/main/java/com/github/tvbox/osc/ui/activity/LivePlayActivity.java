package com.github.tvbox.osc.ui.activity;

import static xyz.doikki.videoplayer.util.PlayerUtils.safeTimeMs;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.Epginfo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveDayListGroup;
import com.github.tvbox.osc.bean.LiveEpgDate;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.player.controller.LiveController;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgDateAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSourceAdapter;
import com.github.tvbox.osc.ui.adapter.Ku9GuideChannelAdapter;
import com.github.tvbox.osc.ui.adapter.Ku9GuideDateAdapter;
import com.github.tvbox.osc.ui.adapter.Ku9GuideProgramAdapter;
import com.github.tvbox.osc.ui.adapter.MyEpgAdapter;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileLogger;
import com.github.tvbox.osc.util.epg.EpgManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.QRCodeUtil;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;
import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayActivity extends BaseActivity {
    public static Context context;
    private VideoView mVideoView;

    // ========== 酷9手势与窗口 ==========
    private View gestureOverlay;
    private GestureDetector gestureDetector;
    private LinearLayout llBottomInfoBar;
    private ProgressBar epgProgressBar;
    private TextView tvEpgRemaining;
    private TextView tvCurrentProgramName;
    private TextView tvNextProgramName;
    private TextView tvDesc;
    private boolean isBottomInfoBarShowing = false;
    private static final float GESTURE_EDGE_RATIO = 0.18f;
    private static final long BOTTOM_INFO_SHOW_DURATION = 5000L;
    private final Runnable mEpgProgressRun = new Runnable() { @Override public void run() { updateEpgProgress(); mHandler.postDelayed(this, 30000L); } };
    private final Runnable mHideBottomInfoRun = new Runnable() {
        @Override
        public void run() {
            hideBottomInfoBar();
        }
    };
    private View switchChannelSnapshotOverlay;
    private ImageView switchChannelSnapshotImage;
    private TextView tvChannelInfo;
    private TextView tvTime;
    private TextView tvNetSpeed;
    private TextView tvResolution;
    private LinearLayout tvLeftChannelListLayout;
    // 三列控件
    private TvRecyclerView mSourceListView;
    private TvRecyclerView mChannelGroupView;
    private TvRecyclerView mLiveChannelView;
    private LiveSourceAdapter liveSourceAdapter;
    private LiveChannelGroupAdapter liveChannelGroupAdapter;
    private LiveChannelItemAdapter liveChannelItemAdapter;

    // 酷9节目单独立窗口
    private View ku9ProgramGuide;
    private TvRecyclerView ku9GuideChannelList;
    private TvRecyclerView ku9GuideDateList;
    private TvRecyclerView ku9GuideProgramList;
    private Ku9GuideChannelAdapter ku9GuideChannelAdapter;
    private Ku9GuideDateAdapter ku9GuideDateAdapter;
    private Ku9GuideProgramAdapter ku9GuideProgramAdapter;
    private boolean ku9GuideShowing = false;
    private boolean ku9GuideEpgLoadRequested = false;

    // 直播断线自动重连：仅针对当前频道实例，换台/换源会使旧重连任务失效。
    private static final int LIVE_RECONNECT_MAX_RETRIES = Integer.MAX_VALUE;
    private static final long LIVE_RECONNECT_BASE_DELAY = 1500L;
    private int liveReconnectAttempts = 0;
    private int liveReconnectGeneration = 0;
    private boolean liveReconnectListenerInstalled = false;
    private TextView ku9GuideButton;
    private TextView ku9GuideChannelGroupButton;
    private int ku9GuideChannelFocusPosition = -1;

    private LinearLayout tvRightSettingLayout;
    private TvRecyclerView mSettingGroupView;
    private TvRecyclerView mSettingItemView;
    private LiveSettingGroupAdapter liveSettingGroupAdapter;
    private LiveSettingItemAdapter liveSettingItemAdapter;
    private List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();

    public static int currentChannelGroupIndex = 0;
    private Handler mHandler = new Handler();

    private TextView tvCurrentSourceName;
    private String currentSourceName = "";

    private android.content.BroadcastReceiver liveRefreshReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if ("com.github.tvbox.osc.LIVE_REFRESH".equals(action)) {
                String liveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
                if (!liveUrl.isEmpty()) {
                    Toast.makeText(context, "直播源已更新，重新加载中...", Toast.LENGTH_SHORT).show();
                    refreshLiveChannelListAndPlay("", -1);
                }
            } else if ("com.github.tvbox.osc.EPG_REFRESH".equals(action)) {
                String epgUrl = Hawk.get(HawkConfig.EPG_URL, "");
                if (!epgUrl.isEmpty()) {
                    epgStringAddress = epgUrl;
                    EpgManager.getInstance(LivePlayActivity.this).setEpgUrl(epgUrl);
                    EpgManager.getInstance(LivePlayActivity.this).refreshEpg(new EpgManager.RefreshCallback() {
                        @Override public void onSuccess() {
                            hsEpg.clear();
                            if (liveChannelItemAdapter != null) liveChannelItemAdapter.notifyDataSetChanged();
                            if (ku9GuideChannelAdapter != null) ku9GuideChannelAdapter.notifyDataSetChanged();
                            if (ku9GuideShowing && ku9GuideChannelAdapter != null && ku9GuideDateAdapter != null
                                    && ku9GuideDateAdapter.getSelectedIndex() >= 0) {
                                int cp = Math.max(0, ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex);
                                rebuildKu9GuideDatesForChannel(cp);
                                int di = ku9GuideDateAdapter.getSelectedIndex();
                                if (di < 0) di = 0;
                                if (di < ku9GuideDateAdapter.getItemCount()) {
                                    loadKu9GuidePrograms(cp, ku9GuideDateAdapter.getItem(di).getDateParamVal());
                                }
                            }
                            if (channel_Name != null) {
                                getEpg(new Date());
                                showBottomEpg();
                            }
                            Toast.makeText(context, "EPG已下载并刷新", Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onError(String msg) {
                            Toast.makeText(context, "EPG下载失败: " + msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }
    };

    private int resolutionInfoRetryCount = 0;
    private boolean resolutionInfoPending = false;
    private boolean exitingLivePlay = false;
    private static final long EPG_LOAD_DELAY = 1200L;
    private static final int RESOLUTION_INFO_MAX_RETRY = 10;
    private static final long RESOLUTION_INFO_RETRY_DELAY = 300L;
    private static final long RESOLUTION_INFO_HIDE_DELAY = 3000L;
    private static final String DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}";
    private static final Pattern CATCHUP_TOKEN_PATTERN = Pattern.compile("(\\Q$\\E?\\Q{\\E[^}]*\\Q}\\E)");
    private static final Pattern CATCHUP_TAG_PATTERN = Pattern.compile("\\Q{\\E([^}]*)\\Q}\\E");
    private final Runnable mLoadEpgRun = new Runnable() {
        @Override
        public void run() {
            if (channel_Name != null && liveEpgDateAdapter != null && liveEpgDateAdapter.getSelectedIndex() >= 0) {
                getEpg(new Date());
            }
        }
    };
    private boolean firstLiveEpgLoad = true;

    private List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private int currentLiveChannelIndex = -1;
    private int currentLiveLookBackIndex = -1;
    private int currentLiveChangeSourceTimes = 0;
    private boolean allowLiveSwitchPlayer = true;
    private LiveChannelItem currentLiveChannelItem = null;
    private String pendingLiveRefreshChannelName = null;
    private int pendingLiveRefreshSourceIndex = -1;
    private boolean refreshingLiveChannelList = false;
    private int liveConfigRequestId = 0;
    private LivePlayerManager livePlayerManager = new LivePlayerManager();
    private ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();

    private static LiveChannelItem channel_Name = null;
    private static Hashtable<String, ArrayList<Epginfo>> hsEpg = new Hashtable<>();
    private CountDownTimer countDownTimer;
    private View ll_right_top_loading;
    private View ll_right_top_huikan;
    private View divLoadEpg;
    private View divLoadEpgDivider;
    private View divLoadEpgleft;
    private LinearLayout divEpg;
    RelativeLayout ll_epg;
    TextView tv_channelnum;
    TextView tip_chname;
    TextView tip_epg1;
    TextView tip_epg2;
    TextView tv_srcinfo;
    TextView tv_curepg_left;
    TextView tv_nextepg_left;
    private MyEpgAdapter myAdapter;
    private TextView tv_right_top_tipnetspeed;
    private TextView tv_right_top_channel_name;
    private TextView tv_right_top_epg_name;
    private TextView tv_right_top_type;
    private ImageView iv_circle_bg;
    private TextView tv_shownum;
    private TextView txtNoEpg;
    private ImageView iv_back_bg;

    private ObjectAnimator objectAnimator;
    public String epgStringAddress = "";

    private TvRecyclerView mEpgDateGridView;
    private TvRecyclerView mRightEpgList;
    private LiveEpgDateAdapter liveEpgDateAdapter;
    private LiveEpgAdapter epgListAdapter;

    private List<LiveDayListGroup> liveDayList = new ArrayList<>();

    public static SimpleDateFormat formatDate = new SimpleDateFormat("yyyy-MM-dd");
    public static SimpleDateFormat formatDate1 = new SimpleDateFormat("MM-dd");
    public static String day = formatDate.format(new Date());
    public static Date nowday = new Date();

    private boolean isSHIYI = false;
    private boolean isBack = false;
    private static int shiyi_time_c;
    public static String playUrl;

    private ImageView imgLiveIcon;
    private FrameLayout liveIconNullBg;
    private TextView liveIconNullText;
    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
    private View backcontroller;
    private CountDownTimer countDownTimer3;
    private final int videoWidth = 1920;
    private final int videoHeight = 1080;
    private TextView tv_currentpos;
    private TextView tv_duration;
    private SeekBar sBar;
    private View iv_playpause;
    private View iv_play;
    private boolean show = false;
    private static final int postTimeout = 6000;

    private int selectedChannelNumber = 0;
    private TextView tvSelectedChannel;

    private JsonObject catchup = null;
    private String logoUrl = null;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live_play;
    }

    @Override
    protected void init() {
        try {
            context = this;
            epgStringAddress = getConfiguredEpgAddress();
            EpgManager epgManager = EpgManager.getInstance(this);
            epgManager.setEpgUrl(epgStringAddress);
            if (isXmlEpgAddress(epgStringAddress)) {
                epgManager.refreshEpg(null);
            }

            setLoadSir(findViewById(R.id.live_root));
            mVideoView = findViewById(R.id.mVideoView);
            switchChannelSnapshotOverlay = findViewById(R.id.switchChannelSnapshotOverlay);
            switchChannelSnapshotImage = findViewById(R.id.switchChannelSnapshotImage);

            tvLeftChannelListLayout = findViewById(R.id.tvLeftChannnelListLayout);
            mSourceListView = findViewById(R.id.mSourceListView);
            mChannelGroupView = findViewById(R.id.mGroupGridView);
            mLiveChannelView = findViewById(R.id.mChannelGridView);
            tvRightSettingLayout = findViewById(R.id.tvRightSettingLayout);
            mSettingGroupView = findViewById(R.id.mSettingGroupView);
            mSettingItemView = findViewById(R.id.mSettingItemView);
            tvChannelInfo = findViewById(R.id.tvChannel);
            tvTime = findViewById(R.id.tvTime);
            tvNetSpeed = findViewById(R.id.tvNetSpeed);
            tvResolution = findViewById(R.id.tvResolution);

            tvCurrentSourceName = findViewById(R.id.tv_current_source_name);
            if (tvCurrentSourceName != null) {
                tvCurrentSourceName.setVisibility(View.GONE);
            }

            ku9GuideButton = findViewById(R.id.btn_ku9_program_guide);
            if (ku9GuideButton != null) {
                ku9GuideButton.setOnClickListener(v -> showKu9ProgramGuide());
            }
            ku9GuideChannelGroupButton = findViewById(R.id.btn_ku9_channel_group);
            if (ku9GuideChannelGroupButton != null) {
                ku9GuideChannelGroupButton.setOnClickListener(v -> showChannelListFromGuide());
            }

            tip_chname = findViewById(R.id.tv_channel_bar_name);
            tv_channelnum = findViewById(R.id.tv_channel_bottom_number);
            tip_epg1 = findViewById(R.id.tv_current_program_time);
            tip_epg2 = findViewById(R.id.tv_next_program_time);
            tv_srcinfo = findViewById(R.id.tv_source);
            tv_curepg_left = findViewById(R.id.tv_current_program);
            tv_nextepg_left = findViewById(R.id.tv_next_program);
            ll_epg = findViewById(R.id.ll_epg);
            tv_right_top_channel_name = findViewById(R.id.tv_right_top_channel_name);
            tv_right_top_epg_name = findViewById(R.id.tv_right_top_epg_name);
            iv_circle_bg = findViewById(R.id.iv_circle_bg);
            iv_back_bg = findViewById(R.id.iv_back_bg);
            tv_shownum = findViewById(R.id.tv_shownum);
            txtNoEpg = findViewById(R.id.txtNoEpg);
            ll_right_top_loading = findViewById(R.id.ll_right_top_loading);
            ll_right_top_huikan = findViewById(R.id.ll_right_top_huikan);
            divLoadEpg = findViewById(R.id.divLoadEpg);
            divLoadEpgDivider = findViewById(R.id.divLoadEpgDivider);
            divLoadEpgleft = findViewById(R.id.divLoadEpgleft);
            divEpg = findViewById(R.id.divEPG);

            objectAnimator = ObjectAnimator.ofFloat(iv_circle_bg, "rotation", 360.0f);
            objectAnimator.setDuration(postTimeout);
            objectAnimator.setRepeatCount(-1);
            objectAnimator.start();

            mEpgDateGridView = findViewById(R.id.mEpgDateGridView);
            Hawk.put(HawkConfig.NOW_DATE, formatDate.format(new Date()));
            day = formatDate.format(new Date());
            nowday = new Date();

            mRightEpgList = findViewById(R.id.lv_epg);
            imgLiveIcon = findViewById(R.id.img_live_icon);
            liveIconNullBg = findViewById(R.id.live_icon_null_bg);
            liveIconNullText = findViewById(R.id.live_icon_null_text);
            if (imgLiveIcon != null) imgLiveIcon.setVisibility(View.INVISIBLE);
            if (liveIconNullText != null) liveIconNullText.setVisibility(View.INVISIBLE);
            if (liveIconNullBg != null) liveIconNullBg.setVisibility(View.INVISIBLE);

            sBar = findViewById(R.id.pb_progressbar);
            tv_currentpos = findViewById(R.id.tv_currentpos);
            backcontroller = findViewById(R.id.backcontroller);
            tv_duration = findViewById(R.id.tv_duration);
            iv_playpause = findViewById(R.id.iv_playpause);
            iv_play = findViewById(R.id.iv_play);
            tvSelectedChannel = findViewById(R.id.tv_selected_channel);

            ku9ProgramGuide = findViewById(R.id.ku9_program_guide);
            ku9GuideChannelList = findViewById(R.id.ku9GuideChannelList);
            ku9GuideDateList = findViewById(R.id.ku9GuideDateList);
            ku9GuideProgramList = findViewById(R.id.ku9GuideProgramList);
            initKu9ProgramGuide();
            applyKu9ResponsiveSizing();

            gestureOverlay = findViewById(R.id.gesture_overlay);
            llBottomInfoBar = findViewById(R.id.ll_bottom_info_bar);
            tvCurrentProgramName = findViewById(R.id.tv_current_program_name);
            tvNextProgramName = findViewById(R.id.tv_next_program_name);
            tvDesc = findViewById(R.id.tv_desc);
            epgProgressBar = findViewById(R.id.epgProgressBar);
            tvEpgRemaining = findViewById(R.id.tv_epg_remaining);
            applyKu9ResponsiveSizing();
            initGestureDetector();

            if (show) {
                if (backcontroller != null) backcontroller.setVisibility(View.VISIBLE);
                if (ll_epg != null) ll_epg.setVisibility(View.GONE);
            } else {
                if (backcontroller != null) backcontroller.setVisibility(View.GONE);
                if (ll_epg != null && !isListOrSettingLayoutVisible()) {
                    ll_epg.setVisibility(View.VISIBLE);
                }
            }

            if (iv_play != null) {
                iv_play.setOnClickListener(arg0 -> {
                    if (mVideoView == null) return;
                    mVideoView.start();
                    iv_play.setVisibility(View.INVISIBLE);
                    if (countDownTimer != null) countDownTimer.start();
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                });
            }

            if (iv_playpause != null) {
                iv_playpause.setOnClickListener(arg0 -> {
                    if (mVideoView == null) return;
                    if (mVideoView.isPlaying()) {
                        mVideoView.pause();
                        if (countDownTimer != null) countDownTimer.cancel();
                        iv_play.setVisibility(View.VISIBLE);
                        iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                    } else {
                        mVideoView.start();
                        iv_play.setVisibility(View.INVISIBLE);
                        if (countDownTimer != null) countDownTimer.start();
                        iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                    }
                });
            }

            if (sBar != null) {
                sBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onStopTrackingTouch(SeekBar arg0) {
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar arg0) {
                    }

                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean fromuser) {
                        if (!fromuser) return;
                        if (countDownTimer != null && mVideoView != null) {
                            mVideoView.seekTo(progress);
                            countDownTimer.cancel();
                            countDownTimer.start();
                        }
                    }
                });

                sBar.setOnKeyListener((arg0, keycode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keycode == KeyEvent.KEYCODE_DPAD_CENTER || keycode == KeyEvent.KEYCODE_ENTER) {
                            if (mVideoView == null) return false;
                            if (mVideoView.isPlaying()) {
                                mVideoView.pause();
                                if (countDownTimer != null) countDownTimer.cancel();
                                iv_play.setVisibility(View.VISIBLE);
                                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                            } else {
                                mVideoView.start();
                                iv_play.setVisibility(View.INVISIBLE);
                                if (countDownTimer != null) countDownTimer.start();
                                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                            }
                        }
                    }
                    return false;
                });
            }

            initEpgDateView();
            initEpgListView();
            initDayList();
            initVideoView();

            initSourceListView();
            initChannelGroupView();
            initLiveChannelView();

            initSettingGroupView();
            initSettingItemView();
            initLiveChannelList();
            initLiveSettingGroupList();
            Hawk.put(HawkConfig.PLAYER_IS_LIVE, true);

            safeInitSettingPanel();

        } catch (Exception e) {
            android.util.Log.e("LivePlayActivity", "init error", e);
            Toast.makeText(this, "直播启动失败，进入主页: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }
    }

    // ========== 源列表相关方法 ==========
    private void initSourceListView() {
        if (mSourceListView == null) return;
        mSourceListView.setHasFixedSize(true);
        mSourceListView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveSourceAdapter = new LiveSourceAdapter();
        mSourceListView.setAdapter(liveSourceAdapter);
        mSourceListView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                liveSourceAdapter.setFocusedPosition(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                liveSourceAdapter.setFocusedPosition(position);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                switchToSource(position);
            }
        });
        liveSourceAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            switchToSource(position);
        });
    }

    private void refreshSourceList() {
        SharedPreferences prefs = App.getInstance().getSharedPreferences("live_source_pref", Context.MODE_PRIVATE);
        String json = prefs.getString("source_list", "[]");
        JsonArray sourceArray = JsonParser.parseString(json).getAsJsonArray();
        List<String> sourceNames = new ArrayList<>();
        if (sourceArray == null || sourceArray.size() == 0) {
            sourceNames.add("无源");
        } else {
            for (int i = 0; i < sourceArray.size(); i++) {
                JsonObject obj = sourceArray.get(i).getAsJsonObject();
                String name = obj.has("name") ? obj.get("name").getAsString() : "未命名";
                sourceNames.add(name);
            }
        }
        if (liveSourceAdapter != null) {
            liveSourceAdapter.setNewData(sourceNames);
            int selected = Hawk.get(HawkConfig.LIVE_SOURCE_SELECTED, 0);
            if (selected >= 0 && selected < sourceNames.size()) {
                liveSourceAdapter.setSelectedPosition(selected);
            } else {
                liveSourceAdapter.setSelectedPosition(0);
            }
        }
    }

    private void switchToSource(int position) {
        SharedPreferences prefs = App.getInstance().getSharedPreferences("live_source_pref", Context.MODE_PRIVATE);
        String json = prefs.getString("source_list", "[]");
        JsonArray sourceArray = JsonParser.parseString(json).getAsJsonArray();
        if (sourceArray == null || position >= sourceArray.size()) {
            Toast.makeText(this, "无效源", Toast.LENGTH_SHORT).show();
            return;
        }
        JsonObject obj = sourceArray.get(position).getAsJsonObject();
        String url = obj.get("url").getAsString();
        String name = obj.get("name").getAsString();

        Hawk.put(HawkConfig.LIVE_SOURCE_SELECTED, position);
        Hawk.put(HawkConfig.LIVE_API_URL, url);
        updateCurrentSourceName(name);

        Toast.makeText(this, "加载源: " + name, Toast.LENGTH_SHORT).show();

        ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                runOnUiThread(() -> {
                    initLiveChannelList();
                    if (liveChannelGroupAdapter != null) {
                        liveChannelGroupAdapter.setNewData(new ArrayList<>(liveChannelGroupList));
                    }
                    if (!liveChannelGroupList.isEmpty()) {
                        selectChannelGroup(0, false, 0);
                    }
                    refreshSourceList();
                    mHandler.removeCallbacks(mHideChannelListRun);
                    mHandler.postDelayed(mHideChannelListRun, 500);
                });
            }

            @Override
            public void error(String msg) {
                runOnUiThread(() -> Toast.makeText(LivePlayActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void notice(String msg) {
            }
        });
    }

    private void safeInitSettingPanel() {
        try {
            ApiConfig.get().refreshLiveApiHistoryItems();
            loadCurrentSourceList();
            if (liveSettingGroupAdapter != null) {
                liveSettingGroupAdapter.setNewData(getVisibleLiveSettingGroupList());
            }
            int settingGroupIndex = getDefaultSettingGroupIndex();
            LiveSettingGroup defaultGroup = findSettingGroupByIndex(settingGroupIndex);
            if (defaultGroup != null) {
                if (liveSettingGroupAdapter != null) {
                    liveSettingGroupAdapter.setSelectedGroupIndex(settingGroupIndex);
                }
                if (liveSettingItemAdapter != null) {
                    List<LiveSettingItem> items = defaultGroup.getLiveSettingItems();
                    liveSettingItemAdapter.setNewData(items != null ? items : new ArrayList<>());
                }
            }
        } catch (Exception e) {
            FileLogger.write("LivePlay", "safeInitSettingPanel error: " + e.getMessage());
        }
    }

    private List<Epginfo> epgdata = new ArrayList<>();

    private void showEpg(Date date, ArrayList<Epginfo> arrayList) {
        boolean hasEpg = arrayList != null && arrayList.size() > 0;
        updateEpgPanelState(hasEpg);
        if (hasEpg) {
            enrichEpgDescriptions(channel_Name != null ? channel_Name.getChannelName() : "", arrayList);
            epgdata = arrayList;
            if (epgListAdapter != null) {
                if (currentLiveChannelItem != null) {
                    epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
                }
                epgListAdapter.setNewData(epgdata);
                updateCurrentEpgSelectedIndex();
            }
            if (ku9GuideProgramAdapter != null) {
                ku9GuideProgramAdapter.setCanBack(currentLiveChannelItem != null && currentLiveChannelItem.getinclude_back());
                ku9GuideProgramAdapter.setNewData(epgdata);
                int current = findCurrentEpgIndex(epgdata);
                if (current >= 0) ku9GuideProgramAdapter.setSelectedIndex(current);
            }
            int currentPreviewIndex = findCurrentEpgIndex(epgdata);
            if (currentPreviewIndex >= 0) {
                Epginfo previewInfo = epgdata.get(currentPreviewIndex);
                String previewTitle = previewInfo.start + "-" + previewInfo.end + "  " + previewInfo.title;
                String previewDesc = previewInfo.desc;
                if (liveChannelItemAdapter != null && channel_Name != null) {
                    liveChannelItemAdapter.setCurrentProgramPreview(channel_Name.getChannelName(), previewTitle, previewDesc);
                }
                if (ku9GuideChannelAdapter != null && channel_Name != null) {
                    ku9GuideChannelAdapter.setCurrentProgramPreview(channel_Name.getChannelName(), previewTitle, previewDesc);
                }
            }
        }
    }

    private int findCurrentEpgIndex(List<Epginfo> epgList) {
        if (epgList == null || epgList.isEmpty()) return -1;
        Date now = new Date();
        for (int i = epgList.size() - 1; i >= 0; i--) {
            Epginfo epgInfo = epgList.get(i);
            if (epgInfo == null || epgInfo.startdateTime == null || epgInfo.enddateTime == null) {
                continue;
            }
            Date endDateTime = epgInfo.enddateTime;
            if (!endDateTime.after(epgInfo.startdateTime)) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(endDateTime);
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                endDateTime = calendar.getTime();
            }
            if (!now.before(epgInfo.startdateTime) && now.before(endDateTime)) {
                return i;
            }
        }
        return -1;
    }

    private int getCurrentEpgIndexOrSelected() {
        if (epgListAdapter == null) return 0;
        int epgIndex = findCurrentEpgIndex(epgListAdapter.getData());
        if (epgIndex >= 0) return epgIndex;
        epgIndex = epgListAdapter.getSelectedIndex();
        if (epgIndex >= 0 && epgIndex < epgListAdapter.getData().size()) return epgIndex;
        return 0;
    }

    private void updateCurrentEpgSelectedIndex() {
        if (epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) return;
        int epgIndex = findCurrentEpgIndex(epgListAdapter.getData());
        if (epgIndex >= 0) {
            epgListAdapter.setSelectedEpgIndex(epgIndex);
        }
    }

    private void syncCurrentEpgSelection(boolean focus) {
        if (mRightEpgList == null || epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty())
            return;
        int epgIndex = getCurrentEpgIndexOrSelected();
        mRightEpgList.setSelectedPosition(epgIndex);
        mRightEpgList.setSelection(epgIndex);
        epgListAdapter.setSelectedEpgIndex(epgIndex);
        if (focus) {
            epgListAdapter.setFocusedEpgIndex(epgIndex);
            focusEpgPosition(epgIndex);
        } else {
            mRightEpgList.post(() -> mRightEpgList.smoothScrollToPosition(epgIndex));
        }
    }

    private void updateEpgPanelState(boolean hasEpg) {
        if (txtNoEpg == null) return;
        if (hasEpg) {
            txtNoEpg.setVisibility(View.GONE);
            if (mRightEpgList != null) mRightEpgList.setVisibility(View.VISIBLE);
            if (divLoadEpgDivider != null) divLoadEpgDivider.setVisibility(View.VISIBLE);
            if (divEpg != null && divEpg.getVisibility() != View.VISIBLE) {
                if (divLoadEpg != null) divLoadEpg.setVisibility(View.VISIBLE);
                if (divLoadEpgleft != null) divLoadEpgleft.setVisibility(View.GONE);
            }
        } else {
            epgdata = new ArrayList<>();
            if (epgListAdapter != null) epgListAdapter.setNewData(epgdata);
            txtNoEpg.setVisibility(View.GONE);
            if (mRightEpgList != null) mRightEpgList.setVisibility(View.GONE);
            if (divEpg != null) divEpg.setVisibility(View.GONE);
            if (divLoadEpg != null) divLoadEpg.setVisibility(View.GONE);
            if (divLoadEpgDivider != null) divLoadEpgDivider.setVisibility(View.GONE);
            if (divLoadEpgleft != null) divLoadEpgleft.setVisibility(View.GONE);
            if (mChannelGroupView != null) mChannelGroupView.setVisibility(View.VISIBLE);
        }
    }

    private String getFirstPartBeforeSpace(String str) {
        if (str == null || str.isEmpty()) return str;
        int spaceIndex = str.indexOf(' ');
        if (spaceIndex == -1) return str;
        return str.substring(0, spaceIndex);
    }

    private ArrayList<String> getCurrentLiveGroupChannelNames() {
        ArrayList<String> names = new ArrayList<>();
        try {
            List<LiveChannelItem> channels = getLiveChannels(currentChannelGroupIndex);
            if (channels != null) {
                for (LiveChannelItem item : channels) {
                    if (item == null || TextUtils.isEmpty(item.getChannelName())) continue;
                    if (!names.contains(item.getChannelName())) names.add(item.getChannelName());
                }
            }
        } catch (Exception e) {
            FileLogger.write("LivePlay", "获取当前频道组 EPG 名称失败", e);
        }
        return names;
    }

    public void getEpg(Date date) {
        if (channel_Name == null) return;
        final String channelName = channel_Name.getChannelName();
        final String channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelName));

        @SuppressLint("SimpleDateFormat") SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
        timeFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        final String dateStr = timeFormat.format(date);
        String epgTagName = channelNameReal;

        if (isXmlEpgAddress(epgStringAddress)) {
            final EpgManager manager = EpgManager.getInstance(this);
            manager.refreshEpg(new EpgManager.RefreshCallback() {
                @Override public void onSuccess() {
                    if (channel_Name == null || !channelName.equals(channel_Name.getChannelName())) return;

                    final ArrayList<String> currentGroupNames = getCurrentLiveGroupChannelNames();
                    if (currentGroupNames.isEmpty()) currentGroupNames.add(channelName);
                    manager.loadChannelGroup(currentGroupNames, () -> {
                        if (channel_Name == null || !channelName.equals(channel_Name.getChannelName())) return;
                        ArrayList<Epginfo> xmlList = new ArrayList<>();
                        try {
                            List<EpgManager.EpgProgram> ps = manager.getProgramsForChannelOnDate(channelName, date);
                            int idx = 0;
                            for (EpgManager.EpgProgram p : ps) {
                                if (p == null || p.start == null || p.stop == null || !p.stop.after(p.start)) continue;
                                Epginfo info = createXmlEpgInfoFromProgram(p, idx++);
                                info.desc = p.description == null ? "" : p.description;
                                xmlList.add(info);
                            }
                        } catch (Exception e) {
                            FileLogger.write("LivePlay", "XMLTV当前频道读取失败: " + channelName, e);
                        }
                        String key = channelName + "_" + dateStr;
                        hsEpg.put(key, xmlList);
                        showEpg(date, xmlList);
                        showBottomEpg();
                        updateBottomInfoBar();
                    });
                }
                @Override public void onError(String msg) {
                    FileLogger.write("LivePlay", "XMLTV EPG刷新失败: " + msg);
                }
            });
            if (epgListAdapter != null && currentLiveChannelItem != null) {
                epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
            }
            return;
        }

        if (logoUrl == null || logoUrl.isEmpty()) {
            String[] epgInfo = EpgUtil.getEpgInfo(channelNameReal);
            if (epgInfo != null && epgInfo.length > 1 && !epgInfo[1].isEmpty()) {
                epgTagName = epgInfo[1];
            }
            updateChannelIcon(channelName, epgInfo == null ? null : epgInfo[0]);
        } else if (logoUrl.equals("false")) {
            updateChannelIcon(channelName, null);
        } else {
            String logo = logoUrl.replace("{name}", epgTagName);
            updateChannelIcon(channelName, logo);
        }

        final String finalEpgTagName = epgTagName;
        if (epgListAdapter != null && currentLiveChannelItem != null) {
            epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
        }

        if (!hasEpgAddress()) {
            updateEpgPanelState(false);
            return;
        }

        ArrayList<String> epgQueryNames = buildEpgQueryNames(channelName, channelNameReal, finalEpgTagName);
        String url = buildEpgUrl(epgStringAddress, epgQueryNames.get(0), date, timeFormat);

        if (liveEpgDateAdapter == null || liveEpgDateAdapter.getSelectedIndex() < 0) {
            updateEpgPanelState(false);
            return;
        }

        String savedEpgKey = channelName + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();

        if (hsEpg.containsKey(savedEpgKey)) {
            showEpg(date, hsEpg.get(savedEpgKey));
            showBottomEpg();
            return;
        }
        ArrayList<Epginfo> dbEpg = EpgUtil.loadEpgData(channel_Name.getChannelName(), dateStr, date);
        if (!dbEpg.isEmpty()) {
            enrichEpgDescriptions(channel_Name.getChannelName(), dbEpg);
            hsEpg.put(savedEpgKey, dbEpg);
            showEpg(date, dbEpg);
            showBottomEpg();
            updateBottomInfoBar();
            refreshKu9GuideIfShowing();
            return;
        }

        updateEpgPanelState(false);
        requestEpg(url, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, 0);
    }

    private String buildEpgUrl(String address, String epgTagName, Date date, SimpleDateFormat timeFormat) {
        if (address == null) return "";
        if (address.contains("{name}") || address.contains("{date}")) {
            return address.replace("{name}", encodeEpgParam(epgTagName)).replace("{date}", timeFormat.format(date));
        } else if (isXmlEpgAddress(address)) {
            return address;
        } else {
            return address + (address.contains("?") ? "&" : "?") + "ch=" + encodeEpgParam(epgTagName) + "&date=" + timeFormat.format(date);
        }
    }

    private String encodeEpgParam(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private ArrayList<String> buildEpgQueryNames(String channelName, String channelNameReal, String epgTagName) {
        ArrayList<String> queryNames = new ArrayList<>();
        addEpgQueryName(queryNames, epgTagName);
        addEpgQueryName(queryNames, channelNameReal);
        addEpgQueryName(queryNames, normalizeEpgChannelName(getFirstPartBeforeSpace(channelName)));
        addEpgQueryName(queryNames, getFirstPartBeforeSpace(channelName));
        addEpgQueryName(queryNames, channelName);
        if (queryNames.isEmpty()) queryNames.add("");
        return queryNames;
    }

    private void addEpgQueryName(ArrayList<String> queryNames, String name) {
        if (name == null) return;
        String trimName = name.trim();
        if (trimName.isEmpty() || queryNames.contains(trimName)) return;
        queryNames.add(trimName);
    }

    private String getConfiguredEpgAddress() {
        String userEpgAddress = Hawk.get(HawkConfig.EPG_URL, "");
        if (userEpgAddress != null && userEpgAddress.trim().length() >= 5) {
            return userEpgAddress.trim();
        }
        try (InputStream is = getAssets().open("configuration.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject configuration = root.getAsJsonObject("Configuration");
            if (configuration != null && configuration.has("EPG_URLS")) {
                String urls = configuration.get("EPG_URLS").getAsString();
                if (urls != null) {
                    for (String part : urls.split("\\|\\|")) {
                        String value = part == null ? "" : part.trim();
                        if (value.isEmpty()) continue;
                        int dollar = value.lastIndexOf('$');
                        if (dollar > 0) value = value.substring(0, dollar).trim();
                        if (!value.isEmpty()) return value;
                    }
                }
            }
        } catch (Exception e) {
            FileLogger.write("LivePlay", "读取 configuration.json EPG_URLS 失败", e);
        }
        return DEFAULT_EPG_ADDRESS;
    }

    private boolean hasEpgAddress() {
        return epgStringAddress != null && !epgStringAddress.trim().isEmpty();
    }

    private void requestEpg(String url, Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                            ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (url == null || url.isEmpty()) {
            onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex);
            return;
        }
        okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
        if (client == null) client = com.github.catvod.net.OkHttp.client();
        if (client == null) {
            onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex);
            return;
        }
        client.newCall(new okhttp3.Request.Builder().url(url).build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                mHandler.post(() -> onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex));
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response == null || response.code() != 200) {
                    if (response != null) response.close();
                    mHandler.post(() -> onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex));
                    return;
                }
                final String body;
                try {
                    body = response.body() != null ? response.body().string() : "";
                } finally {
                    response.close();
                }
                mHandler.post(() -> onEpgRequestResponse(body, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex));
            }
        });
    }

    private void onEpgRequestFailure(Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                                     ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (!isCurrentEpgRequest(savedEpgKey)) return;
        if (requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) return;
        if (requestDefaultEpgOnFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) return;
        updateEpgPanelState(false);
    }

    private void onEpgRequestResponse(String paramString, Date date, String channelNameReal, String finalEpgTagName,
                                      String savedEpgKey, ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (!isCurrentEpgRequest(savedEpgKey)) return;
        if (paramString == null || paramString.trim().isEmpty()) {
            updateEpgPanelState(false);
            return;
        }
        FileLogger.write("LivePlay", "echo-epgTagName:" + channelNameReal);
        ArrayList<Epginfo> arrayList = new ArrayList<>();
        try {
            if (isXmlEpgResponse(paramString)) {
                arrayList = parseXmlEpg(paramString, finalEpgTagName, date);
            } else if (paramString.contains("epg_data") || paramString.trim().startsWith("{")) {
                arrayList = parseJsonEpg(paramString, date);
            }
        } catch (JSONException jSONException) {
            jSONException.printStackTrace();
        }
        if (arrayList.isEmpty() && requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return;
        }
        if (!arrayList.isEmpty()) {
            hsEpg.put(savedEpgKey, arrayList);
            String dbDate = timeFormat.format(date);
            String dbChannel = channel_Name != null ? channel_Name.getChannelName() : "";
            if (!dbChannel.isEmpty()) {
                EpgUtil.saveEpgData(dbChannel, dbDate, arrayList);
            }
        }
        if (!isCurrentEpgRequest(savedEpgKey)) return;
        showEpg(date, arrayList);
        showBottomEpg();
        updateBottomInfoBar();
        refreshKu9GuideIfShowing();
    }

    private boolean requestDefaultEpgOnFailure(Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                                               ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (DEFAULT_EPG_ADDRESS.equals(epgStringAddress) || epgQueryNames == null || queryIndex >= epgQueryNames.size()) {
            return false;
        }
        String fallbackUrl = buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames.get(0), date, timeFormat);
        FileLogger.write("LivePlay", "echo-epg fallback default address");
        requestEpg(fallbackUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, epgQueryNames.size());
        return true;
    }

    private boolean requestNextEpgQueryName(Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                                            ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (!isTemplateEpgAddress(epgStringAddress) || epgQueryNames == null || queryIndex + 1 >= epgQueryNames.size()) {
            return false;
        }
        int nextIndex = queryIndex + 1;
        String nextUrl = buildEpgUrl(epgStringAddress, epgQueryNames.get(nextIndex), date, timeFormat);
        FileLogger.write("LivePlay", "echo-epg retry query name:" + epgQueryNames.get(nextIndex));
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex);
        return true;
    }

    private boolean isTemplateEpgAddress(String address) {
        return address != null && (address.contains("{name}") || address.contains("{date}"));
    }

    private boolean isCurrentEpgRequest(String savedEpgKey) {
        if (channel_Name == null || liveEpgDateAdapter == null || liveEpgDateAdapter.getSelectedIndex() < 0) return false;
        String currentEpgKey = channel_Name.getChannelName() + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();
        return savedEpgKey.equals(currentEpgKey);
    }

    private boolean isXmlEpgAddress(String address) {
        if (address == null) return false;
        String lowerAddress = address.toLowerCase(Locale.ROOT);
        int queryIndex = lowerAddress.indexOf("?");
        if (queryIndex >= 0) lowerAddress = lowerAddress.substring(0, queryIndex);
        int suffix = lowerAddress.indexOf('$');
        if (suffix > 0) lowerAddress = lowerAddress.substring(0, suffix);
        return lowerAddress.endsWith(".xml") || lowerAddress.endsWith(".xml.gz") || lowerAddress.endsWith(".gz");
    }

    private boolean isXmlEpgResponse(String response) {
        if (response == null) return false;
        String trimResponse = response.trim();
        return trimResponse.startsWith("<?xml") || trimResponse.startsWith("<tv") || trimResponse.startsWith("<epg");
    }

    private ArrayList<Epginfo> parseJsonEpg(String response, Date date) throws JSONException {
        ArrayList<Epginfo> epgList = new ArrayList<>();
        JSONObject jsonObject = new JSONObject(response);
        String channelName = jsonObject.optString("channel_name", jsonObject.optString("channel", ""));
        if (isUnavailableEpgText(channelName)) return epgList;
        JSONArray epgArray = findJsonEpgArray(jsonObject);
        if (epgArray == null) return epgList;
        for (int i = 0; i < epgArray.length(); i++) {
            JSONObject item = epgArray.optJSONObject(i);
            if (item == null) continue;
            String title = cleanEpgTitle(item.optString("title", item.optString("name", "")));
            String desc = item.optString("desc", item.optString("description", ""));
            if (TextUtils.isEmpty(title) || isUnavailableEpgText(title)) continue;
            String startText = item.optString("start", item.optString("start_time", item.optString("starttime", "")));
            String endText = item.optString("end", item.optString("end_time", item.optString("endtime", "")));
            Date startDate = parseJsonEpgDate(date, startText);
            Date endDate = parseJsonEpgDate(date, endText);
            if (startDate == null || endDate == null) continue;
            if (!endDate.after(startDate)) {
                endDate = new Date(endDate.getTime() + TimeUnit.DAYS.toMillis(1));
            }
            Epginfo info = createXmlEpgInfo(date, title, startDate, endDate, epgList.size());
            info.desc = desc == null ? "" : desc.trim();
            epgList.add(info);
        }
        return epgList;
    }

    private JSONArray findJsonEpgArray(JSONObject jsonObject) {
        if (jsonObject == null) return null;
        JSONArray epgArray = jsonObject.optJSONArray("epg_data");
        if (epgArray != null) return epgArray;
        epgArray = jsonObject.optJSONArray("data");
        if (epgArray != null) return epgArray;
        epgArray = jsonObject.optJSONArray("list");
        if (epgArray != null) return epgArray;
        JSONObject dataObject = jsonObject.optJSONObject("data");
        if (dataObject != null) {
            epgArray = dataObject.optJSONArray("epg_data");
            if (epgArray != null) return epgArray;
            epgArray = dataObject.optJSONArray("list");
        }
        return epgArray;
    }

    private Date parseJsonEpgDate(Date date, String timeText) {
        if (timeText == null || timeText.trim().isEmpty()) return null;
        String trimText = timeText.trim();
        String[] fullPatterns = new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String pattern : fullPatterns) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                return dateFormat.parse(trimText);
            } catch (ParseException ignored) {
            }
        }
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        dayFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String dayText = dayFormat.format(date);
        String[] timePatterns = new String[]{"HH:mm:ss", "HH:mm"};
        for (String pattern : timePatterns) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd " + pattern, Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                return dateFormat.parse(dayText + " " + trimText);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    private String cleanEpgTitle(String title) {
        if (title == null) return "";
        return title.replace(" --免费使用", "").replace("--免费使用", "").trim();
    }

    private boolean isUnavailableEpgText(String text) {
        return text != null && (text.contains("未提供") || text.contains("暂无"));
    }

    private String normalizeEpgChannelName(String channelName) {
        if (channelName == null) return "";
        String trimName = channelName.trim();
        String compactName = trimName.replace("-", "").replace(" ", "");
        Matcher cctvMatcher = Pattern.compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|)$").matcher(compactName);
        if (cctvMatcher.matches()) {
            return cctvMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        if (compactName.toUpperCase(Locale.ROOT).startsWith("CCTV")) {
            return compactName.toUpperCase(Locale.ROOT);
        }
        return trimName;
    }

    @SuppressLint("SetTextI18n")
    private void showBottomEpg() {
        if (isSHIYI) return;
        if (channel_Name == null || channel_Name.getChannelName() == null) return;

        tip_chname.setText(channel_Name.getChannelName());
        tv_channelnum.setText("" + channel_Name.getChannelNum());

        Epginfo currentEpg = null, nextEpg = null;
        if (epgdata != null && !epgdata.isEmpty()) {
            int idx = findCurrentEpgIndex(epgdata);
            if (idx >= 0) {
                currentEpg = epgdata.get(idx);
                if (idx + 1 < epgdata.size()) nextEpg = epgdata.get(idx + 1);
            }
        }
        EpgManager manager = EpgManager.getInstance(this);
        EpgManager.EpgProgram currentProgram = manager.getCurrentProgram(channel_Name.getChannelName());
        EpgManager.EpgProgram nextProgram = manager.getNextProgram(channel_Name.getChannelName());
        if (currentEpg == null || nextEpg == null) {
            try {
                List<EpgManager.EpgProgram> downloaded = manager.getProgramsForChannelOnDate(channel_Name.getChannelName(), new Date());
                Date now = new Date();
                EpgManager.EpgProgram foundCurrent = null;
                EpgManager.EpgProgram foundNext = null;
                for (EpgManager.EpgProgram p : downloaded) {
                    if (p == null || p.start == null || p.stop == null) continue;
                    if (!now.before(p.start) && now.before(p.stop)) foundCurrent = p;
                    else if (p.start.after(now) && foundNext == null) foundNext = p;
                }
                if (currentProgram == null) currentProgram = foundCurrent;
                if (nextProgram == null) nextProgram = foundNext;
                if (currentEpg == null && foundCurrent != null) {
                    currentEpg = createXmlEpgInfo(new Date(), foundCurrent.title, foundCurrent.start, foundCurrent.stop, 0);
                    currentEpg.desc = foundCurrent.description == null ? "" : foundCurrent.description.trim();
                }
                if (nextEpg == null && foundNext != null) {
                    nextEpg = createXmlEpgInfo(new Date(), foundNext.title, foundNext.start, foundNext.stop, 1);
                    nextEpg.desc = foundNext.description == null ? "" : foundNext.description.trim();
                }
            } catch (Exception ignored) { }
        }

        if (currentEpg != null) {
            tip_epg1.setText(currentEpg.start + "-" + currentEpg.end);
            if (tvCurrentProgramName != null) tvCurrentProgramName.setText(currentEpg.title);
            if (tvDesc != null) {
                tvDesc.setText(TextUtils.isEmpty(currentEpg.desc) ? "暂无节目简介" : currentEpg.desc);
                tvDesc.setVisibility(View.VISIBLE);
            }
        } else if (currentProgram != null) {
            tip_epg1.setText(formatEpgTime(currentProgram.start) + "-" + formatEpgTime(currentProgram.stop));
            if (tvCurrentProgramName != null) tvCurrentProgramName.setText(currentProgram.title);
            if (tvDesc != null) {
                tvDesc.setText(TextUtils.isEmpty(currentProgram.description) ? "暂无节目简介" : currentProgram.description);
                tvDesc.setVisibility(View.VISIBLE);
            }
        } else {
            tip_epg1.setText("暂无当前节目");
            if (tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目预告");
            if (tvDesc != null) { tvDesc.setText("暂无节目简介"); tvDesc.setVisibility(View.VISIBLE); }
        }

        if (nextEpg != null) {
            tip_epg2.setText(nextEpg.start + "-" + nextEpg.end);
            if (tvNextProgramName != null) tvNextProgramName.setText(nextEpg.title);
        } else if (nextProgram != null) {
            tip_epg2.setText(formatEpgTime(nextProgram.start) + "-" + formatEpgTime(nextProgram.stop));
            if (tvNextProgramName != null) tvNextProgramName.setText(nextProgram.title);
        } else {
            tip_epg2.setText("暂无下一节目");
            if (tvNextProgramName != null) tvNextProgramName.setText("暂无节目预告");
        }

        TextView tvSource = findViewById(R.id.tv_source);
        if (channel_Name == null || channel_Name.getSourceNum() <= 0) {
            if (tvSource != null) tvSource.setText("1/1");
        } else {
            if (tvSource != null) tvSource.setText("线路" + (channel_Name.getSourceIndex() + 1) + "/" + channel_Name.getSourceNum());
        }

        if (tv_right_top_channel_name != null) tv_right_top_channel_name.setText(channel_Name.getChannelName());
        if (tv_right_top_epg_name != null) tv_right_top_epg_name.setText(channel_Name.getChannelName());

        updateBottomInfoBar();

        if (countDownTimer != null) countDownTimer.cancel();
        if (!"暂无当前节目".equals(tip_epg1.getText().toString())) {
            if (ll_right_top_loading != null) ll_right_top_loading.setVisibility(View.VISIBLE);
            if (ll_epg != null && !isListOrSettingLayoutVisible()) {
                ll_epg.setVisibility(View.VISIBLE);
            }
            countDownTimer = new CountDownTimer(postTimeout, 1000) {
                public void onTick(long j) {
                }

                public void onFinish() {
                    if (ll_right_top_loading != null) ll_right_top_loading.setVisibility(View.GONE);
                    if (ll_right_top_huikan != null) ll_right_top_huikan.setVisibility(View.GONE);
                    if (ll_epg != null && !isListOrSettingLayoutVisible()) {
                        ll_epg.setVisibility(View.GONE);
                    }
                }
            };
            countDownTimer.start();
        } else {
            if (ll_right_top_loading != null) ll_right_top_loading.setVisibility(View.GONE);
            if (ll_right_top_huikan != null) ll_right_top_huikan.setVisibility(View.GONE);
            if (ll_epg != null) ll_epg.setVisibility(View.GONE);
        }

        updateCurrentChannelIcon();
    }

    private String formatEpgTime(Date value) {
        if (value == null) return "--:--";
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(value);
    }

    private void setDefaultBottomEpg(TextView currentProgramName, TextView nextProgramName) {
        TimeZone timeZone = TimeZone.getTimeZone("GMT+8:00");
        Calendar currentStart = Calendar.getInstance(timeZone);
        currentStart.set(Calendar.MINUTE, 0);
        currentStart.set(Calendar.SECOND, 0);
        currentStart.set(Calendar.MILLISECOND, 0);
        Calendar currentEnd = (Calendar) currentStart.clone();
        currentEnd.add(Calendar.MINUTE, 59);
        Calendar nextStart = (Calendar) currentEnd.clone();
        nextStart.add(Calendar.MINUTE, 1);
        Calendar nextEnd = (Calendar) nextStart.clone();
        nextEnd.add(Calendar.MINUTE, 59);

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        timeFormat.setTimeZone(timeZone);
        tip_epg1.setText(timeFormat.format(currentStart.getTime()) + "-" + timeFormat.format(currentEnd.getTime()));
        if (currentProgramName != null) currentProgramName.setText("暂无节目预告");
        tip_epg2.setText(timeFormat.format(nextStart.getTime()) + "-" + timeFormat.format(nextEnd.getTime()));
        Epginfo current = null, next = null;
        if (epgdata != null && !epgdata.isEmpty()) {
            int idx = findCurrentEpgIndex(epgdata);
            if (idx >= 0) {
                current = epgdata.get(idx);
                if (idx + 1 < epgdata.size()) next = epgdata.get(idx + 1);
            }
        }
        if (current != null) {
            if (tip_epg1 != null) tip_epg1.setText(current.start + "-" + current.end);
            if (tvCurrentProgramName != null) tvCurrentProgramName.setText(current.title);
            if (tvDesc != null) {
                tvDesc.setText(TextUtils.isEmpty(current.desc) ? "暂无节目简介" : current.desc);
                tvDesc.setVisibility(View.VISIBLE);
            }
        } else {
            if (tip_epg1 != null) tip_epg1.setText("暂无当前节目");
            if (tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无当前节目");
            if (tvDesc != null) { tvDesc.setText("暂无节目简介"); tvDesc.setVisibility(View.VISIBLE); }
        }
        if (next != null) {
            if (tip_epg2 != null) tip_epg2.setText(next.start + "-" + next.end);
            if (tvNextProgramName != null) tvNextProgramName.setText(next.title);
        } else {
            if (tip_epg2 != null) tip_epg2.setText("暂无节目预告");
            if (tvNextProgramName != null) tvNextProgramName.setText("暂无节目预告");
        }
    }

    private void updateCurrentChannelIcon() {
        if (channel_Name == null || channel_Name.getChannelName() == null) return;
        final String channelName = channel_Name.getChannelName();
        final String channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelName));
        mHandler.post(() -> {
            String epgTagName = channelNameReal;
            String iconUrl = null;
            final EpgManager epgManager = EpgManager.getInstance(this);
            if (isXmlEpgAddress(epgStringAddress)) {
                epgManager.loadProcessedChannelIcon(channelName, file -> {
                    if (file != null && file.exists() && channel_Name != null && channelName.equals(channel_Name.getChannelName()) && imgLiveIcon != null) {
                        imgLiveIcon.setVisibility(View.VISIBLE);
                        if (liveIconNullBg != null) liveIconNullBg.setVisibility(View.INVISIBLE);
                        if (liveIconNullText != null) liveIconNullText.setVisibility(View.INVISIBLE);
                        com.bumptech.glide.Glide.with(LivePlayActivity.this)
                                .load(file)
                                .signature(new com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                                .dontAnimate()
                                .into(imgLiveIcon);
                    }
                });
                return;
            }
            if (channel_Name.getChannelLogo() != null && !channel_Name.getChannelLogo().isEmpty()) {
                iconUrl = channel_Name.getChannelLogo();
            } else if (logoUrl == null || logoUrl.isEmpty()) {
                String[] epgInfo = EpgUtil.getEpgInfo(channelNameReal);
                if (epgInfo != null) {
                    iconUrl = epgInfo[0];
                    if (epgInfo.length > 1 && !epgInfo[1].isEmpty()) {
                        epgTagName = epgInfo[1];
                    }
                }
                if (TextUtils.isEmpty(iconUrl)) {
                    try { iconUrl = EpgManager.getInstance(this).getChannelIconUrl(channelName); } catch (Exception ignored) { }
                }
            } else if (!logoUrl.equals("false")) {
                iconUrl = logoUrl.replace("{name}", epgTagName);
            }
            updateChannelIcon(channelName, iconUrl);
        });
    }

    @SuppressLint("SetTextI18n")
    private void updateChannelIcon(String channelName, String logoUrl) {
        if (channel_Name == null || channel_Name.getChannelName() == null || !channel_Name.getChannelName().equals(channelName)) return;
        if (imgLiveIcon == null) return;
        if (org.apache.commons.lang3.StringUtils.isEmpty(logoUrl)) {
            imgLiveIcon.setImageDrawable(null);
            if (liveIconNullBg != null) liveIconNullBg.setVisibility(View.VISIBLE);
            if (liveIconNullText != null) {
                liveIconNullText.setVisibility(View.VISIBLE);
                liveIconNullText.setText("" + channel_Name.getChannelNum());
            }
            imgLiveIcon.setVisibility(View.INVISIBLE);
        } else {
            imgLiveIcon.setVisibility(View.VISIBLE);
            if (liveIconNullBg != null) liveIconNullBg.setVisibility(View.INVISIBLE);
            if (liveIconNullText != null) liveIconNullText.setVisibility(View.INVISIBLE);
            final String finalLogoUrl = logoUrl;
            com.github.tvbox.osc.util.logo.LogoManager lm = com.github.tvbox.osc.util.logo.LogoManager.getInstance(this);
            File local = lm.getLocalLogo(channel_Name.getChannelName());
            if (local != null && local.exists()) {
                com.bumptech.glide.Glide.with(this)
                        .load(local)
                        .signature(new com.bumptech.glide.signature.ObjectKey(local.lastModified()))
                        .dontAnimate()
                        .into(imgLiveIcon);
            } else {
                imgLiveIcon.setImageDrawable(null);
                lm.downloadLogo(channel_Name.getChannelName(), finalLogoUrl, new com.github.tvbox.osc.util.logo.LogoManager.LogoCallback() {
                    @Override public void onSuccess(File file) {
                        if (file != null && file.exists() && imgLiveIcon != null) {
                            com.bumptech.glide.Glide.with(LivePlayActivity.this)
                                    .load(file)
                                    .signature(new com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                                    .dontAnimate()
                                    .into(imgLiveIcon);
                        }
                    }
                    @Override public void onError(String msg) {
                    }
                });
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void divLoadEpgRight(View view) {
        if (epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) {
            updateEpgPanelState(false);
            return;
        }
        mHandler.removeCallbacks(mHideChannelListRun);
        mHandler.postDelayed(mHideChannelListRun, postTimeout);
        if (mChannelGroupView != null) mChannelGroupView.setVisibility(View.GONE);
        if (divEpg != null) divEpg.setVisibility(View.VISIBLE);
        if (mRightEpgList != null) mRightEpgList.setVisibility(View.VISIBLE);
        if (divLoadEpgleft != null) divLoadEpgleft.setVisibility(View.VISIBLE);
        if (divLoadEpg != null) divLoadEpg.setVisibility(View.GONE);
        if (liveChannelItemAdapter != null) liveChannelItemAdapter.setFocusedChannelIndex(-1);
        epgListAdapter.notifyDataSetChanged();
        mRightEpgList.post(this::focusCurrentEpgInMenu);
    }

    public void divLoadEpgLeft(View view) {
        mHandler.removeCallbacks(mHideChannelListRun);
        mHandler.postDelayed(mHideChannelListRun, postTimeout);
        if (mChannelGroupView != null) mChannelGroupView.setVisibility(View.VISIBLE);
        if (divEpg != null) divEpg.setVisibility(View.GONE);
        if (divLoadEpgleft != null) divLoadEpgleft.setVisibility(View.GONE);
        if (divLoadEpg != null) divLoadEpg.setVisibility(View.VISIBLE);
        focusCurrentChannelInMenu();
    }

    @Override
    public void onBackPressed() {
        if (ku9GuideShowing) {
            hideKu9ProgramGuide();
            return;
        }
        if (isBottomInfoBarShowing) {
            hideBottomInfoBar();
            return;
        }
        if (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
            return;
        }
        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
            return;
        }
        if (backcontroller != null && backcontroller.getVisibility() == View.VISIBLE) {
            backcontroller.setVisibility(View.GONE);
            return;
        }
        showSettingGroup();
    }

    private final Runnable mPlaySelectedChannel = new Runnable() {
        @Override
        public void run() {
            int channelNumber = selectedChannelNumber;
            selectedChannelNumber = 0;
            int currentTotal = 0;
            int groupIndex = 0;
            int channelIndex = -1;
            if (liveChannelGroupList != null) {
                for (LiveChannelGroup group : liveChannelGroupList) {
                    if (group == null || group.getLiveChannels() == null) continue;
                    int groupChannelCount = group.getLiveChannels().size();
                    if (currentTotal + groupChannelCount >= channelNumber) {
                        channelIndex = channelNumber - currentTotal - 1;
                        break;
                    }
                    currentTotal += groupChannelCount;
                    groupIndex++;
                }
            }
            if (tvSelectedChannel != null) {
                tvSelectedChannel.setVisibility(View.INVISIBLE);
                tvSelectedChannel.setText("");
            }
            if (channelIndex >= 0) {
                loadChannelGroupDataAndPlay(groupIndex, channelIndex);
            } else {
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, false);
            }
        }
    };

    @SuppressLint("SetTextI18n")
    private void numericKeyDown(int digit) {
        selectedChannelNumber = selectedChannelNumber * 10 + digit;
        if (tvSelectedChannel != null) {
            tvSelectedChannel.setText(Integer.toString(selectedChannelNumber));
            if (ll_right_top_loading != null) ll_right_top_loading.setVisibility(View.GONE);
            if (ll_right_top_huikan != null) ll_right_top_huikan.setVisibility(View.GONE);
            tvSelectedChannel.setVisibility(View.VISIBLE);
        }
        mHandler.removeCallbacks(mPlaySelectedChannel);
        mHandler.postDelayed(mPlaySelectedChannel, 2500);
    }

    private final Handler mmHandler = new Handler();
    private Runnable mLongPressRunnable;
    private static final long LONG_PRESS_DELAY = 800;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (ku9GuideShowing) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    hideKu9ProgramGuide();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(ku9GuideProgramList)) {
                    ku9GuideDateList.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(ku9GuideChannelList)) {
                    if (ku9GuideChannelGroupButton != null) ku9GuideChannelGroupButton.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(ku9GuideChannelGroupButton)) {
                    ku9GuideChannelList.requestFocus();
                    return true;
                }
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && isFocusInView(ku9GuideChannelGroupButton)) {
                    showChannelListFromGuide();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(ku9GuideDateList)) {
                    ku9GuideProgramList.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(ku9GuideChannelList)) {
                    ku9GuideDateList.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(ku9GuideDateList)) {
                    ku9GuideChannelList.requestFocus();
                    return true;
                }
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && isFocusInView(ku9GuideChannelList)) {
                    int p = ku9GuideChannelFocusPosition;
                    if (p >= 0 && p < ku9GuideChannelAdapter.getData().size()) {
                        selectKu9GuideChannel(p);
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(mChannelGroupView)) {
                    focusChannelFromSelectedGroup();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(mLiveChannelView)) {
                    if (ku9GuideButton != null) {
                        ku9GuideButton.requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(ku9GuideButton)) {
                    focusCurrentChannelInMenu();
                    return true;
                }
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && isFocusInView(ku9GuideButton)) {
                    showKu9ProgramGuide();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(mRightEpgList)) {
                    divLoadEpgLeft(null);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !isFocusInView(mLiveChannelView) && !isFocusInView(mRightEpgList)) {
                    focusCurrentGroupInMenu();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(mChannelGroupView)) {
                    if (mSourceListView != null) {
                        mSourceListView.requestFocus();
                        mSourceListView.setSelection(liveSourceAdapter.getSelectedPosition());
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(mLiveChannelView)) {
                    if (mChannelGroupView != null) {
                        mChannelGroupView.requestFocus();
                        mChannelGroupView.setSelection(liveChannelGroupAdapter.getSelectedGroupIndex());
                    }
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_HELP) {
                showSettingGroup();
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (isBack) {
                            showProgressBars(true);
                        } else {
                            playPreSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (isBack) {
                            showProgressBars(true);
                        } else {
                            playNextSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        break;
                    default:
                        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                            keyCode -= KeyEvent.KEYCODE_0;
                        } else if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                            keyCode -= KeyEvent.KEYCODE_NUMPAD_0;
                        } else {
                            break;
                        }
                        numericKeyDown(keyCode);
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (!isListOrSettingLayoutVisible()) {
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event.getRepeatCount() == 0) {
                    showChannelList();
                }
            }
        }

        if (!isListOrSettingLayoutVisible() && !isBack && event.getAction() == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                showBottomInfoBar();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event.getRepeatCount() == 0) {
            mLongPressRunnable = () -> showSettingGroup();
            mmHandler.postDelayed(mLongPressRunnable, LONG_PRESS_DELAY);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (mLongPressRunnable != null) {
                mmHandler.removeCallbacks(mLongPressRunnable);
                mLongPressRunnable = null;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        exitingLivePlay = false;
        if (mVideoView != null) {
            mVideoView.resume();
        }
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("com.github.tvbox.osc.LIVE_REFRESH");
        filter.addAction("com.github.tvbox.osc.EPG_REFRESH");
        registerReceiver(liveRefreshReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(liveRefreshReceiver);
        } catch (Exception e) {
        }
        if (mVideoView != null && !exitingLivePlay) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        cancelLiveReconnect();

        super.onDestroy();
        try {
            unregisterReceiver(liveRefreshReceiver);
        } catch (Exception e) {
        }
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);
        hideSwitchChannelSnapshot();
        mHandler.removeCallbacks(mLoadEpgRun);
        mHandler.removeCallbacks(mUpdateResolutionInfoRun);
        mHandler.removeCallbacks(mHideResolutionInfoRun);
    }

    private void showChannelList() {
        hideBottomInfoBar();

        if (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
            return;
        }
        if (ll_epg != null) ll_epg.setVisibility(View.GONE);
        if (tvLeftChannelListLayout != null) {
            tvLeftChannelListLayout.setTranslationX(0);
            tvLeftChannelListLayout.bringToFront();
        }
        if (liveChannelGroupList == null || liveChannelGroupList.isEmpty()) return;
        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.INVISIBLE) {
            refreshSourceList();
            if (currentLiveLookBackIndex > -1 && mRightEpgList != null) {
                mRightEpgList.setSelectedPosition(currentLiveLookBackIndex);
                mRightEpgList.post(() -> mRightEpgList.smoothScrollToPosition(currentLiveLookBackIndex));
            }
            refreshChannelList(currentChannelGroupIndex);
            mHandler.postDelayed(mFocusCurrentChannelAndShowChannelList, 50);
        } else {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        }
    }

    private int mLastChannelGroupIndex = -1;
    private List<LiveChannelItem> mLastChannelList = new ArrayList<>();

    private void refreshChannelList(int currentChannelGroupIndex) {
        List<LiveChannelItem> newChannels = getLiveChannels(currentChannelGroupIndex);
        if (currentChannelGroupIndex == mLastChannelGroupIndex && isSameData(newChannels, mLastChannelList)) {
            return;
        }
        if (currentLiveChannelIndex > -1 && mLiveChannelView != null) {
            mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
            mLiveChannelView.setSelection(currentLiveChannelIndex);
        }
        if (mChannelGroupView != null) {
            mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            mChannelGroupView.setSelection(currentChannelGroupIndex);
        }
        mLastChannelGroupIndex = currentChannelGroupIndex;
        mLastChannelList = new ArrayList<>(newChannels != null ? newChannels : new ArrayList<>());
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setNewData(newChannels != null ? newChannels : new ArrayList<>());
        }
    }

    private boolean isSameData(List<LiveChannelItem> list1, List<LiveChannelItem> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null || list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equals(list2.get(i))) return false;
        }
        return true;
    }

    private Runnable mFocusCurrentChannelAndShowChannelList = new Runnable() {
        @Override
        public void run() {
            if ((mChannelGroupView != null && mChannelGroupView.isScrolling())
                    || (mLiveChannelView != null && mLiveChannelView.isScrolling())
                    || (mChannelGroupView != null && mChannelGroupView.isComputingLayout())
                    || (mLiveChannelView != null && mLiveChannelView.isComputingLayout())) {
                mHandler.postDelayed(this, 100);
            } else {
                if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.VISIBLE);
                focusCurrentChannelInMenu();
                if (tvLeftChannelListLayout != null) {
                    ViewObj viewObj = new ViewObj(tvLeftChannelListLayout, (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams());
                    ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginLeft", new IntEvaluator(), -tvLeftChannelListLayout.getLayoutParams().width, 0);
                    animator.setDuration(200);
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            focusCurrentChannelInMenu();
                            mHandler.removeCallbacks(mHideChannelListRun);
                            mHandler.postDelayed(mHideChannelListRun, postTimeout);
                        }
                    });
                    animator.start();
                }
            }
        }
    };

    private boolean isFocusInView(View view) {
        View focused = getCurrentFocus();
        return focused != null && view != null && (focused == view || isChildOf(view, focused));
    }

    private boolean isChildOf(View parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private void focusRecyclerPosition(TvRecyclerView recyclerView, int position) {
        if (recyclerView == null || position < 0) return;
        recyclerView.scrollToPosition(position);
        recyclerView.setSelection(position);
        requestRecyclerItemFocus(recyclerView, position, 0);
    }

    private void focusCurrentGroupInMenu() {
        if (currentChannelGroupIndex < 0) return;
        if (mChannelGroupView != null) mChannelGroupView.setVisibility(View.VISIBLE);
        if (divEpg != null) divEpg.setVisibility(View.GONE);
        if (divLoadEpgleft != null) divLoadEpgleft.setVisibility(View.GONE);
        if (divLoadEpg != null) {
            boolean hasEpg = epgListAdapter != null && epgListAdapter.getData() != null && !epgListAdapter.getData().isEmpty();
            divLoadEpg.setVisibility(hasEpg ? View.VISIBLE : View.GONE);
        }
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter.setSelectedGroupIndex(currentChannelGroupIndex);
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
        }
        if (epgListAdapter != null) epgListAdapter.setFocusedEpgIndex(-1);
        if (mLiveChannelView != null) mLiveChannelView.clearFocus();
        if (mRightEpgList != null) mRightEpgList.clearFocus();
        focusRecyclerPosition(mChannelGroupView, currentChannelGroupIndex);
    }

    private void focusEpgPosition(int position) {
        if (mRightEpgList == null || position < 0) return;
        RecyclerView.LayoutManager layoutManager = mRightEpgList.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            int offset = Math.max(0, (mRightEpgList.getHeight() - getResources().getDimensionPixelSize(R.dimen.ts_100)) / 2);
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, offset);
        } else {
            mRightEpgList.scrollToPosition(position);
        }
        mRightEpgList.setSelection(position);
        requestRecyclerItemFocus(mRightEpgList, position, 0);
    }

    private void requestRecyclerItemFocus(TvRecyclerView recyclerView, int position, int retryCount) {
        if (recyclerView == null) return;
        recyclerView.post(() -> {
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.itemView.requestFocus();
            } else if (retryCount < Integer.MAX_VALUE) {
                requestRecyclerItemFocus(recyclerView, position, retryCount + 1);
            }
        });
    }

    private void focusCurrentChannelInMenu() {
        if (currentChannelGroupIndex < 0 || currentLiveChannelIndex < 0) return;
        if (liveChannelGroupAdapter != null && liveChannelGroupAdapter.getSelectedGroupIndex() != currentChannelGroupIndex) {
            liveChannelGroupAdapter.setSelectedGroupIndex(currentChannelGroupIndex);
            List<LiveChannelItem> channels = getLiveChannels(currentChannelGroupIndex);
            if (liveChannelItemAdapter != null) {
                liveChannelItemAdapter.setNewData(channels != null ? channels : new ArrayList<>());
            }
            mLastChannelGroupIndex = currentChannelGroupIndex;
            mLastChannelList = new ArrayList<>(channels != null ? channels : new ArrayList<>());
        }
        if (liveChannelGroupAdapter != null) liveChannelGroupAdapter.setFocusedGroupIndex(-1);
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(currentLiveChannelIndex);
        }
        focusRecyclerPosition(mLiveChannelView, currentLiveChannelIndex);
    }

    private void focusChannelFromSelectedGroup() {
        int groupIndex = liveChannelGroupAdapter != null ? liveChannelGroupAdapter.getSelectedGroupIndex() : currentChannelGroupIndex;
        if (groupIndex < 0) groupIndex = currentChannelGroupIndex;
        if (groupIndex < 0 || liveChannelGroupList == null || groupIndex >= liveChannelGroupList.size()) return;
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1);
            return;
        }
        if (mChannelGroupView == null || mChannelGroupView.getVisibility() != View.VISIBLE) return;
        int channelIndex = groupIndex == currentChannelGroupIndex && currentLiveChannelIndex >= 0 ? currentLiveChannelIndex : 0;
        List<LiveChannelItem> channels = getLiveChannels(groupIndex);
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setNewData(channels != null ? channels : new ArrayList<>());
        }
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            liveChannelGroupAdapter.setFocusedGroupIndex(-1);
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setSelectedChannelIndex(groupIndex == currentChannelGroupIndex ? currentLiveChannelIndex : -1);
            liveChannelItemAdapter.setFocusedChannelIndex(channelIndex);
        }
        focusRecyclerPosition(mLiveChannelView, channelIndex);
    }

    private void focusCurrentEpgInMenu() {
        if (mRightEpgList == null || epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) return;
        syncCurrentEpgSelection(true);
    }

    private Runnable mHideChannelListRun = new Runnable() {
        @Override
        public void run() {
            if (tvLeftChannelListLayout == null) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams();
            if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                ViewObj viewObj = new ViewObj(tvLeftChannelListLayout, params);
                ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginLeft", new IntEvaluator(), 0, -tvLeftChannelListLayout.getLayoutParams().width);
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
                        if (ll_epg != null && tv_curepg_left != null && !"暂无信息".equals(tip_epg1 != null ? tip_epg1.getText().toString() : "")) {
                            ll_epg.setVisibility(View.VISIBLE);
                        }
                    }
                });
                animator.start();
            }
        }
    };

    private void showChannelInfo() {
        if (currentLiveChannelItem == null || tvChannelInfo == null) return;
        tvChannelInfo.setText(String.format(Locale.getDefault(), "%d %s %s(%d/%d)", currentLiveChannelItem.getChannelNum(),
                currentLiveChannelItem.getChannelName(), currentLiveChannelItem.getSourceName(),
                currentLiveChannelItem.getSourceIndex() + 1, currentLiveChannelItem.getSourceNum()));

        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            lParams.gravity = Gravity.LEFT;
            lParams.leftMargin = 60;
            lParams.topMargin = 30;
        } else {
            lParams.gravity = Gravity.RIGHT;
            lParams.rightMargin = 60;
            lParams.topMargin = 30;
        }
        tvChannelInfo.setLayoutParams(lParams);
        tvChannelInfo.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 3000);
    }

    private Runnable mHideChannelInfoRun = () -> {
        if (tvChannelInfo != null) tvChannelInfo.setVisibility(View.INVISIBLE);
    };

    private void initLiveObj() {
        catchup = null;
        logoUrl = null;
        int position = Hawk.get(HawkConfig.LIVE_SOURCE_SELECTED, 0);
        JsonArray live_groups = Hawk.get(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
        if (live_groups == null || live_groups.size() == 0 || position < 0 || position >= live_groups.size()) {
            return;
        }
        JsonObject livesOBJ = live_groups.get(position).getAsJsonObject();
        String type = livesOBJ.has("type") ? livesOBJ.get("type").getAsString() : "0";

        if (livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject()) {
            catchup = livesOBJ.getAsJsonObject("catchup");
            FileLogger.write("LivePlay", "echo-catchup :" + catchup.toString());
        }
        if (livesOBJ.has("logo")) {
            logoUrl = livesOBJ.get("logo").getAsString();
        }
        if (type.equals("3")) {
            String py_jar = "";
            if (livesOBJ.has("jar")) {
                py_jar = livesOBJ.has("jar") ? livesOBJ.get("jar").getAsString() : "";
            } else if (livesOBJ.has("api")) {
                py_jar = livesOBJ.has("api") ? livesOBJ.get("api").getAsString() : "";
                String ext = "";
                if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject() || livesOBJ.get("ext").isJsonArray())) {
                    ext = livesOBJ.get("ext").toString();
                } else {
                    ext = DefaultConfig.safeJsonString(livesOBJ, "ext", "");
                }
                FileLogger.write("LivePlay", "echo-ext:" + ext);
                if (!ext.isEmpty()) py_jar = py_jar + "?extend=" + ext;
            }
            ApiConfig.get().setLiveJar(py_jar);
        }
    }

    private HashMap<String, String> liveWebHeader() {
        return Hawk.get(HawkConfig.LIVE_WEB_HEADER);
    }

    private HashMap<String, String> liveChannelHeader() {
        if (currentLiveChannelItem == null) return liveWebHeader();
        HashMap<String, String> header = new HashMap<>();
        HashMap<String, String> liveHeader = liveWebHeader();
        if (liveHeader != null) header.putAll(liveHeader);
        if (currentLiveChannelItem.getHeaders() != null) {
            header.putAll(currentLiveChannelItem.getHeaders());
        }
        if (currentLiveChannelItem.getChannelFormat() != null && !currentLiveChannelItem.getChannelFormat().isEmpty()) {
            header.put(ExoMediaSourceHelper.HEADER_FORMAT, currentLiveChannelItem.getChannelFormat());
        }
        if (header.isEmpty()) return null;
        return header;
    }

    private boolean currentChannelHasCatchup() {
        return currentLiveChannelItem != null && hasCatchupSource(currentLiveChannelItem.getChannelCatchup());
    }

    private JsonObject currentCatchup() {
        if (currentChannelHasCatchup()) return currentLiveChannelItem.getChannelCatchup();
        return catchup;
    }

    private String getCatchupValue(JsonObject catchupObj, String key) {
        if (catchupObj == null || !catchupObj.has(key) || catchupObj.get(key).isJsonNull()) return "";
        try {
            return catchupObj.get(key).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean hasCatchupSource(JsonObject catchupObj) {
        return !TextUtils.isEmpty(getCatchupValue(catchupObj, "source"));
    }

    private boolean canCurrentChannelCatchup() {
        if (currentLiveChannelItem == null) return false;
        String url = currentLiveChannelItem.getUrl();
        JsonObject catchupObj = currentCatchup();
        if (hasCatchupSource(catchupObj)) {
            String regex = getCatchupValue(catchupObj, "regex");
            if (TextUtils.isEmpty(regex)) return true;
            try {
                return url.contains(regex) || Pattern.compile(regex).matcher(url).find();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return url != null && url.contains("/PLTV/");
    }

    private String buildCatchupUrl(String url, Epginfo epg) {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return "";
        JsonObject catchupObj = currentCatchup();
        if (hasCatchupSource(catchupObj)) {
            return formatCatchupUrl(url, catchupObj, epg);
        }
        if (url == null || !url.contains("/PLTV/")) return "";
        String source = "?playseek=" + formatCatchupTime(epg.startdateTime, "yyyyMMddHHmmss")
                + "-" + formatCatchupTime(epg.enddateTime, "yyyyMMddHHmmss");
        return appendCatchupUrl(url, "/PLTV/,/TVOD/", source);
    }

    private String formatCatchupUrl(String url, JsonObject catchupObj, Epginfo epg) {
        String source = formatCatchupSource(getCatchupValue(catchupObj, "source"), epg);
        if ("default".equalsIgnoreCase(getCatchupValue(catchupObj, "type"))) return source;
        return appendCatchupUrl(url, getCatchupValue(catchupObj, "replace"), source);
    }

    private String appendCatchupUrl(String url, String replace, String source) {
        String replayUrl = url;
        if (replace == null) replace = "";
        String[] parts = replace.split(",", 2);
        if (parts.length == 2 && !TextUtils.isEmpty(parts[0])) {
            try {
                replayUrl = replayUrl.replaceAll(parts[0], parts[1]);
            } catch (Throwable ignored) {
            }
        }
        int queryIndex = replayUrl.indexOf('?');
        if (queryIndex >= 0 && queryIndex < replayUrl.length() - 1) source = source.replace("?", "&");
        return replayUrl + source;
    }

    private String formatCatchupSource(String source, Epginfo epg) {
        Matcher matcher = CATCHUP_TOKEN_PATTERN.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(formatCatchupToken(matcher.group(1), epg)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String formatCatchupToken(String token, Epginfo epg) {
        Matcher matcher = CATCHUP_TAG_PATTERN.matcher(token);
        if (!matcher.find()) return "";
        String tag = matcher.group(1);
        if (tag.startsWith("utcend:")) return String.valueOf(epg.enddateTime.getTime() / 1000);
        if (tag.startsWith("utc:")) return String.valueOf(epg.startdateTime.getTime() / 1000);
        int bracketIndex = tag.indexOf(')');
        if (tag.startsWith("(b") && bracketIndex >= 0)
            return formatCatchupTime(epg.startdateTime, tag.substring(bracketIndex + 1));
        if (tag.startsWith("(e") && bracketIndex >= 0)
            return formatCatchupTime(epg.enddateTime, tag.substring(bracketIndex + 1));
        return "";
    }

    private String formatCatchupTime(Date time, String pattern) {
        if ("timestamp".equals(pattern)) return String.valueOf(time.getTime() / 1000);
        try {
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(time);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private int getCatchupDurationSeconds(Epginfo epg) {
        if (epg == null || epg.startdateTime == null || epg.enddateTime == null) return 0;
        long duration = Math.max(0, epg.enddateTime.getTime() - epg.startdateTime.getTime()) / 1000;
        return duration > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) duration;
    }

    private void showSwitchChannelSnapshot() {
        if (switchChannelSnapshotImage != null && mVideoView != null) {
            Bitmap bitmap = null;
            try {
                bitmap = mVideoView.doScreenShot();
            } catch (Throwable ignored) {
            }
            if (bitmap != null) {
                switchChannelSnapshotImage.setImageBitmap(bitmap);
                switchChannelSnapshotImage.setVisibility(View.VISIBLE);
            } else {
                switchChannelSnapshotImage.setImageBitmap(null);
                switchChannelSnapshotImage.setVisibility(View.GONE);
            }
        }
        if (switchChannelSnapshotOverlay != null) {
            switchChannelSnapshotOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideSwitchChannelSnapshot() {
        if (switchChannelSnapshotOverlay != null) {
            switchChannelSnapshotOverlay.setVisibility(View.GONE);
        }
        if (switchChannelSnapshotImage != null) {
            switchChannelSnapshotImage.setImageBitmap(null);
            switchChannelSnapshotImage.setVisibility(View.GONE);
        }
    }

    private void updateCurrentSourceName(String sourceName) {
        if (TextUtils.isEmpty(sourceName)) {
            if (tvCurrentSourceName != null) {
                tvCurrentSourceName.setVisibility(View.GONE);
            }
            return;
        }
        currentSourceName = sourceName;
        if (tvCurrentSourceName != null) {
            tvCurrentSourceName.setText(sourceName);
            tvCurrentSourceName.setVisibility(View.VISIBLE);
        }
    }

    private void installLiveReconnectListener() {
        if (liveReconnectListenerInstalled || mVideoView == null) return;
        liveReconnectListenerInstalled = true;
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PLAYING || playState == VideoView.STATE_BUFFERED) {
                    liveReconnectAttempts = 0;
                    mHandler.removeCallbacks(mLiveReconnectRun);
                } else if (playState == VideoView.STATE_ERROR || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    scheduleLiveReconnect("player_state=" + playState);
                }
            }
        });
    }

    private final Runnable mLiveReconnectRun = new Runnable() {
        @Override public void run() {
            if (isFinishing() || mVideoView == null || currentLiveChannelItem == null) return;
            if (!hasCurrentLiveChannelSource()) return;
            if (liveReconnectAttempts >= LIVE_RECONNECT_MAX_RETRIES) {
                return;
            }
            final int generation = liveReconnectGeneration;
            final String channelName = currentLiveChannelItem.getChannelName();
            final int group = currentChannelGroupIndex;
            final int channel = currentLiveChannelIndex;
            final String url = currentLiveChannelItem.getUrl();
            if (TextUtils.isEmpty(url)) return;

            liveReconnectAttempts++;
            FileLogger.write("LivePlay", "直播断线自动重连: attempt=" + liveReconnectAttempts
                    + "/" + LIVE_RECONNECT_MAX_RETRIES + " channel=[" + channelName + "]");

            try { mVideoView.release(); } catch (Exception ignored) { }
            if (generation != liveReconnectGeneration || isFinishing()) return;
            mVideoView.setUrl(url, liveChannelHeader());
            mVideoView.start();
        }
    };

    private void scheduleLiveReconnect(String reason) {
        if (isFinishing() || currentLiveChannelItem == null || !hasCurrentLiveChannelSource()) return;
        if (mVideoView != null && (mVideoView.getCurrentPlayState() == VideoView.STATE_PLAYING
                || mVideoView.getCurrentPlayState() == VideoView.STATE_BUFFERED)) return;
        if (liveReconnectAttempts >= LIVE_RECONNECT_MAX_RETRIES) return;
        mHandler.removeCallbacks(mLiveReconnectRun);
        int attempt = Math.min(liveReconnectAttempts, LIVE_RECONNECT_MAX_RETRIES - 1);
        long delay = Math.min(30000L, LIVE_RECONNECT_BASE_DELAY * (1L << Math.min(attempt, 4)));
        FileLogger.write("LivePlay", "直播断线，" + delay + "ms 后自动重连: " + reason);
        mHandler.postDelayed(mLiveReconnectRun, delay);
    }

    private void cancelLiveReconnect() {
        liveReconnectGeneration++;
        liveReconnectAttempts = 0;
        mHandler.removeCallbacks(mLiveReconnectRun);
    }

    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                || (changeSource && currentLiveChannelItem != null && currentLiveChannelItem.getSourceNum() == 1)) {
            return true;
        }
        ArrayList<LiveChannelItem> groupChannels = getLiveChannels(channelGroupIndex);
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size()) {
            return false;
        }
        boolean showPreviousFrame = currentLiveChannelItem != null && mVideoView != null && mVideoView.isPlaying();
        allowLiveSwitchPlayer = true;
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex).get(currentLiveChannelIndex);
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem.getChannelName());
            livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelName());
        }

        cancelLiveReconnect();
        channel_Name = currentLiveChannelItem;
        currentLiveLookBackIndex = -1;
        if (epgListAdapter != null) epgListAdapter.setSelectedEpgIndex(-1);
        isSHIYI = false;
        isBack = false;
        if (canCurrentChannelCatchup()) {
            currentLiveChannelItem.setinclude_back(true);
        } else {
            currentLiveChannelItem.setinclude_back(false);
        }
        updateCurrentChannelIcon();
        showBottomEpg();
        if (backcontroller != null) backcontroller.setVisibility(View.GONE);
        if (ll_right_top_huikan != null) ll_right_top_huikan.setVisibility(View.GONE);
        if (mVideoView != null) {
            if (liveChannelHeader() != null) FileLogger.write("LivePlay", "echo-" + liveChannelHeader().toString());
            if (showPreviousFrame) {
                showSwitchChannelSnapshot();
            } else {
                hideSwitchChannelSnapshot();
            }
            mVideoView.release();
            mVideoView.setUrl(currentLiveChannelItem.getUrl(), liveChannelHeader());
            mVideoView.start();
            showResolutionAfterChannelSwitch();
        }
        loadEpgAfterChannelStarted();
        return true;
    }

    private void loadEpgAfterChannelStarted() {
        mHandler.removeCallbacks(mLoadEpgRun);
        if (!hasEpgAddress()) {
            updateEpgPanelState(false);
            return;
        }
        if (hasCurrentEpgCache()) {
            firstLiveEpgLoad = false;
            return;
        }
        if (firstLiveEpgLoad) {
            firstLiveEpgLoad = false;
            mHandler.postDelayed(mLoadEpgRun, EPG_LOAD_DELAY);
        } else {
            getEpg(new Date());
        }
    }

    private boolean hasCurrentEpgCache() {
        if (channel_Name == null || liveEpgDateAdapter == null || liveEpgDateAdapter.getSelectedIndex() < 0) return false;
        String currentEpgKey = channel_Name.getChannelName() + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();
        if (hsEpg.containsKey(currentEpgKey)) return true;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        return EpgUtil.hasEpgData(channel_Name.getChannelName(), sdf.format(new Date()));
    }

    private void playNext() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    private void playPrevious() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(-1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void playNextSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    private void showSettingGroup() {
        hideBottomInfoBar();

        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        }
        if (ll_epg != null) ll_epg.setVisibility(View.GONE);
        if (tvRightSettingLayout != null) {
            tvRightSettingLayout.setTranslationX(0);
            tvRightSettingLayout.bringToFront();
        }
        if (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            ApiConfig.get().refreshLiveApiHistoryItems();
            loadCurrentSourceList();
            if (liveSettingGroupAdapter != null) {
                liveSettingGroupAdapter.setNewData(getVisibleLiveSettingGroupList());
                liveSettingGroupAdapter.setSelectedGroupIndex(-1);
            }
            int settingGroupIndex = getDefaultSettingGroupIndex();
            selectSettingGroup(settingGroupIndex, false);
            int settingGroupPosition = liveSettingGroupAdapter != null ? liveSettingGroupAdapter.findPositionByGroupIndex(settingGroupIndex) : 0;
            if (mSettingGroupView != null) mSettingGroupView.scrollToPosition(settingGroupPosition < 0 ? 0 : settingGroupPosition);
            int settingItemIndex = currentLiveChannelItem == null ? 0 : currentLiveChannelItem.getSourceIndex();
            if (liveSettingItemAdapter != null && (liveSettingItemAdapter.getData().isEmpty() || settingItemIndex < 0 || settingItemIndex >= liveSettingItemAdapter.getData().size())) {
                settingItemIndex = 0;
            }
            if (mSettingItemView != null) mSettingItemView.scrollToPosition(settingItemIndex);
            mHandler.postDelayed(mFocusAndShowSettingGroup, 50);
        } else {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        }
    }

    private Runnable mFocusAndShowSettingGroup = new Runnable() {
        @Override
        public void run() {
            if ((mSettingGroupView != null && mSettingGroupView.isScrolling())
                    || (mSettingItemView != null && mSettingItemView.isScrolling())
                    || (mSettingGroupView != null && mSettingGroupView.isComputingLayout())
                    || (mSettingItemView != null && mSettingItemView.isComputingLayout())) {
                mHandler.postDelayed(this, 100);
            } else {
                int settingGroupIndex = getDefaultSettingGroupIndex();
                int settingGroupPosition = liveSettingGroupAdapter != null ? liveSettingGroupAdapter.findPositionByGroupIndex(settingGroupIndex) : 0;
                if (mSettingGroupView != null) {
                    RecyclerView.ViewHolder holder = mSettingGroupView.findViewHolderForAdapterPosition(settingGroupPosition < 0 ? 0 : settingGroupPosition);
                    if (holder != null) holder.itemView.requestFocus();
                }
                if (tvRightSettingLayout != null) tvRightSettingLayout.bringToFront();
                if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.VISIBLE);
                if (tvRightSettingLayout != null) {
                    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvRightSettingLayout.getLayoutParams();
                    ViewObj viewObj = new ViewObj(tvRightSettingLayout, params);
                    ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginRight", new IntEvaluator(), -tvRightSettingLayout.getLayoutParams().width, livePanelEdgeMargin());
                    animator.setDuration(200);
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
                        }
                    });
                    animator.start();
                }
            }
        }
    };

    private Runnable mHideSettingLayoutRun = new Runnable() {
        @Override
        public void run() {
            if (tvRightSettingLayout == null) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvRightSettingLayout.getLayoutParams();
            if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                ViewObj viewObj = new ViewObj(tvRightSettingLayout, params);
                ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginRight", new IntEvaluator(), params.rightMargin, -tvRightSettingLayout.getLayoutParams().width);
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        tvRightSettingLayout.setVisibility(View.INVISIBLE);
                        if (liveSettingGroupAdapter != null) liveSettingGroupAdapter.setSelectedGroupIndex(-1);
                        if (ll_epg != null && tv_curepg_left != null && !"暂无信息".equals(tip_epg1 != null ? tip_epg1.getText().toString() : "")) {
                            ll_epg.setVisibility(View.VISIBLE);
                        }
                    }
                });
                animator.start();
            }
        }
    };

    private int livePanelEdgeMargin() {
        return 0;
    }

    private void applyKu9ResponsiveSizing() {
        if (tvLeftChannelListLayout == null || ku9ProgramGuide == null) return;
        tvLeftChannelListLayout.post(() -> {
            int w = tvLeftChannelListLayout.getRootView().getWidth();
            int h = tvLeftChannelListLayout.getRootView().getHeight();
            if (w <= 0 || h <= 0) return;
            int panelW = Math.round(w * 0.597f);
            int panelH = Math.round(h * 0.952f);
            int marginX = Math.round(w * 0.050f);
            int marginY = Math.round(h * 0.025f);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams();
            lp.width = panelW; lp.height = panelH; lp.leftMargin = marginX; lp.topMargin = marginY;
            lp.rightMargin = 0; lp.bottomMargin = 0; tvLeftChannelListLayout.setLayoutParams(lp);

            ViewGroup.MarginLayoutParams gp = (ViewGroup.MarginLayoutParams) ku9ProgramGuide.getLayoutParams();
            gp.width = panelW; gp.height = panelH; gp.leftMargin = marginX; gp.topMargin = marginY;
            gp.rightMargin = 0; gp.bottomMargin = 0; ku9ProgramGuide.setLayoutParams(gp);

            if (llBottomInfoBar != null) {
                ViewGroup.MarginLayoutParams bp = (ViewGroup.MarginLayoutParams) llBottomInfoBar.getLayoutParams();
                bp.width = Math.round(w * 0.597f);
                bp.height = Math.round(h * 0.235f);
                bp.leftMargin = Math.round(w * 0.050f); bp.rightMargin = 0; bp.bottomMargin = Math.round(h * 0.025f);
                llBottomInfoBar.setLayoutParams(bp);
            }
        });
    }

    private void initKu9ProgramGuide() {
        if (ku9GuideChannelList == null || ku9GuideDateList == null || ku9GuideProgramList == null) return;
        ku9GuideChannelList.setHasFixedSize(true);
        ku9GuideChannelList.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        ku9GuideChannelAdapter = new Ku9GuideChannelAdapter();
        ku9GuideChannelList.setAdapter(ku9GuideChannelAdapter);
        ku9GuideChannelList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) { ku9GuideChannelAdapter.setFocusedIndex(-1); }
            @Override public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                ku9GuideChannelFocusPosition = position;
                ku9GuideChannelAdapter.setFocusedIndex(position);
            }
            @Override public void onItemClick(TvRecyclerView parent, View itemView, int position) { selectKu9GuideChannel(position); }
        });
        ku9GuideChannelAdapter.setOnItemClickListener((adapter, view, position) -> selectKu9GuideChannel(position));

        ku9GuideDateList.setHasFixedSize(true);
        ku9GuideDateList.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        ku9GuideDateAdapter = new Ku9GuideDateAdapter();
        ku9GuideDateList.setAdapter(ku9GuideDateAdapter);
        ku9GuideDateList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                ku9GuideDateAdapter.setFocusedIndex(-1);
            }
            @Override public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                ku9GuideDateAdapter.setFocusedIndex(position);
                if (position >= 0 && position < ku9GuideDateAdapter.getItemCount()) {
                    ku9GuideDateAdapter.setSelectedIndex(position);
                    // 焦点一移动就立即加载，让节目单不会停留在“今天”
                    if (ku9GuideShowing) {
                        LiveEpgDate d = ku9GuideDateAdapter.getItem(position);
                        if (d != null) {
                            int cp = Math.max(0, Math.min(
                                    ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex,
                                    Math.max(0, ku9GuideChannelAdapter.getItemCount() - 1)));
                            loadKu9GuidePrograms(cp, d.getDateParamVal());
                        }
                    }
                }
            }
            @Override public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                selectKu9GuideDate(position);
            }
        });

        // 强制：焦点在日期栏时，按 UP/DOWN 立即切换节目列表
        ku9GuideDateList.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 等 TV RecyclerView 完成选中位置更新
                    ku9GuideDateList.postDelayed(() -> {
                        if (!ku9GuideShowing || ku9GuideDateAdapter == null) return;
                        int pos = ku9GuideDateList.getSelectedPosition();
                        if (pos < 0 || pos >= ku9GuideDateAdapter.getItemCount()) {
                            pos = ku9GuideDateAdapter.getSelectedIndex();
                        }
                        if (pos < 0 || pos >= ku9GuideDateAdapter.getItemCount()) return;

                        ku9GuideDateAdapter.setSelectedIndex(pos);
                        LiveEpgDate d = ku9GuideDateAdapter.getItem(pos);
                        if (d == null) return;

                        int cp = Math.max(0, Math.min(
                                ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex,
                                Math.max(0, ku9GuideChannelAdapter.getItemCount() - 1)));
                        FileLogger.write("LivePlay", "日期栏UP/DOWN: pos=" + pos + " -> loadKu9GuidePrograms");
                        loadKu9GuidePrograms(cp, d.getDateParamVal());
                    }, 30);
                }
            }
            return false;
        });

        ku9GuideProgramList.setHasFixedSize(true);
        ku9GuideProgramList.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        ku9GuideProgramAdapter = new Ku9GuideProgramAdapter();
        ku9GuideProgramList.setAdapter(ku9GuideProgramAdapter);
        ku9GuideProgramList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) { ku9GuideProgramAdapter.setFocusedIndex(-1); }
            @Override public void onItemSelected(TvRecyclerView parent, View itemView, int position) { ku9GuideProgramAdapter.setFocusedIndex(position); }
            @Override public void onItemClick(TvRecyclerView parent, View itemView, int position) { clickEpgItem(position); }
        });
    }

    private void showKu9ProgramGuide() {
        FileLogger.write("LivePlay", "=== showKu9ProgramGuide 进入 ===");
        if (ku9ProgramGuide == null || liveChannelGroupList == null || liveChannelGroupList.isEmpty()) {
            FileLogger.write("LivePlay", "=== showKu9ProgramGuide 提前返回: programGuide=" + (ku9ProgramGuide != null)
                    + " groupListSize=" + (liveChannelGroupList == null ? "null" : liveChannelGroupList.size()) + " ===");
            return;
        }
        hideBottomInfoBar();
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.INVISIBLE);
        if (ll_epg != null) ll_epg.setVisibility(View.GONE);
        ku9GuideShowing = true;
        ku9ProgramGuide.setVisibility(View.VISIBLE);
        ku9ProgramGuide.bringToFront();

        List<LiveChannelItem> channels = getLiveChannels(currentChannelGroupIndex);
        ku9GuideChannelAdapter.setNewData(channels != null ? channels : new ArrayList<>());
        preloadCurrentGroupEpgResources(currentChannelGroupIndex);

        int selected = Math.max(0, currentLiveChannelIndex);
        if (selected >= ku9GuideChannelAdapter.getItemCount()) selected = 0;
        if (ku9GuideChannelAdapter.getItemCount() > 0) {
            ku9GuideChannelAdapter.setSelectedIndex(ku9GuideChannelAdapter.getData().get(selected).getChannelIndex());
            ku9GuideChannelList.setSelection(selected);
        }
        // lambda 捕获的局部变量必须是 effectively final，所以这里用 final 副本
        final int selectedChannelPos = selected;

        // 先用已有数据同步构建日期栏；即使 EPG 尚未加载完成，也会用固定日期兜底
        rebuildKu9GuideDatesForChannel(selectedChannelPos);
        if (ku9GuideDateAdapter != null) ku9GuideDateAdapter.notifyDataSetChanged();
        ku9GuideDateList.post(() -> {
            if (ku9GuideDateAdapter == null) return;
            int di = findTodayGuideDateIndex();
            if (di < 0) di = 0;
            if (ku9GuideDateAdapter.getItemCount() > 0) {
                di = Math.max(0, Math.min(di, ku9GuideDateAdapter.getItemCount() - 1));
                ku9GuideDateAdapter.setSelectedIndex(di);
                ku9GuideDateList.setSelection(di);
                if (ku9GuideChannelAdapter.getItemCount() > 0) {
                    LiveEpgDate selectedDate = ku9GuideDateAdapter.getItem(di);
                    if (selectedDate != null) loadKu9GuidePrograms(selectedChannelPos, selectedDate.getDateParamVal());
                }
            }
        });

        // 打开节目单时再做一次 hash 检查：hash 变化才下载，随后用真实 EPG 日期刷新日期栏
        ensureKu9GuideEpgLoaded(selectedChannelPos);

        if (ku9GuideChannelGroupButton != null) {
            ku9GuideChannelGroupButton.setVisibility(View.VISIBLE);
            ku9GuideChannelGroupButton.bringToFront();
        }
        ku9GuideDateList.requestFocus();
    }

    private void ensureKu9GuideEpgLoaded(int channelPosition) {
        if (!ku9GuideShowing || !isXmlEpgAddress(epgStringAddress) || ku9GuideChannelAdapter == null) return;
        if (channelPosition < 0 || channelPosition >= ku9GuideChannelAdapter.getItemCount()) channelPosition = 0;
        LiveChannelItem item = ku9GuideChannelAdapter.getItem(channelPosition);
        if (item == null) return;
        if (ku9GuideEpgLoadRequested) return;
        ku9GuideEpgLoadRequested = true;
        EpgManager.getInstance(this).refreshEpg(new EpgManager.RefreshCallback() {
            @Override public void onSuccess() {
                ku9GuideEpgLoadRequested = false;
                if (!ku9GuideShowing || ku9GuideChannelAdapter == null || ku9GuideChannelAdapter.getItemCount() == 0) return;
                ArrayList<String> groupNames = new ArrayList<>();
                for (int i = 0; i < ku9GuideChannelAdapter.getItemCount(); i++) {
                    LiveChannelItem ci = ku9GuideChannelAdapter.getItem(i);
                    if (ci != null && !TextUtils.isEmpty(ci.getChannelName())) {
                        groupNames.add(ci.getChannelName());
                    }
                }
                EpgManager.getInstance(LivePlayActivity.this).loadChannelGroup(groupNames, () -> {
                    if (!ku9GuideShowing || ku9GuideChannelAdapter == null || ku9GuideChannelAdapter.getItemCount() == 0) return;
                    int cp = ku9GuideChannelFocusPosition >= 0
                            ? ku9GuideChannelFocusPosition : Math.max(0, currentLiveChannelIndex);
                    cp = Math.max(0, Math.min(cp, ku9GuideChannelAdapter.getItemCount() - 1));

                    String preservedDateKey = getSelectedKu9GuideDateKey();
                    // EPG 已解析完成，这里会拿到真实日期；如果仍为空，会走 rebuild 里的固定日期兜底
                    rebuildKu9GuideDatesForChannel(cp);
                    if (ku9GuideDateAdapter != null) ku9GuideDateAdapter.notifyDataSetChanged();
                    if (ku9GuideDateAdapter.getItemCount() == 0) return;

                    // 让 UI 先完成一次布局，再设置选中项并触发节目列表刷新
                    ku9GuideDateList.post(() -> {
                        if (!ku9GuideShowing || ku9GuideDateAdapter == null) return;
                        int di = findKu9GuideDateIndex(preservedDateKey);
                        if (di < 0) di = findTodayGuideDateIndex();
                        if (di < 0) di = 0;
                        di = Math.max(0, Math.min(di, ku9GuideDateAdapter.getItemCount() - 1));
                        ku9GuideDateAdapter.setSelectedIndex(di);
                        ku9GuideDateList.setSelection(di);
                        LiveEpgDate selectedDate = ku9GuideDateAdapter.getItem(di);
                        if (selectedDate != null) {
                            // 强制立即加载当前选中日期的节目列表
                            int cpFinal = Math.max(0, Math.min(
                                    ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex,
                                    ku9GuideChannelAdapter.getItemCount() - 1));
                            loadKu9GuidePrograms(cpFinal, selectedDate.getDateParamVal());
                        }
                    });
                });
            }
            @Override public void onError(String msg) {
                ku9GuideEpgLoadRequested = false;
            }
        });
    }

    private String getSelectedKu9GuideDateKey() {
        if (ku9GuideDateAdapter == null) return null;
        int index = ku9GuideDateAdapter.getSelectedIndex();
        if (index < 0 || index >= ku9GuideDateAdapter.getItemCount()) return null;
        LiveEpgDate item = ku9GuideDateAdapter.getItem(index);
        if (item == null || item.getDateParamVal() == null) return null;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        return fmt.format(item.getDateParamVal());
    }

    private int findKu9GuideDateIndex(String dateKey) {
        if (dateKey == null || ku9GuideDateAdapter == null) return -1;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        for (int i = 0; i < ku9GuideDateAdapter.getItemCount(); i++) {
            LiveEpgDate item = ku9GuideDateAdapter.getItem(i);
            if (item != null && item.getDateParamVal() != null && dateKey.equals(fmt.format(item.getDateParamVal()))) return i;
        }
        return -1;
    }

    private int findTodayGuideDateIndex() {
        if (ku9GuideDateAdapter == null) return 0;
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        day.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String today = day.format(new Date());
        for (int i = 0; i < ku9GuideDateAdapter.getItemCount(); i++) {
            LiveEpgDate item = ku9GuideDateAdapter.getItem(i);
            if (item != null && today.equals(day.format(item.getDateParamVal()))) return i;
        }
        return ku9GuideDateAdapter.getItemCount() > 0 ? 0 : -1;
    }

    private void rebuildKu9GuideDatesForChannel(int channelPosition) {
        if (ku9GuideDateAdapter == null || ku9GuideChannelAdapter == null || ku9GuideChannelAdapter.getItemCount() == 0) return;
        if (channelPosition < 0 || channelPosition >= ku9GuideChannelAdapter.getItemCount()) channelPosition = 0;
        LiveChannelItem item = ku9GuideChannelAdapter.getItem(channelPosition);
        if (item == null) return;
        String channelName = item.getChannelName();
        if (TextUtils.isEmpty(channelName)) return;

        List<Date> available = new ArrayList<>();
        try {
            if (isXmlEpgAddress(epgStringAddress)) {
                available.addAll(EpgManager.getInstance(this).getAvailableDatesForChannel(channelName));
            } else {
                available.addAll(EpgManager.getInstance(this).getAllAvailableDates());
            }
        } catch (Exception e) {
            FileLogger.write("LivePlay", "rebuildKu9GuideDatesForChannel error: " + e.getMessage());
        }
        FileLogger.write("LivePlay", "rebuildKu9GuideDatesForChannel: channel=[" + channelName + "] available=" + available.size());

        ku9GuideDateAdapter.setNewData(new ArrayList<LiveEpgDate>());
        SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.CHINA);
        SimpleDateFormat md = new SimpleDateFormat("MM-dd", Locale.CHINA);
        md.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        Calendar todayCal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00"));
        todayCal.set(Calendar.HOUR_OF_DAY, 0); todayCal.set(Calendar.MINUTE, 0);
        todayCal.set(Calendar.SECOND, 0); todayCal.set(Calendar.MILLISECOND, 0);
        String todayKey = md.format(todayCal.getTime());

        if (available.isEmpty()) {
            // EPG 尚未解析完成时先用固定范围占位；解析完成后 ensureKu9GuideEpgLoaded 会再次重建
            for (int i = -2; i <= 7; i++) {
                Calendar d = (Calendar) todayCal.clone();
                d.add(Calendar.DAY_OF_MONTH, i);
                addKu9GuideDateItem(d.getTime(), i + 2, weekday, md, todayKey);
            }
        } else {
            int idx = 0;
            for (Date d : available) addKu9GuideDateItem(d, idx++, weekday, md, todayKey);
        }

        ku9GuideDateAdapter.notifyDataSetChanged();
        FileLogger.write("LivePlay", "rebuildKu9GuideDatesForChannel: 实际填充 " + ku9GuideDateAdapter.getItemCount() + " 天");
    }

    private void addKu9GuideDateItem(Date date, int index, SimpleDateFormat weekday, SimpleDateFormat md, String todayKey) {
        LiveEpgDate item = new LiveEpgDate();
        item.setIndex(index);
        String key = md.format(date);
        String dayLabel;
        if (key.equals(todayKey)) dayLabel = "今天";
        else {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00")); c.setTime(date);
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00")); now.set(Calendar.HOUR_OF_DAY,0); now.set(Calendar.MINUTE,0); now.set(Calendar.SECOND,0); now.set(Calendar.MILLISECOND,0);
            long diff = (c.getTimeInMillis() - now.getTimeInMillis()) / TimeUnit.DAYS.toMillis(1);
            if (diff == 1) dayLabel = "明天";
            else if (diff == 2) dayLabel = "后天";
            else dayLabel = weekday.format(date);
        }
        item.setDatePresented(dayLabel + "\n" + md.format(date));
        item.setDateParamVal(date);
        ku9GuideDateAdapter.addData(item);
    }

    private void refreshKu9GuideIfShowing() {
        if (!ku9GuideShowing || ku9GuideChannelAdapter == null || ku9GuideDateAdapter == null) return;
        int channelPos = Math.max(0, ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex);
        int datePos = ku9GuideDateAdapter.getSelectedIndex();
        if (datePos < 0) datePos = findTodayGuideDateIndex();
        if (datePos < 0) datePos = 0;
        if (datePos < ku9GuideDateAdapter.getData().size()) {
            loadKu9GuidePrograms(channelPos, ku9GuideDateAdapter.getItem(datePos).getDateParamVal());
            ku9GuideChannelAdapter.notifyDataSetChanged();
        }
    }

    private void hideKu9ProgramGuide() {
        ku9GuideShowing = false;
        if (ku9ProgramGuide != null) ku9ProgramGuide.setVisibility(View.GONE);
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
    }

    private void showChannelListFromGuide() {
        hideKu9ProgramGuide();
        if (tvLeftChannelListLayout != null) {
            tvLeftChannelListLayout.setVisibility(View.VISIBLE);
            tvLeftChannelListLayout.bringToFront();
        }
        if (mChannelGroupView != null) {
            mChannelGroupView.requestFocus();
            mChannelGroupView.setSelection(Math.max(0, currentChannelGroupIndex));
        }
    }

    private void loadKu9GuidePrograms(int channelPosition, Date date) {
        if (ku9GuideChannelAdapter == null || ku9GuideProgramAdapter == null || date == null) {
            FileLogger.write("LivePlay", "loadKu9GuidePrograms 提前返回: channelAdapter=" + (ku9GuideChannelAdapter != null)
                    + " programAdapter=" + (ku9GuideProgramAdapter != null) + " date=" + date);
            return;
        }
        if (channelPosition < 0 || channelPosition >= ku9GuideChannelAdapter.getData().size()) channelPosition = 0;
        LiveChannelItem item = ku9GuideChannelAdapter.getItem(channelPosition);
        if (item == null) return;
        String channel = item.getChannelName();
        if (TextUtils.isEmpty(channel)) return;

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date);
        FileLogger.write("LivePlay", "loadKu9GuidePrograms: channel=[" + channel + "] date=[" + dateStr + "] pos=" + channelPosition);

        ArrayList<Epginfo> list = new ArrayList<>();
        try {
            List<EpgManager.EpgProgram> downloaded = EpgManager.getInstance(this).getProgramsForChannelOnDate(channel, date);
            if (downloaded != null) {
                for (EpgManager.EpgProgram p : downloaded) {
                    if (p == null || p.start == null || p.stop == null || !p.stop.after(p.start)) continue;
                    Epginfo info = createXmlEpgInfoFromProgram(p, list.size());
                    info.desc = p.description == null ? "" : p.description;
                    list.add(info);
                }
            }
        } catch (Exception e) {
            FileLogger.write("LivePlay", "loadKu9GuidePrograms error: " + e.getMessage());
        }

        if (!isXmlEpgAddress(epgStringAddress) && list.isEmpty()) {
            try { list = EpgUtil.loadEpgData(channel, dateStr, date); } catch (Exception ignored) { }
        }
        if (!isXmlEpgAddress(epgStringAddress) && list.isEmpty()) {
            String key = channel + "_" + new SimpleDateFormat("EEE\nMM-dd", Locale.CHINA).format(date);
            ArrayList<Epginfo> cached = hsEpg.get(key);
            if (cached != null) list = new ArrayList<>(cached);
        }

        if (list.isEmpty()) {
            ku9GuideProgramAdapter.setNewData(new ArrayList<>());
            ku9GuideProgramAdapter.setSelectedIndex(-1);
            FileLogger.write("LivePlay", "loadKu9GuidePrograms EMPTY: channel=[" + channel + "] date=[" + dateStr + "]");
            return;
        }

        Collections.sort(list, (a, b) -> {
            if (a == null || a.startdateTime == null) return 1;
            if (b == null || b.startdateTime == null) return -1;
            return a.startdateTime.compareTo(b.startdateTime);
        });
        for (int i = 0; i < list.size(); i++) if (list.get(i) != null) list.get(i).index = i;
        ku9GuideProgramAdapter.setCanBack(item.getinclude_back());
        ku9GuideProgramAdapter.setNewData(list);
        int current = findCurrentEpgIndex(list);
        ku9GuideProgramAdapter.setSelectedIndex(current);
        FileLogger.write("LivePlay", "loadKu9GuidePrograms loaded: channel=[" + channel + "] date=[" + dateStr + "] count=" + list.size());
    }

    private void enrichEpgDescriptions(String channelName, ArrayList<Epginfo> list) {
        if (TextUtils.isEmpty(channelName) || list == null || list.isEmpty()) return;
        try {
            List<EpgManager.EpgProgram> programs = EpgManager.getInstance(this).getProgramsForChannel(channelName);
            if (programs == null || programs.isEmpty()) return;
            for (Epginfo info : list) {
                if (info == null || !TextUtils.isEmpty(info.desc)) continue;
                for (EpgManager.EpgProgram p : programs) {
                    if (p == null || p.start == null || p.stop == null) continue;
                    boolean same = info.startdateTime != null && info.enddateTime != null
                            && Math.abs(info.startdateTime.getTime() - p.start.getTime()) < 120000
                            && Math.abs(info.enddateTime.getTime() - p.stop.getTime()) < 120000;
                    if (same) { info.desc = p.description == null ? "" : p.description; break; }
                }
            }
        } catch (Exception ignored) { }
    }

    private void selectKu9GuideChannel(int position) {
        if (ku9GuideChannelAdapter == null || position < 0 || position >= ku9GuideChannelAdapter.getData().size()) return;
        LiveChannelItem item = ku9GuideChannelAdapter.getItem(position);
        if (item == null) return;

        int targetGroup = currentChannelGroupIndex;
        int targetIndex = position;
        if (liveChannelGroupList != null) {
            boolean found = false;
            for (int gi = 0; gi < liveChannelGroupList.size() && !found; gi++) {
                List<LiveChannelItem> group = getLiveChannels(gi);
                if (group == null) continue;
                for (int ci = 0; ci < group.size(); ci++) {
                    LiveChannelItem candidate = group.get(ci);
                    if (candidate == item || (candidate != null && item.getChannelName() != null && item.getChannelName().equals(candidate.getChannelName()))) {
                        targetGroup = gi;
                        targetIndex = ci;
                        found = true;
                        break;
                    }
                }
            }
        }

        boolean switched = playChannel(targetGroup, targetIndex, false);
        if (!switched) {
            FileLogger.write("LivePlay", "节目单换台失败: group=" + targetGroup + ", index=" + targetIndex + ", channel=" + item.getChannelName());
            return;
        }
        currentChannelGroupIndex = targetGroup;
        currentLiveChannelIndex = targetIndex;
        currentLiveChannelItem = getLiveChannels(targetGroup).get(targetIndex);
        channel_Name = currentLiveChannelItem;

        List<LiveChannelItem> newChannels = getLiveChannels(targetGroup);
        ku9GuideChannelAdapter.setNewData(newChannels != null ? newChannels : new ArrayList<>());
        int guidePos = Math.max(0, Math.min(targetIndex, ku9GuideChannelAdapter.getItemCount() - 1));
        ku9GuideChannelFocusPosition = guidePos;
        if (ku9GuideChannelAdapter.getItemCount() > 0) {
            ku9GuideChannelAdapter.setSelectedIndex(currentLiveChannelItem.getChannelIndex());
            ku9GuideChannelList.setSelection(guidePos);
        }

        String keepDateKey = null;
        int oldDateIndex = ku9GuideDateAdapter == null ? -1 : ku9GuideDateAdapter.getSelectedIndex();
        if (oldDateIndex >= 0 && oldDateIndex < ku9GuideDateAdapter.getItemCount()) {
            keepDateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(ku9GuideDateAdapter.getItem(oldDateIndex).getDateParamVal());
        }
        final String preservedDateKey = keepDateKey;
        if (isXmlEpgAddress(epgStringAddress)) {
            ArrayList<String> groupNames = new ArrayList<>();
            for (LiveChannelItem ci : newChannels) {
                if (ci != null && !TextUtils.isEmpty(ci.getChannelName())) groupNames.add(ci.getChannelName());
            }
            EpgManager.getInstance(this).loadChannelGroup(groupNames, () -> {
                if (!ku9GuideShowing || ku9GuideChannelAdapter == null || guidePos >= ku9GuideChannelAdapter.getItemCount()) return;
                rebuildKu9GuideDatesForChannel(guidePos);
                loadGuideChannelAllDatesAndSelect(guidePos, preservedDateKey);
                ku9GuideChannelAdapter.notifyDataSetChanged();
            });
        } else {
            rebuildKu9GuideDatesForChannel(guidePos);
        }
        ensureKu9GuideEpgLoaded(guidePos);
        int dateIndex = -1;
        if (keepDateKey != null) {
            for (int i = 0; i < ku9GuideDateAdapter.getItemCount(); i++) {
                if (keepDateKey.equals(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(ku9GuideDateAdapter.getItem(i).getDateParamVal()))) { dateIndex = i; break; }
            }
        }
        if (dateIndex < 0) {
            dateIndex = 0;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            for (int i = 0; i < ku9GuideDateAdapter.getItemCount(); i++) {
                if (today.equals(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(ku9GuideDateAdapter.getItem(i).getDateParamVal()))) { dateIndex = i; break; }
            }
        }
        if (dateIndex < ku9GuideDateAdapter.getItemCount()) {
            ku9GuideDateAdapter.setSelectedIndex(dateIndex);
            ku9GuideDateList.setSelection(dateIndex);
            Date date = ku9GuideDateAdapter.getItem(dateIndex).getDateParamVal();
            loadKu9GuidePrograms(guidePos, date);
            if (liveEpgDateAdapter != null && dateIndex < liveEpgDateAdapter.getItemCount()) liveEpgDateAdapter.setSelectedIndex(dateIndex);
        }
        refreshKu9GuideIfShowing();
    }

    private void loadGuideChannelAllDatesAndSelect(int channelPosition, String preferredDateKey) {
        if (!ku9GuideShowing || ku9GuideDateAdapter == null || ku9GuideChannelAdapter == null) return;
        rebuildKu9GuideDatesForChannel(channelPosition);
        if (ku9GuideDateAdapter.getItemCount() == 0) return;

        int dateIndex = -1;
        if (preferredDateKey != null) {
            SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            for (int i = 0; i < ku9GuideDateAdapter.getItemCount(); i++) {
                LiveEpgDate d = ku9GuideDateAdapter.getItem(i);
                if (d != null && preferredDateKey.equals(keyFormat.format(d.getDateParamVal()))) {
                    dateIndex = i;
                    break;
                }
            }
        }
        if (dateIndex < 0) {
            dateIndex = findTodayGuideDateIndex();
            if (dateIndex < 0) dateIndex = 0;
        }
        ku9GuideDateAdapter.setSelectedIndex(dateIndex);
        ku9GuideDateList.setSelection(dateIndex);
        LiveEpgDate selectedDate = ku9GuideDateAdapter.getItem(dateIndex);
        if (selectedDate != null) loadKu9GuidePrograms(channelPosition, selectedDate.getDateParamVal());
    }

    private void selectKu9GuideDate(int position) {
        if (ku9GuideDateAdapter == null || position < 0 || position >= ku9GuideDateAdapter.getData().size()) return;
        ku9GuideDateAdapter.setSelectedIndex(position);
        if (liveEpgDateAdapter != null && position < liveEpgDateAdapter.getItemCount()) liveEpgDateAdapter.setSelectedIndex(position);
        LiveEpgDate item = ku9GuideDateAdapter.getItem(position);
        if (item != null) {
            int channelPos = Math.max(0, ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex);
            loadKu9GuidePrograms(channelPos, item.getDateParamVal());
            if (ku9GuideProgramAdapter.getItemCount() == 0 && !isXmlEpgAddress(epgStringAddress)) {
                getEpg(item.getDateParamVal());
            }
        }
        ku9GuideDateList.setSelection(position);
    }

    private void initEpgListView() {
        if (mRightEpgList == null) return;
        mRightEpgList.setHasFixedSize(true);
        mRightEpgList.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        epgListAdapter = new LiveEpgAdapter();
        mRightEpgList.setAdapter(epgListAdapter);

        mRightEpgList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });
        mRightEpgList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (epgListAdapter != null) epgListAdapter.setFocusedEpgIndex(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
                if (epgListAdapter != null) epgListAdapter.setFocusedEpgIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickEpgItem(position);
            }
        });

        epgListAdapter.setOnItemClickListener((adapter, view, position) -> clickEpgItem(position));
    }

    private void clickEpgItem(int position) {
        if (position == currentLiveLookBackIndex) return;
        if (liveEpgDateAdapter == null) return;
        Date date = liveEpgDateAdapter.getSelectedIndex() < 0 ? new Date() :
                liveEpgDateAdapter.getData().get(liveEpgDateAdapter.getSelectedIndex()).getDateParamVal();
        if (epgListAdapter == null) return;
        Epginfo selectedData = epgListAdapter.getItem(position);
        if (selectedData == null || selectedData.startdateTime == null || selectedData.enddateTime == null) return;
        Date now = new Date();
        if (new Date().compareTo(selectedData.startdateTime) < 0) return;
        if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return;
        currentLiveLookBackIndex = position;
        epgListAdapter.setSelectedEpgIndex(position);
        if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
            if (mVideoView != null) mVideoView.release();
            isSHIYI = false;
            mVideoView.setUrl(currentLiveChannelItem.getUrl(), liveChannelHeader());
            mVideoView.start();
            epgListAdapter.setShiyiSelection(-1, false, timeFormat.format(date));
            epgListAdapter.notifyDataSetChanged();
            showProgressBars(false);
            return;
        }
        String shiyiUrl = currentLiveChannelItem.getUrl();
        if (now.compareTo(selectedData.startdateTime) < 0) {
        } else if (canCurrentChannelCatchup()) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, 100);
            if (mVideoView != null) mVideoView.release();
            isSHIYI = true;
            shiyiUrl = buildCatchupUrl(shiyiUrl, selectedData);
            if (TextUtils.isEmpty(shiyiUrl)) return;
            FileLogger.write("LivePlay", "echo-回看地址playUrl :" + shiyiUrl);
            playUrl = shiyiUrl;
            if (liveChannelHeader() != null) FileLogger.write("LivePlay", "echo-liveWebHeader :" + liveChannelHeader().toString());
            mVideoView.setUrl(playUrl, liveChannelHeader());
            mVideoView.start();
            epgListAdapter.setShiyiSelection(position, true, timeFormat.format(date));
            epgListAdapter.notifyDataSetChanged();
            mRightEpgList.setSelectedPosition(position);
            mRightEpgList.post(() -> mRightEpgList.smoothScrollToPosition(position));
            shiyi_time_c = getCatchupDurationSeconds(selectedData);
            ViewGroup.LayoutParams lp = iv_play.getLayoutParams();
            lp.width = videoHeight / 7;
            lp.height = videoHeight / 7;
            if (sBar != null) {
                sBar.setMax(safeTimeMs((long) shiyi_time_c * 1000));
                sBar.setProgress(safeTimeMs(mVideoView.getCurrentPosition()));
            }
            if (tv_currentpos != null) tv_currentpos.setText(durationToString(safeTimeMs(mVideoView.getCurrentPosition())));
            if (tv_duration != null) tv_duration.setText(durationToString(safeTimeMs((long) shiyi_time_c * 1000)));
            showProgressBars(true);
            isBack = true;
        }
    }

    private void initDayList() {
        liveDayList.clear();
        LiveDayListGroup daylist = new LiveDayListGroup();
        Date newday = new Date((nowday.getTime()));
        String day = formatDate1.format(newday);
        FileLogger.write("LivePlay", "echo-date" + day);
        daylist.setGroupIndex(0);
        daylist.setGroupName(day);
        liveDayList.add(daylist);
    }

    private void initEpgDateView() {
        if (mEpgDateGridView == null) return;
        mEpgDateGridView.setHasFixedSize(true);
        mEpgDateGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveEpgDateAdapter = new LiveEpgDateAdapter();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        @SuppressLint("SimpleDateFormat") SimpleDateFormat datePresentFormat = new SimpleDateFormat("EEE\nMM-dd", Locale.CHINA);
        for (int i = -6; i <= 2; i++) {
            Calendar d = (Calendar) calendar.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            LiveEpgDate epgDate = new LiveEpgDate();
            epgDate.setIndex(i + 6);
            epgDate.setDatePresented(datePresentFormat.format(d.getTime()));
            epgDate.setDateParamVal(d.getTime());
            liveEpgDateAdapter.addData(epgDate);
        }
        mEpgDateGridView.setAdapter(liveEpgDateAdapter);
        liveEpgDateAdapter.setSelectedIndex(6);
        mEpgDateGridView.setVisibility(View.GONE);
    }

    private void initVideoView() {
        LiveController controller = new LiveController(this);
        controller.setListener(new LiveController.LiveControlListener() {
            @Override
            public boolean singleTap() {
                showChannelList();
                return true;
            }

            @Override
            public void longPress() {
                if (isBack) {
                    showProgressBars(true);
                } else {
                    showSettingGroup();
                }
            }

            @Override
            public void playStateChanged(int playState) {
                mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                switch (playState) {
                    case VideoView.STATE_IDLE:
                    case VideoView.STATE_PAUSED:
                        break;
                    case VideoView.STATE_PREPARED:
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        hideSwitchChannelSnapshot();
                        if (resolutionInfoPending) {
                            resolutionInfoRetryCount = 0;
                            mHandler.removeCallbacks(mUpdateResolutionInfoRun);
                            mHandler.post(mUpdateResolutionInfoRun);
                        }
                        currentLiveChangeSourceTimes = 0;
                        allowLiveSwitchPlayer = true;
                        break;
                    case VideoView.STATE_ERROR:
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        hideSwitchChannelSnapshot();
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, 3500);
                        break;
                    case VideoView.STATE_PREPARING:
                    case VideoView.STATE_BUFFERING:
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L);
                        break;
                    default:
                        FileLogger.write("LivePlay", "echo-Unexpected live_play state: " + playState);
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0) {
                    if (isBack) showProgressBars(true);
                    else playNextSource();
                } else {
                    playPreSource();
                }
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    private boolean switchLivePlayerAndReplay() {
        if (!allowLiveSwitchPlayer || currentLiveChannelItem == null || mVideoView == null) {
            return false;
        }
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mVideoView.release();
        if (!livePlayerManager.switchLivePlayer(mVideoView, currentLiveChannelItem.getChannelName())) {
            allowLiveSwitchPlayer = false;
            return false;
        }
        allowLiveSwitchPlayer = false;
        String retryUrl = isSHIYI && !TextUtils.isEmpty(playUrl) ? playUrl : currentLiveChannelItem.getUrl();
        mVideoView.setUrl(retryUrl, liveChannelHeader());
        mVideoView.start();
        return true;
    }

    private Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            if (switchLivePlayerAndReplay()) {
                return;
            }
            currentLiveChangeSourceTimes++;
            if (currentLiveChannelItem != null && currentLiveChannelItem.getSourceNum() == currentLiveChangeSourceTimes) {
                currentLiveChangeSourceTimes = 0;
                Integer[] groupChannelIndex = getNextChannel(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false) ? -1 : 1);
                playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
            } else {
                playNextSource();
            }
        }
    };

    private void initChannelGroupView() {
        if (mChannelGroupView == null) return;
        mChannelGroupView.setHasFixedSize(true);
        mChannelGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveChannelGroupAdapter = new LiveChannelGroupAdapter();
        mChannelGroupView.setAdapter(liveChannelGroupAdapter);
        mChannelGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });
        mChannelGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectChannelGroup(position, true, -1);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (isNeedInputPassword(position)) {
                    showPasswordDialog(position, -1);
                }
            }
        });
        liveChannelGroupAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            selectChannelGroup(position, false, -1);
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus && tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() != View.VISIBLE) return;
        mLastChannelGroupIndex = groupIndex;
        if (focus) {
            if (liveChannelGroupAdapter != null) liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            clearFocusedChannelInMenu();
        }
        if (liveChannelGroupAdapter != null && (groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            if (focus && liveChannelIndex < 0) {
                loadChannelGroupData(groupIndex);
            } else {
                loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
            }
        }
        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, postTimeout);
        }
    }

    private void clearFocusedChannelInMenu() {
        if (tvLeftChannelListLayout == null || tvLeftChannelListLayout.getVisibility() != View.VISIBLE) return;
        if (mLiveChannelView == null) return;
        if (mLiveChannelView.isComputingLayout() || mLiveChannelView.isScrolling()) {
            mLiveChannelView.postDelayed(this::clearFocusedChannelInMenu, 50);
            return;
        }
        if (liveChannelItemAdapter != null) liveChannelItemAdapter.setFocusedChannelIndex(-1);
    }

    private void initLiveChannelView() {
        if (mLiveChannelView == null) return;
        mLiveChannelView.setHasFixedSize(true);
        mLiveChannelView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveChannelItemAdapter = new LiveChannelItemAdapter();
        mLiveChannelView.setAdapter(liveChannelItemAdapter);
        mLiveChannelView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });
        mLiveChannelView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                if (liveChannelGroupAdapter != null) liveChannelGroupAdapter.setFocusedGroupIndex(-1);
                if (liveChannelItemAdapter != null) liveChannelItemAdapter.setFocusedChannelIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickLiveChannel(position);
            }
        });
        liveChannelItemAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            if (liveChannelItemAdapter != null) liveChannelItemAdapter.setSelectedChannelIndex(position);
            clickLiveChannel(position);
        });
    }

    private void clickLiveChannel(int position) {
        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, postTimeout);
        }
        int groupIndex = liveChannelGroupAdapter != null ? liveChannelGroupAdapter.getSelectedGroupIndex() : currentChannelGroupIndex;
        playChannel(groupIndex, position, false);
    }

    private void initSettingGroupView() {
        if (mSettingGroupView == null) return;
        mSettingGroupView.setHasFixedSize(true);
        mSettingGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveSettingGroupAdapter = new LiveSettingGroupAdapter();
        mSettingGroupView.setAdapter(liveSettingGroupAdapter);
        mSettingGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
            }
        });
        mSettingGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectVisibleSettingGroup(position, true);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        liveSettingGroupAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            selectVisibleSettingGroup(position, false);
        });
    }

    private void selectVisibleSettingGroup(int position, boolean focus) {
        if (liveSettingGroupAdapter == null || position < 0 || position >= liveSettingGroupAdapter.getData().size()) return;
        selectSettingGroup(liveSettingGroupAdapter.getData().get(position).getGroupIndex(), focus);
    }

    private LiveSettingGroup findSettingGroupByIndex(int groupIndex) {
        if (liveSettingGroupList == null) return null;
        for (LiveSettingGroup group : liveSettingGroupList) {
            if (group != null && group.getGroupIndex() == groupIndex) {
                return group;
            }
        }
        return null;
    }

    private void selectSettingGroup(int position, boolean focus) {
        if (focus) {
            if (liveSettingGroupAdapter != null) liveSettingGroupAdapter.setFocusedGroupIndex(position);
            if (liveSettingItemAdapter != null) liveSettingItemAdapter.setFocusedItemIndex(-1);
        }
        LiveSettingGroup targetGroup = findSettingGroupByIndex(position);
        if (liveSettingGroupAdapter != null && (position == liveSettingGroupAdapter.getSelectedGroupIndex() || targetGroup == null))
            return;

        if (liveSettingGroupAdapter != null) liveSettingGroupAdapter.setSelectedGroupIndex(position);
        if (liveSettingItemAdapter != null && targetGroup != null) {
            List<LiveSettingItem> items = targetGroup.getLiveSettingItems();
            liveSettingItemAdapter.setNewData(items != null ? items : new ArrayList<>());
        }

        switch (position) {
            case 0:
                if (currentLiveChannelItem != null && currentLiveChannelItem.getSourceIndex() >= 0
                        && liveSettingItemAdapter != null && currentLiveChannelItem.getSourceIndex() < liveSettingItemAdapter.getData().size()) {
                    liveSettingItemAdapter.selectItem(currentLiveChannelItem.getSourceIndex(), true, false);
                }
                break;
            case 1:
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerScale(), true, true);
                break;
            case 2:
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerType(), true, true);
                break;
            case 6:
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(getCurrentLiveApiHistoryIndex(), true, true);
                break;
            case 7:
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(-1, false, false);
                break;
            case 10:
                if (liveSettingItemAdapter != null) {
                    int sourceIndex = EpgManager.LOGO_SOURCE_GITHUB.equals(EpgManager.getLogoSource(this)) ? 1 : 0;
                    liveSettingItemAdapter.selectItem(sourceIndex, true, false);
                }
                break;
        }
        int scrollToPosition = liveSettingItemAdapter != null ? liveSettingItemAdapter.getSelectedItemIndex() : 0;
        if (scrollToPosition < 0) scrollToPosition = 0;
        if (mSettingItemView != null) mSettingItemView.scrollToPosition(scrollToPosition);
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
    }

    private void initSettingItemView() {
        if (mSettingItemView == null) return;
        mSettingItemView.setHasFixedSize(true);
        mSettingItemView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveSettingItemAdapter = new LiveSettingItemAdapter();
        mSettingItemView.setAdapter(liveSettingItemAdapter);
        mSettingItemView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
            }
        });
        mSettingItemView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                if (liveSettingGroupAdapter != null) liveSettingGroupAdapter.setFocusedGroupIndex(-1);
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.setFocusedItemIndex(position);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickSettingItem(position);
            }
        });
        liveSettingItemAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            clickSettingItem(position);
        });
    }

    private void clickSettingItem(int position) {
        int realGroupIndex = liveSettingGroupAdapter != null ? liveSettingGroupAdapter.getSelectedGroupIndex() : -1;

        if (realGroupIndex >= 0 && realGroupIndex < 3 && !isCurrentLiveChannelValid()) {
            return;
        }
        if (realGroupIndex < 4) {
            if (liveSettingItemAdapter != null && position == liveSettingItemAdapter.getSelectedItemIndex())
                return;
            if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(position, true, true);
        }
        switch (realGroupIndex) {
            case 0:
                if (currentLiveChannelItem == null || position < 0 || position >= currentLiveChannelItem.getSourceNum()) break;
                currentLiveChannelItem.setSourceIndex(position);
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
                break;
            case 1:
                livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem != null ? currentLiveChannelItem.getChannelName() : "");
                break;
            case 2:
                if (mVideoView != null) mVideoView.release();
                livePlayerManager.changeLivePlayerType(mVideoView, position, currentLiveChannelItem != null ? currentLiveChannelItem.getChannelName() : "");
                if (currentLiveChannelItem != null && mVideoView != null) {
                    mVideoView.setUrl(currentLiveChannelItem.getUrl(), liveChannelHeader());
                    mVideoView.start();
                }
                break;
            case 3:
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position);
                break;
            case 4:
                boolean select = false;
                switch (position) {
                    case 0:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_TIME, select);
                        showTime();
                        break;
                    case 1:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, select);
                        showNetSpeed();
                        break;
                    case 2:
                        select = !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false);
                        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, select);
                        break;
                    case 3:
                        select = !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false);
                        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, select);
                        break;
                }
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(position, select, false);
                break;
            case 5:
                if (position == ApiConfig.getLiveGroupIndex()) break;
                String currentChannelName = getPreferredLiveRefreshChannelName();
                int currentSourceIndex = getPreferredLiveRefreshSourceIndex();
                JsonArray live_groups = Hawk.get(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
                if (live_groups == null || position >= live_groups.size()) break;
                liveConfigRequestId++;
                JsonObject livesOBJ = live_groups.get(position).getAsJsonObject();
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(position, true, true);
                ApiConfig.setLiveGroupIndex(position);
                ApiConfig.get().loadLiveApi(livesOBJ);
                if (ApiConfig.get().getChannelGroupList().isEmpty()) {
                    if (mVideoView != null) mVideoView.release();
                    setEmptyLiveChannelList(false);
                    break;
                }
                refreshLiveChannelListAndPlay(currentChannelName, currentSourceIndex);
                break;
            case 6: {
                ArrayList<String> history = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<>());
                if (history.isEmpty() || position < 0 || position >= history.size()) break;
                String value = history.get(position);
                String oldLiveApi = Hawk.get(HawkConfig.LIVE_API_URL, "");
                String configChannelName = getPreferredLiveRefreshChannelName();
                int configSourceIndex = getPreferredLiveRefreshSourceIndex();
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(position, true, true);
                if (value.equals(oldLiveApi)) break;
                final int requestId = ++liveConfigRequestId;
                Hawk.put(HawkConfig.LIVE_API_URL, value);
                HistoryHelper.setLiveApiHistory(value);
                ApiConfig.get().refreshLiveApiHistoryItems();
                ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        mHandler.post(() -> {
                            if (requestId != liveConfigRequestId || isFinishing()) return;
                            refreshLiveChannelListAndPlay(configChannelName, configSourceIndex);
                        });
                    }

                    @Override
                    public void error(String msg) {
                        mHandler.post(() -> {
                            if (requestId != liveConfigRequestId || isFinishing()) return;
                            if (mVideoView != null) mVideoView.release();
                            ApiConfig.get().refreshLiveApiHistoryItems();
                            setEmptyLiveChannelList(false);
                            Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void notice(String msg) {
                        mHandler.post(() -> {
                            if (requestId != liveConfigRequestId || isFinishing()) return;
                            Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
                break;
            }
            case 7:
                if (position == 0) {
                    showSourceManageDialog();
                } else if (position == 1) {
                    performUpdateSubscription();
                }
                break;
            case 8:
                if (position == 0) {
                    showInputDialog("EPG订阅地址", Hawk.get(HawkConfig.EPG_URL, ""), val -> {
                        if (!val.isEmpty()) {
                            Hawk.put(HawkConfig.EPG_URL, val);
                            epgStringAddress = val;
                            EpgManager.getInstance(this).setEpgUrl(val);
                            Toast.makeText(this, "已保存EPG地址", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else if (position == 1) {
                    EpgManager.getInstance(this).refreshEpg(new EpgManager.RefreshCallback() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(LivePlayActivity.this, "EPG 更新成功", Toast.LENGTH_SHORT).show();
                            if (channel_Name != null) {
                                getEpg(new Date());
                            }
                        }

                        @Override
                        public void onError(String msg) {
                            Toast.makeText(LivePlayActivity.this, "EPG 更新失败: " + msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                break;
            case 9:
                float[] speedValues = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
                if (position >= 0 && position < speedValues.length) {
                    if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(position, true, true);
                    if (mVideoView != null) mVideoView.setSpeed(speedValues[position]);
                }
                break;
            case 10:
                EpgManager.setLogoSource(this, position == 1 ? EpgManager.LOGO_SOURCE_GITHUB : EpgManager.LOGO_SOURCE_EPG);
                if (liveSettingItemAdapter != null) liveSettingItemAdapter.selectItem(position, true, false);
                EpgManager.getInstance(this).clearLogoSourceCache();
                if (liveChannelItemAdapter != null) liveChannelItemAdapter.notifyDataSetChanged();
                if (ku9GuideChannelAdapter != null) ku9GuideChannelAdapter.notifyDataSetChanged();
                if (channel_Name != null) updateCurrentChannelIcon();
                break;
        }
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
    }

    private void showSourceManageDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("列表订阅");
        builder.setCancelable(true);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.HORIZONTAL);
        mainLayout.setPadding(40, 30, 40, 30);
        mainLayout.setBackgroundColor(0xDD000000);
        mainLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout leftPanel = new LinearLayout(this);
        leftPanel.setOrientation(LinearLayout.VERTICAL);
        leftPanel.setGravity(Gravity.CENTER);
        leftPanel.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        leftPanel.setLayoutParams(leftParams);

        ImageView qrImage = new ImageView(this);
        qrImage.setLayoutParams(new ViewGroup.LayoutParams(180, 180));
        leftPanel.addView(qrImage);

        TextView deviceInfo = new TextView(this);
        String ip = getDeviceIp();
        String port = "9978";
        String content = "http://" + ip + ":" + port + "/";
        deviceInfo.setText("扫码输入（点击二维码查看说明）\n" + content);
        deviceInfo.setTextColor(0xFFFFFFFF);
        deviceInfo.setTextSize(14);
        deviceInfo.setGravity(Gravity.CENTER);
        deviceInfo.setPadding(0, 16, 0, 0);
        leftPanel.addView(deviceInfo);

        Bitmap qrBitmap = QRCodeUtil.createQRCode(content, 180);
        if (qrBitmap != null) qrImage.setImageBitmap(qrBitmap);

        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        rightPanel.setPadding(20, 0, 0, 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        rightPanel.setLayoutParams(rightParams);

        ListView listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 350));
        listView.setDividerHeight(2);
        listView.setDivider(new ColorDrawable(0x44FFFFFF));
        rightPanel.addView(listView);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, 16, 0, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("名称(选填)");
        nameInput.setTextColor(0xFFFFFFFF);
        nameInput.setHintTextColor(0xFF888888);
        nameInput.setBackgroundColor(0x33FFFFFF);
        nameInput.setPadding(12, 12, 12, 12);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameLp.setMargins(0, 0, 8, 0);
        nameInput.setLayoutParams(nameLp);
        inputRow.addView(nameInput);

        EditText urlInput = new EditText(this);
        urlInput.setHint("地址（点击左侧提示按钮配置headers）");
        urlInput.setTextColor(0xFFFFFFFF);
        urlInput.setHintTextColor(0xFF888888);
        urlInput.setBackgroundColor(0x33FFFFFF);
        urlInput.setPadding(12, 12, 12, 12);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        urlLp.setMargins(0, 0, 8, 0);
        urlInput.setLayoutParams(urlLp);
        inputRow.addView(urlInput);

        Button btnAdd = new Button(this);
        btnAdd.setText("确定");
        btnAdd.setBackgroundColor(0xFF03DAC5);
        btnAdd.setTextColor(0xFF000000);
        btnAdd.setPadding(20, 12, 20, 12);
        inputRow.addView(btnAdd);

        rightPanel.addView(inputRow);

        Button btnClose = new Button(this);
        btnClose.setText("关闭");
        btnClose.setBackgroundColor(0x66FFFFFF);
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setPadding(0, 12, 0, 12);
        rightPanel.addView(btnClose);

        mainLayout.addView(leftPanel);
        mainLayout.addView(rightPanel);

        builder.setView(mainLayout);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));

        SharedPreferences prefs = App.getInstance().getSharedPreferences("live_source_pref", Context.MODE_PRIVATE);
        String json = prefs.getString("source_list", "[]");
        JsonArray sourceArray = JsonParser.parseString(json).getAsJsonArray();
        List<SourceItem> dataList = new ArrayList<>();
        for (int i = 0; i < sourceArray.size(); i++) {
            JsonObject obj = sourceArray.get(i).getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String url = obj.has("url") ? obj.get("url").getAsString() : "";
            dataList.add(new SourceItem(name, url));
        }

        SourceAdapter adapter = new SourceAdapter(this, dataList, dialog);
        listView.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(name)) {
                name = extractNameFromUrl(url);
            }
            for (SourceItem item : dataList) {
                if (item.url.equals(url)) {
                    Toast.makeText(this, "地址已存在", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            dataList.add(new SourceItem(name, url));
            adapter.notifyDataSetChanged();
            saveSourceList(dataList);
            nameInput.setText("");
            urlInput.setText("");
            Toast.makeText(this, "添加成功", Toast.LENGTH_SHORT).show();
            refreshSourceList();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    class SourceItem {
        String name, url;

        SourceItem(String n, String u) {
            name = n;
            url = u;

        }
    }

    class SourceAdapter extends BaseAdapter {
        private Context context;
        private List<SourceItem> data;
        private AlertDialog dialog;

        public SourceAdapter(Context context, List<SourceItem> data, AlertDialog dialog) {
            this.context = context;
            this.data = data;
            this.dialog = dialog;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout itemLayout;
            if (convertView == null) {
                itemLayout = new LinearLayout(context);
                itemLayout.setOrientation(LinearLayout.HORIZONTAL);
                itemLayout.setPadding(12, 12, 12, 12);
                itemLayout.setBackgroundColor(0x33FFFFFF);

                TextView tvName = new TextView(context);
                tvName.setTextColor(0xFFFFFFFF);
                tvName.setTextSize(15);
                tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                itemLayout.addView(tvName);

                TextView tvUrl = new TextView(context);
                tvUrl.setTextColor(0xFFAAAAAA);
                tvUrl.setTextSize(12);
                tvUrl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
                itemLayout.addView(tvUrl);

                TextView btnCopy = new TextView(context);
                btnCopy.setText("复制");
                btnCopy.setTextColor(0xFFFFFFFF);
                btnCopy.setBackgroundColor(0x33666666);
                btnCopy.setPadding(8, 4, 8, 4);
                btnCopy.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                itemLayout.addView(btnCopy);

                TextView btnDelete = new TextView(context);
                btnDelete.setText("删除");
                btnDelete.setTextColor(0xFFFF0000);
                btnDelete.setBackgroundColor(0x33666666);
                btnDelete.setPadding(8, 4, 8, 4);
                btnDelete.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                itemLayout.addView(btnDelete);

                convertView = itemLayout;
            } else {
                itemLayout = (LinearLayout) convertView;
            }

            SourceItem item = data.get(position);
            TextView tvName = (TextView) itemLayout.getChildAt(0);
            TextView tvUrl = (TextView) itemLayout.getChildAt(1);
            TextView btnCopy = (TextView) itemLayout.getChildAt(2);
            TextView btnDelete = (TextView) itemLayout.getChildAt(3);

            tvName.setText(item.name);
            tvUrl.setText(item.url);

            int selectedIdx = Hawk.get(HawkConfig.LIVE_SOURCE_SELECTED, 0);
            if (position == selectedIdx) {
                itemLayout.setBackgroundColor(0x33FFFFFF);
                tvName.setTextColor(0xFF03DAC5);
            } else {
                itemLayout.setBackgroundColor(0x00000000);
                tvName.setTextColor(0xFFFFFFFF);
            }

            convertView.setOnClickListener(v -> {
                loadSourceByIndex(position);
                dialog.dismiss();
            });

            btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setText(item.url);
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
            });

            btnDelete.setOnClickListener(v -> {
                data.remove(position);
                notifyDataSetChanged();
                saveSourceList(data);
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                refreshSourceList();
            });

            return convertView;
        }
    }

    private void loadSourceByIndex(int index) {
        SharedPreferences prefs = App.getInstance().getSharedPreferences("live_source_pref", Context.MODE_PRIVATE);
        String json = prefs.getString("source_list", "[]");
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        if (index >= array.size()) return;
        JsonObject obj = array.get(index).getAsJsonObject();
        String url = obj.get("url").getAsString();
        String name = obj.get("name").getAsString();

        Hawk.put(HawkConfig.LIVE_API_URL, url);
        Hawk.put(HawkConfig.LIVE_SOURCE_SELECTED, index);
        updateCurrentSourceName(name);

        ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                runOnUiThread(() -> {
                    initLiveChannelList();
                    if (liveChannelGroupAdapter != null) {
                        liveChannelGroupAdapter.setNewData(new ArrayList<>(liveChannelGroupList));
                    }
                    if (!liveChannelGroupList.isEmpty()) {
                        selectChannelGroup(0, false, 0);
                    }
                    refreshSourceList();
                    Toast.makeText(LivePlayActivity.this, "已加载：" + name, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void error(String msg) {
                runOnUiThread(() -> Toast.makeText(LivePlayActivity.this, "加载失败：" + msg, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void notice(String msg) {
                runOnUiThread(() -> Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveSourceList(List<SourceItem> list) {
        JsonArray array = new JsonArray();
        for (SourceItem item : list) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", item.name);
            obj.addProperty("url", item.url);
            array.add(obj);
        }
        SharedPreferences prefs = App.getInstance().getSharedPreferences("live_source_pref", Context.MODE_PRIVATE);
        prefs.edit().putString("source_list", array.toString()).apply();
    }

    private String getDeviceIp() {
        try {
            String addr = ControlManager.get().getAddress(true);
            if (addr != null && addr.startsWith("http://")) {
                addr = addr.replace("http://", "");
                int colon = addr.indexOf(':');
                if (colon > 0) return addr.substring(0, colon);
                return addr;
            }
        } catch (Exception e) {
        }
        return "127.0.0.1";
    }

    private String extractNameFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                String[] parts = host.split("\\.");
                return parts.length > 0 ? parts[0] : "直播";
            }
        } catch (Exception e) {
        }
        return "直播";
    }

    private void performUpdateSubscription() {
        String liveApiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        if (liveApiUrl.isEmpty()) {
            Toast.makeText(this, "请先设置直播订阅地址", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在更新订阅...", Toast.LENGTH_SHORT).show();
        final int reqId = ++liveConfigRequestId;
        String cfgChannelName = getPreferredLiveRefreshChannelName();
        int cfgSourceIndex = getPreferredLiveRefreshSourceIndex();
        ApiConfig.get().loadLiveConfig(true, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                mHandler.post(() -> {
                    if (reqId != liveConfigRequestId || isFinishing()) return;
                    refreshLiveChannelListAndPlay(cfgChannelName, cfgSourceIndex);
                    Toast.makeText(LivePlayActivity.this, "订阅更新成功", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void error(String msg) {
                mHandler.post(() -> {
                    if (reqId != liveConfigRequestId || isFinishing()) return;
                    Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void notice(String msg) {
                mHandler.post(() -> {
                    if (reqId != liveConfigRequestId || isFinishing()) return;
                    Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String getPreferredLiveRefreshChannelName() {
        if (currentLiveChannelItem != null) return currentLiveChannelItem.getChannelName();
        return Hawk.get(HawkConfig.LIVE_CHANNEL, "");
    }

    private int getPreferredLiveRefreshSourceIndex() {
        if (currentLiveChannelItem != null) return currentLiveChannelItem.getSourceIndex();
        return -1;
    }

    private void refreshLiveChannelListAndPlay(String channelName, int sourceIndex) {
        refreshingLiveChannelList = true;
        pendingLiveRefreshChannelName = channelName;
        pendingLiveRefreshSourceIndex = sourceIndex;
        currentLiveLookBackIndex = -1;
        currentLiveChangeSourceTimes = 0;
        allowLiveSwitchPlayer = true;
        channelGroupPasswordConfirmed.clear();
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mHandler.removeCallbacks(mLoadEpgRun);
        hideSwitchChannelSnapshot();
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.INVISIBLE);
        if (liveChannelGroupAdapter != null) liveChannelGroupAdapter.clearGroupState();
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
            liveChannelItemAdapter.setNewData(new ArrayList<>());
        }
        initLiveChannelList();
        initLiveSettingGroupList();
    }

    private int getCurrentLiveApiHistoryIndex() {
        ArrayList<String> history = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<>());
        if (history.isEmpty()) return -1;
        String current = Hawk.get(HawkConfig.LIVE_API_URL, "");
        int idx = history.indexOf(current);
        return idx >= 0 ? idx : -1;
    }

    private void initLiveChannelList() {
        if (ApiConfig.get().shouldReloadLiveConfig()) {
            loadLiveConfigOnEnter();
            return;
        }
        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        if (list == null || list.isEmpty()) {
            loadLiveConfigOnEnter();
            return;
        }
        initLiveObj();
        if (list.size() == 1 && list.get(0) != null && list.get(0).getGroupName() != null
                && list.get(0).getGroupName().startsWith("http://127.0.0.1")) {
            loadProxyLives(list.get(0).getGroupName());
        } else {
            applyLiveChannelGroups(list);
        }
    }

    private boolean loadingLiveConfigOnEnter = false;

    private void loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return;
        loadingLiveConfigOnEnter = true;
        showLoading();
        ApiConfig.get().loadLiveConfig(true, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                mHandler.post(() -> {
                    loadingLiveConfigOnEnter = false;
                    initLiveChannelList();
                    initLiveSettingGroupList();
                    safeInitSettingPanel();
                });
            }

            @Override
            public void error(String msg) {
                mHandler.post(() -> {
                    loadingLiveConfigOnEnter = false;
                    setEmptyLiveChannelList();
                });
            }

            @Override
            public void notice(String msg) {
                mHandler.post(() -> Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show());
            }
        });
    }

    public void loadProxyLives(String url) {
        try {
            Uri parsedUrl = Uri.parse(url);
            url = new String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
        } catch (Throwable th) {
            if (url == null || !url.startsWith("http://127.0.0.1")) {
                setEmptyLiveChannelList();
                return;
            }
        }
        if (!isValidLiveProxyUrl(url)) {
            setEmptyLiveChannelList();
            return;
        }
        if (!refreshingLiveChannelList) {
            showLoading();
        }

        FileLogger.write("LivePlay", "echo-live-url:" + url);

        if (url.contains(".py") || url.contains(".js")) {
            String finalUrl = url;
            Runnable waitResponse = () -> {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<String> future = executor.submit(() -> {
                    Spider sp = ApiConfig.get().getLiveCSP(finalUrl);
                    return sp != null ? sp.liveContent(finalUrl) : "";
                });
                String sortJson = null;
                try {
                    sortJson = future.get(ApiConfig.get().getLiveConnectTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    e.printStackTrace();
                    future.cancel(true);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                } finally {
                    if (sortJson == null || sortJson.isEmpty()) {
                        mHandler.post(() -> setEmptyLiveChannelList());
                        return;
                    }
                    JsonArray livesArray = TxtSubscribe.parseToJsonArray(sortJson);
                    ApiConfig.get().loadLives(livesArray);
                    List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
                    if (list == null || list.isEmpty()) {
                        mHandler.post(() -> setEmptyLiveChannelList());
                        return;
                    }
                    final ArrayList<LiveChannelGroup> loadedGroups = new ArrayList<>(list);
                    mHandler.post(() -> applyLiveChannelGroups(loadedGroups));
                    try {
                        executor.shutdown();
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            };
            Executors.newSingleThreadExecutor().execute(waitResponse);
        } else {
            OkGo.<String>get(url).execute(new AbsCallback<String>() {
                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    return response.body() != null ? response.body().string() : "";
                }

                @Override
                public void onSuccess(Response<String> response) {
                    if (response.body() == null) {
                        mHandler.post(() -> setEmptyLiveChannelList());
                        return;
                    }
                    JsonArray livesArray = TxtSubscribe.parseToJsonArray(response.body());
                    ApiConfig.get().loadLives(livesArray);
                    List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
                    if (list == null || list.isEmpty()) {
                        mHandler.post(() -> setEmptyLiveChannelList());
                        return;
                    }
                    final ArrayList<LiveChannelGroup> loadedGroups = new ArrayList<>(list);
                    mHandler.post(() -> applyLiveChannelGroups(loadedGroups));
                }

                @Override
                public void onError(Response<String> response) {
                    mHandler.post(() -> setEmptyLiveChannelList());
                }
            });
        }
    }

    private boolean isValidLiveProxyUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lowerUrl = url.trim().toLowerCase(Locale.US);
        return lowerUrl.startsWith("http://")
                || lowerUrl.startsWith("https://")
                || lowerUrl.startsWith("rtsp://")
                || lowerUrl.startsWith("rtmp://")
                || lowerUrl.startsWith("rtp://");
    }

    private void applyLiveChannelGroups(List<LiveChannelGroup> groups) {
        if (groups == null) return;
        liveChannelGroupList.clear();
        liveChannelGroupList.addAll(groups);
        showSuccess();
        String sourceName = getCurrentSourceNameFromConfig();
        updateCurrentSourceName(sourceName);
        refreshSourceList();
        initLiveState();
    }

    private String getCurrentSourceNameFromConfig() {
        int selected = Hawk.get(HawkConfig.LIVE_SOURCE_SELECTED, 0);
        JsonArray list = Hawk.get(HawkConfig.LIVE_SOURCE_LIST, new JsonArray());
        if (list != null && list.size() > selected) {
            JsonObject obj = list.get(selected).getAsJsonObject();
            return obj.has("name") ? obj.get("name").getAsString() : "未命名";
        }
        return "默认源";
    }

    private void initLiveState() {
        refreshingLiveChannelList = false;
        String lastChannelName = pendingLiveRefreshChannelName == null ? Hawk.get(HawkConfig.LIVE_CHANNEL, "") : pendingLiveRefreshChannelName;
        int sourceIndex = pendingLiveRefreshSourceIndex;
        pendingLiveRefreshChannelName = null;
        pendingLiveRefreshSourceIndex = -1;

        int selected = Hawk.get(HawkConfig.LIVE_SOURCE_SELECTED, 0);
        JsonArray sourceList = Hawk.get(HawkConfig.LIVE_SOURCE_LIST, new JsonArray());
        if (sourceList != null && sourceList.size() > selected) {
            JsonObject obj = sourceList.get(selected).getAsJsonObject();
            String url = obj.get("url").getAsString();
            if (!url.equals(Hawk.get(HawkConfig.LIVE_API_URL, ""))) {
                Hawk.put(HawkConfig.LIVE_API_URL, url);
                ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        runOnUiThread(() -> {
                            initLiveChannelList();
                            continueInitLiveState(lastChannelName, sourceIndex);
                        });
                    }

                    @Override
                    public void error(String msg) {
                        runOnUiThread(() -> Toast.makeText(LivePlayActivity.this, "加载源失败: " + msg, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void notice(String msg) {
                    }
                });
                return;
            }
        }

        continueInitLiveState(lastChannelName, sourceIndex);
    }

    private void continueInitLiveState(String lastChannelName, int sourceIndex) {
        int lastChannelGroupIndex = -1;
        int lastLiveChannelIndex = -1;
        LiveChannelItem lastLiveChannelItem = null;
        if (liveChannelGroupList != null) {
            for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
                if (liveChannelGroup == null) continue;
                ArrayList<LiveChannelItem> groupChannels = liveChannelGroup.getLiveChannels();
                if (groupChannels == null || groupChannels.isEmpty()) continue;
                for (LiveChannelItem liveChannelItem : groupChannels) {
                    if (liveChannelItem == null || liveChannelItem.getChannelName() == null) continue;
                    if (liveChannelItem.getChannelName().equals(lastChannelName)) {
                        lastChannelGroupIndex = liveChannelGroup.getGroupIndex();
                        lastLiveChannelIndex = liveChannelItem.getChannelIndex();
                        lastLiveChannelItem = liveChannelItem;
                        break;
                    }
                }
                if (lastChannelGroupIndex != -1) break;
            }
        }
        if (lastChannelGroupIndex == -1) {
            Integer[] cctv1Channel = getFirstChannelByName("CCTV1");
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0];
                lastLiveChannelIndex = cctv1Channel[1];
            } else {
                lastChannelGroupIndex = getFirstNoPasswordChannelGroup();
                if (lastChannelGroupIndex == -1) lastChannelGroupIndex = 0;
                lastLiveChannelIndex = 0;
            }
        }
        if (lastLiveChannelItem != null && sourceIndex >= 0 && lastLiveChannelItem.getSourceNum() > 0) {
            lastLiveChannelItem.setSourceIndex(Math.min(sourceIndex, lastLiveChannelItem.getSourceNum() - 1));
        }

        livePlayerManager.init(mVideoView);
        installLiveReconnectListener();
        showTime();
        showNetSpeed();
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.INVISIBLE);

        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter.clearGroupState();
            liveChannelGroupAdapter.setNewData(new ArrayList<>(liveChannelGroupList != null ? liveChannelGroupList : new ArrayList<>()));
        }
        currentLiveChannelIndex = -1;
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex);
    }

    private boolean isListOrSettingLayoutVisible() {
        return (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
                || (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.VISIBLE);
    }

    private boolean hasCurrentLiveChannelSource() {
        return currentLiveChannelItem != null
                && currentLiveChannelItem.getChannelUrls() != null
                && currentLiveChannelItem.getSourceNum() > 0
                && currentLiveChannelItem.getSourceIndex() >= 0
                && currentLiveChannelItem.getSourceIndex() < currentLiveChannelItem.getChannelUrls().size();
    }

    private int getDefaultSettingGroupIndex() {
        if (hasCurrentLiveChannelSource()) return 0;
        return liveSettingGroupList != null && liveSettingGroupList.size() > 6 ? 6 : 0;
    }

    private ArrayList<LiveSettingGroup> getVisibleLiveSettingGroupList() {
        ArrayList<LiveSettingGroup> visibleGroups = new ArrayList<>();
        if (liveSettingGroupList == null) return visibleGroups;
        boolean showChannelOptions = hasCurrentLiveChannelSource();
        for (LiveSettingGroup group : liveSettingGroupList) {
            if (group == null) continue;
            int groupIndex = group.getGroupIndex();
            if (!showChannelOptions && groupIndex >= 0 && groupIndex <= 2) continue;
            visibleGroups.add(group);
        }
        return visibleGroups;
    }

    private void initLiveSettingGroupList() {
        liveSettingGroupList = ApiConfig.get().getLiveSettingGroupList();
        if (liveSettingGroupList == null) {
            liveSettingGroupList = new ArrayList<>();
        }
        java.util.Iterator<LiveSettingGroup> it = liveSettingGroupList.iterator();
        while (it.hasNext()) {
            LiveSettingGroup g = it.next();
            if (g != null && g.getGroupIndex() >= 7) {
                it.remove();
            }
        }
        LiveSettingGroup timeoutGroup = findSettingGroupByIndex(3);
        if (timeoutGroup != null && timeoutGroup.getLiveSettingItems() != null) {
            int timeoutIdx = Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1);
            if (timeoutIdx >= 0 && timeoutIdx < timeoutGroup.getLiveSettingItems().size()) {
                timeoutGroup.getLiveSettingItems().get(timeoutIdx).setItemSelected(true);
            }
        }
        LiveSettingGroup displayGroup = findSettingGroupByIndex(4);
        if (displayGroup != null && displayGroup.getLiveSettingItems() != null
                && displayGroup.getLiveSettingItems().size() > 3) {
            displayGroup.getLiveSettingItems().get(0).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_TIME, false));
            displayGroup.getLiveSettingItems().get(1).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false));
            displayGroup.getLiveSettingItems().get(2).setItemSelected(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false));
            displayGroup.getLiveSettingItems().get(3).setItemSelected(Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false));
        }
        int liveGroupIndex = ApiConfig.getLiveGroupIndex();
        LiveSettingGroup lineGroup = findSettingGroupByIndex(5);
        if (lineGroup != null && lineGroup.getLiveSettingItems() != null
                && liveGroupIndex >= 0 && liveGroupIndex < lineGroup.getLiveSettingItems().size()) {
            lineGroup.getLiveSettingItems().get(liveGroupIndex).setItemSelected(true);
        }

        LiveSettingGroup sourceGroup = new LiveSettingGroup();
        sourceGroup.setGroupIndex(7);
        sourceGroup.setGroupName("直播订阅");
        ArrayList<LiveSettingItem> sourceItems = new ArrayList<>();
        LiveSettingItem s1 = new LiveSettingItem();
        s1.setItemIndex(0);
        s1.setItemName("订阅地址");
        sourceItems.add(s1);
        LiveSettingItem s2 = new LiveSettingItem();
        s2.setItemIndex(1);
        s2.setItemName("更新订阅");
        sourceItems.add(s2);
        sourceGroup.setLiveSettingItems(sourceItems);
        liveSettingGroupList.add(sourceGroup);

        LiveSettingGroup epgGroup = new LiveSettingGroup();
        epgGroup.setGroupIndex(8);
        epgGroup.setGroupName("EPG订阅");
        ArrayList<LiveSettingItem> epgItems = new ArrayList<>();
        LiveSettingItem e1 = new LiveSettingItem();
        e1.setItemIndex(0);
        e1.setItemName("EPG地址");
        epgItems.add(e1);
        LiveSettingItem e2 = new LiveSettingItem();
        e2.setItemIndex(1);
        e2.setItemName("更新EPG");
        epgItems.add(e2);
        epgGroup.setLiveSettingItems(epgItems);
        liveSettingGroupList.add(epgGroup);

        LiveSettingGroup speedGroup = new LiveSettingGroup();
        speedGroup.setGroupIndex(9);
        speedGroup.setGroupName("播放倍速");
        ArrayList<LiveSettingItem> speedItems = new ArrayList<>();
        float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        for (int i = 0; i < speeds.length; i++) {
            LiveSettingItem item = new LiveSettingItem();
            item.setItemIndex(i);
            item.setItemName(speeds[i] + "x");
            if (speeds[i] == 1.0f) item.setItemSelected(true);
            speedItems.add(item);
        }
        speedGroup.setLiveSettingItems(speedItems);
        liveSettingGroupList.add(speedGroup);

        java.util.Iterator<LiveSettingGroup> logoIt = liveSettingGroupList.iterator();
        while (logoIt.hasNext()) {
            LiveSettingGroup g = logoIt.next();
            if (g != null && g.getGroupIndex() == 10) logoIt.remove();
        }
        LiveSettingGroup logoGroup = new LiveSettingGroup();
        logoGroup.setGroupIndex(10);
        logoGroup.setGroupName("台标来源");
        ArrayList<LiveSettingItem> logoItems = new ArrayList<>();
        LiveSettingItem logoEpg = new LiveSettingItem();
        logoEpg.setItemIndex(0); logoEpg.setItemName("EPG文件台标");
        logoEpg.setItemSelected(EpgManager.LOGO_SOURCE_EPG.equals(EpgManager.getLogoSource(this)));
        logoItems.add(logoEpg);
        LiveSettingItem logoGithub = new LiveSettingItem();
        logoGithub.setItemIndex(1); logoGithub.setItemName("GitHub台标");
        logoGithub.setItemSelected(EpgManager.LOGO_SOURCE_GITHUB.equals(EpgManager.getLogoSource(this)));
        logoItems.add(logoGithub);
        logoGroup.setLiveSettingItems(logoItems);
        liveSettingGroupList.add(logoGroup);
    }

    private void loadCurrentSourceList() {
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        if (currentLiveChannelItem != null && currentLiveChannelItem.getChannelSourceNames() != null) {
            ArrayList<String> currentSourceNames = currentLiveChannelItem.getChannelSourceNames();
            for (int j = 0; j < currentSourceNames.size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(currentSourceNames.get(j));
                liveSettingItemList.add(liveSettingItem);
            }
        }
        LiveSettingGroup sourceGroup = findSettingGroupByIndex(0);
        if (sourceGroup != null) {
            sourceGroup.setLiveSettingItems(liveSettingItemList);
        }
    }

    private void showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true;
        resolutionInfoRetryCount = 0;
        if (tvResolution != null) {
            tvResolution.setText("");
            tvResolution.setVisibility(View.GONE);
        }
        mHandler.removeCallbacks(mHideResolutionInfoRun);
        mHandler.removeCallbacks(mUpdateResolutionInfoRun);
        mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY);
    }

    private void showResolutionSetting() {
        mHandler.removeCallbacks(mHideResolutionInfoRun);
        mHandler.removeCallbacks(mUpdateResolutionInfoRun);
        if (Hawk.get(HawkConfig.LIVE_SHOW_RESOLUTION, false)) {
            resolutionInfoPending = true;
            resolutionInfoRetryCount = 0;
            if (tvResolution != null) {
                tvResolution.setVisibility(View.GONE);
                mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY);
            }
        } else {
            showResolutionAfterChannelSwitch();
        }
    }

    private final Runnable mHideResolutionInfoRun = () -> {
        if (tvResolution != null) tvResolution.setVisibility(View.GONE);
    };

    private final Runnable mUpdateResolutionInfoRun = new Runnable() {
        @Override
        public void run() {
            if (tvResolution == null || mVideoView == null) return;
            if (mVideoView.getCurrentPlayState() != VideoView.STATE_PREPARED
                    && mVideoView.getCurrentPlayState() != VideoView.STATE_BUFFERED
                    && mVideoView.getCurrentPlayState() != VideoView.STATE_PLAYING) {
                retryOrHideResolutionInfo();
                return;
            }
            int[] videoSize = mVideoView.getVideoSize();
            if (videoSize != null && videoSize.length >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
                updateResolutionText(videoSize[0], videoSize[1]);
                return;
            }
            retryOrHideResolutionInfo();
        }
    };

    private void updateResolutionText(int width, int height) {
        resolutionInfoPending = false;
        tvResolution.setText(width + " x " + height);
        tvResolution.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideResolutionInfoRun);
    }

    private void retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY);
        } else {
            if (tvResolution != null) tvResolution.setVisibility(View.GONE);
        }
    }

    void showTime() {
        if (Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)) {
            mHandler.post(mUpdateTimeRun);
            if (tvTime != null) tvTime.setVisibility(View.VISIBLE);
        } else {
            mHandler.removeCallbacks(mUpdateTimeRun);
            if (tvTime != null) tvTime.setVisibility(View.GONE);
        }
    }

    private Runnable mUpdateTimeRun = new Runnable() {
        @Override
        public void run() {
            Date day = new Date();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat df = new SimpleDateFormat("HH:mm");
            if (tvTime != null) tvTime.setText(df.format(day));
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showNetSpeed() {
        mHandler.removeCallbacks(mUpdateNetSpeedRun);
        if (Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)) {
            mHandler.post(mUpdateNetSpeedRun);
            if (tvNetSpeed != null) tvNetSpeed.setVisibility(View.VISIBLE);
        } else {
            if (tvNetSpeed != null) tvNetSpeed.setVisibility(View.GONE);
        }
    }

    private Runnable mUpdateNetSpeedRun = new Runnable() {
        @Override
        public void run() {
            if (mVideoView == null || tvNetSpeed == null) return;
            String speed = PlayerHelper.getDisplaySpeedBps(mVideoView.getTcpSpeed(), true);
            tvNetSpeed.setText(speed);
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {
        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
            mHandler.removeCallbacks(mHideChannelListRun);

        LivePasswordDialog dialog = new LivePasswordDialog(this);
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                if (liveChannelGroupList != null && groupIndex >= 0 && groupIndex < liveChannelGroupList.size()
                        && password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    Toast.makeText(App.getInstance(), "密码错误", Toast.LENGTH_SHORT).show();
                }
                if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
                    mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter != null ? liveChannelGroupAdapter.getSelectedGroupIndex() : 0;
                    List<LiveChannelItem> channels = getLiveChannels(groupIndex);
                    if (liveChannelItemAdapter != null) liveChannelItemAdapter.setNewData(channels != null ? channels : new ArrayList<>());
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        if (liveChannelGroupAdapter != null) liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
        loadChannelGroupData(groupIndex);
        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            if (mChannelGroupView != null) mChannelGroupView.scrollToPosition(groupIndex);
            if (mLiveChannelView != null) mLiveChannelView.scrollToPosition(liveChannelIndex);
        }
    }

    private void preloadCurrentGroupEpgResources(int groupIndex) {
        if (liveChannelGroupList == null || groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) return;
        final int requestedGroup = groupIndex;
        if (!isXmlEpgAddress(epgStringAddress)) return;
        EpgManager manager = EpgManager.getInstance(this);
        manager.refreshEpg(new EpgManager.RefreshCallback() {
            @Override public void onSuccess() {
                int activeGroup = currentChannelGroupIndex;
                if (activeGroup != requestedGroup) requestedGroupPreload(activeGroup);
                else requestedGroupPreload(requestedGroup);
            }
            @Override public void onError(String msg) {
                requestedGroupPreload(currentChannelGroupIndex);
            }
        });
    }

    private void requestedGroupPreload(int groupIndex) {
        if (liveChannelGroupList == null || groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) return;
        List<LiveChannelItem> channels = getLiveChannels(groupIndex);
        if (channels == null || channels.isEmpty()) return;
        ArrayList<String> names = new ArrayList<>();
        for (LiveChannelItem item : channels) {
            if (item != null && !TextUtils.isEmpty(item.getChannelName())) names.add(item.getChannelName());
        }
        EpgManager.getInstance(this).preloadGroupResources(names, () -> {
            if (liveChannelItemAdapter != null && groupIndex == currentChannelGroupIndex) liveChannelItemAdapter.notifyDataSetChanged();
            if (ku9GuideChannelAdapter != null && ku9GuideShowing && groupIndex == currentChannelGroupIndex) {
                ku9GuideChannelAdapter.notifyDataSetChanged();
                int cp = Math.max(0, Math.min(ku9GuideChannelFocusPosition >= 0 ? ku9GuideChannelFocusPosition : currentLiveChannelIndex, ku9GuideChannelAdapter.getItemCount() - 1));
                String preservedDateKey = getSelectedKu9GuideDateKey();
                rebuildKu9GuideDatesForChannel(cp);
                ku9GuideDateAdapter.notifyDataSetChanged();
                int di = findKu9GuideDateIndex(preservedDateKey);
                if (di < 0) di = findTodayGuideDateIndex();
                if (di >= 0) {
                    ku9GuideDateAdapter.setSelectedIndex(di);
                    ku9GuideDateList.setSelection(di);
                    loadKu9GuidePrograms(cp, ku9GuideDateAdapter.getItem(di).getDateParamVal());
                }
            }
            if (channel_Name != null && currentLiveChannelItem != null && groupIndex == currentChannelGroupIndex) {
                updateCurrentChannelIcon();
                getEpg(new Date());
            }
        });
    }

    private void loadChannelGroupData(int groupIndex) {
        List<LiveChannelItem> channels = getLiveChannels(groupIndex);
        if (liveChannelItemAdapter != null) liveChannelItemAdapter.setNewData(channels != null ? channels : new ArrayList<>());
        preloadCurrentGroupEpgResources(groupIndex);
        if (mLiveChannelView != null) {
            if (groupIndex == currentChannelGroupIndex && currentLiveChannelIndex > -1) {
                mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
                liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
            } else {
                mLiveChannelView.scrollToPosition(0);
                if (liveChannelItemAdapter != null) liveChannelItemAdapter.setSelectedChannelIndex(-1);
            }
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        return liveChannelGroupList != null && groupIndex >= 0 && groupIndex < liveChannelGroupList.size()
                && liveChannelGroupList.get(groupIndex) != null
                && !liveChannelGroupList.get(groupIndex).getGroupPassword().isEmpty()
                && !isPasswordConfirmed(groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        for (Integer confirmedNum : channelGroupPasswordConfirmed) {
            if (confirmedNum == groupIndex) return true;
        }
        return false;
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        if (liveChannelGroupList == null || groupIndex < 0 || groupIndex >= liveChannelGroupList.size())
            return new ArrayList<>();
        if (!isNeedInputPassword(groupIndex)) {
            List<LiveChannelItem> channels = liveChannelGroupList.get(groupIndex).getLiveChannels();
            return channels != null ? new ArrayList<>(channels) : new ArrayList<>();
        } else {
            return new ArrayList<>();
        }
    }

    private Integer[] getNextChannel(int direction) {
        int channelGroupIndex = currentChannelGroupIndex;
        int liveChannelIndex = currentLiveChannelIndex;

        if (direction > 0) {
            liveChannelIndex++;
            List<LiveChannelItem> channels = getLiveChannels(channelGroupIndex);
            if (liveChannelIndex >= (channels != null ? channels.size() : 0)) {
                liveChannelIndex = 0;
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false) && liveChannelGroupList != null) {
                    do {
                        channelGroupIndex++;
                        if (channelGroupIndex >= liveChannelGroupList.size())
                            channelGroupIndex = 0;
                    } while (channelGroupIndex < liveChannelGroupList.size()
                            && !liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty()
                            || channelGroupIndex == currentChannelGroupIndex);
                }
            }
        } else {
            liveChannelIndex--;
            if (liveChannelIndex < 0) {
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false) && liveChannelGroupList != null) {
                    do {
                        channelGroupIndex--;
                        if (channelGroupIndex < 0)
                            channelGroupIndex = liveChannelGroupList.size() - 1;
                    } while (channelGroupIndex >= 0 && channelGroupIndex < liveChannelGroupList.size()
                            && !liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty()
                            || channelGroupIndex == currentChannelGroupIndex);
                }
                List<LiveChannelItem> channels = getLiveChannels(channelGroupIndex);
                liveChannelIndex = (channels != null ? channels.size() : 0) - 1;
            }
        }
        return new Integer[]{channelGroupIndex, liveChannelIndex};
    }

    private Integer[] getFirstChannelByName(String keyword) {
        if (TextUtils.isEmpty(keyword) || liveChannelGroupList == null) return null;
        String upperKeyword = keyword.toUpperCase(Locale.US);
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup == null || isNeedInputPassword(liveChannelGroup.getGroupIndex())) continue;
            ArrayList<LiveChannelItem> groupChannels = liveChannelGroup.getLiveChannels();
            if (groupChannels == null || groupChannels.isEmpty()) continue;
            for (LiveChannelItem item : groupChannels) {
                if (item == null || TextUtils.isEmpty(item.getChannelName())) continue;
                if (item.getChannelName().toUpperCase(Locale.US).contains(upperKeyword)) {
                    return new Integer[]{liveChannelGroup.getGroupIndex(), item.getChannelIndex()};
                }
            }
        }
        return null;
    }

    private int getFirstNoPasswordChannelGroup() {
        if (liveChannelGroupList == null) return -1;
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup != null && liveChannelGroup.getGroupPassword().isEmpty())
                return liveChannelGroup.getGroupIndex();
        }
        return -1;
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance(), "请先选择频道", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private ArrayList<Epginfo> parseXmlEpg(String xml, String channelName, Date date) {
        ArrayList<Epginfo> epgList = new ArrayList<>();
        if (xml == null || channelName == null || date == null) return epgList;
        try {
            String targetName = normalizeEpgChannelName(channelName);
            Date dayStart = getDayStart(date);
            Date dayEnd = new Date(dayStart.getTime() + TimeUnit.DAYS.toMillis(1));

            org.xmlpull.v1.XmlPullParser parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new java.io.StringReader(xml));

            ArrayList<String> channelIds = new ArrayList<>();
            String currentChannelId = null;
            String currentTitle = null;
            String currentStart = null;
            String currentStop = null;
            String currentDesc = null;
            boolean inTargetChannel = false;

            int eventType = parser.getEventType();
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();

                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if ("channel".equals(tagName)) {
                        currentChannelId = parser.getAttributeValue(null, "id");
                        inTargetChannel = false;
                    } else if ("display-name".equals(tagName) && currentChannelId != null) {
                        String displayName = parser.nextText();
                        if (targetName.equals(normalizeEpgChannelName(displayName))) {
                            channelIds.add(currentChannelId);
                            inTargetChannel = true;
                        }
                    } else if ("programme".equals(tagName)) {
                        currentChannelId = parser.getAttributeValue(null, "channel");
                        currentStart = parser.getAttributeValue(null, "start");
                        currentStop = parser.getAttributeValue(null, "stop");
                        currentTitle = null;
                        currentDesc = null;
                    } else if ("title".equals(tagName)) {
                        currentTitle = parser.nextText();
                    } else if ("desc".equals(tagName)) {
                        currentDesc = parser.nextText();
                    }
                } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    if ("programme".equals(tagName) && currentStart != null && currentStop != null) {
                        if (channelIds.contains(currentChannelId) || targetName.equals(normalizeEpgChannelName(currentChannelId))) {
                            Date startDate = parseXmlTvDate(currentStart);
                            Date endDate = parseXmlTvDate(currentStop);
                            if (startDate != null && endDate != null && endDate.after(startDate)
                                    && startDate.before(dayEnd) && endDate.after(dayStart)) {
                                Epginfo info = createXmlEpgInfo(date, currentTitle != null ? currentTitle : "", startDate, endDate, epgList.size());
                                info.desc = currentDesc != null ? currentDesc.trim() : "";
                                epgList.add(info);
                            }
                        }
                        currentStart = null;
                        currentStop = null;
                        currentTitle = null;
                        currentDesc = null;
                    } else if ("channel".equals(tagName)) {
                        currentChannelId = null;
                        inTargetChannel = false;
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return epgList;
    }

    private Date getDayStart(Date date) throws ParseException {
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        dayFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        return dayFormat.parse(dayFormat.format(date));
    }

    private Date parseXmlTvDate(String dateText) {
        if (dateText == null || dateText.trim().isEmpty()) return null;
        String trimDate = dateText.trim();
        try {
            return new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).parse(trimDate);
        } catch (ParseException ignored) {
        }
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
            return dateFormat.parse(trimDate);
        } catch (ParseException ignored) {
        }
        return null;
    }

    private Epginfo createXmlEpgInfoFromProgram(EpgManager.EpgProgram p, int index) {
        if (p == null || p.start == null || p.stop == null) return null;
        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        day.setTimeZone(tz);
        Date naturalDay;
        try { naturalDay = day.parse(day.format(p.start)); }
        catch (Exception e) { naturalDay = p.start; }
        Epginfo info = createXmlEpgInfo(naturalDay, p.title == null ? "" : p.title.trim(), p.start, p.stop, index);
        if (info != null) info.desc = p.description == null ? "" : p.description;
        return info;
    }

    private Epginfo createXmlEpgInfo(Date epgDate, String title, Date startDate, Date endDate, int index) {
        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        timeFormat.setTimeZone(tz);
        Epginfo epgInfo = new Epginfo(epgDate, title, startDate, timeFormat.format(startDate), timeFormat.format(endDate), index);
        epgInfo.startdateTime = startDate;
        epgInfo.enddateTime = endDate;
        epgInfo.start = timeFormat.format(startDate);
        epgInfo.end = timeFormat.format(endDate);
        epgInfo.originStart = epgInfo.start;
        epgInfo.originEnd = epgInfo.end;
        epgInfo.datestart = Integer.parseInt(epgInfo.start.replace(":", ""));
        epgInfo.dateend = Integer.parseInt(epgInfo.end.replace(":", ""));
        return epgInfo;
    }

    public static long getTime(String startTime, String endTime) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long eTime = 0;
        try {
            eTime = df.parse(endTime).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        long sTime = 0;
        try {
            sTime = df.parse(startTime).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return (eTime - sTime) / 1000;
    }

    private String durationToString(int duration) {
        if (duration < 0) duration = 0;
        int dur = duration / 1000;
        int hour = dur / 3600;
        int min = (dur / 60) % 60;
        int sec = dur % 60;
        if (hour > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hour, min, sec);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
        }
    }

    public void showProgressBars(boolean show) {
        if (sBar != null) sBar.requestFocus();
        if (show) {
            if (ll_right_top_huikan != null) ll_right_top_huikan.setVisibility(View.VISIBLE);
            if (backcontroller != null) backcontroller.setVisibility(View.VISIBLE);
            if (ll_epg != null) ll_epg.setVisibility(View.GONE);
        } else {
            if (backcontroller != null) backcontroller.setVisibility(View.GONE);
            if (ll_right_top_huikan != null) ll_right_top_huikan.setVisibility(View.GONE);
            if (!"暂无信息".equals(tip_epg1 != null ? tip_epg1.getText().toString() : "")) {
                if (ll_epg != null && !isListOrSettingLayoutVisible()) {
                    ll_epg.setVisibility(View.VISIBLE);
                }
            }
        }

        if (iv_play != null) {
            iv_play.setOnClickListener(arg0 -> {
                if (mVideoView == null) return;
                mVideoView.start();
                iv_play.setVisibility(View.INVISIBLE);
                if (countDownTimer3 != null) countDownTimer3.start();
                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
            });
        }

        if (iv_playpause != null) {
            iv_playpause.setOnClickListener(arg0 -> {
                if (mVideoView == null) return;
                if (mVideoView.isPlaying()) {
                    mVideoView.pause();
                    if (countDownTimer3 != null) countDownTimer3.cancel();
                    iv_play.setVisibility(View.VISIBLE);
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                } else {
                    mVideoView.start();
                    iv_play.setVisibility(View.INVISIBLE);
                    if (countDownTimer3 != null) countDownTimer3.start();
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                }
            });
        }

        if (sBar != null) {
            sBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onStopTrackingTouch(SeekBar arg0) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar arg0) {
                }

                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromuser) {
                    if (fromuser && countDownTimer3 != null && mVideoView != null) {
                        mVideoView.seekTo(progress);
                        countDownTimer3.cancel();
                        countDownTimer3.start();
                    }
                }
            });

            sBar.setOnKeyListener((arg0, keycode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keycode == KeyEvent.KEYCODE_DPAD_CENTER || keycode == KeyEvent.KEYCODE_ENTER) {
                        if (mVideoView == null) return false;
                        if (mVideoView.isPlaying()) {
                            mVideoView.pause();
                            if (countDownTimer3 != null) countDownTimer3.cancel();
                            iv_play.setVisibility(View.VISIBLE);
                            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                        } else {
                            mVideoView.start();
                            iv_play.setVisibility(View.INVISIBLE);
                            if (countDownTimer3 != null) countDownTimer3.start();
                            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                        }
                    }
                }
                return false;
            });
        }

        if (mVideoView != null) {
            if (mVideoView.isPlaying()) {
                if (iv_play != null) iv_play.setVisibility(View.INVISIBLE);
                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
            } else {
                if (iv_play != null) iv_play.setVisibility(View.VISIBLE);
                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
            }
        }

        if (countDownTimer3 == null) {
            countDownTimer3 = new CountDownTimer(postTimeout, 1000) {
                @Override
                public void onTick(long arg0) {
                    if (mVideoView != null && sBar != null) {
                        sBar.setProgress(safeTimeMs(mVideoView.getCurrentPosition()));
                        if (tv_currentpos != null) tv_currentpos.setText(durationToString(safeTimeMs(mVideoView.getCurrentPosition())));
                    }
                }

                @Override
                public void onFinish() {
                    if (backcontroller != null && backcontroller.getVisibility() == View.VISIBLE) {
                        backcontroller.setVisibility(View.GONE);
                    }
                }
            };
        } else {
            countDownTimer3.cancel();
        }
        countDownTimer3.start();
    }

    private void clearLiveChannelList() {
        clearLiveChannelList(true);
    }

    private void clearLiveChannelList(boolean releasePlayer) {
        refreshingLiveChannelList = false;
        pendingLiveRefreshChannelName = null;
        pendingLiveRefreshSourceIndex = -1;
        currentLiveChannelItem = null;
        currentLiveChannelIndex = -1;
        currentLiveLookBackIndex = -1;
        currentLiveChangeSourceTimes = 0;
        if (liveChannelGroupList != null) liveChannelGroupList.clear();
        ApiConfig.get().getChannelGroupList().clear();
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mHandler.removeCallbacks(mLoadEpgRun);
        hideSwitchChannelSnapshot();
        if (releasePlayer && mVideoView != null) mVideoView.release();
        showSuccess();
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter.clearGroupState();
            liveChannelGroupAdapter.setNewData(new ArrayList<>());
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
            liveChannelItemAdapter.setNewData(new ArrayList<>());
        }
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.INVISIBLE);
    }

    private void setEmptyLiveChannelList() {
        setEmptyLiveChannelList(true);
    }

    private void setEmptyLiveChannelList(boolean releasePlayer) {
        clearLiveChannelList(releasePlayer);
    }

    private void showInputDialog(String title, String defaultValue, OnInputConfirmListener listener) {
        EditText editText = new EditText(this);
        editText.setText(defaultValue);
        editText.setSelection(defaultValue != null ? defaultValue.length() : 0);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(editText)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (listener != null) listener.onConfirm(editText.getText().toString().trim());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface OnInputConfirmListener {
        void onConfirm(String value);
    }

    private void initGestureDetector() {
        if (gestureOverlay == null) return;
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                handleSingleTap(e);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                handleLongPress(e);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                handleFling(e1, e2, velocityX, velocityY);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                handleScroll(e1, e2, distanceX, distanceY);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mVideoView != null) {
                    if (mVideoView.isPlaying()) {
                        mVideoView.pause();
                    } else {
                        mVideoView.start();
                    }
                }
                return true;
            }
        });
        gestureOverlay.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void handleSingleTap(MotionEvent e) {
        if (e == null) return;
        float x = e.getX();
        float width = getResources().getDisplayMetrics().widthPixels;
        float edge = width * GESTURE_EDGE_RATIO;

        if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
            return;
        }
        if (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
            return;
        }

        if (x < edge) {
            showChannelList();
        } else if (x > width - edge) {
            showChannelList();
        } else {
            if (isBottomInfoBarShowing) {
                hideBottomInfoBar();
            } else {
                showBottomInfoBar();
            }
        }
    }

    private void handleLongPress(MotionEvent e) {
        if (e == null) return;
        float x = e.getX();
        float width = getResources().getDisplayMetrics().widthPixels;
        float edge = width * GESTURE_EDGE_RATIO;

        if (x > width - edge) {
            showSettingGroup();
        } else if (x < edge) {
            if (tvLeftChannelListLayout != null && tvLeftChannelListLayout.getVisibility() != View.VISIBLE) {
                showChannelList();
                mHandler.postDelayed(() -> {
                    if (mLiveChannelView != null && mLiveChannelView.hasFocus()) {
                        divLoadEpgRight(null);
                    }
                }, 300);
            }
        }
    }

    private void handleFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (e1 == null || e2 == null) return;
        float dx = e2.getX() - e1.getX();
        float dy = e2.getY() - e1.getY();
        float width = getResources().getDisplayMetrics().widthPixels;
        float edge = width * GESTURE_EDGE_RATIO;
        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);

        if (absDx > absDy * 1.5f) {
            if (dx > 80) {
                if (!isBack) playPreSource();
            } else if (dx < -80) {
                if (!isBack) playNextSource();
            }
        } else if (absDy > absDx * 1.5f) {
            if (e1.getX() > width - edge) {
                if (dy > 50) {
                    playPrevious();
                } else if (dy < -50) {
                    playNext();
                }
            }
        }
    }

    private void handleScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
    }

    private void showBottomInfoBar() {
        if (llBottomInfoBar == null || currentLiveChannelItem == null) return;
        if (isListOrSettingLayoutVisible()) return;
        if (backcontroller != null && backcontroller.getVisibility() == View.VISIBLE) return;

        isBottomInfoBarShowing = true;
        llBottomInfoBar.setVisibility(View.VISIBLE);
        updateCurrentChannelIcon();
        llBottomInfoBar.setAlpha(0f);
        llBottomInfoBar.animate().alpha(1f).setDuration(200).start();
        mHandler.removeCallbacks(mEpgProgressRun);
        mHandler.post(mEpgProgressRun);

        mHandler.removeCallbacks(mHideBottomInfoRun);
        mHandler.postDelayed(mHideBottomInfoRun, BOTTOM_INFO_SHOW_DURATION);
    }

    private void hideBottomInfoBar() {
        if (llBottomInfoBar == null) return;
        isBottomInfoBarShowing = false;
        llBottomInfoBar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!isBottomInfoBarShowing) llBottomInfoBar.setVisibility(View.GONE);
        }).start();
        mHandler.removeCallbacks(mHideBottomInfoRun);
        mHandler.removeCallbacks(mEpgProgressRun);
    }

    @SuppressLint("SetTextI18n")
    private void updateBottomInfoBar() {
        if (llBottomInfoBar == null || currentLiveChannelItem == null) return;

        if (tv_channelnum != null) tv_channelnum.setText(String.valueOf(currentLiveChannelItem.getChannelNum()));
        if (tip_chname != null) tip_chname.setText(currentLiveChannelItem.getChannelName());

        Epginfo parsedCurrent = null, parsedNext = null;
        if (epgdata != null && !epgdata.isEmpty()) {
            int idx = findCurrentEpgIndex(epgdata);
            if (idx >= 0) {
                parsedCurrent = epgdata.get(idx);
                if (idx + 1 < epgdata.size()) parsedNext = epgdata.get(idx + 1);
            }
        }
        EpgManager manager = EpgManager.getInstance(this);
        EpgManager.EpgProgram currentProgram = manager.getCurrentProgram(currentLiveChannelItem.getChannelName());
        EpgManager.EpgProgram nextProgram = manager.getNextProgram(currentLiveChannelItem.getChannelName());

        if (tvCurrentProgramName != null) {
            if (parsedCurrent != null) {
                tvCurrentProgramName.setText(parsedCurrent.start + "-" + parsedCurrent.end + "  " + parsedCurrent.title);
            } else if (currentProgram != null) {
                tvCurrentProgramName.setText(currentProgram.title);
                if (tip_epg1 != null) tip_epg1.setText(formatEpgTime(currentProgram.start) + "-" + formatEpgTime(currentProgram.stop));
            } else {
                tvCurrentProgramName.setText("暂无信息");
            }
        }
        if (tvNextProgramName != null) {
            if (parsedNext != null) {
                tvNextProgramName.setText(parsedNext.start + "-" + parsedNext.end + "  " + parsedNext.title);
            } else if (nextProgram != null) {
                tvNextProgramName.setText(nextProgram.title);
                if (tip_epg2 != null) tip_epg2.setText(formatEpgTime(nextProgram.start) + "-" + formatEpgTime(nextProgram.stop));
            } else {
                tvNextProgramName.setText("暂无节目预告");
            }
        }

        if (tvDesc != null) {
            if (parsedCurrent != null && !TextUtils.isEmpty(parsedCurrent.desc)) {
                tvDesc.setText(parsedCurrent.desc);
                tvDesc.setVisibility(View.VISIBLE);
            } else if (currentProgram != null && !TextUtils.isEmpty(currentProgram.description)) {
                tvDesc.setText(currentProgram.description);
                tvDesc.setVisibility(View.VISIBLE);
            } else {
                tvDesc.setVisibility(View.GONE);
            }
        }

        if (tv_srcinfo != null) {
            if (currentLiveChannelItem.getSourceNum() <= 0) {
                tv_srcinfo.setText("1/1");
            } else {
                tv_srcinfo.setText("线路" + (currentLiveChannelItem.getSourceIndex() + 1) + "/" + currentLiveChannelItem.getSourceNum());
            }
        }

        updateCurrentChannelIcon();
    }

    private void updateEpgProgress() {
        if (epgProgressBar == null || channel_Name == null) return;
        EpgManager.EpgProgram current = null;
        try { current = EpgManager.getInstance(this).getCurrentProgram(channel_Name.getChannelName()); } catch (Exception ignored) { }
        if (current == null && epgdata != null && !epgdata.isEmpty()) {
            int idx = findCurrentEpgIndex(epgdata);
            if (idx >= 0) {
                Epginfo e = epgdata.get(idx);
                if (e != null) {
                    current = new EpgManager.EpgProgram(e.title, e.desc, e.startdateTime, e.enddateTime);
                }
            }
        }
        if (current == null || current.start == null || current.stop == null || !current.stop.after(current.start)) {
            epgProgressBar.setProgress(0);
            if (tvEpgRemaining != null) tvEpgRemaining.setText("");
            return;
        }
        long now = System.currentTimeMillis();
        long total = current.stop.getTime() - current.start.getTime();
        long elapsed = now - current.start.getTime();
        int progress = (int)Math.max(0, Math.min(100, elapsed * 100L / Math.max(1L, total)));
        epgProgressBar.setProgress(progress);
        long remain = Math.max(0L, current.stop.getTime() - now);
        long minutes = (remain + 59999L) / 60000L;
        if (tvEpgRemaining != null) tvEpgRemaining.setText("距结束 " + minutes + " 分钟");
    }
}
