package tv.own.owntv.di

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import tv.own.owntv.features.more.MoreCountsViewModel
import org.koin.dsl.module
import tv.own.owntv.features.customize.CustomizeItemsViewModel
import tv.own.owntv.features.customize.CustomizeViewModel
import tv.own.owntv.features.downloads.DownloadsViewModel
import tv.own.owntv.features.recordings.RecordingsViewModel
import tv.own.owntv.features.epg.EpgViewModel
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.home.HomeFeedReader
import tv.own.owntv.core.live.GuideReader
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.WatchSession
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.SyncActivityTracker
import tv.own.owntv.features.home.HomeViewModel
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.profiles.ProfileGateSessionViewModel
import tv.own.owntv.features.profiles.ProfilesViewModel
import tv.own.owntv.features.search.SearchViewModel
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.features.settings.LocalSyncViewModel
import tv.own.owntv.features.settings.BackupViewModel
import tv.own.owntv.features.settings.DeleteSubtitlesViewModel
import tv.own.owntv.features.settings.EpgSourcesViewModel
import tv.own.owntv.features.settings.HomeSettingsViewModel
import tv.own.owntv.features.settings.LanguageSettingsViewModel
import tv.own.owntv.features.settings.OpenSubtitlesViewModel
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.setup.DisplaySizeViewModel
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.features.shell.ShellViewModel
import tv.own.owntv.features.subtitles.SubtitleSearchViewModel
import tv.own.owntv.smart.SmartProviderCandidateService
import tv.own.owntv.smart.SmartProviderDiscoveryTrigger

/**
 * Root Koin module. Each feature will contribute its own bindings as the app grows;
 * for now this wires settings persistence and the shell view model.
 *
 * ViewModels are bound with `viewModelOf(::X)` constructor references, never positional `get()`
 * lists: with 15-23 parameters, a positional list only has to *count* right, so inserting or
 * reordering two same-typed parameters would silently swap two dependencies at runtime with no
 * compile error and no Koin error. A constructor reference binds by the declared constructor, so
 * reordering is safe and a missing binding fails immediately, naming the type.
 */
val appModule = module {
    viewModelOf(::ShellViewModel)
    // Home's rails are core's, shared with the phone app; the view model only decorates them.
    // LiveEpgReader is registered because GuideReader now needs one: a guide row whose stored data
    // has run out falls back to the provider's short-EPG, which is this reader's to fetch. One
    // instance, so the grid, the Live rows and the preview pane share its cache instead of each
    // asking the provider the same question.
    singleOf(::LiveEpgReader)
    singleOf(::GuideReader)
    singleOf(::SearchReader)
    singleOf(::HomeFeedReader)
    // Smart Provider Engine, phase 3: the on-demand pass that remembers what the app-local analyzer
    // (phase 1) found, in core's candidate table (phase 2). Spelled out rather than `singleOf(::...)`
    // because three of its four dependencies are the single-method seams it was written with, and Koin
    // cannot build a lambda from the graph. They are resolved once, here, and closed over — so nothing
    // re-enters the container on a tune.
    single {
        val sourceDao = get<SourceDao>()
        val channelDao = get<ChannelDao>()
        val settings = get<SettingsRepository>()
        val customizationStore = get<CustomizationStore>()
        SmartProviderCandidateService(
            smartProviderDao = get(),
            // `SourceDao` declares 27 methods and `ChannelDao` 67; this pass calls one of each.
            allSourceIds = { sourceDao.allSourceIds() },
            channelsForSources = { sourceIds, limit -> channelDao.allForSources(sourceIds, limit) },
            // The hidden keys of the active profile, spelled the way `CustomizeKeys.channel` spells
            // them. No active profile (-1's sentinel) hides nothing — a profile-scoped customization
            // read needs a profile to be scoped to.
            hiddenItemKeys = {
                val profileId = settings.activeProfileId.first()
                if (profileId < 0) {
                    emptySet()
                } else {
                    customizationStore.observe(profileId, MediaType.LIVE).first().hiddenItems.keys
                }
            },
        )
    }
    // Smart Provider Engine, the trigger: when the catalogue has gone quiet, walk the active profile's
    // most recently watched channels and let phase 3 remember what each of them could also be watched
    // on. Spelled out for the same reason as the service above — every dependency but the flow and the
    // tracker is a single-method seam Koin cannot build from the graph. One instance, and its owner is
    // the process: OwnTVApp starts it against appScope, so it outlives every screen and every ViewModel.
    single {
        val sourceDao = get<SourceDao>()
        val channelDao = get<ChannelDao>()
        val profileDao = get<ProfileDao>()
        val settings = get<SettingsRepository>()
        val syncActivity = get<SyncActivityTracker>()
        val watchSession = get<WatchSession>()
        val openStreams = get<OpenStreamRegistry>()
        val candidates = get<SmartProviderCandidateService>()
        SmartProviderDiscoveryTrigger(
            // The map is already keyed by sourceId and already a singleton, so parallel source syncs
            // collapse into one non-empty -> empty edge with no per-source state of our own.
            syncActivity = syncActivity.active,
            // The predicate `CatalogBackfillWorker.shouldYield` uses for "the user is watching": fullscreen
            // playback, which never claims a connection, plus the claims Multiview and recordings take.
            playbackActive = {
                watchSession.watching.value.isNotEmpty() || openStreams.claims.value.isNotEmpty()
            },
            // -1 is "no profile chosen yet"; resolveExistingProfileId answers null for it, and null for a
            // device with no profiles at all — which is exactly when there is no scope to run a pass in.
            activeProfileId = { profileDao.resolveExistingProfileId(settings.activeProfileId.first()) },
            sourceIdsForProfile = { profileId -> sourceDao.sourceIdsForProfile(profileId) },
            // The source filter is inside the query, ahead of the LIMIT, and the order is watchedAt DESC —
            // so the pass walks what this profile actually watched, most recent first.
            recentlyWatchedAnchors = { profileId, sourceIds, limit ->
                channelDao.recentlyWatchedWithTimestampFiltered(profileId, sourceIds, limit)
                    .first()
                    .map { it.channel }
            },
            discover = { anchor -> candidates.discover(anchor) },
        )
    }
    viewModelOf(::HomeViewModel)
    viewModelOf(::SetupViewModel)
    viewModelOf(::DisplaySizeViewModel)
    // Takes a Context first; Koin resolves it from androidContext().
    //
    // Spelled out rather than `viewModelOf(::LiveViewModel)`: that reflective helper is generated for
    // up to 22 constructor parameters and this class now has 24. The failure is a "none of the
    // following candidates is applicable" at the call above, which says nothing about arity — hence
    // this note. Every argument is resolved by type, so the order here does not matter.
    viewModel {
        LiveViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
    viewModelOf(::MovieViewModel)
    viewModelOf(::SeriesViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::ProfilesViewModel)
    // Activity-scoped session state for the profile gate (configuration-only retention, no saved
    // state — see ProfileGateSessionViewModel).
    viewModelOf(::ProfileGateSessionViewModel)
    // 24 constructor parameters — past the highest arity Koin's `*Of` DSL generates, so this one
    // binding stays explicit. Named arguments give it the same guarantee viewModelOf gives the rest:
    // reordering the constructor is safe, and adding a parameter is a compile error here, not a
    // silently mis-wired dependency at runtime.
    viewModel {
        SettingsViewModel(
            profileDao = get(),
            sourceDao = get(),
            sourceRepository = get(),
            settings = get(),
            epgDao = get(),
            importFinalizer = get(),
            channelDao = get(),
            categoryDao = get(),
            customizationStore = get(),
            navVisibility = get(),
            userDataWriter = get(),
            epgRepository = get(),
            epgSourceStore = get(),
            launcherIntegrationRepository = get(),
            catalogSyncScheduler = get(),
            okHttpClient = get(),
            metadataProvider = get(),
            metadataRepository = get(),
            metadataBudget = get(),
            stalkerAuth = get(),
            stalkerClient = get(),
            xtreamClient = get(),
            sourceTester = get(),
            companion = get(),
            vodEngineStore = get(),
            forceMpvStore = get(),
            archiveDecodeStore = get(),
            playbackPrefs = get(),
            connectionLimits = get(),
            player = get(),
            livePreview = get(),
            enginePool = get(),
            importer = get(),
        )
    }
    viewModelOf(::LocalSyncViewModel)
    viewModelOf(::HomeSettingsViewModel)
    viewModelOf(::LanguageSettingsViewModel)
    viewModelOf(::OpenSubtitlesViewModel)
    viewModelOf(::DeleteSubtitlesViewModel)
    viewModelOf(::SubtitleSearchViewModel)
    viewModelOf(::DownloadsViewModel)
    viewModelOf(::RecordingsViewModel)
    viewModelOf(::EpgViewModel)
    viewModelOf(::CustomizeViewModel)
    viewModelOf(::CustomizeItemsViewModel)
    viewModelOf(::BackupViewModel)
    viewModelOf(::EpgSourcesViewModel)
    // The counts the More rows and the Favourites / History tabs carry.
    viewModelOf(::MoreCountsViewModel)
}
