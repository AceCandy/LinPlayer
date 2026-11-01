using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Media.Imaging;
using Avalonia.Styling;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

// 仅替换上游资料和图片：页面、导航、Go 转换及主题都走正式实现。
static class VisualAlignment
{
    public static void Run(string library)
    {
        var repo = new DirectoryInfo(Environment.CurrentDirectory);
        while (repo is not null && !File.Exists(Path.Combine(repo.FullName, "tools/visual-fixtures/catalog.json"))) repo = repo.Parent;
        if (repo is null) throw new Exception("找不到统一视觉夹具");
        var fixture = Path.Combine(repo.FullName, "tools/visual-fixtures");
        var catalog = JsonSerializer.Deserialize<JsonElement>(File.ReadAllText(Path.Combine(fixture, "catalog.json")));
        var heroItems = catalog.GetProperty("hero").EnumerateArray().Select(id =>
            catalog.GetProperty("items").EnumerateArray().Single(item => item.GetProperty("id").GetString() == id.GetString())).ToList();
        var output = Path.Combine(repo.FullName, "build/visual-alignment/desktop");
        Directory.CreateDirectory(output);
        var root = Path.Combine(Path.GetTempPath(), "linplayer-visual-" + Guid.NewGuid());
        Directory.CreateDirectory(root);
        var app = Application.Current!;
        app.Resources.MergedDictionaries.Add(new ResourceInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml"),
        });
        app.Styles.Add(new StyleInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/Controls.axaml"),
        });
        using var server = new Upstream(visual: new VisualCatalog(fixture));
        var core = new CoreClient(Path.GetFullPath(library), root, "visual-fixture");
        var program = typeof(HomePage).Assembly.GetType("LinPlayer.Desktop.Program")!;
        program.GetProperty("Core")!.SetValue(null, core);
        program.GetProperty("DataDir")!.SetValue(null, root);
        MainWindow? window = null;
        try {
            Await(core.CallAsync("emby.login", new { server = server.BaseUrl, username = "fixture", password = "fixture", device_id = "fixture" }));
            Nav.Session = Sess.From(Await(core.CallAsync("emby.currentSession", null)));
            var s = Nav.Session!;
            Await(core.CallAsync("emby.itemDetail", new { s.server, s.token, s.user_id, s.device_id, item_id = "visual-1" }));
            app.RequestedThemeVariant = ThemeVariant.Dark;
            window = new MainWindow { Width = 1440, Height = 900 };
            window.Show();
            Wait(() => Nav.Current is HomePage && Text(window, "远岸来信"), "正式首页未就绪");
            foreach (var light in new[] { false, true }) {
                app.RequestedThemeVariant = light ? ThemeVariant.Light : ThemeVariant.Dark;
                var mode = light ? "light" : "dark";
                if (light) Nav.Root(new HomePage(core));
                Shot("home", () => Text(window, "远岸来信") && window.GetVisualDescendants().OfType<Hero>().Any());
                Nav.Push(new LibraryGridPage(core, s.server, "visual-library", "电影"));
                Shot("library", () => Text(window, "远岸来信"));
                window.Width = 640;
                Shot("library-narrow", () => Text(window, "远岸来信"));
                window.Width = 1440;
                Nav.Push(new DetailPage(core, s.server, "visual-1"));
                Shot("detail", () => Text(window, "远岸来信"));
                window.Width = 640;
                Shot("detail-narrow", () => Text(window, "远岸来信"));
                window.Width = 1440;
                Nav.Push(new DetailPage(core, s.server, "visual-series"));
                Shot("series", () => Text(window, "山海之间") && Text(window, "归途"));
                Nav.Push(new SettingsPage(core));
                Shot("settings", () => Text(window, "明暗模式"));
                var player = new PlayerPage(core, "", "远岸来信", 0, isLocal: true);
                object Field(object target, string field) => target.GetType().GetField(field, BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Instance)!.GetValue(target)!;
                ((DispatcherTimer)Field(player, "_poll")).Stop();
                var view = Field(player, "_view");
                view.GetType().GetField("OnReady")!.SetValue(view, null);
                ((Control)view).IsVisible = false;
                typeof(PlayerPage).GetField("_position", BindingFlags.NonPublic | BindingFlags.Instance)!.SetValue(player, 1517.0);
                typeof(PlayerPage).GetField("_duration", BindingFlags.NonPublic | BindingFlags.Instance)!.SetValue(player, 2729.0);
                ((PlayerBar)Field(player, "_bar")).Sync(1517, 2729, 2050);
                ((TextBlock)Field(player, "_time")).Text = "25:17";
                ((TextBlock)Field(player, "_total")).Text = "45:29";
                ((TextBlock)Field(player, "_msg")).Text = "";
                using var still = new Bitmap(Path.Combine(fixture, "visual-1-Backdrop.png"));
                ((Panel)Field(player, "_root")).Children.Insert(0, new Image { Source = still, Stretch = Avalonia.Media.Stretch.Uniform });
                Nav.Push(player);
                Shot("player", () => Text(window, "25:17"));
                Nav.Root(new HomePage(core));

                void Shot(string name, Func<bool> ready) {
                    try { Wait(ready, $"{name} 内容未就绪"); }
                    catch {
                        Console.WriteLine(string.Join(" | ", window.GetVisualDescendants().OfType<TextBlock>().Select(x => x.Text)));
                        throw;
                    }
                    foreach (var hero in window.GetVisualDescendants().OfType<Hero>()) {
                        typeof(Hero).GetField("_hover", BindingFlags.NonPublic | BindingFlags.Instance)!.SetValue(hero, true);
                        foreach (var image in hero.GetVisualDescendants().OfType<Image>()) image.Transitions = null;
                    }
                    // 等待真实图片请求结束与淡入结束，不用固定等待替代内容断言。
                    if (name != "settings")
                        Wait(() => window.GetVisualDescendants().OfType<Image>().Any(x => x.Source is Bitmap), $"{name} 图片未加载");
                    var until = Environment.TickCount64 + 800;
                    while (Environment.TickCount64 < until) { Dispatcher.UIThread.RunJobs(); AvaloniaHeadlessPlatform.ForceRenderTimerTick(); Thread.Sleep(10); }
                    if (name == "home") {
                        // 候选随机选择另有集成回归；视觉对照固定同一影片，仍由正式 Hero 渲染。
                        var hero = window.GetVisualDescendants().OfType<Hero>().Single();
                        hero.Show(s.server, heroItems);
                        Wait(() => Text(hero, "远岸来信") && hero.GetVisualDescendants().OfType<Image>().Any(x => x.Source is Bitmap), "固定首页样本未就绪");
                        until = Environment.TickCount64 + 800;
                        while (Environment.TickCount64 < until) { Dispatcher.UIThread.RunJobs(); AvaloniaHeadlessPlatform.ForceRenderTimerTick(); Thread.Sleep(10); }
                        // 静态门禁冻结慢推与入场，不把真实时间采样点当作布局差异。
                        foreach (var control in hero.GetVisualDescendants().OfType<Control>()) control.Transitions = null;
                        foreach (var field in new[] { "_washBoxA", "_washBoxB" })
                            if (typeof(Hero).GetField(field, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(hero) is Control wash)
                                wash.RenderTransform = new Avalonia.Media.ScaleTransform(1, 1);
                        var front = typeof(Hero).GetField("_front", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(hero);
                        foreach (var field in new[] { "_layerA", "_layerB" }) {
                            var layer = (Control)typeof(Hero).GetField(field, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(hero)!;
                            layer.Opacity = ReferenceEquals(layer, front) ? 1 : 0;
                        }
                    }
                    window.UpdateLayout();
                    if (name == "home") {
                        var libraryButton = window.GetVisualDescendants().OfType<Button>()
                            .Single(b => b.Content is TextBlock { Text: "电影" });
                        var resumeTitle = window.GetVisualDescendants().OfType<TextBlock>().Single(t => t.Text == "继续观看");
                        if (libraryButton.Bounds.Height > 48 ||
                            libraryButton.TranslatePoint(default, window)!.Value.Y >= resumeTitle.TranslatePoint(default, window)!.Value.Y)
                            throw new Exception("媒体库入口未紧凑排列在继续观看之前");
                    }
                    if (name == "library" && (!Text(window, "★ 8.6") || !Text(window, "2025")))
                        throw new Exception("正式列表丢失评分或年份");
                    if (name == "detail" && !Text(window, "★ 8.6 · 2025/03/08 · 2小时0分0秒"))
                        throw new Exception("详情评分、上映日期和时长没有独立展示");
                    if (name == "settings") {
                        var check = window.GetVisualDescendants().OfType<CheckBox>().First(x => x.IsChecked == true && x.IsEnabled);
                        var face = check.GetVisualDescendants().OfType<Border>().Single(x => x.Name == "NormalRectangle");
                        if (((Avalonia.Media.ISolidColorBrush)face.Background!).Color != ((Avalonia.Media.ISolidColorBrush)Tok.Of("Accent")).Color)
                            throw new Exception("设置选中控件仍使用框架默认配色");
                    }
                    if (window.FindControl<Border>("ErrorBar")!.IsVisible) throw new Exception("正式窗口出现错误条，拒绝记录截图");
                    using var bitmap = new RenderTargetBitmap(new PixelSize((int)window.ClientSize.Width, (int)window.ClientSize.Height));
                    bitmap.Render(window);
                    bitmap.Save(Path.Combine(output, $"{mode}-{name}.png"));
                    Console.WriteLine($"视觉截图：desktop/{mode}-{name}");
                }
            }
        }
        finally {
            window?.Close();
            Dispatcher.UIThread.RunJobs();
            Nav.Session = null;
            program.GetProperty("Core")!.SetValue(null, null);
            core.Dispose();
            Directory.Delete(root, true);
        }
    }

    private static bool Text(Control root, string text) => root.GetVisualDescendants().OfType<TextBlock>().Any(x => x.Text?.Contains(text) == true);
    private static JsonElement Await(Task<JsonElement> task) { Wait(() => task.IsCompleted, "核心命令超时"); return task.GetAwaiter().GetResult(); }
    private static void Wait(Func<bool> ready, string error) {
        var until = Environment.TickCount64 + 15000;
        while (!ready() && Environment.TickCount64 < until) { Dispatcher.UIThread.RunJobs(); AvaloniaHeadlessPlatform.ForceRenderTimerTick(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs();
        if (!ready()) throw new Exception(error);
    }
}

sealed class VisualCatalog
{
    private readonly string _folder;
    private readonly JsonElement _catalog;
    private readonly Dictionary<string, JsonElement> _items;
    public VisualCatalog(string folder) {
        _folder = folder;
        _catalog = JsonSerializer.Deserialize<JsonElement>(File.ReadAllText(Path.Combine(folder, "catalog.json")));
        _items = _catalog.GetProperty("items").EnumerateArray().Concat(_catalog.GetProperty("details").EnumerateArray())
            .ToDictionary(x => x.GetProperty("id").GetString()!);
    }

    public (byte[], string, int) Response(Uri uri, Dictionary<string, string> query) {
        var path = uri.AbsolutePath;
        (byte[], string, int) Json(object value) => (JsonSerializer.SerializeToUtf8Bytes(value), "application/json", 200);
        object Page(IEnumerable<object> items) { var list = items.ToArray(); return new { Items = list, TotalRecordCount = list.Length }; }
        object[] Selected(string key) => _catalog.GetProperty(key).EnumerateArray().Select(x => Emby(_items[x.GetString()!])).ToArray();
        if (path.Contains("/Images/")) {
            if (path.Contains("/Logo")) return ([], "image/png", 404);
            var id = path.Split('/').FirstOrDefault(x => _items.ContainsKey(x)) ?? "visual-1";
            var kind = path.Contains("/Backdrop") ? "Backdrop" : "Primary";
            if (id == "visual-4") return ([], "image/png", 404);
            if (_items[id].TryGetProperty("art_id", out var art)) id = art.GetString()!;
            return (File.ReadAllBytes(Path.Combine(_folder, $"{id}-{kind}.png")), "image/png", 200);
        }
        if (path.EndsWith("AuthenticateByName")) return Json(new { AccessToken = "fixture", User = new { Id = "fixture", Name = "fixture" } });
        if (path.EndsWith("/System/Info/Public")) return Json(new { Id = "visual", ServerName = "视觉样本库", Version = "4.8.0.0" });
        if (path.EndsWith("/Views")) return Json(Page(new[] { new { Id = "visual-library", Name = "电影", Type = "CollectionFolder", CollectionType = "movies", ImageTags = new { Primary = "fixture" } } }));
        if (path.EndsWith("/Resume")) return Json(Page(Selected("resume")));
        if (path.EndsWith("/Latest")) return Json(Selected("latest"));
        if (path.EndsWith("/Similar")) return Json(Page(_catalog.GetProperty("items").EnumerateArray().Skip(1).Select(Emby)));
        if (path.EndsWith("/PlaybackInfo")) return Json(new {
            MediaSources = _catalog.GetProperty("media").EnumerateArray().Select(version => new {
                Id = version.GetProperty("id").GetString(), Name = version.GetProperty("name").GetString(),
                Container = version.GetProperty("container").GetString(),
                Size = Optional(version, "size_bytes"), Bitrate = Optional(version, "bitrate"),
                MediaStreams = version.GetProperty("streams").EnumerateArray().Select(stream => new {
                    Type = stream.GetProperty("type_").GetString(), Index = stream.GetProperty("index").GetInt32(),
                    Codec = stream.GetProperty("codec").GetString(), Language = Optional(stream, "language"),
                    Title = Optional(stream, "title"), Width = Optional(stream, "width"), Height = Optional(stream, "height"),
                    Channels = Optional(stream, "channels"), AverageFrameRate = Optional(stream, "frame_rate"),
                }),
            }),
        });
        var itemId = path.Split('/').Last();
        if (_items.TryGetValue(itemId, out var item)) return Json(Emby(item));
        if (path.EndsWith("/Items")) return Json(Page(query.GetValueOrDefault("IncludeItemTypes") switch {
            "BoxSet" => [],
            "Season" => Selected("seasons"),
            "Episode" => int.Parse(query.GetValueOrDefault("StartIndex", "0")) == 0 ? Selected("episodes") : [],
            _ => _catalog.GetProperty("items").EnumerateArray().Select(Emby),
        }));
        var facet = path.Split('/').Last() switch {
            "Genres" => "genres", "Tags" => "tags", "Studios" => "studios", "OfficialRatings" => "official_ratings", _ => null,
        };
        if (facet is not null)
            return Json(Page(_catalog.GetProperty("filters").GetProperty(facet).EnumerateArray().Select(value => new { Name = value.GetString() })));
        if (path.EndsWith("/Sessions")) return Json(Array.Empty<object>());
        return (System.Text.Encoding.UTF8.GetBytes("{}"), "application/json", 404);
    }

    private static JsonElement? Optional(JsonElement item, string key) => item.TryGetProperty(key, out var value) ? value : null;

    private static object Emby(JsonElement item) => new {
        Id = item.GetProperty("id").GetString(), Name = item.GetProperty("name").GetString(),
        Type = item.GetProperty("type_").GetString(), ProductionYear = item.GetProperty("year").GetInt32(),
        CommunityRating = item.GetProperty("rating").GetDouble(), RunTimeTicks = item.GetProperty("runtime_secs").GetInt64() * 10000000,
        Overview = item.GetProperty("overview").GetString(), Genres = item.GetProperty("genres"),
        PremiereDate = Optional(item, "premiere_date"), ChildCount = Optional(item, "child_count"),
        SeriesId = Optional(item, "series_id"), SeriesName = Optional(item, "series_name"),
        SeasonId = Optional(item, "season_id"), ParentIndexNumber = Optional(item, "season_no"),
        IndexNumber = Optional(item, "index_no") ?? Optional(item, "episode_no"),
        People = item.GetProperty("people").EnumerateArray().Select(person => new {
            Id = person.GetProperty("id").GetString(), Name = person.GetProperty("name").GetString(),
            Role = person.GetProperty("role").GetString(), Type = person.GetProperty("type_").GetString(),
        }),
        ImageTags = item.GetProperty("has_primary").GetBoolean() ? new Dictionary<string, string> { ["Primary"] = "fixture" } : new(),
        BackdropImageTags = item.GetProperty("has_backdrop").GetBoolean() ? new[] { "fixture" } : Array.Empty<string>(),
        UserData = new { Played = item.GetProperty("played").GetBoolean(), PlaybackPositionTicks = item.GetProperty("resume_secs").GetInt64() * 10000000, IsFavorite = false, UnplayedItemCount = Optional(item, "unplayed") },
    };
}
