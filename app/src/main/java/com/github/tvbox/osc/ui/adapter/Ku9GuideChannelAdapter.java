package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.util.logo.LogoManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.epg.EpgManager;

import java.io.File;
import java.util.ArrayList;

public class Ku9GuideChannelAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    private int selectedIndex = -1;
    private String previewChannel = "";
    private String previewProgram = "";
    private String previewDescription = "";
    private int focusedIndex = -1;

    public Ku9GuideChannelAdapter() {
        super(R.layout.item_ku9_guide_channel, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        TextView num = holder.getView(R.id.tv_guide_channel_num);
        TextView name = holder.getView(R.id.tv_guide_channel_name);
        TextView program = holder.getView(R.id.tv_guide_channel_program);
        TextView programDesc = holder.getView(R.id.tv_guide_channel_program_desc);
        ImageView logo = holder.getView(R.id.iv_guide_channel_logo);

        num.setText(String.valueOf(item.getChannelNum()));
        name.setText(item.getChannelName());
        String preview = "精彩节目";
        String description = "";
        if (item.getChannelName() != null && item.getChannelName().equals(previewChannel) && previewProgram != null && !previewProgram.isEmpty()) {
            preview = previewProgram;
            description = previewDescription;
        } else {
            try {
                EpgManager.EpgProgram current = EpgManager.getInstance(mContext).getCurrentProgram(item.getChannelName());
                if (current != null && current.title != null && !current.title.trim().isEmpty()) {
                    preview = current.title.trim();
                    description = current.description == null ? "" : current.description.trim();
                }
            } catch (Exception ignored) { }
        }
        program.setText(preview);
        programDesc.setText(description);
        holder.itemView.setSelected(item.getChannelIndex() == selectedIndex);

        int index = item.getChannelIndex();
        int color = (index == selectedIndex && index != focusedIndex) ? Color.rgb(45, 112, 235) : Color.WHITE;
        num.setTextColor(color);
        name.setTextColor(color);

        Glide.with(mContext).clear(logo);
        String logoUrl = item.getChannelLogo();
        if (logoUrl == null || logoUrl.isEmpty() || "false".equalsIgnoreCase(logoUrl)) {
            try {
                String[] info = EpgUtil.getEpgInfo(item.getChannelName());
                if (info != null && info.length > 0) logoUrl = info[0];
            } catch (Exception ignored) { }
        }
        final LogoManager logoManager = LogoManager.getInstance(mContext);
        File local = logoManager.getLocalLogo(item.getChannelName());
        if (local != null && local.exists()) {
            Glide.with(mContext).load(local).into(logo);
        } else if (logoUrl != null && !logoUrl.isEmpty()) {
            logoManager.downloadLogo(item.getChannelName(), logoUrl, new LogoManager.LogoCallback() {
                @Override public void onSuccess(File file) { if (holder.getLayoutPosition() >= 0) Glide.with(mContext).load(file).into(logo); }
                @Override public void onError(String msg) { }
            });
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

    public void setSelectedIndex(int index) {
        if (index == selectedIndex) return;
        int old = selectedIndex;
        selectedIndex = index;
        if (old >= 0) notifyItemChanged(old);
        if (index >= 0) notifyItemChanged(index);
    }

    public void setFocusedIndex(int index) {
        int old = focusedIndex;
        focusedIndex = index;
        if (old >= 0) notifyItemChanged(old);
        if (index >= 0) notifyItemChanged(index);
    }
}
