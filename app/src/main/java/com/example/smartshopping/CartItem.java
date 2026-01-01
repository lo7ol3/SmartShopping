package com.example.smartshopping;

public class CartItem {
    public String name;
    public float price;
    public int qty;

    public CartItem(String name, float price, int qty) {
        this.name = name;
        this.price = price;
        this.qty = qty;
    }

    public float getTotal() {
        return price * qty;
    }
}
