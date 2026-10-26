package ru.firedrop.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small hand-rolled design system so the app keeps the FireDrop look without AndroidX. */
public final class Ui {
    public static final int BG = 0xFF070B14;
    public static final int BG_SOFT = 0xFF0A1120;
    public static final int SURFACE = 0xFF0F1728;
    public static final int SURFACE_HI = 0xFF16203A;
    public static final int BORDER = 0x337E99CD;
    public static final int TEXT = 0xFFF2F6FF;
    public static final int MUTED = 0xFF8D9AB7;
    public static final int FAINT = 0xFF7888A5;
    public static final int ACCENT = 0xFF22D3EE;
    public static final int SUCCESS = 0xFF34D399;
    public static final int DANGER = 0xFFFB7185;
    public static final int WARNING = 0xFFFBBF24;

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable roundRect(int fill, float radiusDp, int strokeColor, Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radiusDp));
        if (Color.alpha(strokeColor) != 0) {
            shape.setStroke(dp(context, 1), strokeColor);
        }
        return shape;
    }

    public static GradientDrawable accentGradient(Context context) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF22D3EE, 0xFF38BDF8});
        shape.setCornerRadius(dp(context, 16));
        return shape;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    public static LinearLayout card(Context context) {
        LinearLayout layout = column(context);
        layout.setBackground(roundRect(SURFACE, 20, BORDER, context));
        int pad = dp(context, 18);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    public static void marginBottom(View view, Context context, float dpValue) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).bottomMargin = dp(context, dpValue);
        }
    }

    public static void marginTop(View view, Context context, float dpValue) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).topMargin = dp(context, dpValue);
        }
    }

    public static TextView button(Context context, CharSequence label, boolean primary) {
        TextView view = text(context, label, 16, primary ? 0xFF04131C : TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16));
        GradientDrawable background = primary
                ? accentGradient(context)
                : roundRect(SURFACE_HI, 16, BORDER, context);
        GradientDrawable rippleShape = new GradientDrawable();
        rippleShape.setColor(Color.TRANSPARENT);
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x33000000 : 0x33A5B4FC), background, null));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    public static TextView chip(Context context, CharSequence label) {
        TextView view = text(context, label, 13, ACCENT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
        view.setBackground(roundRect(0x2022D3EE, 999, 0x3322D3EE, context));
        return view;
    }

    public static ProgressBar progressBar(Context context, int color) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        GradientDrawable track = roundRect(0xFF1B2740, 999, 0, context);
        GradientDrawable fill = roundRect(color, 999, 0, context);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{track, fill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);
        bar.setMinimumHeight(dp(context, 6));
        bar.setMaxHeight(dp(context, 6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6));
        params.topMargin = dp(context, 10);
        bar.setLayoutParams(params);
        return bar;
    }

    public static final class Screen {
        public final LinearLayout root;
        public final LinearLayout content;
        public final LinearLayout bottom;

        Screen(LinearLayout root, LinearLayout content, LinearLayout bottom) {
            this.root = root;
            this.content = content;
            this.bottom = bottom;
        }
    }

    public static Screen screen(Activity activity, String title, String subtitle, String backLabel) {
        LinearLayout root = column(activity);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollParams);

        LinearLayout content = column(activity);
        int pad = dp(activity, 20);
        content.setPadding(pad, dp(activity, 22), pad, dp(activity, 28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroll);

        if (backLabel != null) {
            TextView back = text(activity, backLabel, 15, MUTED, true);
            back.setPadding(0, dp(activity, 4), 0, dp(activity, 14));
            back.setOnClickListener(v -> activity.finish());
            content.addView(back);
        }

        TextView heading = text(activity, title, 27, TEXT, true);
        content.addView(heading);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = text(activity, subtitle, 14, MUTED, false);
            sub.setPadding(0, dp(activity, 6), 0, 0);
            content.addView(sub);
        }

        View spacer = new View(activity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(activity, 18)));
        content.addView(spacer);

        LinearLayout bottom = column(activity);
        bottom.setPadding(pad, dp(activity, 12), pad, dp(activity, 18));
        bottom.setBackgroundColor(BG);
        bottom.setVisibility(View.GONE);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        activity.setContentView(root);
        return new Screen(root, content, bottom);
    }

    public static void gap(Context context, LinearLayout parent, float dpValue) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, dpValue)));
        parent.addView(view);
    }

    public static String formatBytes(long bytes) {
        String[] units = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        if (unit == 0) return ((long) value) + " " + units[unit];
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit]);
    }

    public static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond <= 1) return "";
        return formatBytes((long) bytesPerSecond) + "/с";
    }
}
