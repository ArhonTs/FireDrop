package ru.firedrop.app;

import android.app.Application;

/** Applies the saved language preference before any activity builds its UI. */
public final class FireDropApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        L10n.configure(new Prefs(this).language());
    }
}
