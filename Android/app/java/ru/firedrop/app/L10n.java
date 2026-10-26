package ru.firedrop.app;

import java.util.Locale;

/**
 * Runtime UI strings. English is the default for every user-visible string;
 * Russian is selected automatically when the system language is Russian.
 *
 * Tables live in fragment classes (one per source group) with a fixed search
 * order: L10nA, L10nS, L10nR, L10nQ. Each fragment implements
 * {@code static String get(String key)} and returns null when the key is
 * unknown; the common {@link #lookup} helper also falls back to the CORE
 * table of strings needed by the shared design system.
 */
public final class L10n {
    private static final boolean RU;

    static {
        String language = Locale.getDefault().getLanguage();
        RU = language != null && language.startsWith("ru");
    }

    /* Core strings used by Ui.java and any other shared code. */

    private static final String[][] CORE = {
            {"units.bytes", "Б,КБ,МБ,ГБ,ТБ", "B,KB,MB,GB,TB"},
    };

    private L10n() {
    }

    /** Core lookup with safe fallback: never returns null. */
    public static String core(String key) {
        return find(CORE, key, RU);
    }

    /** True when Russian system language is active. */
    public static boolean isRu() {
        return RU;
    }

    /** Translates key; falls back to the key itself when no table has it. */
    public static String s(String key) {
        String value;
        if ((value = L10nA.get(key)) != null) return value;
        if ((value = L10nS.get(key)) != null) return value;
        if ((value = L10nR.get(key)) != null) return value;
        if ((value = L10nQ.get(key)) != null) return value;
        String coreValue = lookup(CORE, key);
        return coreValue == null ? key : coreValue;
    }

    /** Fragment helper: same lookup as s(), but null when key is unknown. */
    static String lookup(String[][] table, String key) {
        return find(table, key, RU);
    }

    /** Fragment helper: same, with an explicit language switch. */
    static String lookup(String[][] table, String key, boolean ru) {
        return find(table, key, ru);
    }

    private static String find(String[][] table, String key, boolean ru) {
        for (String[] entry : table) {
            if (entry[0].equals(key)) {
                return ru ? entry[1] : entry[2];
            }
        }
        return null;
    }
}
