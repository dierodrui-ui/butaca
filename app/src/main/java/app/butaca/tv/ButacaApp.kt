package app.butaca.tv

import android.app.Application
import app.butaca.tv.data.ButacaDb
import app.butaca.tv.data.Prefs
import app.butaca.tv.data.Repo
import app.butaca.tv.data.TmdbClient
import app.butaca.tv.data.XtreamClient
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ButacaApp : Application(), ImageLoaderFactory {

    lateinit var repo: Repo
        private set
    lateinit var http: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val prefs = Prefs(this)
        val tmdb = TmdbClient(http, cacheDir) { prefs.value.tmdbKey.trim().ifBlank { BuildConfig.TMDB_KEY } }
        repo = Repo(ButacaDb.create(this), XtreamClient(http), prefs, tmdb)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()

    companion object {
        lateinit var instance: ButacaApp
            private set
        val repo: Repo get() = instance.repo
    }
}
