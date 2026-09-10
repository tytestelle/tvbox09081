package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;
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
        String preview = "暂无节目预告";
        String description = "";
        if (item.getChannelName() != null && item.getChannelName().equals(previewChannel) && previewProgram != null && !previewProgram.isEmpty()) {
            preview = previewProgram;
            description = previewDescription;
        } else {
            try {
                EpgManager.EpgProgram current = EpgManager.getInstance(mContext).getCurrentProgram(item.getChannelName());
                if (current != null && current.title != null && !current.title.trim().isEmpty()) {
                    String currentTitle = current.title.trim();
                    String start = current.start == null ? "" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(current.start);
                    String stop = current.stop == null ? "" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(current.stop);
                    preview = (!start.isEmpty() && !stop.isEmpty() ? start + "-" + stop + "  " : "") + currentTitle;
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
        // 节目单台标唯一来源：files/logos/epgid.png。
        // epgid 来自 epg_data.json 精确映射，禁止 M3U/GitHub/原始 URL 等其它来源。
        EpgManager epgManager = EpgManager.getInstance(mContext);
        // 不再先显示旧 PNG。旧缓存可能仍是白底；必须等本次透明化完成后再显示，
        // 保证节目单中所有台标的唯一显示来源都是已经处理过的 files/logos/epgid.png。
        logo.setImageDrawable(null);
        final String targetName = item.getChannelName();
        epgManager.loadProcessedChannelIcon(targetName, file -> {
            if (file == null || !file.exists()) return;
            // 必须把处理完成的 files/logos/epgid.png 真正设置到节目单台标控件。
            // 旧代码只 notifyItemChanged()，重新绑定时又没有 into()，因此节目单台标会消失。
            if (targetName.equals(item.getChannelName())) {
                Glide.with(mContext).load(file).dontAnimate().into(logo);
            }
        });
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
