// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

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
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LeXiaMetaRepository
import com.nuvio.tv.data.repository.LeXiaSubtitleRepository
import com.nuvio.tv.data.repository.LeXiaWatchProgressRepository
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.plugin.PluginViewModel
import kotlinx.coroutines.flow.first
import com.nuvio.tv.ui.screens.stream.StreamScreenViewModel
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
    @Volatile private var factory: ProfileDataStoreFactory? = null
    @Volatile internal var streams: StreamRepository? = null
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
    suspend fun setStremioAddons(context: Context, urls: List<String>) {
        val store = addonPreferences(context)
        val have = store.installedAddonUrls.first()
        fun key(url: String) = url.trim().trimEnd('/').removeSuffix("/manifest.json").lowercase()
        val wanted = urls.map(::key).toSet()
        have.filter { key(it) !in wanted }.forEach { store.removeAddon(it) }
        val kept = have.map(::key).toSet()
        urls.filter { key(it) !in kept }.forEach { store.addAddon(it) }
        if (urls.isNotEmpty()) store.setAddonOrder(urls)
    }

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

    private fun streamViewModel(context: Context, args: Map<String, String?>): StreamScreenViewModel {
        val app = context.applicationContext
        val stores = dataStoreFactory(app)
        val badgeSettings = StreamBadgeSettingsDataStore(stores, profileManager)
        return StreamScreenViewModel(
            app,
            streamRepository(app),
            addonRepository(app),
            pluginManager(app),
            LeXiaMetaRepository(),
            PlayerSettingsDataStore(stores, profileManager, app),
            StreamLinkCacheDataStore(stores, profileManager),
            StreamBadgePresentation(badgeSettings),
            badgeSettings,
            BingeGroupCacheDataStore(stores, profileManager),
            TorrentSettings(app),
            LeXiaWatchProgressRepository(),
            TrackingScrobbleCoordinator(TrackingProviderRegistry(emptyList<TrackingProvider>())),
            DirectDebridResolver(),
            DirectDebridStreamPreparer(),
            debridPresentation,
            YouTubeStreamResolver(InAppYouTubeExtractor()),
            ExternalPlaybackTracker(),
            LeXiaSubtitleRepository(),
            SubtitleFileCache(app, addonClient(app)),
            TorrentService(),
            serverStreams,
            ServerPlayback(),
            PartyRepository(),
            profileManager,
            SavedStateHandle(args)
        )
    }

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
                PluginViewModel::class.java -> PluginViewModel(pluginManager(app), profileManager, app)
                StreamScreenViewModel::class.java -> streamViewModel(app, navigationArguments(context))
                else -> throw IllegalArgumentException("No Nuvio view model for ${modelClass.name}")
            } as T
        }
    }
}
