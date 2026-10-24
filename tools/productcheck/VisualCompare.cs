using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Media.Imaging;
using Avalonia.Platform;

// 同形态、同工具链的严格像素门禁；只读取基准，绝不自动更新。
static class VisualCompare
{
    public static void Run(string baseline, string current)
    {
        if (Path.GetFullPath(baseline) == Path.GetFullPath(current))
            throw new ArgumentException("基准与当前目录不能相同");
        string[] Files(string folder) => Directory.GetFiles(folder, "*.png", SearchOption.AllDirectories)
            .Select(file => Path.GetRelativePath(folder, file)).Order(StringComparer.Ordinal).ToArray();
        var expected = Files(baseline);
        var actual = Files(current);
        if (expected.Length == 0 || !expected.SequenceEqual(actual))
            throw new Exception("截图集合不一致或基准为空，拒绝跳过缺失页面");
        var failed = 0;
        foreach (var name in expected) {
            using var before = new Bitmap(Path.Combine(baseline, name));
            using var after = new Bitmap(Path.Combine(current, name));
            if (before.PixelSize != after.PixelSize) {
                Console.WriteLine($"尺寸变化：{name} {before.PixelSize} -> {after.PixelSize}");
                failed++;
                continue;
            }
            var beforePixels = Pixels(before);
            var afterPixels = Pixels(after);
            var changed = Changed(beforePixels, afterPixels);
            if (changed == 0) continue;
            failed++;
            Console.WriteLine($"像素变化：{name} {changed} / {before.PixelSize.Width * before.PixelSize.Height}");
            var width = before.PixelSize.Width;
            var minX = width; var minY = before.PixelSize.Height;
            var maxX = 0; var maxY = 0;
            for (var i = 0; i < beforePixels.Length; i += 4)
                if (!beforePixels.AsSpan(i, 4).SequenceEqual(afterPixels.AsSpan(i, 4))) {
                    var x = i / 4 % width; var y = i / 4 / width;
                    minX = Math.Min(minX, x); maxX = Math.Max(maxX, x);
                    minY = Math.Min(minY, y); maxY = Math.Max(maxY, y);
                }
            Console.WriteLine($"变化范围：({minX},{minY})-({maxX},{maxY})");
        }
        if (failed > 0) throw new Exception($"{failed} 张截图变化；请人工复核，基准未改动");
        Console.WriteLine($"视觉回归：{expected.Length} 张截图逐像素一致");
    }

    private static byte[] Pixels(Bitmap bitmap)
    {
        var format = bitmap.Format;
        if (format != PixelFormat.Bgra8888 && format != PixelFormat.Rgba8888)
            throw new Exception($"不支持的截图像素格式：{format}");
        var stride = checked(bitmap.PixelSize.Width * 4);
        var bytes = new byte[checked(stride * bitmap.PixelSize.Height)];
        var pin = GCHandle.Alloc(bytes, GCHandleType.Pinned);
        try { bitmap.CopyPixels(new PixelRect(bitmap.PixelSize), pin.AddrOfPinnedObject(), bytes.Length, stride); }
        finally { pin.Free(); }
        if (format == PixelFormat.Bgra8888)
            for (var i = 0; i < bytes.Length; i += 4) (bytes[i], bytes[i + 2]) = (bytes[i + 2], bytes[i]);
        return bytes;
    }

    private static int Changed(byte[] before, byte[] after)
    {
        if (before.Length != after.Length || before.Length % 4 != 0)
            throw new ArgumentException("像素缓冲区大小不一致");
        var count = 0;
        for (var i = 0; i < before.Length; i += 4)
            if (!before.AsSpan(i, 4).SequenceEqual(after.AsSpan(i, 4))) count++;
        return count;
    }

    public static void SelfCheck()
    {
        byte[] image = [12, 34, 56, 255, 78, 90, 12, 255];
        if (Changed(image, image.ToArray()) != 0) throw new Exception("相同像素被误报");
        var modified = image.ToArray();
        modified[4]++;
        if (Changed(image, modified) != 1) throw new Exception("未检出单通道单像素变化");
        modified[7]--;
        if (Changed(image, modified) != 1) throw new Exception("同一像素被重复计数");
        try { Changed(image, []); }
        catch (ArgumentException) { Console.WriteLine("像素比较自检通过：相同、单像素变化、透明度及尺寸拒绝"); return; }
        throw new Exception("错误尺寸未拒绝");
    }
}
