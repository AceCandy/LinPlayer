using Avalonia;
using Avalonia.Markup.Xaml.Styling;

namespace LinPlayer.Desktop.Views;

/// <summary>临时叠加私密配色，保留普通深浅模式与主题偏好。</summary>
internal static class PrivacyTheme
{
    private static ResourceInclude? _tokens;

    public static void Apply(bool active)
    {
        if (Application.Current is not { } app) return;
        _tokens ??= new ResourceInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/PrivateTokens.axaml"),
        };
        var merged = app.Resources.MergedDictionaries;
        if (active && !merged.Contains(_tokens)) merged.Add(_tokens);
        else if (!active) merged.Remove(_tokens);
    }
}
