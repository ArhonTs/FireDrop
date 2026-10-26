package ru.firedrop.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SendActivity extends Activity {
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PIN = "pin";

    private static final int REQUEST_FILES = 101;
    private static final int REQUEST_FOLDER = 102;
    private static final int REQUEST_QR = 103;
    private static final int MODE_IDLE = 0;
    private static final int MODE_RUNNING = 1;
    private static final int MODE_DONE = 2;

    private Prefs prefs;
    private Ui.Screen screen;
    private EditText hostInput;
    private EditText pinInput;
    private TextView discoverButton;
    private LinearLayout deviceList;
    private TextView devicesHint;
    private LinearLayout previewList;
    private TextView sourceSummary;
    private LinearLayout sourceCard;
    private LinearLayout targetCard;
    private LinearLayout transferCard;
    private TextView transferTitle;
    private TextView transferMeta;
    private TextView transferStatus;
    private ProgressBar overallBar;
    private LinearLayout transferList;
    private TextView statusLine;
    private TextView primaryButton;
    private TextView secondaryButton;

    private final List<UploadTask.Item> items = new ArrayList<>();
    private final List<String> folders = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private UploadTask task;
    private int mode = MODE_IDLE;
    private String token = "";
    private long lastSent;
    private long lastTick;
    private double speedEma;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 250);
        }
    };

    private static final class Row {
        UploadTask.Item item;
        TextView percent;
        TextView state;
        ProgressBar bar;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        build();
        readExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readExtras(intent);
    }

    private void readExtras(Intent intent) {
        if (intent == null) return;
        String host = intent.getStringExtra(EXTRA_HOST);
        String port = intent.getStringExtra(EXTRA_PORT);
        String pin = intent.getStringExtra(EXTRA_PIN);
        if (host != null && !host.isEmpty()) {
            hostInput.setText(port == null || port.isEmpty() ? host : host + ":" + port);
        }
        if (pin != null && !pin.isEmpty()) pinInput.setText(pin);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        screen = Ui.screen(this, "Отправить", "Выберите получателя и файлы", "← Назад");
        LinearLayout content = screen.content;

        content.addView(targetCard());
        content.addView(sourceCard());
        content.addView(transferCard());

        statusLine = Ui.text(this, "Файлы ещё не выбраны", 13, Ui.MUTED, false);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.setPadding(0, 0, 0, Ui.dp(this, 10));
        screen.bottom.addView(statusLine);

        primaryButton = Ui.button(this, "Отправить", true);
        primaryButton.setOnClickListener(v -> onPrimary());
        screen.bottom.addView(primaryButton);

        secondaryButton = Ui.button(this, "Отправить ещё", false);
        secondaryButton.setVisibility(View.GONE);
        secondaryButton.setOnClickListener(v -> resetForNewTransfer());
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondaryParams.topMargin = Ui.dp(this, 10);
        screen.bottom.addView(secondaryButton, secondaryParams);

        screen.bottom.setVisibility(View.VISIBLE);
        updateSummary();
    }

    private View targetCard() {
        targetCard = Ui.card(this);
        LinearLayout card = targetCard;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        card.addView(Ui.text(this, "Получатель", 18, Ui.TEXT, true));

        devicesHint = Ui.text(this,
                "Найдите устройство в сети или введите адрес вручную.",
                13, Ui.MUTED, false);
        devicesHint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        card.addView(devicesHint);

        discoverButton = Ui.button(this, "Найти рядом", false);
        discoverButton.setOnClickListener(v -> discover());
        card.addView(discoverButton);

        deviceList = Ui.column(this);
        card.addView(deviceList);

        Ui.gap(this, card, 14);

        hostInput = new EditText(this);
        hostInput.setHint("192.168.1.10");
        hostInput.setText(prefs.lastHost());
        hostInput.setSingleLine(true);
        hostInput.setTextColor(Ui.TEXT);
        hostInput.setHintTextColor(Ui.FAINT);
        hostInput.setTextSize(16);
        hostInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setBackground(Ui.roundRect(0xFF101A2E, 14, Ui.BORDER, this));
        hostInput.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        hostInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // A token from one receiver must never be sent to another one.
                token = "";
            }
        });
        card.addView(hostInput);

        Ui.gap(this, card, 10);

        pinInput = new EditText(this);
        pinInput.setHint("Код из 6 цифр");
        pinInput.setText(prefs.lastPin());
        pinInput.setSingleLine(true);
        pinInput.setTextColor(Ui.TEXT);
        pinInput.setHintTextColor(Ui.FAINT);
        pinInput.setTextSize(16);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setBackground(Ui.roundRect(0xFF101A2E, 14, Ui.BORDER, this));
        pinInput.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        card.addView(pinInput);

        Ui.gap(this, card, 10);

        TextView scan = Ui.button(this, "Сканировать QR-код", false);
        scan.setOnClickListener(v -> scanQr());
        card.addView(scan);

        return card;
    }

    private View sourceCard() {
        sourceCard = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        sourceCard.setLayoutParams(params);

        sourceCard.addView(Ui.text(this, "Что отправить", 18, Ui.TEXT, true));

        sourceSummary = Ui.text(this, "Ничего не выбрано", 13, Ui.MUTED, false);
        sourceSummary.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        sourceCard.addView(sourceSummary);

        LinearLayout buttons = Ui.row(this);
        TextView files = Ui.button(this, "Файлы", false);
        TextView folder = Ui.button(this, "Папка", false);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        first.rightMargin = Ui.dp(this, 8);
        buttons.addView(files, first);
        buttons.addView(folder, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        files.setOnClickListener(v -> pickFiles());
        folder.setOnClickListener(v -> pickFolder());
        sourceCard.addView(buttons);

        previewList = Ui.column(this);
        sourceCard.addView(previewList);

        return sourceCard;
    }

    private View transferCard() {
        transferCard = Ui.card(this);
        transferCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        transferCard.setLayoutParams(params);

        LinearLayout head = Ui.row(this);
        transferTitle = Ui.text(this, "Передача", 18, Ui.TEXT, true);
        head.addView(transferTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.chip(this, "по Wi-Fi"));
        transferCard.addView(head);

        transferMeta = Ui.text(this, "Подготовка…", 13, Ui.MUTED, false);
        transferMeta.setPadding(0, Ui.dp(this, 6), 0, 0);
        transferCard.addView(transferMeta);

        overallBar = Ui.progressBar(this, Ui.ACCENT);
        transferCard.addView(overallBar);

        transferStatus = Ui.text(this, "", 13, Ui.MUTED, false);
        transferStatus.setPadding(0, Ui.dp(this, 8), 0, 0);
        transferCard.addView(transferStatus);

        transferList = Ui.column(this);
        transferList.setPadding(0, Ui.dp(this, 10), 0, 0);
        transferCard.addView(transferList);

        return transferCard;
    }

    // --------------------------------------------------------------- picking

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_FILES);
        } catch (Exception error) {
            toast("На этом устройстве нет файлового менеджера");
        }
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_FOLDER);
        } catch (Exception error) {
            toast("Выбор папки недоступен на этом устройстве");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_FILES) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri uri : uris) keepPermission(uri);
            if (!uris.isEmpty()) {
                final List<Uri> selected = uris;
                statusLine.setText("Читаем выбранные файлы…");
                new Thread(() -> {
                    Picker.Result result = Picker.fromDocuments(this, selected);
                    handler.post(() -> mergeResult(result));
                }, "firedrop-pick").start();
            }
            return;
        }

        if (requestCode == REQUEST_FOLDER) {
            Uri tree = data.getData();
            if (tree == null) return;
            keepPermission(tree);
            statusLine.setText("Читаем папку…");
            new Thread(() -> {
                Picker.Result result = Picker.fromTree(this, tree);
                handler.post(() -> mergeResult(result));
            }, "firedrop-tree").start();
            return;
        }

        if (requestCode == REQUEST_QR) {
            if (resultCode != RESULT_OK || data == null) return;
            String payload = data.getStringExtra(ScannerActivity.EXTRA_PAYLOAD);
            if (payload == null || payload.trim().isEmpty()) {
                toast("QR-код не распознан. Введите адрес и код вручную.");
                return;
            }
            applyQr(payload.trim());
        }
    }

    private void keepPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void mergeResult(Picker.Result result) {
        Set<String> seen = new LinkedHashSet<>();
        for (UploadTask.Item item : items) {
            seen.add(item.uri + "|" + item.relativePath);
        }

        int added = 0;
        for (UploadTask.Item item : result.items) {
            if (seen.add(item.uri + "|" + item.relativePath)) {
                items.add(item);
                added++;
            }
        }
        for (String folder : result.folders) {
            if (!folders.contains(folder)) folders.add(folder);
        }

        String message = added == 0 ? "Новых файлов нет"
                : "Добавлено файлов: " + added;
        if (result.skipped > 0) message += " · пропущено: " + result.skipped;
        //noinspection SetTextI18n
        toast(message);
        updateSummary();
    }

    private void updateSummary() {
        long total = 0;
        for (UploadTask.Item item : items) total += Math.max(0, item.size);

        if (items.isEmpty() && folders.isEmpty()) {
            sourceSummary.setText("Ничего не выбрано");
            statusLine.setText("Выберите файлы или папку");
        } else if (items.isEmpty()) {
            sourceSummary.setText("Пустых папок: " + folders.size());
            statusLine.setText("Готово к отправке · только папки");
        } else {
            sourceSummary.setText("Файлов: " + items.size() + " · " + Ui.formatBytes(total));
            statusLine.setText("Готово к отправке · " + Ui.formatBytes(total));
        }

        rebuildPreview();
        if (mode == MODE_IDLE) {
            int count = items.size();
            primaryButton.setText(count == 0
                    ? "Отправить пустые папки"
                    : "Отправить · " + count);
            primaryButton.setAlpha(items.isEmpty() && folders.isEmpty() ? 0.5f : 1f);
        }
    }

    private void rebuildPreview() {
        previewList.removeAllViews();
        if (items.isEmpty()) return;
        int limit = Math.min(items.size(), 8);
        for (int i = 0; i < limit; i++) {
            previewList.addView(previewRow(items.get(i)));
        }
        if (items.size() > limit) {
            TextView more = Ui.text(this, "и ещё " + (items.size() - limit) + " файлов", 13,
                    Ui.FAINT, false);
            more.setPadding(0, Ui.dp(this, 8), 0, 0);
            previewList.addView(more);
        }
    }

    private View previewRow(UploadTask.Item item) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, 0);

        LinearLayout body = Ui.column(this);
        TextView name = Ui.text(this, item.relativePath.isEmpty() ? item.name
                : item.relativePath + "/" + item.name, 14, Ui.TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(name);
        body.addView(Ui.text(this, Ui.formatBytes(item.size), 12, Ui.FAINT, false));

        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // ------------------------------------------------------------ discovery

    private void discover() {
        discoverButton.setEnabled(false);
        discoverButton.setText("Ищем устройства…");
        deviceList.removeAllViews();

        new Thread(() -> {
            List<Net.Device> found = Net.discover(2200);
            handler.post(() -> {
                if (isFinishing()) return;
                discoverButton.setEnabled(true);
                discoverButton.setText("Найти рядом");
                showDevices(found);
            });
        }, "firedrop-discover").start();
    }

    private void showDevices(List<Net.Device> found) {
        deviceList.removeAllViews();
        if (found.isEmpty()) {
            devicesHint.setText("Устройства не найдены. Проверьте, что оба подключены к одной "
                    + "Wi-Fi сети (адрес компьютера — вида 192.168.x.x), выключите VPN и "
                    + "мобильный интернет, затем введите адрес вручную.");
            return;
        }
        devicesHint.setText("Нажмите на устройство, чтобы подставить адрес.");
        for (Net.Device device : found) {
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                hostInput.setText(device.host + ":" + device.port);
                if ("phone".equals(device.kind)) {
                    toast("Введите код, который показан на экране телефона");
                } else {
                    toast("Введите код, который показан на экране компьютера");
                }
            });

            LinearLayout body = Ui.column(this);
            body.addView(Ui.text(this, device.title(), 15, Ui.TEXT, true));
            body.addView(Ui.text(this, device.subtitle(), 12, Ui.MUTED, false));
            row.addView(body, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(Ui.chip(this, "phone".equals(device.kind) ? "телефон" : "ПК"));
            deviceList.addView(row);
        }
    }

    // ------------------------------------------------------------------ QR

    private void scanQr() {
        hideKeyboard();
        try {
            startActivityForResult(new Intent(this, ScannerActivity.class), REQUEST_QR);
        } catch (Exception error) {
            toast("Сканер недоступен. Введите адрес и код вручную.");
        }
    }

    private void applyQr(String text) {
        try {
            Uri uri = Uri.parse(text);
            String scheme = uri.getScheme();
            if ("firedrop".equalsIgnoreCase(scheme)) {
                String host = uri.getQueryParameter("host");
                String port = uri.getQueryParameter("port");
                String pin = uri.getQueryParameter("pin");
                if (host != null) {
                    token = "";
                    hostInput.setText(port == null ? host : host + ":" + port);
                    if (pin != null) pinInput.setText(pin);
                    toast("Получатель подставлен. Выберите файлы.");
                    return;
                }
            }

            if (uri.getHost() != null && (uri.getScheme() == null
                    || uri.getScheme().startsWith("http"))) {
                int port = uri.getPort() > 0 ? uri.getPort() : 47820;
                hostInput.setText(uri.getHost() + ":" + port);
                String tokenValue = uri.getQueryParameter("t");
                if (tokenValue != null && !tokenValue.isEmpty()) {
                    token = tokenValue;
                    pinInput.setText("");
                    toast("Ссылка с компьютера распознана. Код не нужен.");
                }
                return;
            }
        } catch (Exception ignored) {
        }
        toast("Это не QR-код FireDrop");
    }

    // -------------------------------------------------------------- sending

    private void onPrimary() {
        if (mode == MODE_RUNNING) {
            confirmCancel();
            return;
        }
        if (mode == MODE_DONE) {
            finish();
            return;
        }
        startTransfer();
    }

    private void startTransfer() {
        if (items.isEmpty() && folders.isEmpty()) {
            toast("Сначала выберите файлы или папку");
            return;
        }

        String hostValue = hostInput.getText().toString().trim();
        String pinValue = pinInput.getText().toString().trim()
                .replace(" ", "").replace("-", "");

        if (hostValue.isEmpty()) {
            toast("Укажите адрес компьютера или телефона");
            return;
        }

        // Accept "192.168.1.10", "192.168.1.10:47820", "http://host:port/path".
        String hostName = hostValue.trim();
        if (hostName.toLowerCase(Locale.US).startsWith("http://")) {
            hostName = hostName.substring(7);
        } else if (hostName.toLowerCase(Locale.US).startsWith("https://")) {
            hostName = hostName.substring(8);
        }
        int slash = hostName.indexOf('/');
        if (slash >= 0) hostName = hostName.substring(0, slash);

        int portValue = 47820;
        int colon = hostName.lastIndexOf(':');
        if (colon > 0 && colon < hostName.length() - 1) {
            String portPart = hostName.substring(colon + 1);
            if (portPart.matches("\\d{2,5}")) {
                try {
                    int parsed = Integer.parseInt(portPart);
                    if (parsed >= 1024 && parsed <= 65535) portValue = parsed;
                } catch (NumberFormatException ignored) {
                }
                hostName = hostName.substring(0, colon);
            }
        }

        if (token.isEmpty() && !pinValue.matches("\\d{6}")) {
            toast("Введите шестизначный код с экрана получателя");
            return;
        }

        prefs.setLastTarget(hostName, portValue);
        prefs.setLastPin(pinValue);

        hideKeyboard();
        task = new UploadTask(getContentResolver(), hostName, portValue, pinValue, items, folders);
        task.token = token;
        task.start();

        mode = MODE_RUNNING;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        targetCard.setVisibility(View.GONE);
        sourceCard.setVisibility(View.GONE);
        transferCard.setVisibility(View.VISIBLE);
        transferList.removeAllViews();
        rows.clear();
        buildTransferRows();

        primaryButton.setText("Отмена");
        secondaryButton.setVisibility(View.GONE);
        lastSent = 0;
        lastTick = System.currentTimeMillis();
        speedEma = 0;
        statusLine.setText("Подключение…");
        updateSummaryVisibility();
    }

    private void buildTransferRows() {
        int limit = Math.min(items.size(), 120);
        for (int i = 0; i < limit; i++) {
            UploadTask.Item item = items.get(i);
            Row row = new Row();
            row.item = item;

            LinearLayout container = Ui.column(this);
            container.setPadding(0, Ui.dp(this, 10), 0, 0);

            LinearLayout head = Ui.row(this);
            TextView name = Ui.text(this, item.name, 13, Ui.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            head.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.percent = Ui.text(this, "0%", 12, Ui.MUTED, true);
            head.addView(row.percent);
            container.addView(head);

            row.bar = Ui.progressBar(this, Ui.SUCCESS);
            container.addView(row.bar);

            row.state = Ui.text(this, Ui.formatBytes(item.size), 11, Ui.FAINT, false);
            container.addView(row.state);

            transferList.addView(container);
            rows.add(row);
        }

        if (items.size() > limit) {
            TextView more = Ui.text(this, "Показаны первые " + limit + " файлов из "
                    + items.size(), 12, Ui.FAINT, false);
            more.setPadding(0, Ui.dp(this, 10), 0, 0);
            transferList.addView(more);
        }
    }

    private void tick() {
        if (task == null) return;

        long now = System.currentTimeMillis();
        long sent = task.sentBytes();
        long total = task.totalBytes();

        if (mode == MODE_RUNNING && !task.finished) {
            long deltaBytes = sent - lastSent;
            long deltaMs = Math.max(1, now - lastTick);
            double instant = deltaBytes * 1000.0 / deltaMs;
            speedEma = speedEma == 0 ? instant : speedEma * 0.7 + instant * 0.3;
            lastSent = sent;
            lastTick = now;

            int percent = total <= 0 ? 0 : (int) Math.min(1000, sent * 1000 / total);
            overallBar.setProgress(percent);
            transferTitle.setText("Передача · " + (percent / 10) + "%");

            StringBuilder meta = new StringBuilder();
            meta.append(Ui.formatBytes(sent)).append(" из ").append(Ui.formatBytes(total));
            if (speedEma > 1) meta.append(" · ").append(Ui.formatSpeed(speedEma));
            if (speedEma > 1 && total > sent) {
                long seconds = (long) ((total - sent) / speedEma);
                if (seconds < 3600) meta.append(" · осталось ").append(prettyDuration(seconds));
            }
            transferMeta.setText(meta.toString());

            String target = task.targetName.isEmpty() ? "" : " → " + task.targetName;
            transferStatus.setText("Файлов готово: " + task.completedFiles()
                    + " из " + items.size() + target);

            for (Row row : rows) {
                if (row.item == null) continue;
                long size = Math.max(1, row.item.size);
                long done = Math.min(row.item.size, row.item.sent.get());
                int value = (int) Math.min(1000, done * 1000 / size);
                row.bar.setProgress(value);
                row.percent.setText((value / 10) + "%");
                if (row.item.status == UploadTask.ERROR) {
                    row.state.setText(row.item.error == null || row.item.error.isEmpty()
                            ? "ошибка" : row.item.error);
                    row.state.setTextColor(Ui.DANGER);
                    row.bar.setProgress(1000);
                } else if (row.item.status == UploadTask.DONE) {
                    row.state.setText("готово · " + Ui.formatBytes(row.item.size));
                    row.state.setTextColor(Ui.SUCCESS);
                } else if (row.item.status == UploadTask.RUNNING) {
                    row.state.setText(Ui.formatBytes(done) + " из " + Ui.formatBytes(row.item.size));
                    row.state.setTextColor(Ui.FAINT);
                } else {
                    row.state.setText("в очереди");
                    row.state.setTextColor(Ui.FAINT);
                }
            }
        }

        if (task.finished && mode == MODE_RUNNING) {
            finishTransfer();
        }
    }

    private void finishTransfer() {
        mode = MODE_DONE;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        long seconds = Math.max(1, (task.finishedAt - task.startedAt) / 1000);
        long total = task.totalBytes();

        overallBar.setProgress(1000);
        transferTitle.setText(task.failedFiles() == 0 && task.fatalError.isEmpty()
                ? "Готово" : "Завершено с ошибками");
        transferMeta.setText(Ui.formatBytes(total) + " за " + prettyDuration(seconds)
                + " · " + Ui.formatSpeed(total / (double) seconds));

        if (!task.fatalError.isEmpty()) {
            transferStatus.setText(task.fatalError);
            transferStatus.setTextColor(Ui.DANGER);
        } else if (task.failedFiles() > 0) {
            transferStatus.setText("Не удалось отправить файлов: " + task.failedFiles());
            transferStatus.setTextColor(Ui.DANGER);
        } else {
            transferStatus.setText("Все файлы переданы. Получатель сохранил их в папку загрузок.");
            transferStatus.setTextColor(Ui.SUCCESS);
        }

        primaryButton.setText("Готово");
        secondaryButton.setVisibility(View.VISIBLE);
        statusLine.setText(task.failedFiles() == 0 && task.fatalError.isEmpty()
                ? "Передача завершена" : "Есть ошибки передачи");
        tone();
    }

    private void resetForNewTransfer() {
        task = null;
        token = "";
        mode = MODE_IDLE;
        items.clear();
        folders.clear();
        rows.clear();
        transferList.removeAllViews();
        previewList.removeAllViews();
        transferCard.setVisibility(View.GONE);
        targetCard.setVisibility(View.VISIBLE);
        sourceCard.setVisibility(View.VISIBLE);
        overallBar.setProgress(0);
        transferStatus.setTextColor(Ui.MUTED);
        secondaryButton.setVisibility(View.GONE);
        primaryButton.setText("Отправить");
        updateSummary();
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Отменить передачу?")
                .setMessage("Незавершённые файлы не будут сохранены.")
                .setPositiveButton("Отменить передачу", (dialog, which) -> {
                    if (task != null) task.cancel();
                    statusLine.setText("Отмена…");
                })
                .setNegativeButton("Продолжить", null)
                .show();
    }

    private void tone() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.os.VibratorManager manager = (android.os.VibratorManager)
                        getSystemService(VIBRATOR_MANAGER_SERVICE);
                if (manager != null) manager.getDefaultVibrator().vibrate(30);
            } else {
                android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(30);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateSummaryVisibility() {
        statusLine.setText("Идёт передача… Не выключайте экран.");
    }

    private static String prettyDuration(long seconds) {
        if (seconds < 60) return seconds + " с";
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) return String.format(Locale.US, "%d:%02d", minutes, rest);
        return String.format(Locale.US, "%d ч %d мин", minutes / 60, minutes % 60);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager manager =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            View view = getCurrentFocus();
            if (manager != null && view != null) {
                manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
