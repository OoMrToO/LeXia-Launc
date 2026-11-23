package tv.lexia.launcher

import android.content.Context
import android.util.Log
import com.nuvio.tv.LeXiaCinema
import com.nuvio.tv.LeXiaNuvio
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quiet work done while nobody is watching:
 * - the Nuvio repositories are refreshed once a day (sites move; their owners update the code,
 *   and an old copy finds nothing);
 * - while the screensaver is on, sources are looked up for the films in Listem, so "Oynat" opens
 *   one at once and the poster's "izlenebilir" mark is true.
 */
internal object Housekeeping {
    private const val REFRESH_MS = 20 * 60 * 60 * 1000L
    private const val PREFETCH_FILMS = 6

    private val refreshing = AtomicBoolean(false)
    private val prefetching = AtomicBoolean(false)
    @Volatile private var stop = false

    /** Refreshes every Nuvio repository when the last refresh is older than a day. Off the main thread. */
    fun refreshRepos(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (LeXiaCinema.playing || System.currentTimeMillis() - prefs.reposRefreshedAt < REFRESH_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try {
                val repo = NuvioJsRepository(app)
                val all = repo.installedRepositories()
                var ok = all.isEmpty()
                for (r in all) {
                    if (LeXiaCinema.playing) break
                    try {
                        repo.refreshRepository(r.id)
                        ok = true
                    } catch (e: Exception) {
                        Log.w("LeXiaHousekeeping", "Repository not refreshed: ${e.message}")
                    }
                }
                if (ok) prefs.reposRefreshedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w("LeXiaHousekeeping", "Repositories not refreshed: ${e.message}")
            } finally {
                refreshing.set(false)
            }
        }.start()
    }

    /** The screensaver came on: Listem's films get their sources looked up, one after another. */
    fun prefetch(ctx: Context) {
        val app = ctx.applicationContext
        stop = false
        if (LeXiaCinema.playing || !prefetching.compareAndSet(false, true)) return
        Thread {
            try {
                val prefs = Prefs(app)
                val addons = MediaAddonRepository(app)
                val nuvio = NuvioJsRepository(app)
                if (addons.installedAddons().isEmpty() && nuvio.installedRepositories().isEmpty()) return@Thread
                val memory = SourceMemory(app)
                val store = MediaPlaybackStore(app)
                val films = prefs.watchlist
                    .filter { !it.isSeries && Surprise.watchable(it) && !store.isFinished(it.key) }
                    .take(PREFETCH_FILMS)
                runBlocking { LeXiaNuvio.setStremioAddons(app, addons.installedAddons().filter { it.enabled }.map { it.baseUrl }) }
                for (film in films) {
                    if (stop || LeXiaCinema.playing) break
                    val imdb = film.imdbId.ifBlank { addons.imdbId("movie", film.id) }
                    val key = imdb.ifBlank { "tmdb:${film.id}" }
                    if (memory.kept(key).isNotEmpty()) continue
                    if (imdb.isNotBlank()) LeXiaNuvio.tmdbService.preCacheMapping(imdb, film.id, "movie")
                    val found = NuvioSearch.streams(app, "movie", key, null, null, prefs.favSource) { }
                    if (found.isNotEmpty()) {
                        memory.keep(key, found)
                        if (!store.everFound(film.key)) store.markReady(film.key)
                        store.markAvailable(film.key)
                    }
                }
            } catch (e: Exception) {
                Log.w("LeXiaHousekeeping", "Sources not looked up: ${e.message}")
            } finally {
                prefetching.set(false)
            }
        }.start()
    }

    /** The viewer is back: no new film is started (the one under way finishes on its own). */
    fun stopPrefetch() {
        stop = true
    }
}
