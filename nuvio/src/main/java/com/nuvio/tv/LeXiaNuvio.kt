// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.player.EpisodeShufflePlayback
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.EpisodeShuffleStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrackPreferenceDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import com.nuvio.tv.data.remote.api.PlaybackIssueReportApi
import com.nuvio.tv.data.repository.LeXiaProgress
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.PlaybackIssueReportRepository
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklRelatedService
import com.nuvio.tv.data.simkl.defaultSimklApiConfiguration
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import android.app.Activity
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.PluginRuntime
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParser
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.DirectDebridStreamPreparer
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import com.nuvio.tv.core.player.SubtitleFileCache
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.core.streams.YouTubeStreamResolver
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.mediaserver.ServerPlayback
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.data.repository.LeXiaMetaRepository
import com.nuvio.tv.data.repository.LeXiaSubtitleRepository
import com.nuvio.tv.data.repository.LeXiaWatchProgressRepository
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.flow.first
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * What Hilt does in NuvioTV, by hand: Nuvio's singletons are made once for the app, and Nuvio's
 * view models are built from them.
 */
object LeXiaNuvio {
    @Volatile private var dataStore: PluginDataStore? = null
    @Volatile private var manager: PluginManager? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var permissiveClient: OkHttpClient? = null
    @Volatile private var addons: AddonRepository? = null
    @Volatile private var api: AddonApi? = null
    @Volatile private var preferences: AddonPreferences? = null
    @Volatile private var meta: MetaRepository? = null
    @Volatile private var factory: ProfileDataStoreFactory? = null
    /** Set only by off-device screen tests, to show fixed sources. */
    @Volatile var streams: StreamRepository? = null
    val profileManager: ProfileManager by lazy { ProfileManager() }

    fun pluginDataStore(context: Context): PluginDataStore = dataStore ?: synchronized(this) {
        dataStore ?: PluginDataStore(
            context.applicationContext,
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            dataStoreFactory(context),
            profileManager
        ).also { dataStore = it }
    }

    fun pluginManager(context: Context): PluginManager = manager ?: synchronized(this) {
        manager ?: PluginManager(
            pluginDataStore(context),
            PluginRuntime(),
            PluginSyncService(),
            AuthManager(),
            ExternalRepoParser(),
            ExternalExtensionLoader(),
            ExternalExtensionRunner()
        ).also { manager = it }
    }

    /** As Nuvio's NetworkModule builds them: one validating client, and a permissive one for addon addresses. */
    private val moshi: Moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }

    private fun okHttpClient(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .cache(Cache(File(context.cacheDir, "http_cache_v2"), 50L * 1024 * 1024))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Nuvio/dev").build())
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code in 400..599) response.newBuilder().header("Cache-Control", "no-store").build() else response
            }
            .build().also { client = it }
    }

    private fun addonClient(context: Context): OkHttpClient = permissiveClient ?: synchronized(this) {
        permissiveClient ?: run {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
            okHttpClient(context).newBuilder()
                .cache(Cache(File(context.cacheDir, "addon_http_cache"), 50L * 1024 * 1024))
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build().also { permissiveClient = it }
        }
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /** Nuvio's Stremio addon store and client (AddonPreferences, AddonRepositoryImpl). */
    fun addonRepository(context: Context): AddonRepository = addons ?: synchronized(this) {
        addons ?: AddonRepositoryImpl(
            addonApi(context),
            addonPreferences(context),
            AddonSyncService(),
            AuthManager(),
            context.applicationContext
        ).also { addons = it }
    }

    private fun addonPreferences(context: Context): AddonPreferences = preferences ?: synchronized(this) {
        preferences ?: AddonPreferences(dataStoreFactory(context), profileManager).also { preferences = it }
    }

    /**
     * Makes Nuvio's list of Stremio addons the given one, in that order. LeXia's own settings screen
     * still keeps the list; this hands it to Nuvio's store before a search.
     */
    suspend fun setStremioAddons(context: Context, given: List<String>) {
        // Cinemeta stays, as in Nuvio's defaults (AddonPreferences): it is where episode lists and
        // details come from when no other add-on gives them; it gives no streams.
        val urls = if (given.any { it.contains("v3-cinemeta.strem.io") }) given else given + CINEMETA
        val store = addonPreferences(context)
        val have = store.installedAddonUrls.first()
        fun key(url: String) = url.trim().trimEnd('/').removeSuffix("/manifest.json").lowercase()
        val wanted = urls.map(::key).toSet()
        have.filter { key(it) !in wanted }.forEach { store.removeAddon(it) }
        val kept = have.map(::key).toSet()
        urls.filter { key(it) !in kept }.forEach { store.addAddon(it) }
        if (urls.isNotEmpty()) store.setAddonOrder(urls)
    }

    /** Nuvio's details source (MetaRepositoryImpl): film and episode information from the add-ons. */
    fun metaRepository(context: Context): MetaRepository = meta ?: synchronized(this) {
        meta ?: MetaRepositoryImpl(context.applicationContext, addonApi(context), addonRepository(context), ServerCatalog())
            .also { meta = it }
    }

    private const val CINEMETA = "https://v3-cinemeta.strem.io"

    private fun addonApi(context: Context): AddonApi = api ?: synchronized(this) {
        api ?: retrofit("https://placeholder.nuvio.tv/", addonClient(context)).create(AddonApi::class.java).also { api = it }
    }

    private fun dataStoreFactory(context: Context): ProfileDataStoreFactory = factory ?: synchronized(this) {
        factory ?: ProfileDataStoreFactory(context.applicationContext).also { factory = it }
    }

    val tmdbService: TmdbService by lazy {
        TmdbService(
            Retrofit.Builder()
                .baseUrl("https://api.themoviedb.org/3/")
                .client(OkHttpClient.Builder().dns(IPv4FirstDns()).build())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(TmdbApi::class.java)
        )
    }

    private val serverStreams = ServerStreams()
    private val debridPresentation = DebridStreamPresentation()

    /** Nuvio's stream search (StreamRepositoryImpl): Stremio addons and plugin scrapers together. */
    fun streamRepository(context: Context): StreamRepository = streams ?: synchronized(this) {
        streams ?: StreamRepositoryImpl(
            context.applicationContext,
            addonApi(context),
            addonRepository(context),
            pluginManager(context),
            profileManager,
            DebridSettingsDataStore(dataStoreFactory(context), profileManager),
            tmdbService,
            debridPresentation,
            LocalDebridAvailabilityService(),
            serverStreams
        ).also { streams = it }
    }


    private val playerClient: OkHttpClient by lazy { OkHttpClient.Builder().dns(IPv4FirstDns()).build() }

    private fun playerRetrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(playerClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /** Nuvio's skip-intro lookups (IntroDB, AniSkip, Anime Skip), with NuvioTV's addresses. */
    private val skipIntroRepository: (Context) -> SkipIntroRepository = { context ->
        SkipIntroRepository(
            playerRetrofit(BuildConfig.INTRODB_API_URL.ifEmpty { "https://localhost/" }).create(IntroDbApi::class.java),
            playerRetrofit("https://api.aniskip.com/v2/").create(AniSkipApi::class.java),
            playerRetrofit("https://api.anime-skip.com/").create(AnimeSkipApi::class.java),
            SimklIdResolver(playerClient, defaultSimklApiConfiguration()),
            AnimeSkipSettingsDataStore(dataStoreFactory(context), profileManager),
            tmdbService
        )
    }

    private val tmdbApi: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(OkHttpClient.Builder().dns(IPv4FirstDns()).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApi::class.java)
    }

    /**
     * LeXia's choices among the player settings NuvioTV leaves off by default, written once into
     * Nuvio's own settings store: the next episode's sources are found while this one plays, the
     * next episode starts in the player (same provider first), and a source that will not start
     * gives way to the next one. Raise [PLAYER_DEFAULTS] to write a new set.
     */
    fun applyPlayerDefaults(context: Context) {
        val app = context.applicationContext
        val marks = app.getSharedPreferences("lexia_nuvio", Context.MODE_PRIVATE)
        val done = marks.getInt("player_defaults", 0)
        if (done >= PLAYER_DEFAULTS) return
        val settings = PlayerSettingsDataStore(dataStoreFactory(app), profileManager, app)
        kotlinx.coroutines.runBlocking {
            if (done < 2) {
                // A video that will not start in ExoPlayer is tried once more in MPV (Nuvio's setting, off by default there).
                settings.setAutoSwitchInternalPlayerOnError(true)
            }
            if (done >= 1) return@runBlocking
            settings.setPreloadNextEpisodeSources(true)
            settings.setStreamAutoPlayNextEpisodeEnabled(true)
            settings.setStreamAutoPlayPreferBingeGroupForNextEpisode(true)
            settings.setStreamAutoPlayMode(com.nuvio.tv.data.local.StreamAutoPlayMode.FIRST_STREAM)
            settings.setStreamAutoPlayFallbackOnFailure(true)
            settings.setStreamReuseLastLinkCacheHours(6)
            settings.setSkipIntroEnabled(true)
        }
        marks.edit().putInt("player_defaults", PLAYER_DEFAULTS).apply()
    }

    private const val PLAYER_DEFAULTS = 2

    /**
     * LeXia's own language choices (Ayarlar › Oynatma ve Takip) for Nuvio's player: the audio and
     * subtitle language it picks by itself. Empty = the TV's language (Nuvio's default).
     */
    fun syncLanguages(context: Context, audio: String, subtitle: String) {
        val store = playerSettingsStore(context)
        val now = playerSettings(context)
        kotlinx.coroutines.runBlocking {
            val a = audio.ifBlank { com.nuvio.tv.data.local.AudioLanguageOption.DEVICE }
            if (now.preferredAudioLanguage != a) store.setPreferredAudioLanguage(a)
            if (subtitle.isNotBlank() && subtitle != "off" && now.subtitleStyle.preferredLanguage != subtitle) {
                store.setSubtitlePreferredLanguage(subtitle)
            }
        }
    }

    private fun playerSettingsStore(context: Context) =
        PlayerSettingsDataStore(dataStoreFactory(context.applicationContext), profileManager, context.applicationContext)

    private fun playerSettings(context: Context): com.nuvio.tv.data.local.PlayerSettings =
        kotlinx.coroutines.runBlocking { playerSettingsStore(context).playerSettings.first() }

    /** Nuvio's night sound ("off", "light", "medium", "strong"): loud scenes come down, quiet ones up. */
    fun nightMode(context: Context): String = playerSettings(context).nightMode.settingValue

    fun setNightMode(context: Context, value: String) = kotlinx.coroutines.runBlocking {
        playerSettingsStore(context).setNightMode(com.nuvio.tv.data.local.NightMode.fromSettingValue(value))
    }

    /** Nuvio's centre channel level (dB): voices stand out over music and effects. */
    fun dialogueBoost(context: Context): Int = playerSettings(context).centerMixLevelDb

    fun setDialogueBoost(context: Context, db: Int) = kotlinx.coroutines.runBlocking {
        playerSettingsStore(context).setCenterMixLevelDb(db)
    }

    /** A sound system on the optical cable (which cannot tell what it plays): Dolby and DTS go to it as they are. */
    fun opticalPassthrough(context: Context): Boolean = playerSettings(context).forceOpticalPassthrough

    fun setOpticalPassthrough(context: Context, on: Boolean) = kotlinx.coroutines.runBlocking {
        playerSettingsStore(context).setForceOpticalPassthrough(on)
    }

    /**
     * Nuvio's player view model (ui/screens/player/PlayerViewModel.kt), built as Hilt builds it in
     * NuvioTV. [arguments] are its route arguments; [progress] is LeXia's record of where films stop.
     */
    fun playerViewModel(context: Context, arguments: Map<String, String?>, progress: LeXiaProgress?): PlayerViewModel {
        val app = context.applicationContext
        val stores = dataStoreFactory(app)
        val badgeSettings = StreamBadgeSettingsDataStore(stores, profileManager)
        val watchProgress = LeXiaWatchProgressRepository(progress)
        val watchedItems = WatchedItemsPreferences(stores, profileManager)
        val layout = LayoutPreferenceDataStore()
        return PlayerViewModel(
            context = app,
            watchProgressRepository = watchProgress,
            metaRepository = metaRepository(app),
            streamRepository = streamRepository(app),
            addonRepository = addonRepository(app),
            pluginManager = pluginManager(app),
            subtitleRepository = LeXiaSubtitleRepository(),
            parentalGuideRepository = ParentalGuideRepository(
                playerRetrofit("https://api.tiffara.com/").create(ParentalGuideApi::class.java)
            ),
            trackingScrobbleCoordinator = TrackingScrobbleCoordinator(TrackingProviderRegistry(emptyList<TrackingProvider>())),
            traktEpisodeMappingService = TraktEpisodeMappingService(),
            skipIntroRepository = skipIntroRepository(app),
            playerSettingsDataStore = PlayerSettingsDataStore(stores, profileManager, app),
            deviceLocalPlayerPreferences = DeviceLocalPlayerPreferences(app),
            streamLinkCacheDataStore = StreamLinkCacheDataStore(stores, profileManager),
            streamBadgeSettingsDataStore = badgeSettings,
            bingeGroupCacheDataStore = BingeGroupCacheDataStore(stores, profileManager),
            layoutPreferenceDataStore = layout,
            episodeShufflePlayback = EpisodeShufflePlayback(
                EpisodeShuffleStore(stores, profileManager, layout),
                EpisodeShuffle(),
                watchProgress,
                watchedItems,
                profileManager
            ),
            watchedItemsPreferences = watchedItems,
            watchedSeriesStateHolder = WatchedSeriesStateHolder(stores, profileManager),
            trackPreferenceDataStore = TrackPreferenceDataStore(stores, profileManager),
            audioDelayRouteDataStore = AudioDelayRouteDataStore(stores, profileManager),
            torrentService = TorrentService(),
            torrentSettings = TorrentSettings(app),
            tmdbService = tmdbService,
            tmdbMetadataService = TmdbMetadataService(tmdbApi, Dispatchers.IO),
            tmdbSettingsDataStore = TmdbSettingsDataStore(stores, profileManager),
            mdbListRepository = MDBListRepository(),
            mdbListSettingsDataStore = MDBListSettingsDataStore(stores, profileManager),
            trailerPlayerPool = TrailerPlayerPool(),
            trailerService = TrailerService(),
            trailerSettingsDataStore = TrailerSettingsDataStore(app, stores, profileManager),
            traktRelatedService = TraktRelatedService(),
            traktAuthDataStore = TraktAuthDataStore(),
            traktSettingsDataStore = TraktSettingsDataStore(stores, profileManager),
            simklRelatedService = SimklRelatedService(),
            simklAuthRepository = SimklAuthRepository(),
            directDebridResolver = DirectDebridResolver(),
            youTubeStreamResolver = YouTubeStreamResolver(InAppYouTubeExtractor()),
            directDebridStreamPreparer = DirectDebridStreamPreparer(),
            cloudLibraryRepository = CloudLibraryRepository(),
            cloudPlaybackProgressStore = CloudLibraryPlaybackProgressStore(app),
            cloudPlaybackSessionStore = CloudLibraryPlaybackSessionStore(app),
            streamBadgePresentation = StreamBadgePresentation(badgeSettings),
            playbackIssueReportRepository = PlaybackIssueReportRepository(
                playerRetrofit("https://localhost/").create(PlaybackIssueReportApi::class.java)
            ),
            externalPlaybackTracker = ExternalPlaybackTracker(),
            subtitleFileCache = SubtitleFileCache(app, addonClient(app)),
            tvRecommendationManager = TvRecommendationManager(),
            serverPlayback = ServerPlayback(),
            serverStreams = serverStreams,
            partyRepository = PartyRepository(),
            profileManager = profileManager,
            savedStateHandle = SavedStateHandle(arguments)
        )
    }

    /** Nuvio's plugin screen asks before switching on such a scraper (PluginSafety is internal to this module). */
    fun isVideoEasyScraper(id: String, name: String, filename: String): Boolean =
        com.nuvio.tv.core.plugin.PluginSafety.isVideoEasyScraper(id, name, filename)

    /** What Nuvio's navigation hands a screen as route arguments: here, the opening activity's text extras. */
    private fun navigationArguments(context: Context): Map<String, String?> {
        val extras = (context as? Activity)?.intent?.extras ?: return emptyMap()
        return extras.keySet().associateWith { key -> extras.getString(key) }
    }

    fun viewModelFactory(context: Context): ViewModelProvider.Factory {
        val app = context.applicationContext
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when (modelClass) {
                else -> throw IllegalArgumentException("No Nuvio view model for ${modelClass.name}")
            } as T
        }
    }
}
