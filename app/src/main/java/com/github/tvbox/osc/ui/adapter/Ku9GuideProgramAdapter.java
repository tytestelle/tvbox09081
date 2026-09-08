package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Epginfo;

import java.util.ArrayList;
import java.util.Date;

public class Ku9GuideProgramAdapter extends BaseQuickAdapter<Epginfo, BaseViewHolder> {
    private int selectedIndex = -1;
    private int focusedIndex = -1;
    private boolean canBack;

    public Ku9GuideProgramAdapter() { super(R.layout.item_ku9_guide_program, new ArrayList<>()); }
    public void setCanBack(boolean value) { canBack = value; }

    @Override protected void convert(BaseViewHolder holder, Epginfo item) {
        TextView time = holder.getView(R.id.tv_guide_program_time);
        TextView name = holder.getView(R.id.tv_guide_program_name);
        TextView desc = holder.getView(R.id.tv_guide_program_desc);
        TextView state = holder.getView(R.id.tv_guide_program_state);
        time.setText(item.start + "-" + item.end);
        name.setText(item.title);
        if (desc != null) desc.setText(item.desc == null ? "" : item.desc);
        holder.itemView.setSelected(item.index == selectedIndex || item.index == focusedIndex);

        Date now = new Date();
        boolean current = item.startdateTime != null && item.enddateTime != null
                && !now.before(item.startdateTime) && !now.after(item.enddateTime);
        boolean past = item.enddateTime != null && now.after(item.enddateTime);
        if (current) {
            state.setVisibility(View.VISIBLE); state.setText("直播中");
            state.setTextColor(Color.rgb(255, 50, 50));
            state.setBackgroundColor(Color.YELLOW);
            time.setTextColor(Color.rgb(45, 112, 235));
            name.setTextColor(Color.rgb(45, 112, 235));
        } else if (past && canBack) {
            state.setVisibility(View.VISIBLE); state.setText("回看");
            state.setTextColor(Color.WHITE); state.setBackgroundColor(Color.rgb(45, 112, 235));
            time.setTextColor(Color.WHITE); name.setTextColor(Color.WHITE);
        } else {
            state.setVisibility(View.GONE);
            int c = (item.index == selectedIndex && item.index != focusedIndex) ? Color.rgb(45, 112, 235) : Color.WHITE;
            time.setTextColor(c); name.setTextColor(c);
        }
    }

    public void setSelectedIndex(int index) { int old=selectedIndex; selectedIndex=index; if(old>=0)notifyItemChanged(old); if(index>=0)notifyItemChanged(index); }
    public void setFocusedIndex(int index) { int old=focusedIndex; focusedIndex=index; if(old>=0)notifyItemChanged(old); if(index>=0)notifyItemChanged(index); }
}
