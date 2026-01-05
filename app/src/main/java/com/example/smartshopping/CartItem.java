package com.example.smartshopping;

public class CartItem {

    private String name;
    private float price;
    private int qty;

    public CartItem(String name, float price, int qty) {
        this.name = name;
        this.price = price;
        this.qty = qty;
    }

    // ---------- GETTERS ----------
    public String getName() {
        return name;
    }

    public float getPrice() {
        return price;
    }

    public int getQty() {
        return qty;
    }

    // ---------- LOGIC ----------
    public void increaseQty(int amount) {
        this.qty += amount;
    }

    public float getTotal() {
        return price * qty;
    }
}
