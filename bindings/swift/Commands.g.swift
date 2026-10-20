// 本文件由 scripts/gen-bindings.py 从 docs/go-migration/COMMANDS.md 生成。
// 不要手改 —— 改了会在下一次生成时被覆盖,而且四方比对会红。
import Foundation

protocol LinPlayerCommands: AnyObject {
    func call(_ command: String, _ args: [String: JSONValue]) async throws -> JSONValue
}

enum LinPlayerAbi { static let version: Int32 = 1 }

enum LinPlayerCommandNames {
    static let all: [String] = [
        "emby.aggregateOverview",
        "emby.aggregateSearch",
        "emby.extCapabilities",
        "emby.extRankings",
        "emby.extRankingItems",
        "emby.extCalendar",
        "emby.extResolve",
        "emby.aggregateVersions",
        "emby.blockedList",
        "emby.counts",
        "emby.currentSession",
        "emby.getFilters",
        "emby.isAdmin",
        "emby.itemDetail",
        "emby.itemMedia",
        "emby.listCollections",
        "emby.collectionItems",
        "emby.listFavorites",
        "emby.listItems",
        "emby.listItemsPage",
        "emby.listLatest",
        "emby.listNextUp",
        "emby.heroCandidates",
        "emby.cachedItems",
        "emby.listRandom",
        "emby.listResume",
        "emby.login",
        "emby.logout",
        "emby.personDetail",
        "emby.personItems",
        "emby.personItemsPage",
        "emby.permissions",
        "emby.refreshItem",
        "emby.relogin",
        "emby.reportProgress",
        "emby.scanLibraries",
        "emby.search",
        "emby.playbackSeason",
        "emby.seasonEpisodes",
        "emby.seriesSeasons",
        "emby.providers",
        "emby.setBlocked",
        "emby.setFavorite",
        "emby.setPlayed",
        "emby.hideResume",
        "emby.similarItems",
        "emby.views",
        "emby.watchHistoryClear",
        "emby.watchHistoryDelete",
        "emby.watchHistoryList",
        "emby.watchHistoryRestoreCandidate",
        "emby.watchHistoryScanRestore",
        "account.batchAddServers",
        "account.batchParse",
        "account.clearAccountIcon",
        "account.getCrossServerResume",
        "account.icon",
        "account.listAccounts",
        "account.parseDeepLink",
        "account.probeAccounts",
        "account.probeLine",
        "account.probeLines",
        "account.removeAccount",
        "account.reorderAccounts",
        "account.setAccountIconFile",
        "account.setActiveLine",
        "account.setAggregatePrimaryServer",
        "account.setActiveServer",
        "account.setCrossServerResume",
        "account.setLines",
        "account.startupDeepLink",
        "account.syncLines",
        "account.testConnection",
        "account.updateAccount",
        "player.addSubtitle",
        "player.chapterInfo",
        "player.getMpvConf",
        "player.getPlaybackPrefs",
        "player.getScreenshotDir",
        "player.getSkipRange",
        "player.mpvCommand",
        "player.mpvGet",
        "player.mpvSet",
        "player.opts",
        "player.prepareNext",
        "player.warmPrepared",
        "player.queuePrepared",
        "player.advanceQueued",
        "player.cancelPrepared",
        "player.play",
        "player.playExternal",
        "player.playLocal",
        "player.playUrl",
        "player.screenshot",
        "player.seek",
        "player.setAspectRatio",
        "player.setAudioDelay",
        "player.setHwdec",
        "player.setMpvConf",
        "player.setMute",
        "player.setPause",
        "player.danmakuBegin",
        "player.danmakuOffset",
        "player.danmakuSet",
        "player.setDanmakuEnabled",
        "player.setDanmakuStyle",
        "player.getDanmakuStyle",
        "player.danmakuLayout",
        "player.danmakuHeatmap",
        "player.setPlaybackPrefs",
        "player.setScreenshotDir",
        "player.setSecondarySub",
        "player.setSecondarySubOpts",
        "player.setShaderLevel",
        "player.setSkipRange",
        "player.setSpeed",
        "player.setSubDelay",
        "player.setSubStyle",
        "player.getSubStyle",
        "player.interpLevels",
        "player.setInterpLevel",
        "player.interpInstall",
        "player.selectTrackPreferences",
        "player.setTrack",
        "player.setTrackRegexes",
        "player.setVolume",
        "player.shaderLevels",
        "player.status",
        "player.stopPlayback",
        "player.takePending",
        "player.thumbnail",
        "player.tracks",
        "player.validateTrackRegex",
        "player.windowClose",
        "player.windowOpen",
        "source.currentSource",
        "source.listDir",
        "source.login",
        "source.formSchema",
        "source.play",
        "source.search",
        "source.watchdog",
        "source.caps",
        "source.home",
        "source.category",
        "source.searchItems",
        "source.person",
        "source.detail",
        "source.playItem",
        "source.continueWatching",
        "source.createSources",
        "source.addSources",
        "source.setAggregate",
        "source.setHost",
        "source.removeGroup",
        "source.serverMenus",
        "source.runCommand",
        "source.aggregateSearch",
        "source.switchCandidates",
        "source.checkAll",
        "source.setFavorite",
        "source.isFavorite",
        "source.favorites",
        "source.history",
        "source.linkSwitch",
        "danmaku.resolve",
        "danmaku.forget",
        "danmaku.testSource",
        "danmaku.autoLoad",
        "danmaku.cacheClear",
        "danmaku.cacheSize",
        "danmaku.episodes",
        "danmaku.filter",
        "danmaku.getDanmakuConfig",
        "danmaku.getOfficialDanmaku",
        "danmaku.importBlocklist",
        "danmaku.load",
        "danmaku.loadLocal",
        "danmaku.match",
        "danmaku.lastMatch",
        "danmaku.minAutoScore",
        "danmaku.search",
        "danmaku.setDanmakuConfig",
        "danmaku.getBlockwords",
        "danmaku.setBlockwords",
        "download.andApplyUpdate",
        "download.clearCompleted",
        "download.enqueue",
        "download.enqueueSeason",
        "download.list",
        "download.pause",
        "download.remove",
        "download.resume",
        "download.setThreads",
        "prefs.applyPrefs",
        "prefs.cfProxyDisable",
        "prefs.cfProxyEnable",
        "prefs.cfProxyStatus",
        "prefs.cfSpeedTest",
        "prefs.configExportQr",
        "prefs.configImportQr",
        "prefs.backupExport",
        "prefs.backupImport",
        "prefs.backupPreview",
        "prefs.getHomeSettings",
        "prefs.getPrefetchSettings",
        "prefs.getPrimaryProgressServer",
        "prefs.getPrefs",
        "prefs.getPreloadSettings",
        "prefs.getProxy",
        "prefs.getUpdateSettings",
        "prefs.getWritebackSettings",
        "prefs.iconLibrary",
        "prefs.setIconSources",
        "prefs.preloadCancel",
        "prefs.preloadItem",
        "prefs.setDetailBlur",
        "prefs.setHomeSettings",
        "prefs.setPrefetchSettings",
        "prefs.setPrimaryProgressServer",
        "prefs.retryPrimaryProgressSync",
        "prefs.setPrefs",
        "prefs.pushSearch",
        "prefs.setPreloadSettings",
        "prefs.setProxy",
        "prefs.setUpdateSettings",
        "prefs.setWritebackSettings",
        "system.cacheSize",
        "system.cancelUpdate",
        "system.capabilities",
        "system.checkUpdate",
        "system.clearCache",
        "system.dataPaths",
        "system.downloadUpdate",
        "system.exportDiagnostics",
        "system.installUpdate",
        "system.sendReport",
        "system.openDataDir",
        "system.shortcutStatus",
        "system.makeShortcut",
        "system.pickDirectory",
        "system.pickFile",
        "system.pickLocalFolder",
        "system.ping",
        "system.updateProgress",
        "system.afdianSponsorUrl",
        "system.afdianVerify",
        "companion.start",
        "companion.status",
        "companion.setEnabled",
        "companion.setNowPlaying",
        "sync.traktAccount",
        "sync.traktDeviceCode",
        "sync.traktPoll",
        "sync.traktLogout",
        "sync.traktCalendar",
        "sync.bangumiAccount",
        "sync.bangumiAuthorizeUrl",
        "sync.bangumiExchange",
        "sync.bangumiLoginToken",
        "sync.bangumiLogout",
        "sync.bangumiSummary",
        "sync.bangumiCalendar",
        "sync.calendarLibrary",
        "sync.calendarDue",
        "plugin.list",
        "plugin.pendingRestart",
        "plugin.inspect",
        "plugin.installFile",
        "plugin.installFromRepo",
        "plugin.uninstall",
        "plugin.cancelUninstall",
        "plugin.setEnabled",
        "plugin.disableAll",
        "plugin.restoreAll",
        "plugin.rollback",
        "plugin.setLocked",
        "plugin.skipVersion",
        "plugin.detail",
        "plugin.setSetting",
        "plugin.clearData",
        "plugin.errorDetail",
        "plugin.market",
        "plugin.repos",
        "plugin.addRepo",
        "plugin.removeRepo",
        "plugin.setGithubPrefix",
        "plugin.setAutoUpdate",
        "plugin.updates",
        "plugin.updateAll",
        "plugin.takeovers",
        "plugin.setTakeover",
        "plugin.devLoad",
        "plugin.devUnload",
        "plugin.devList",
        "plugin.setCapabilities",
        "plugin.appEvent",
        "plugin.anchors",
        "plugin.settingsSections",
        "sync.traktRequest",
        "sync.bangumiRequest",
        "player.getSubtitleText",
        "plugin.setEnv",
        "plugin.shellResult",
        "plugin.themes",
        "plugin.activeTheme",
        "plugin.setActiveTheme",
        "plugin.themeFailed",
        "plugin.wallpapers",
        "plugin.activeWallpaper",
        "plugin.initialWallpaper",
        "plugin.setActiveWallpaper",
        "plugin.playerSurfaces",
        "plugin.sidebar",
        "plugin.homeSections",
        "plugin.homeItems",
        "plugin.pageTakeovers",
        "plugin.searchActions",
        "plugin.backRequest",
        "plugin.playerKey",
        "plugin.setCookies",
        "plugin.ui.mount",
        "plugin.ui.unmount",
        "plugin.ui.event",
        "plugin.ui.viewport",
    ]
}

extension LinPlayerCommands {
    // Emby 浏览与详情 · emby.*
    func embyAggregateOverview(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.aggregateOverview", args)
    }
    func embyAggregateSearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.aggregateSearch", args)
    }
    func embyExtCapabilities(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extCapabilities", args)
    }
    func embyExtRankings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extRankings", args)
    }
    func embyExtRankingItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extRankingItems", args)
    }
    func embyExtCalendar(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extCalendar", args)
    }
    func embyExtResolve(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extResolve", args)
    }
    func embyAggregateVersions(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.aggregateVersions", args)
    }
    func embyBlockedList(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.blockedList", args)
    }
    func embyCounts(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.counts", args)
    }
    func embyCurrentSession(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.currentSession", args)
    }
    func embyGetFilters(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.getFilters", args)
    }
    func embyIsAdmin(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.isAdmin", args)
    }
    func embyItemDetail(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.itemDetail", args)
    }
    func embyItemMedia(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.itemMedia", args)
    }
    func embyListCollections(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listCollections", args)
    }
    func embyCollectionItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.collectionItems", args)
    }
    func embyListFavorites(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listFavorites", args)
    }
    func embyListItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listItems", args)
    }
    func embyListItemsPage(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listItemsPage", args)
    }
    func embyListLatest(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listLatest", args)
    }
    func embyListNextUp(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listNextUp", args)
    }
    func embyHeroCandidates(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.heroCandidates", args)
    }
    func embyCachedItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.cachedItems", args)
    }
    func embyListRandom(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listRandom", args)
    }
    func embyListResume(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listResume", args)
    }
    func embyLogin(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.login", args)
    }
    func embyLogout(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.logout", args)
    }
    func embyPersonDetail(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.personDetail", args)
    }
    func embyPersonItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.personItems", args)
    }
    func embyPersonItemsPage(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.personItemsPage", args)
    }
    func embyPermissions(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.permissions", args)
    }
    func embyRefreshItem(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.refreshItem", args)
    }
    func embyRelogin(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.relogin", args)
    }
    func embyReportProgress(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.reportProgress", args)
    }
    func embyScanLibraries(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.scanLibraries", args)
    }
    func embySearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.search", args)
    }
    func embyPlaybackSeason(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.playbackSeason", args)
    }
    func embySeasonEpisodes(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.seasonEpisodes", args)
    }
    func embySeriesSeasons(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.seriesSeasons", args)
    }
    func embyProviders(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.providers", args)
    }
    func embySetBlocked(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.setBlocked", args)
    }
    func embySetFavorite(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.setFavorite", args)
    }
    func embySetPlayed(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.setPlayed", args)
    }
    func embyHideResume(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.hideResume", args)
    }
    func embySimilarItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.similarItems", args)
    }
    func embyViews(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.views", args)
    }
    func embyWatchHistoryClear(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.watchHistoryClear", args)
    }
    func embyWatchHistoryDelete(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.watchHistoryDelete", args)
    }
    func embyWatchHistoryList(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.watchHistoryList", args)
    }
    func embyWatchHistoryRestoreCandidate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.watchHistoryRestoreCandidate", args)
    }
    func embyWatchHistoryScanRestore(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.watchHistoryScanRestore", args)
    }
    // 账号与线路 · account.*
    func accountBatchAddServers(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.batchAddServers", args)
    }
    func accountBatchParse(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.batchParse", args)
    }
    func accountClearAccountIcon(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.clearAccountIcon", args)
    }
    func accountGetCrossServerResume(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.getCrossServerResume", args)
    }
    func accountIcon(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.icon", args)
    }
    func accountListAccounts(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.listAccounts", args)
    }
    func accountParseDeepLink(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.parseDeepLink", args)
    }
    func accountProbeAccounts(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.probeAccounts", args)
    }
    func accountProbeLine(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.probeLine", args)
    }
    func accountProbeLines(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.probeLines", args)
    }
    func accountRemoveAccount(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.removeAccount", args)
    }
    func accountReorderAccounts(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.reorderAccounts", args)
    }
    func accountSetAccountIconFile(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.setAccountIconFile", args)
    }
    func accountSetActiveLine(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.setActiveLine", args)
    }
    func accountSetAggregatePrimaryServer(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.setAggregatePrimaryServer", args)
    }
    func accountSetActiveServer(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.setActiveServer", args)
    }
    func accountSetCrossServerResume(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.setCrossServerResume", args)
    }
    func accountSetLines(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.setLines", args)
    }
    func accountStartupDeepLink(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.startupDeepLink", args)
    }
    func accountSyncLines(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.syncLines", args)
    }
    func accountTestConnection(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.testConnection", args)
    }
    func accountUpdateAccount(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("account.updateAccount", args)
    }
    // 播放器 · player.*
    func playerAddSubtitle(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.addSubtitle", args)
    }
    func playerChapterInfo(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.chapterInfo", args)
    }
    func playerGetMpvConf(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getMpvConf", args)
    }
    func playerGetPlaybackPrefs(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getPlaybackPrefs", args)
    }
    func playerGetScreenshotDir(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getScreenshotDir", args)
    }
    func playerGetSkipRange(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getSkipRange", args)
    }
    func playerMpvCommand(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.mpvCommand", args)
    }
    func playerMpvGet(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.mpvGet", args)
    }
    func playerMpvSet(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.mpvSet", args)
    }
    func playerOpts(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.opts", args)
    }
    func playerPrepareNext(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.prepareNext", args)
    }
    func playerWarmPrepared(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.warmPrepared", args)
    }
    func playerQueuePrepared(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.queuePrepared", args)
    }
    func playerAdvanceQueued(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.advanceQueued", args)
    }
    func playerCancelPrepared(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.cancelPrepared", args)
    }
    func playerPlay(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.play", args)
    }
    func playerPlayExternal(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.playExternal", args)
    }
    func playerPlayLocal(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.playLocal", args)
    }
    func playerPlayUrl(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.playUrl", args)
    }
    func playerScreenshot(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.screenshot", args)
    }
    func playerSeek(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.seek", args)
    }
    func playerSetAspectRatio(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setAspectRatio", args)
    }
    func playerSetAudioDelay(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setAudioDelay", args)
    }
    func playerSetHwdec(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setHwdec", args)
    }
    func playerSetMpvConf(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setMpvConf", args)
    }
    func playerSetMute(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setMute", args)
    }
    func playerSetPause(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setPause", args)
    }
    func playerDanmakuBegin(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.danmakuBegin", args)
    }
    func playerDanmakuOffset(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.danmakuOffset", args)
    }
    func playerDanmakuSet(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.danmakuSet", args)
    }
    func playerSetDanmakuEnabled(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setDanmakuEnabled", args)
    }
    func playerSetDanmakuStyle(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setDanmakuStyle", args)
    }
    func playerGetDanmakuStyle(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getDanmakuStyle", args)
    }
    func playerDanmakuLayout(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.danmakuLayout", args)
    }
    func playerDanmakuHeatmap(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.danmakuHeatmap", args)
    }
    func playerSetPlaybackPrefs(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setPlaybackPrefs", args)
    }
    func playerSetScreenshotDir(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setScreenshotDir", args)
    }
    func playerSetSecondarySub(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setSecondarySub", args)
    }
    func playerSetSecondarySubOpts(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setSecondarySubOpts", args)
    }
    func playerSetShaderLevel(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setShaderLevel", args)
    }
    func playerSetSkipRange(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setSkipRange", args)
    }
    func playerSetSpeed(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setSpeed", args)
    }
    func playerSetSubDelay(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setSubDelay", args)
    }
    func playerSetSubStyle(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setSubStyle", args)
    }
    func playerGetSubStyle(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getSubStyle", args)
    }
    func playerInterpLevels(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.interpLevels", args)
    }
    func playerSetInterpLevel(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setInterpLevel", args)
    }
    func playerInterpInstall(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.interpInstall", args)
    }
    func playerSelectTrackPreferences(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.selectTrackPreferences", args)
    }
    func playerSetTrack(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setTrack", args)
    }
    func playerSetTrackRegexes(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setTrackRegexes", args)
    }
    func playerSetVolume(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.setVolume", args)
    }
    func playerShaderLevels(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.shaderLevels", args)
    }
    func playerStatus(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.status", args)
    }
    func playerStopPlayback(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.stopPlayback", args)
    }
    func playerTakePending(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.takePending", args)
    }
    func playerThumbnail(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.thumbnail", args)
    }
    func playerTracks(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.tracks", args)
    }
    func playerValidateTrackRegex(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.validateTrackRegex", args)
    }
    func playerWindowClose(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.windowClose", args)
    }
    func playerWindowOpen(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.windowOpen", args)
    }
    // 媒体源与数据源 · source.*
    func sourceCurrentSource(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.currentSource", args)
    }
    func sourceListDir(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.listDir", args)
    }
    func sourceLogin(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.login", args)
    }
    func sourceFormSchema(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.formSchema", args)
    }
    func sourcePlay(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.play", args)
    }
    func sourceSearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.search", args)
    }
    func sourceWatchdog(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.watchdog", args)
    }
    func sourceCaps(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.caps", args)
    }
    func sourceHome(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.home", args)
    }
    func sourceCategory(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.category", args)
    }
    func sourceSearchItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.searchItems", args)
    }
    func sourcePerson(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.person", args)
    }
    func sourceDetail(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.detail", args)
    }
    func sourcePlayItem(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.playItem", args)
    }
    func sourceContinueWatching(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.continueWatching", args)
    }
    func sourceCreateSources(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.createSources", args)
    }
    func sourceAddSources(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.addSources", args)
    }
    func sourceSetAggregate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.setAggregate", args)
    }
    func sourceSetHost(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.setHost", args)
    }
    func sourceRemoveGroup(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.removeGroup", args)
    }
    func sourceServerMenus(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.serverMenus", args)
    }
    func sourceRunCommand(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.runCommand", args)
    }
    func sourceAggregateSearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.aggregateSearch", args)
    }
    func sourceSwitchCandidates(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.switchCandidates", args)
    }
    func sourceCheckAll(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.checkAll", args)
    }
    func sourceSetFavorite(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.setFavorite", args)
    }
    func sourceIsFavorite(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.isFavorite", args)
    }
    func sourceFavorites(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.favorites", args)
    }
    func sourceHistory(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.history", args)
    }
    func sourceLinkSwitch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.linkSwitch", args)
    }
    // 弹幕 · danmaku.*
    func danmakuResolve(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.resolve", args)
    }
    func danmakuForget(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.forget", args)
    }
    func danmakuTestSource(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.testSource", args)
    }
    func danmakuAutoLoad(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.autoLoad", args)
    }
    func danmakuCacheClear(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.cacheClear", args)
    }
    func danmakuCacheSize(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.cacheSize", args)
    }
    func danmakuEpisodes(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.episodes", args)
    }
    func danmakuFilter(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.filter", args)
    }
    func danmakuGetDanmakuConfig(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.getDanmakuConfig", args)
    }
    func danmakuGetOfficialDanmaku(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.getOfficialDanmaku", args)
    }
    func danmakuImportBlocklist(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.importBlocklist", args)
    }
    func danmakuLoad(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.load", args)
    }
    func danmakuLoadLocal(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.loadLocal", args)
    }
    func danmakuMatch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.match", args)
    }
    func danmakuLastMatch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.lastMatch", args)
    }
    func danmakuMinAutoScore(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.minAutoScore", args)
    }
    func danmakuSearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.search", args)
    }
    func danmakuSetDanmakuConfig(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.setDanmakuConfig", args)
    }
    func danmakuGetBlockwords(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.getBlockwords", args)
    }
    func danmakuSetBlockwords(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("danmaku.setBlockwords", args)
    }
    // 下载 · download.*
    func downloadAndApplyUpdate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.andApplyUpdate", args)
    }
    func downloadClearCompleted(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.clearCompleted", args)
    }
    func downloadEnqueue(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.enqueue", args)
    }
    func downloadEnqueueSeason(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.enqueueSeason", args)
    }
    func downloadList(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.list", args)
    }
    func downloadPause(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.pause", args)
    }
    func downloadRemove(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.remove", args)
    }
    func downloadResume(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.resume", args)
    }
    func downloadSetThreads(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("download.setThreads", args)
    }
    // 设置与偏好 · prefs.*
    func prefsApplyPrefs(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.applyPrefs", args)
    }
    func prefsCfProxyDisable(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.cfProxyDisable", args)
    }
    func prefsCfProxyEnable(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.cfProxyEnable", args)
    }
    func prefsCfProxyStatus(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.cfProxyStatus", args)
    }
    func prefsCfSpeedTest(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.cfSpeedTest", args)
    }
    func prefsConfigExportQr(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.configExportQr", args)
    }
    func prefsConfigImportQr(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.configImportQr", args)
    }
    func prefsBackupExport(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.backupExport", args)
    }
    func prefsBackupImport(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.backupImport", args)
    }
    func prefsBackupPreview(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.backupPreview", args)
    }
    func prefsGetHomeSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getHomeSettings", args)
    }
    func prefsGetPrefetchSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getPrefetchSettings", args)
    }
    func prefsGetPrimaryProgressServer(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getPrimaryProgressServer", args)
    }
    func prefsGetPrefs(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getPrefs", args)
    }
    func prefsGetPreloadSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getPreloadSettings", args)
    }
    func prefsGetProxy(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getProxy", args)
    }
    func prefsGetUpdateSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getUpdateSettings", args)
    }
    func prefsGetWritebackSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.getWritebackSettings", args)
    }
    func prefsIconLibrary(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.iconLibrary", args)
    }
    func prefsSetIconSources(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setIconSources", args)
    }
    func prefsPreloadCancel(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.preloadCancel", args)
    }
    func prefsPreloadItem(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.preloadItem", args)
    }
    func prefsSetDetailBlur(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setDetailBlur", args)
    }
    func prefsSetHomeSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setHomeSettings", args)
    }
    func prefsSetPrefetchSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setPrefetchSettings", args)
    }
    func prefsSetPrimaryProgressServer(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setPrimaryProgressServer", args)
    }
    func prefsRetryPrimaryProgressSync(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.retryPrimaryProgressSync", args)
    }
    func prefsSetPrefs(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setPrefs", args)
    }
    func prefsPushSearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.pushSearch", args)
    }
    func prefsSetPreloadSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setPreloadSettings", args)
    }
    func prefsSetProxy(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setProxy", args)
    }
    func prefsSetUpdateSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setUpdateSettings", args)
    }
    func prefsSetWritebackSettings(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("prefs.setWritebackSettings", args)
    }
    // 系统 · system.*
    func systemCacheSize(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.cacheSize", args)
    }
    func systemCancelUpdate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.cancelUpdate", args)
    }
    func systemCapabilities(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.capabilities", args)
    }
    func systemCheckUpdate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.checkUpdate", args)
    }
    func systemClearCache(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.clearCache", args)
    }
    func systemDataPaths(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.dataPaths", args)
    }
    func systemDownloadUpdate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.downloadUpdate", args)
    }
    func systemExportDiagnostics(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.exportDiagnostics", args)
    }
    func systemInstallUpdate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.installUpdate", args)
    }
    func systemSendReport(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.sendReport", args)
    }
    func systemOpenDataDir(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.openDataDir", args)
    }
    func systemShortcutStatus(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.shortcutStatus", args)
    }
    func systemMakeShortcut(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.makeShortcut", args)
    }
    func systemPickDirectory(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.pickDirectory", args)
    }
    func systemPickFile(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.pickFile", args)
    }
    func systemPickLocalFolder(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.pickLocalFolder", args)
    }
    func systemPing(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.ping", args)
    }
    func systemUpdateProgress(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.updateProgress", args)
    }
    func systemAfdianSponsorUrl(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.afdianSponsorUrl", args)
    }
    func systemAfdianVerify(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("system.afdianVerify", args)
    }
    // 手机扫码遥控(电视端) · companion.*
    func companionStart(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("companion.start", args)
    }
    func companionStatus(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("companion.status", args)
    }
    func companionSetEnabled(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("companion.setEnabled", args)
    }
    func companionSetNowPlaying(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("companion.setNowPlaying", args)
    }
    // 同步账号与追剧日历 · sync.*
    func syncTraktAccount(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.traktAccount", args)
    }
    func syncTraktDeviceCode(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.traktDeviceCode", args)
    }
    func syncTraktPoll(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.traktPoll", args)
    }
    func syncTraktLogout(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.traktLogout", args)
    }
    func syncTraktCalendar(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.traktCalendar", args)
    }
    func syncBangumiAccount(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiAccount", args)
    }
    func syncBangumiAuthorizeUrl(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiAuthorizeUrl", args)
    }
    func syncBangumiExchange(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiExchange", args)
    }
    func syncBangumiLoginToken(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiLoginToken", args)
    }
    func syncBangumiLogout(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiLogout", args)
    }
    func syncBangumiSummary(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiSummary", args)
    }
    func syncBangumiCalendar(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiCalendar", args)
    }
    func syncCalendarLibrary(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.calendarLibrary", args)
    }
    func syncCalendarDue(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.calendarDue", args)
    }
    // 插件 · plugin.*
    func pluginList(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.list", args)
    }
    func pluginPendingRestart(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.pendingRestart", args)
    }
    func pluginInspect(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.inspect", args)
    }
    func pluginInstallFile(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.installFile", args)
    }
    func pluginInstallFromRepo(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.installFromRepo", args)
    }
    func pluginUninstall(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.uninstall", args)
    }
    func pluginCancelUninstall(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.cancelUninstall", args)
    }
    func pluginSetEnabled(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setEnabled", args)
    }
    func pluginDisableAll(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.disableAll", args)
    }
    func pluginRestoreAll(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.restoreAll", args)
    }
    func pluginRollback(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.rollback", args)
    }
    func pluginSetLocked(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setLocked", args)
    }
    func pluginSkipVersion(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.skipVersion", args)
    }
    func pluginDetail(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.detail", args)
    }
    func pluginSetSetting(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setSetting", args)
    }
    func pluginClearData(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.clearData", args)
    }
    func pluginErrorDetail(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.errorDetail", args)
    }
    func pluginMarket(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.market", args)
    }
    func pluginRepos(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.repos", args)
    }
    func pluginAddRepo(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.addRepo", args)
    }
    func pluginRemoveRepo(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.removeRepo", args)
    }
    func pluginSetGithubPrefix(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setGithubPrefix", args)
    }
    func pluginSetAutoUpdate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setAutoUpdate", args)
    }
    func pluginUpdates(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.updates", args)
    }
    func pluginUpdateAll(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.updateAll", args)
    }
    func pluginTakeovers(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.takeovers", args)
    }
    func pluginSetTakeover(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setTakeover", args)
    }
    func pluginDevLoad(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.devLoad", args)
    }
    func pluginDevUnload(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.devUnload", args)
    }
    func pluginDevList(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.devList", args)
    }
    func pluginSetCapabilities(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setCapabilities", args)
    }
    func pluginAppEvent(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.appEvent", args)
    }
    func pluginAnchors(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.anchors", args)
    }
    func pluginSettingsSections(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.settingsSections", args)
    }
    func syncTraktRequest(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.traktRequest", args)
    }
    func syncBangumiRequest(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("sync.bangumiRequest", args)
    }
    func playerGetSubtitleText(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.getSubtitleText", args)
    }
    func pluginSetEnv(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setEnv", args)
    }
    func pluginShellResult(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.shellResult", args)
    }
    func pluginThemes(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.themes", args)
    }
    func pluginActiveTheme(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.activeTheme", args)
    }
    func pluginSetActiveTheme(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setActiveTheme", args)
    }
    func pluginThemeFailed(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.themeFailed", args)
    }
    func pluginWallpapers(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.wallpapers", args)
    }
    func pluginActiveWallpaper(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.activeWallpaper", args)
    }
    func pluginInitialWallpaper(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.initialWallpaper", args)
    }
    func pluginSetActiveWallpaper(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setActiveWallpaper", args)
    }
    func pluginPlayerSurfaces(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.playerSurfaces", args)
    }
    func pluginSidebar(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.sidebar", args)
    }
    func pluginHomeSections(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.homeSections", args)
    }
    func pluginHomeItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.homeItems", args)
    }
    func pluginPageTakeovers(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.pageTakeovers", args)
    }
    func pluginSearchActions(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.searchActions", args)
    }
    func pluginBackRequest(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.backRequest", args)
    }
    func pluginPlayerKey(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.playerKey", args)
    }
    func pluginSetCookies(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.setCookies", args)
    }
    func pluginUiMount(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.ui.mount", args)
    }
    func pluginUiUnmount(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.ui.unmount", args)
    }
    func pluginUiEvent(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.ui.event", args)
    }
    func pluginUiViewport(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("plugin.ui.viewport", args)
    }
}
