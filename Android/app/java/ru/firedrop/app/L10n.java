package ru.firedrop.app;

import java.util.Locale;

/**
 * Runtime UI strings. English is the default for every user-visible string;
 * Russian is selected when the language preference says so, or automatically
 * when the system language is Russian.
 *
 * Tables live in fragment classes (one per source group) with a fixed search
 * order: L10nA, L10nS, L10nR, L10nQ. Each fragment implements
 * {@code static String get(String key)} and returns null when the key is
 * unknown; the common {@link #lookup} helper falls back to the CORE table of
 * strings needed by the shared design system.
 */
public final class L10n {
    /** Language preference: "auto", "ru" or "en". */
    private static String preference;

    /** Resolved language, recalculated by {@link #configure}. */
    private static boolean ru;

    static {
        configure(null);
    }

    private L10n() {
    }

    /**
     * Applies the language preference from the settings: "ru", "en" or
     * anything else for "follow the system language".
     */
    public static void configure(String value) {
        preference = value == null ? "auto" : value;
        if ("ru".equals(preference)) {
            ru = true;
        } else if ("en".equals(preference)) {
            ru = false;
        } else {
            String language = Locale.getDefault().getLanguage();
            ru = language != null && language.startsWith("ru");
        }
    }

    /** Current preference: "auto", "ru" or "en". */
    public static String preference() {
        return preference;
    }

    /** True when Russian is active. */
    public static boolean isRu() {
        return ru;
    }

    /** Core lookup with safe fallback: never returns null. */
    public static String core(String key) {
        return find(CORE, key);
    }

    /* Core strings used by Ui.java and any other shared code. */

    private static final String[][] CORE = {
            {"units.bytes", "Б,КБ,МБ,ГБ,ТБ", "B,KB,MB,GB,TB"},
    };

    /** Translates key; falls back to the key itself when no table has it. */
    public static String s(String key) {
        String value;
        if ((value = L10nA.get(key)) != null) return value;
        if ((value = L10nS.get(key)) != null) return value;
        if ((value = L10nR.get(key)) != null) return value;
        if ((value = L10nQ.get(key)) != null) return value;
        value = core(key);
        return value == null ? key : value;
    }

    /** Fragment helper: same lookup as s(), but null when key is unknown. */
    static String lookup(String[][] table, String key) {
        return find(table, key);
    }

    private static String find(String[][] table, String key) {
        for (String[] entry : table) {
            if (entry[0].equals(key)) {
                return ru ? entry[1] : entry[2];
            }
        }
        return null;
    }
}
