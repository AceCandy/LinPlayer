using Avalonia.Controls;

namespace LinPlayer.Desktop.Views;

/// <summary>流影两种平台宿主共用的导航和媒体生命周期。</summary>
internal abstract class LiuyingWebHost : NativeControlHost
{
    internal abstract bool BackInPage();
    internal abstract void SetActive(bool active);
    internal abstract void Close();
    internal virtual bool HasNativeFocus => false;
    internal Action? NativeFocusChanged;

    internal static string MediaScript(bool active) => """
                    (() => {
                        const p = HTMLMediaElement.prototype;
                        if (!p.__liuyingPlay) {
                            p.__liuyingPlay = p.play;
                            p.play = function() { return window.__liuyingInactive ? Promise.resolve() : p.__liuyingPlay.call(this); };
                        }
                        const wasInactive = window.__liuyingInactive;
                        window.__liuyingInactive = INACTIVE;
                        if (window.__liuyingInactive) {
                            if (!wasInactive) window.__liuyingResume = Array.from(document.querySelectorAll('video,audio')).filter(x => !x.paused);
                            document.querySelectorAll('video,audio').forEach(x => x.pause());
                        } else {
                            const resume = window.__liuyingResume || [];
                            window.__liuyingResume = [];
                            resume.filter(x => x.isConnected).forEach(x => x.play().catch(() => {}));
                        }
                    })();
                    """.Replace("INACTIVE", active ? "false" : "true");
}
