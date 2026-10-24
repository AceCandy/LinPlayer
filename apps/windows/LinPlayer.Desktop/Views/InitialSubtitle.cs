using System.Globalization;
using System.Text.Json;

namespace LinPlayer.Desktop.Views;

/// <summary>详情预选绑定媒体版本；外挂字幕按身份唯一匹配，不沿用容器流序号。</summary>
public sealed record InitialSubtitle(string SourceId, int? Index, bool External = false,
    string Title = "", string Language = "", string Codec = "")
{
    public string? Match(string sourceId, JsonElement tracks)
    {
        if (sourceId != SourceId) return null;
        if (Index is null) return "no";
        if (tracks.ValueKind != JsonValueKind.Array) return null;
        var candidates = tracks.EnumerateArray().Where(t => Mi.Str(t, "kind") == "sub");
        if (!External)
            candidates = candidates.Where(t => t.TryGetProperty("ff_index", out var index) &&
                index.ValueKind == JsonValueKind.Number && index.TryGetInt32(out var value) && value == Index);
        else {
            var language = NormalizeLanguage(Language);
            if (string.IsNullOrWhiteSpace(Title) && language == "") return null;
            candidates = candidates.Where(t => Mi.Bool(t, "external") &&
                (Codec == "" || NormalizeCodec(Mi.Str(t, "codec")) == NormalizeCodec(Codec)) &&
                (string.IsNullOrWhiteSpace(Title) || Mi.Str(t, "title") == Title) &&
                (language == "" || NormalizeLanguage(Mi.Str(t, "lang")) == language));
        }
        var matches = candidates.Take(2).ToArray();
        return matches.Length == 1 ? Mi.Str(matches[0], "id") : null;
    }

    private static string NormalizeCodec(string value) => value.ToLowerInvariant() switch {
        "srt" => "subrip", "vtt" => "webvtt", "ssa" => "ass", var other => other,
    };

    private static string NormalizeLanguage(string value)
    {
        var language = value.Trim().ToLowerInvariant();
        if (language is "" or "und") return "";
        var parts = language.Split('-', 2);
        var code = parts[0];
        code = code switch {
            "zh" or "chi" => "zho", "en" => "eng", "ja" => "jpn", "ko" => "kor",
            "fr" or "fre" => "fra", "de" or "ger" => "deu", "es" => "spa", "ru" => "rus",
            "pt" => "por", "it" => "ita", "th" => "tha", "vi" => "vie", var other => other,
        };
        if (code.Length == 2) {
            try { code = CultureInfo.GetCultureInfo(code).ThreeLetterISOLanguageName; }
            catch (CultureNotFoundException) { /* 未知语言保留服务端标记，不猜测。 */ }
        }
        if (code == "chi") code = "zho";
        return code + (parts.Length == 2 ? "-" + parts[1] : "");
    }
}
