using System;
using System.Runtime.InteropServices;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 系统「减少动态效果」开关。Windows 那个开关(设置 → 辅助功能 → 视觉效果 → 动画效果)
/// 对应 SPI_GETCLIENTAREAANIMATION,读不到按「不减少」算 —— 猜成「减少」会把所有人的动效都砍掉。
/// </summary>
internal static class Motion
{
    private const uint SpiGetClientAreaAnimation = 0x1042;

    private static bool? _reduced;

    internal static bool Reduced
    {
        get
        {
            if (_reduced is { } v) return v;
            var on = 1;
            try { if (!SystemParametersInfoW(SpiGetClientAreaAnimation, 0, ref on, 0)) on = 1; }
            catch (Exception e) { Log.D("动效", "读不到系统动效开关,按不减少算:" + e.Message); on = 1; }
            _reduced = on == 0;
            return _reduced.Value;
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SystemParametersInfoW(uint action, uint param, ref int pv, uint winIni);
}
