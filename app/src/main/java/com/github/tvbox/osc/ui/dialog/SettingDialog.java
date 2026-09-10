package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置弹窗修复类（适配酷9反编译项目）
 * 修复：直播订阅、EPG订阅点击无响应
 */
public class SettingDialog extends BaseDialog {

    private TextView tvLiveSub;
    private TextView tvEpgSub;
    private TextView tvLiveClear;
    private TextView tvEpgClear;
    private TextView tvLiveStatus;
    private TextView tvEpgStatus;

    private final boolean epgOnly;

    public SettingDialog(@NonNull @NotNull Context context) {
        this(context, false);
    }

    public SettingDialog(@NonNull @NotNull Context context, boolean epgOnly) {
        super(context);
        this.epgOnly = epgOnly;
        setContentView(R.layout.dialog_setting);
        initView();
        if (epgOnly) {
            View liveRow = findViewById(R.id.rowLiveSubscription);
            if (liveRow != null) liveRow.setVisibility(View.GONE);
            TextView title = findViewById(R.id.tvSettingTitle);
            if (title != null) title.setText("EPG订阅");
        }
        initData();
        initListener();
    }

    private void initView() {
        tvLiveSub = findViewById(R.id.tvLiveSub);
        tvEpgSub = findViewById(R.id.tvEpgSub);
        tvLiveClear = findViewById(R.id.tvLiveClear);
        tvEpgClear = findViewById(R.id.tvEpgClear);
        tvLiveStatus = findViewById(R.id.tvLiveStatus);
        tvEpgStatus = findViewById(R.id.tvEpgStatus);
    }

    private void initData() {
        String liveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        String epgUrl = Hawk.get(HawkConfig.EPG_URL, "");

        if (tvLiveStatus != null) {
            tvLiveStatus.setText(liveUrl.isEmpty() ? "未配置" : "已配置");
        }
        if (tvEpgStatus != null) {
            tvEpgStatus.setText(epgUrl.isEmpty() ? "未配置" : "已配置");
        }
    }

    private void initListener() {
        if (tvLiveSub != null) {
            tvLiveSub.setOnClickListener(v -> showLiveSubDialog());
        }

        if (tvEpgSub != null) {
            tvEpgSub.setOnClickListener(v -> showEpgSubDialog());
        }

        if (tvLiveClear != null) {
            tvLiveClear.setOnClickListener(v -> {
                Hawk.put(HawkConfig.LIVE_API_URL, "");
                Hawk.put(HawkConfig.LIVE_API_HISTORY, new ArrayList<>());
                Toast.makeText(getContext(), "直播订阅已清除，重启播放生效", Toast.LENGTH_SHORT).show();
                initData();
                notifyLiveRefresh();
            });
        }

        if (tvEpgClear != null) {
            tvEpgClear.setOnClickListener(v -> {
                Hawk.put(HawkConfig.EPG_URL, "");
                Hawk.put(HawkConfig.EPG_HISTORY, new ArrayList<>());
                Toast.makeText(getContext(), "EPG订阅已清除", Toast.LENGTH_SHORT).show();
                initData();
                notifyEpgRefresh();
            });
        }
    }

    private void showLiveSubDialog() {
        showSubscriptionHistory(true);
    }

    private void showEpgSubDialog() {
        showSubscriptionHistory(false);
    }

    private void showSubscriptionHistory(boolean live) {
        String key = live ? HawkConfig.LIVE_API_HISTORY : HawkConfig.EPG_HISTORY;
        String current = live ? Hawk.get(HawkConfig.LIVE_API_URL, "") : Hawk.get(HawkConfig.EPG_URL, "");
        ArrayList<String> history = Hawk.get(key, new ArrayList<String>());
        if (history == null) history = new ArrayList<>();
        if (current != null && !current.isEmpty() && !history.contains(current)) history.add(0, current);
        if (history.isEmpty()) {
            history.add("");
        }
        int idx = Math.max(0, history.indexOf(current));
        ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
        dialog.setTip(live ? "列表订阅" : "EPG订阅");
        ArrayList<String> finalHistory = history;
        dialog.setAdapter(new com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter.SelectDialogInterface() {
            @Override public void click(String value) {
                if (value == null || value.trim().isEmpty()) return;
                if (live) { Hawk.put(HawkConfig.LIVE_API_URL, value); saveToHistory(key, value); notifyLiveRefresh(); }
                else { Hawk.put(HawkConfig.EPG_URL, value); saveToHistory(key, value); notifyEpgRefresh(); }
                initData();
            }
            @Override public void del(String value, ArrayList<String> data) { Hawk.put(key, data); initData(); }
        }, finalHistory, idx);
        dialog.setAddListener(v -> showAddSubscription(live));
        dialog.show();

    }

    private void showAddSubscription(boolean live) {
        String current = live ? Hawk.get(HawkConfig.LIVE_API_URL, "") : Hawk.get(HawkConfig.EPG_URL, "");
        new InputDialog(getContext())
                .setTitle(live ? "列表订阅" : "EPG订阅")
                .setHint(live ? "请输入直播源订阅链接 (m3u/txt/json)..." : "请输入EPG订阅链接 (XMLTV/JSON)...")
                .setDefaultText(current)
                .setOnConfirmListener(text -> {
                    if (text == null || text.trim().isEmpty()) { Toast.makeText(getContext(), "地址不能为空", Toast.LENGTH_SHORT).show(); return; }
                    String url = text.trim();
                    if (live) { Hawk.put(HawkConfig.LIVE_API_URL, url); saveToHistory(HawkConfig.LIVE_API_HISTORY, url); notifyLiveRefresh(); }
                    else { Hawk.put(HawkConfig.EPG_URL, url); saveToHistory(HawkConfig.EPG_HISTORY, url); notifyEpgRefresh(); }
                    Toast.makeText(getContext(), "订阅已保存", Toast.LENGTH_SHORT).show();
                    initData();
                }).show();
    }

    private void saveToHistory(String key, String url) {
        try {
            List<String> history = Hawk.get(key, new ArrayList<>());
            if (history == null) history = new ArrayList<>();
            history.remove(url);
            history.add(0, url);
            if (history.size() > 10) {
                history = history.subList(0, 10);
            }
            Hawk.put(key, history);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyLiveRefresh() {
        try {
            android.content.Intent intent = new android.content.Intent("com.github.tvbox.osc.LIVE_REFRESH");
            getContext().sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyEpgRefresh() {
        try {
            android.content.Intent intent = new android.content.Intent("com.github.tvbox.osc.EPG_REFRESH");
            getContext().sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
