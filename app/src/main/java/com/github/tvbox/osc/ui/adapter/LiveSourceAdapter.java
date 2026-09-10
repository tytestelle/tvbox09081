package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;
import androidx.annotation.NonNull;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import java.util.ArrayList;

public class LiveSourceAdapter extends BaseQuickAdapter<String, BaseViewHolder> {
    private int selectedPosition = -1;
    private int focusedPosition = -1;

    public LiveSourceAdapter() {
        super(R.layout.item_live_source, new ArrayList<>());
    }

    @Override
    protected void convert(@NonNull BaseViewHolder helper, String item) {
        TextView tvName = helper.getView(R.id.tv_source_name);
        tvName.setText(item);
        int position = helper.getAdapterPosition();
        tvName.setSelected(position == selectedPosition);
        tvName.setActivated(position == focusedPosition);
    }

    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        notifyDataSetChanged();
    }

    public void setFocusedPosition(int position) {
        this.focusedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }
}
