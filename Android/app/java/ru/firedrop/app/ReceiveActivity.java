package ru.firedrop.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public final class ReceiveActivity extends Activity {
    private Ui.Screen screen;
    private TextView pinView;
    private TextView addressView;
    private TextView addressHint;
    private ImageView qrView;
    private TextView statusChip;
    private TextView statsView;
    private LinearLayout transfers;
    private TextView stopButton;
    private TextView folderButton;

    private String lastQrPayload = "";
    private int lastCompleted = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.postDelayed(ticker, 500);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void build() {
        screen = Ui.screen(this, "Принять", "Другое устройство отправит файлы на этот телефон",
                "← Назад");
        LinearLayout content = screen.content;

        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "Код подключения", 16, Ui.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusChip = Ui.chip(this, "выключено");
        head.addView(statusChip);
        card.addView(head);

        pinView = Ui.text(this, "— — — — — —", 40, Ui.ACCENT, true);
        pinView.setGravity(Gravity.CENTER);
        pinView.setLetterSpacing(0.22f);
        pinView.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        card.addView(pinView);

        addressView = Ui.text(this, "", 14, Ui.MUTED, false);
        addressView.setGravity(Gravity.CENTER);
        card.addView(addressView);

        addressHint = Ui.text(this, "", 12, Ui.WARNING, false);
        addressHint.setGravity(Gravity.CENTER);
        addressHint.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), 0);
        addressHint.setVisibility(View.GONE);
        card.addView(addressHint);

        qrView = new ImageView(this);
        int qrSize = Ui.dp(this, 216);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(qrSize, qrSize);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = Ui.dp(this, 16);
        qrView.setLayoutParams(qrParams);
        qrView.setBackground(Ui.roundRect(0xFFFFFFFF, 16, 0, this));
        qrView.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(qrView);

        TextView hint = Ui.text(this,
                "Откройте FireDrop на компьютере или телефоне, нажмите «Отправить» "
                        + "и отсканируйте этот QR-код либо введите адрес и код вручную.",
                13, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 16), 0, 0);
        card.addView(hint);

        content.addView(card);

        LinearLayout stats = Ui.card(this);
        stats.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stats.addView(Ui.text(this, "Принято", 18, Ui.TEXT, true));
        statsView = Ui.text(this, "Пока ничего не принято", 14, Ui.MUTED, false);
        statsView.setPadding(0, Ui.dp(this, 6), 0, 0);
        stats.addView(statsView);

        transfers = Ui.column(this);
        transfers.setPadding(0, Ui.dp(this, 8), 0, 0);
        stats.addView(transfers);
        content.addView(stats);

        stopButton = Ui.button(this, "Остановить приём", false);
        stopButton.setOnClickListener(v -> stopReceiving());
        screen.bottom.addView(stopButton);

        folderButton = Ui.button(this, "Открыть папку «Загрузки»", false);
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        folderParams.topMargin = Ui.dp(this, 10);
        folderButton.setOnClickListener(v -> openDownloads());
        screen.bottom.addView(folderButton, folderParams);
        screen.bottom.setVisibility(View.VISIBLE);
    }

    private void refresh() {
        ReceiverServer server = ReceiverService.server;
        boolean running = ReceiverService.isRunning() && server != null;

        if (!running) {
            statusChip.setText("выключено");
            statusChip.setTextColor(Ui.MUTED);
            statusChip.setBackground(Ui.roundRect(0x1A7E99CD, 999, 0x337E99CD, this));
            pinView.setText("— — — — — —");
            pinView.setTextColor(Ui.FAINT);
            addressView.setText("Приём остановлен");
            addressHint.setVisibility(View.GONE);
            qrView.setImageDrawable(null);
            stopButton.setText("Включить приём");
            stopButton.setOnClickListener(v -> startReceiving());
            lastQrPayload = "";
            return;
        }

        statusChip.setText("включён");
        statusChip.setTextColor(Ui.SUCCESS);
        statusChip.setBackground(Ui.roundRect(0x2034D399, 999, 0x3334D399, this));
        pinView.setTextColor(Ui.ACCENT);
        pinView.setText(server.pin());

        String ip = server.address();
        if (ip.isEmpty()) {
            addressView.setText("Нет подключения к Wi-Fi");
            addressView.setTextColor(Ui.DANGER);
            addressHint.setVisibility(View.GONE);
        } else {
            addressView.setTextColor(Ui.MUTED);
            addressView.setText("http://" + ip + ":" + server.port() + "  ·  "
                    + server.getDeviceName());

            if (Net.isVpnUp()) {
                addressHint.setText("На телефоне включён VPN — он может уводить соединение "
                        + "телефона с Wi-Fi. Если компьютер или другой телефон не "
                        + "подключается, отключите VPN.");
                addressHint.setVisibility(View.VISIBLE);
            } else if (!Net.isHomeLike(ip)) {
                addressHint.setText("Адрес не из домашней подсети (обычно 192.168.x.x): "
                        + "похоже, телефон в гостевой или другой Wi-Fi сети, в мобильном "
                        + "интернете или под VPN. Компьютер из другой подсети сюда не "
                        + "дозвонится — подключите телефон к той же Wi-Fi сети, что и он.");
                addressHint.setVisibility(View.VISIBLE);
            } else {
                addressHint.setVisibility(View.GONE);
            }
        }

        stopButton.setText("Остановить приём");
        stopButton.setOnClickListener(v -> stopReceiving());

        String payload = "firedrop://send?host=" + ip + "&port=" + server.port()
                + "&pin=" + server.pin();
        if (!ip.isEmpty() && !payload.equals(lastQrPayload)) {
            lastQrPayload = payload;
            final String toEncode = payload;
            new Thread(() -> {
                Bitmap bitmap = Qr.encode(toEncode, 640);
                handler.post(() -> {
                    if (bitmap != null) qrView.setImageBitmap(bitmap);
                });
            }, "firedrop-qr").start();
        }

        int count = server.completedCount();
        long bytes = server.completedBytes();
        if (count == 0) {
            statsView.setText("Пока ничего не принято. Папка: Download/FireDrop");
        } else {
            statsView.setText("Файлов: " + count + " · " + Ui.formatBytes(bytes)
                    + " · папка Download/FireDrop");
        }

        if (lastCompleted >= 0 && count > lastCompleted) {
            Toast.makeText(this, "Получен новый файл", Toast.LENGTH_SHORT).show();
        }
        lastCompleted = count;

        renderActive(server.activeSessions());
    }

    private void renderActive(List<ReceiverServer.Session> sessions) {
        transfers.removeAllViews();
        if (sessions.isEmpty()) return;

        for (ReceiverServer.Session session : sessions) {
            LinearLayout container = Ui.column(this);
            container.setPadding(0, Ui.dp(this, 10), 0, 0);

            LinearLayout head = Ui.row(this);
            TextView name = Ui.text(this, session.shortName(), 13, Ui.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            head.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            long done = session.receivedBytes.get();
            int percent = session.size <= 0 ? 100 : (int) Math.min(100, done * 100 / session.size);
            head.addView(Ui.text(this, percent + "%", 12, Ui.MUTED, true));
            container.addView(head);

            ProgressBar bar = Ui.progressBar(this, Ui.SUCCESS);
            bar.setProgress(percent * 10);
            container.addView(bar);

            TextView meta = Ui.text(this, Ui.formatBytes(done) + " из "
                    + Ui.formatBytes(session.size), 11, Ui.FAINT, false);
            container.addView(meta);

            transfers.addView(container);
        }
    }

    private void startReceiving() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 21);
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 22);
        }

        Intent intent = new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        handler.postDelayed(this::refresh, 400);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 21 && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startReceiving();
        }
    }

    private void stopReceiving() {
        if (!ReceiverService.isRunning()) {
            startReceiving();
            return;
        }
        Intent intent = new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_STOP);
        startService(intent);
        handler.postDelayed(this::refresh, 300);
    }

    private void openDownloads() {
        java.util.List<Intent> attempts = new java.util.ArrayList<>();
        attempts.add(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://com.android.providers.downloads.documents/root/downloads"),
                        "vnd.android.document/root"));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            attempts.add(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            "vnd.android.cursor.dir/download"));
        }

        for (Intent intent : attempts) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Папка с файлами")
                .setMessage("Откройте проводник или приложение «Файлы» и перейдите в:\n\n"
                        + "Загрузки / Download → FireDrop\n\n"
                        + "Вложенные папки сохраняются как на отправителе.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private String formatSpeed(long bytesPerSecond) {
        return String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / 1048576.0);
    }
}
