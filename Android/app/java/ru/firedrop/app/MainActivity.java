package ru.firedrop.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private Prefs prefs;
    private Ui.Screen screen;
    private TextView receiveChip;
    private TextView receiveButton;
    private TextView deviceValue;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshState();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        build();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        handler.postDelayed(ticker, 800);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;

        String host = data.getQueryParameter("host");
        String port = data.getQueryParameter("port");
        String pin = data.getQueryParameter("pin");
        if (host == null) return;

        Intent send = new Intent(this, SendActivity.class);
        send.putExtra(SendActivity.EXTRA_HOST, host);
        if (port != null) send.putExtra(SendActivity.EXTRA_PORT, port);
        if (pin != null) send.putExtra(SendActivity.EXTRA_PIN, pin);
        startActivity(send);
    }

    private void build() {
        screen = Ui.screen(this, "FireDrop", "Файлы и папки по Wi-Fi. Без интернета и облака.", null);
        LinearLayout content = screen.content;

        content.addView(sendCard());
        content.addView(receiveCard());
        content.addView(settingsCard());
        content.addView(aboutCard());
    }

    private View sendCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "Отправить", 19, Ui.TEXT, true));
        card.addView(head);

        TextView subtitle = Ui.text(this,
                "Компьютер или другой телефон в этой же сети Wi-Fi", 14, Ui.MUTED, false);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        card.addView(subtitle);

        TextView action = Ui.button(this, "Выбрать файлы или папку", true);
        action.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        card.addView(action);
        return card;
    }

    private View receiveCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "Принять", 19, Ui.TEXT, true));
        receiveChip = Ui.chip(this, "выключено");
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.leftMargin = Ui.dp(this, 10);
        receiveChip.setLayoutParams(chipParams);
        head.addView(receiveChip);
        card.addView(head);

        TextView subtitle = Ui.text(this,
                "Другой телефон или компьютер отправит файлы прямо на этот аппарат",
                14, Ui.MUTED, false);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        card.addView(subtitle);

        receiveButton = Ui.button(this, "Включить приём", false);
        receiveButton.setOnClickListener(v -> openReceive());
        card.addView(receiveButton);
        return card;
    }

    private View settingsCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        card.addView(Ui.text(this, "Настройки", 19, Ui.TEXT, true));
        Ui.gap(this, card, 8);

        card.addView(settingRow("Имя устройства", prefs.deviceName(), v -> editDeviceName()));
        card.addView(divider());
        card.addView(settingRow("Порт приёма", String.valueOf(prefs.port()), v -> editPort()));
        card.addView(divider());
        card.addView(settingRow("Папка приёма", "Download/FireDrop", v -> showFolderInfo()));
        return card;
    }

    private View aboutCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Как это работает", 19, Ui.TEXT, true));
        Ui.gap(this, card, 10);

        String[] steps = {
                "1. Запустите FireDrop на компьютере или включите «Принять» на другом телефоне.",
                "2. Нажмите «Отправить», найдите устройство и введите шестизначный код.",
                "3. Выберите файлы или целую папку — структура папок сохранится.",
        };
        for (String step : steps) {
            TextView line = Ui.text(this, step, 14, Ui.MUTED, false);
            line.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
            card.addView(line);
        }

        Ui.gap(this, card, 6);
        TextView footer = Ui.text(this,
                "FireDrop " + App.VERSION + " · передача не выходит за пределы локальной сети",
                12, Ui.FAINT, false);
        card.addView(footer);

        Ui.gap(this, card, 10);
        TextView about = Ui.button(this, "О приложении", false);
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        card.addView(about);
        return card;
    }

    private View settingRow(String label, String value, View.OnClickListener listener) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        row.setClickable(true);
        row.setOnClickListener(listener);

        TextView title = Ui.text(this, label, 15, Ui.TEXT, false);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView current = Ui.text(this, value, 15, Ui.MUTED, false);
        current.setGravity(Gravity.END);
        row.addView(current);
        if (label.startsWith("Имя")) deviceValue = current;
        return row;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(0x1A7E99CD);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        return view;
    }

    private void refreshState() {
        boolean running = ReceiverService.isRunning();
        if (running) {
            receiveChip.setText("включён");
            receiveChip.setTextColor(Ui.SUCCESS);
            receiveChip.setBackground(Ui.roundRect(0x2034D399, 999, 0x3334D399, this));
            receiveButton.setText("Открыть экран приёма");
        } else {
            receiveChip.setText("выключено");
            receiveChip.setTextColor(Ui.MUTED);
            receiveChip.setBackground(Ui.roundRect(0x1A7E99CD, 999, 0x337E99CD, this));
            receiveButton.setText("Включить приём");
        }
        if (deviceValue != null) deviceValue.setText(prefs.deviceName());
    }

    private void openReceive() {
        if (!ReceiverService.isRunning()) {
            if (!ensureLegacyStoragePermission()) return;
            requestNotificationPermission();
            Intent intent = new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
        startActivity(new Intent(this, ReceiveActivity.class));
    }

    /** Android 8 and older need runtime access to write into the public Downloads folder. */
    private boolean ensureLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 11 && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            openReceive();
        } else if (requestCode == 11) {
            Toast.makeText(this,
                    "Без доступа к памяти принятые файлы не сохранятся в «Загрузки».",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }

    private void editDeviceName() {
        EditText input = new EditText(this);
        input.setText(prefs.deviceName());
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setHint("Например: Телефон Ани");

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Имя устройства")
                .setMessage("Так вас увидят другие устройства в сети.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) value = "Телефон";
                    prefs.setDeviceName(value);
                    refreshState();
                    if (ReceiverService.isRunning()) {
                        Toast.makeText(this, "Имя применится после повторного включения приёма",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editPort() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(prefs.port()));
        input.setTextColor(Ui.TEXT);
        input.setSingleLine(true);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Порт приёма")
                .setMessage("По умолчанию 47820. Меняйте только если порт занят.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    try {
                        int port = Integer.parseInt(input.getText().toString().trim());
                        if (port < 1024 || port > 65535) throw new NumberFormatException();
                        prefs.setPort(port);
                        Toast.makeText(this, "Порт сохранён. Перезапустите приём.",
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Нужен порт от 1024 до 65535", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showFolderInfo() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Папка приёма")
                .setMessage("Принятые файлы сохраняются в общую папку «Загрузки»:\n\n"
                        + "Download/FireDrop\n\nСтруктура вложенных папок сохраняется. "
                        + "Файлы видны в любом проводнике и в галерее.")
                .setPositiveButton("Понятно", null)
                .show();
    }
}
