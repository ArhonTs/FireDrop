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

/** The "About" page: what FireDrop is and where the author can be reached. */
public final class AboutActivity extends Activity {

    private static final String TELEGRAAM_URL = "https://t.me/LoioshS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout content = Ui.screen(
                this,
                L10n.s("about.title"),
                String.format(L10n.s("about.footer"), App.VERSION),
                L10n.s("about.back")).content;

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

        card.addView(Ui.text(this, L10n.s("about.hero.title"), 18, Ui.TEXT, true));
        TextView body = Ui.text(this,
                L10n.s("about.hero.body"),
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

        card.addView(Ui.text(this, L10n.s("about.facts.title"), 18, Ui.TEXT, true));

        String[] facts = {
                L10n.s("about.fact1"),
                L10n.s("about.fact2"),
                L10n.s("about.fact3"),
                L10n.s("about.fact4"),
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

        card.addView(Ui.text(this, L10n.s("about.author.title"), 18, Ui.TEXT, true));

        TextView body = Ui.text(this,
                L10n.s("about.author.body"),
                14, Ui.MUTED, false);
        body.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        card.addView(body);

        TextView open = Ui.button(this, L10n.s("about.author.button"), true);
        open.setOnClickListener(v -> openAuthor());
        card.addView(open);
        return card;
    }

    private void openAuthor() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAAM_URL)));
        } catch (Exception error) {
            Toast.makeText(this,
                    L10n.s("about.author.fail"),
                    Toast.LENGTH_LONG).show();
        }
    }
}
