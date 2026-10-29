using System.Globalization;
using System.Windows.Markup;

namespace FireDrop;

/// <summary>
/// Runtime UI strings. English is the default for every user-visible string;
/// Russian is selected automatically when the system UI language is Russian.
/// Tables live in per-source fragment classes with a fixed search order:
/// L10nUi (window and code-behind), L10nCore (Core layer user messages).
/// </summary>
public static class L10n
{
    private static bool _ru = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ru";
    private static string _preference = "auto";

    public static bool IsRu => _ru;

    /// <summary>Language preference: "auto", "ru" or "en".</summary>
    public static string Preference => _preference;

    /// <summary>
    /// Applies the saved language preference. Must run before any window is
    /// loaded: XAML strings are resolved when the window is built.
    /// </summary>
    public static void Configure(string? preference)
    {
        _preference = string.IsNullOrWhiteSpace(preference) ? "auto" : preference;
        _ru = _preference switch
        {
            "ru" => true,
            "en" => false,
            _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ru",
        };
    }

    public static string Get(string key)
    {
        string value = L10nUi.Lookup(key);
        if (!string.IsNullOrEmpty(value)) return value;
        value = L10nCore.Lookup(key);
        if (!string.IsNullOrEmpty(value)) return value;
        return key;
    }

    public static string S(string key, params object?[] args) => string.Format(Get(key), args);
}

/// <summary>
/// XAML markup extension: Text="{local:T ui.window.title}".
/// </summary>
public sealed class T : MarkupExtension
{
    private readonly string _key;

    public T(string key)
    {
        _key = key;
    }

    public override object ProvideValue(IServiceProvider serviceProvider) => L10n.Get(_key);
}
