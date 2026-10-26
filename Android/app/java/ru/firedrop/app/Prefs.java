package ru.firedrop.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

public final class Prefs {
    private static final String FILE = "firedrop";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String deviceName() {
        String value = prefs.getString("deviceName", null);
        if (value == null || value.trim().isEmpty()) {
            String model = Build.MODEL == null ? "Телефон" : Build.MODEL.trim();
            return model;
        }
        return value;
    }

    public void setDeviceName(String value) {
        prefs.edit().putString("deviceName", value == null ? "" : value.trim()).apply();
    }

    public int port() {
        return prefs.getInt("port", 47820);
    }

    public void setPort(int value) {
        prefs.edit().putInt("port", value).apply();
    }

    public String lastHost() {
        return prefs.getString("lastHost", "");
    }

    public int lastPort() {
        return prefs.getInt("lastPort", 47820);
    }

    public String lastPin() {
        return prefs.getString("lastPin", "");
    }

    public void setLastTarget(String host, int port) {
        prefs.edit().putString("lastHost", host).putInt("lastPort", port).apply();
    }

    public void setLastPin(String pin) {
        prefs.edit().putString("lastPin", pin).apply();
    }
}
