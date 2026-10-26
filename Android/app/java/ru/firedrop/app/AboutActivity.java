package ru.firedrop.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The "О приложении" page: what FireDrop is and where the author can be reached. */
public final class AboutActivity extends Activity {

    private static final String TELEGRAAM_URL = "https://t.me/LoioshS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout content = Ui.screen(
                this,
                "О приложении",
                "FireDrop " + App.VERSION,
                "← Назад").content;

        content.addView(heroCard());
        content.addView(abilitiesCard());
        content.addView(authorCard());
    }

    private View heroCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        card.addView(Ui.text(this, "Что такое FireDrop", 18, Ui.TEXT, true));
        TextView body = Ui.text(this,
                "FireDrop — быстрый обмен файлами и целыми папками по локальной Wi-Fi сети: "
                        + "с компьютера на телефон, с телефона на компьютер и с телефона на телефон. "
                        + "Работает без интернета, облака и регистрации — достаточно, чтобы устройства "
                        + "были подключены к одной сети.",
                14, Ui.MUTED, false);
        body.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(body);
        return card;
    }

    private View abilitiesCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(params);

        card.addView(Ui.text(this, "Главное", 18, Ui.TEXT, true));

        String[] facts = {
                "• Папки передаются целиком, структура вложенных каталогов сохраняется.",
                "• Скорость ограничена только Wi-Fi сетью: без облака в пути.",
                "• Подключение по QR-коду или шестизначному коду, данные не уходят в интернет.",
                "• Принятые файлы не перезаписываются: рядом появляется «имя (1)», «имя (2)»…",
        };
        for (String fact : facts) {
            TextView line = Ui.text(this, fact, 14, Ui.MUTED, false);
            line.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(line);
        }
        return card;
    }

    private View authorCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(params);

        card.addView(Ui.text(this, "Автор", 18, Ui.TEXT, true));

        TextView body = Ui.text(this,
                "Приложение сделал @LoioshS. Идеи, вопросы и сообщения о проблемах можно "
                        + "присылать прямо в Telegram.",
                14, Ui.MUTED, false);
        body.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        card.addView(body);

        TextView open = Ui.button(this, "Написать автору · @LoioshS", true);
        open.setOnClickListener(v -> openAuthor());
        card.addView(open);
        return card;
    }

    private void openAuthor() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAAM_URL)));
        } catch (Exception error) {
            Toast.makeText(this,
                    "Нет браузера или Telegram. Ссылка: t.me/LoioshS",
                    Toast.LENGTH_LONG).show();
        }
    }
}
