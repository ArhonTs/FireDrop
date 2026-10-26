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
    private static readonly bool Ru =
        CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ru";

    public static bool IsRu => Ru;

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
