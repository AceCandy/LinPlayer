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
        "emby.extLiuYingItems",
        "emby.extLiuYingLabels",
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
        "emby.listWatched",
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
        "emby.seriesPlaybackTarget",
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
        "player.liuyingOpen",
        "player.liuyingPrepare",
        "player.liuyingWarm",
        "player.liuyingCancel",
        "player.liuyingPlay",
        "player.liuyingProgress",
        "player.liuyingStop",
        "player.warmPrepared",
        "player.queuePrepared",
        "player.advanceQueued",
        "player.cancelPrepared",
        "player.play",
        "player.playExternal",
        "player.playLocal",
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
        "source.setAggregate",
        "source.aggregateSearch",
        "source.switchCandidates",
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
        "companion.start",
        "companion.status",
        "companion.setEnabled",
        "companion.setNowPlaying",
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
    func embyExtLiuYingItems(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extLiuYingItems", args)
    }
    func embyExtLiuYingLabels(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.extLiuYingLabels", args)
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
    func embyListWatched(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.listWatched", args)
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
    func embySeriesPlaybackTarget(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("emby.seriesPlaybackTarget", args)
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
    func playerLiuyingOpen(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingOpen", args)
    }
    func playerLiuyingPrepare(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingPrepare", args)
    }
    func playerLiuyingWarm(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingWarm", args)
    }
    func playerLiuyingCancel(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingCancel", args)
    }
    func playerLiuyingPlay(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingPlay", args)
    }
    func playerLiuyingProgress(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingProgress", args)
    }
    func playerLiuyingStop(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("player.liuyingStop", args)
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
    // 跨服聚合 · source.*
    func sourceSetAggregate(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.setAggregate", args)
    }
    func sourceAggregateSearch(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.aggregateSearch", args)
    }
    func sourceSwitchCandidates(_ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call("source.switchCandidates", args)
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
}
