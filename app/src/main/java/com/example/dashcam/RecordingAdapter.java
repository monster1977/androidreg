package com.example.dashcam;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Список записей в галерее. */
public class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.RecordingViewHolder> {

    public interface OnRecordingClickListener {
        void onRecordingClick(Recording recording);
    }

    public interface OnRecordingLongClickListener {
        void onRecordingLongClick(Recording recording);
    }

    private final List<Recording> items = new ArrayList<>();
    private final OnRecordingClickListener clickListener;
    private final OnRecordingLongClickListener longClickListener;

    public RecordingAdapter(OnRecordingClickListener clickListener,
                            OnRecordingLongClickListener longClickListener) {
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    public void submit(List<Recording> recordings) {
        items.clear();
        items.addAll(recordings);
        notifyDataSetChanged();
    }

    public void remove(Recording recording) {
        int index = items.indexOf(recording);
        if (index >= 0) {
            items.remove(index);
            notifyItemRemoved(index);
        }
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public RecordingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new RecordingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingViewHolder holder, int position) {
        Recording item = items.get(position);
        holder.textTitle.setText(item.title());
        holder.textSubtitle.setText(item.subtitle());
        holder.itemView.setOnClickListener(v -> clickListener.onRecordingClick(item));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onRecordingLongClick(item);
            return true;
        });
        holder.icon.setImageResource(R.drawable.ic_play);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class RecordingViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView textTitle;
        final TextView textSubtitle;

        RecordingViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.imageItemIcon);
            textTitle = itemView.findViewById(R.id.textItemTitle);
            textSubtitle = itemView.findViewById(R.id.textItemSubtitle);
        }
    }
}
