package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.epg.EpgManager;

import java.io.File;
import java.util.ArrayList;

/**
 * @author pj567
 * @date :2021/1/12
 * @description: 频道列表适配器（含台标异步加载）
 */
public class LiveChannelItemAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    private int selectedChannelIndex = -1;
    private String previewChannel = "";
    private String previewProgram = "";
    private String previewDescription = "";
    private int focusedChannelIndex = -1;

    public LiveChannelItemAdapter() {
        super(R.layout.item_live_channel, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        // 原有视图绑定
        TextView tvChannelNum = holder.getView(R.id.tvChannelNum);
        TextView tvChannel = holder.getView(R.id.tvChannelName);
        tvChannelNum.setText(String.format("%s", item.getChannelNum()));
        tvChannel.setText(item.getChannelName());
        tvChannelNum.setSelected(true);
        tvChannel.setSelected(true);

        // ---------- 台标 + 当前节目 ----------
        ImageView ivLogo = holder.getView(R.id.ivChannelLogo);
        Glide.with(mContext).clear(ivLogo);

        String channelName = item.getChannelName();
        String channelLogoUrl = item.getChannelLogo();
        try {
            String[] info = EpgUtil.getEpgInfo(channelName);
            if ((channelLogoUrl == null || channelLogoUrl.isEmpty()) && info != null && info.length > 0) channelLogoUrl = info[0];
        } catch (Exception ignored) { }
        if (channelLogoUrl == null || channelLogoUrl.isEmpty()) {
            try { channelLogoUrl = EpgManager.getInstance(mContext).getChannelIconUrl(channelName); } catch (Exception ignored) { }
        }
        TextView tvCurrentProgram = holder.getView(R.id.tvCurrentProgram);
        TextView tvCurrentProgramDesc = holder.getView(R.id.tvCurrentProgramDesc);
        String preview = "暂无节目预告";
        String description = "";
        if (channelName != null && channelName.equals(previewChannel) && previewProgram != null && !previewProgram.isEmpty()) {
            preview = previewProgram;
            description = previewDescription;
        } else {
            try {
                EpgManager.EpgProgram current = EpgManager.getInstance(mContext).getCurrentProgram(channelName);
                if (current != null && current.title != null && !current.title.trim().isEmpty()) {
                    String currentTitle = current.title.trim();
                    String start = current.start == null ? "" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(current.start);
                    String stop = current.stop == null ? "" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(current.stop);
                    preview = (!start.isEmpty() && !stop.isEmpty() ? start + "-" + stop + "  " : "") + currentTitle;
                    description = current.description == null ? "" : current.description.trim();
                }
            } catch (Exception ignored) { }
        }
        tvCurrentProgram.setText(preview);
        tvCurrentProgramDesc.setText(description);

        // XMLTV EPG 台标唯一来源：files/logos/epgid.png。
        // 不再调用 LogoManager / M3U / GitHub / 原始 URL，避免其它来源覆盖透明化台标。
        EpgManager epgManager = EpgManager.getInstance(mContext);
        // 永远等待透明化完成后再显示，避免旧版本白底 PNG 被直接闪现。
        ivLogo.setImageDrawable(null);
        final String targetName = channelName;
        epgManager.loadProcessedChannelIcon(targetName, file -> {
            if (file != null && file.exists() && holder.getLayoutPosition() >= 0) {
                Glide.with(mContext).load(file).dontAnimate().into(ivLogo);
            }
        });
        // ---------- 新增结束 ----------

        // 原有选中/焦点颜色逻辑
        int channelIndex = item.getChannelIndex();
        holder.itemView.setSelected(channelIndex == selectedChannelIndex);
        if (channelIndex == selectedChannelIndex && channelIndex != focusedChannelIndex) {
            tvChannelNum.setTextColor(mContext.getResources().getColor(R.color.color_1890FF));
            tvChannel.setTextColor(mContext.getResources().getColor(R.color.color_1890FF));
        } else {
            tvChannelNum.setTextColor(Color.WHITE);
            tvChannel.setTextColor(Color.WHITE);
        }
    }

    public void setCurrentProgramPreview(String channelName, String program) {
        setCurrentProgramPreview(channelName, program, "");
    }

    public void setCurrentProgramPreview(String channelName, String program, String description) {
        previewChannel = channelName == null ? "" : channelName;
        previewProgram = program == null ? "" : program;
        previewDescription = description == null ? "" : description;
        notifyDataSetChanged();
    }

    public void setSelectedChannelIndex(int selectedChannelIndex) {
        if (selectedChannelIndex == this.selectedChannelIndex) return;
        int preSelectedChannelIndex = this.selectedChannelIndex;
        this.selectedChannelIndex = selectedChannelIndex;
        if (preSelectedChannelIndex != -1)
            notifyItemChanged(preSelectedChannelIndex);
        if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }

    public void setFocusedChannelIndex(int focusedChannelIndex) {
        int preFocusedChannelIndex = this.focusedChannelIndex;
        this.focusedChannelIndex = focusedChannelIndex;
        if (preFocusedChannelIndex != -1)
            notifyItemChanged(preFocusedChannelIndex);
        if (this.focusedChannelIndex != -1)
            notifyItemChanged(this.focusedChannelIndex);
        else if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }
}
