package com.lokalno.localfoldersyncclient.ui;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.function.Consumer;

public class AvailableDevicesAdapter extends RecyclerView.Adapter<AvailableDevicesAdapter.ViewHolder> {

    private final List<String> items;
    private final Consumer<String> onItemSelected;
    private int selectedPosition = RecyclerView.NO_POSITION;

    public AvailableDevicesAdapter(List<String> items, Consumer<String> onItemSelected) {
        this.items = items;
        this.onItemSelected = onItemSelected;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView text;

        public ViewHolder(@NonNull View view) {
            super(view);
            text = view.findViewById(android.R.id.text1);
        }

        public void bind(int position) {
            text.setText(items.get(position));
            itemView.setSelected(position == selectedPosition);

            itemView.setOnClickListener(v -> {
                int previousPosition = selectedPosition;
                selectedPosition = getAdapterPosition();
                notifyItemChanged(previousPosition);
                notifyItemChanged(selectedPosition);
                onItemSelected.accept(items.get(selectedPosition));
            });
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}