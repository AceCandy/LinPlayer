using System;
using System.IO;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// WebView2 运行时探测与共用环境(流萤页用)。
/// </summary>
internal static class WebViewHost
{
    private static Task<CoreWebView2Environment>? _env;

    public static bool Available
    {
        get
        {
            if (!OperatingSystem.IsWindows()) return false;
            try { return CoreWebView2Environment.GetAvailableBrowserVersionString() is { Length: > 0 }; }
            catch (WebView2RuntimeNotFoundException) { return false; } // 系统没装 WebView2 运行时
        }
    }

    /// <summary>所有内嵌 WebView 共用一份环境与磁盘目录。</summary>
    internal static Task<CoreWebView2Environment> Env() => _env ??= CoreWebView2Environment.CreateAsync(
        null, Path.Combine(Program.DataDir, "webview"));
}
