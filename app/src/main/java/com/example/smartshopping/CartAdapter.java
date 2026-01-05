package com.example.smartshopping;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {

    private final List<CartItem> cart;

    public CartAdapter(List<CartItem> cart) {
        this.cart = cart;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cart, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CartItem item = cart.get(position);

        String name = item.getName() == null ? "" : item.getName();
        holder.name.setText(name.replace("_", " ").toUpperCase());
        holder.qty.setText("x" + item.getQty());
        holder.price.setText(
                "RM " + String.format("%.2f", item.getTotal())
        );
    }

    @Override
    public int getItemCount() {
        return cart.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, qty, price;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.cartItemName);
            qty = itemView.findViewById(R.id.cartItemQty);
            price = itemView.findViewById(R.id.cartItemPrice);
        }
    }
}
