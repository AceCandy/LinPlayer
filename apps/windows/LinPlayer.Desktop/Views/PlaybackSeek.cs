namespace LinPlayer.Desktop.Views;

/// <summary>单片跳转目标和提交屏障；真实进度仍由播放页从内核读取。</summary>
internal sealed class PlaybackSeek(Func<double, Task> send, Func<long>? clock = null)
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Func<long> _clock = clock ?? (() => Environment.TickCount64);
    private long _requestedAt;
    private bool _submitted;
    private Task? _stop;
    public double? Target { get; private set; }
    public long Revision { get; private set; }
    public bool Stopped { get; private set; }

    public Task SeekBy(double delta, double actual, double duration)
    {
        Expire();
        return Seek((Target ?? actual) + delta, duration);
    }

    /// <summary>等待中的请求只提交最后一个；已发命令必须等回执才能让停止越过。</summary>
    public async Task Seek(double position, double duration)
    {
        if (Stopped || !double.IsFinite(position) || !double.IsFinite(duration) || duration <= 0) return;
        var target = Math.Clamp(position, 0, duration);
        var revision = ++Revision;
        Target = target;
        _submitted = false;
        _requestedAt = _clock();
        await _gate.WaitAsync();
        try
        {
            if (revision != Revision) return;
            await send(target);
            if (revision == Revision) _submitted = true;
        }
        catch
        {
            if (revision == Revision) Clear();
            throw;
        }
        finally { _gate.Release(); }
    }

    /// <summary>回执不是落点；只由同一请求代数的非缓冲真实位置解除显示目标。</summary>
    public void Observe(double position, bool buffering, long revision)
    {
        Expire();
        if (revision == Revision && _submitted && !buffering && Target is { } target
            && Math.Abs(position - target) < 1.5) Clear();
    }

    public bool Expire()
    {
        if (Target is null || _clock() - _requestedAt < 15_000) return false;
        Clear();
        return true;
    }

    /// <summary>立即失效排队请求；重复停止共用回执，避免旧页重复 stop 停掉新媒体。</summary>
    public Task Stop(Func<Task> stop)
    {
        if (_stop is not null) return _stop;
        Stopped = true;
        Clear();
        return _stop = StopCore(stop);
    }

    private async Task StopCore(Func<Task> stop)
    {
        await _gate.WaitAsync();
        try { await stop(); }
        finally { _gate.Release(); }
    }

    private void Clear()
    {
        Revision++;
        Target = null;
        _submitted = false;
    }
}
