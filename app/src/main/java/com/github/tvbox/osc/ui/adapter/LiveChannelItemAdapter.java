package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.util.logo.LogoManager; // 包名已修正
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
        if (channelLogoUrl == null || channelLogoUrl.isEmpty()) {
            try {
                String[] info = EpgUtil.getEpgInfo(channelName);
                if (info != null && info.length > 0) channelLogoUrl = info[0];
            } catch (Exception ignored) { }
        }
        TextView tvCurrentProgram = holder.getView(R.id.tvCurrentProgram);
        TextView tvCurrentProgramDesc = holder.getView(R.id.tvCurrentProgramDesc);
        String preview = "精彩节目";
        String description = "";
        if (channelName != null && channelName.equals(previewChannel) && previewProgram != null && !previewProgram.isEmpty()) {
            preview = previewProgram;
            description = previewDescription;
        } else {
            try {
                EpgManager.EpgProgram current = EpgManager.getInstance(mContext).getCurrentProgram(channelName);
                if (current != null && current.title != null && !current.title.trim().isEmpty()) {
                    preview = current.title.trim();
                    description = current.description == null ? "" : current.description.trim();
                }
            } catch (Exception ignored) { }
        }
        tvCurrentProgram.setText(preview);
        tvCurrentProgramDesc.setText(description);

        LogoManager logoManager = LogoManager.getInstance(mContext);
        File localLogo = logoManager.getLocalLogo(channelName);
        if (localLogo != null && localLogo.exists()) {
            Glide.with(mContext).load(localLogo).into(ivLogo);
        } else {
            logoManager.downloadLogo(channelName, channelLogoUrl, new LogoManager.LogoCallback() {
                @Override public void onSuccess(File file) {
                    if (holder.getLayoutPosition() >= 0) Glide.with(mContext).load(file).into(ivLogo);
                }
                @Override public void onError(String msg) { }
            });
        }
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
