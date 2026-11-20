package com.neon.liquidplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.View
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var controller: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var deviceTracksJson = "[]"
    private var pendingMediaItems: List<MediaItem> = emptyList()
    private var shouldOpenPlayerOnLoad = false

    // کش سریع تصاویر کاور در رم (20 مگابایت) برای نمایش آنی بدون لگ و بدون پرش
    private val artworkCache = object : LruCache<String, ByteArray>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }
    private val albumColorCache = ConcurrentHashMap<Long, Pair<String, String>>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadDeviceSongsIntoPlayer()
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            val mc = controller
            if (mc != null && mc.mediaItemCount > 0 && mc.isPlaying) {
                val posSec = (mc.currentPosition.coerceAtLeast(0L)) / 1000.0
                val durSec = if (mc.duration > 0) mc.duration / 1000.0 else 0.0
                val idx = mc.currentMediaItemIndex
                evalJs("window.onNativeSync && window.onNativeSync($idx, true, $posSec, $durSec);")
            }
            mainHandler.postDelayed(this, 200L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        if (intent?.getBooleanExtra("OPEN_FULL_PLAYER", false) == true) {
            shouldOpenPlayerOnLoad = true
        }

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#070A14"))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                    if (uri.host == "appassets.androidplatform.net" && uri.path == "/albumart") {
                        val songId = uri.getQueryParameter("songId")?.toLongOrNull()
                        val albumId = uri.getQueryParameter("albumId")?.toLongOrNull()
                        val isThumb = uri.getQueryParameter("q") == "thumb"
                        val bytes = loadArtworkBytes(songId, albumId, isThumb)
                        if (bytes != null && bytes.isNotEmpty()) {
                            val headers = mapOf(
                                "Cache-Control" to "public, max-age=31536000, immutable",
                                "Content-Length" to bytes.size.toString()
                            )
                            return WebResourceResponse(
                                "image/jpeg",
                                null,
                                200,
                                "OK",
                                headers,
                                ByteArrayInputStream(bytes)
                            )
                        }
                        val notFoundHeaders = mapOf("Cache-Control" to "public, max-age=60")
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            404,
                            "Not Found",
                            notFoundHeaders,
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    val assetResponse = assetLoader.shouldInterceptRequest(uri)
                    if (assetResponse != null) return assetResponse

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (deviceTracksJson != "[]") {
                        evalJs("window.onNativeTracksLoaded && window.onNativeTracksLoaded($deviceTracksJson);")
                    }
                    if (shouldOpenPlayerOnLoad) {
                        shouldOpenPlayerOnLoad = false
                        evalJs("typeof openFullPlayer === 'function' && openFullPlayer();")
                    }
                }
            }
            addJavascriptInterface(NativeAudioBridge(), "AndroidBridge")
        }

        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        connectMediaController()
        requestAudioPermissions()
    }

    private fun decodeAndCompressJpeg(raw: ByteArray, targetPx: Int, quality: Int): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            var sample = 1
            while ((w / (sample * 2)) >= targetPx && (h / (sample * 2)) >= targetPx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun loadArtworkBytes(songId: Long?, albumId: Long?, isThumb: Boolean): ByteArray? {
        val tag = if (isThumb) "t" else "f"
        val key = "${songId ?: -1}_${albumId ?: -1}_$tag"
        val cached = artworkCache.get(key)
        if (cached != null) {
            return if (cached.isEmpty()) null else cached
        }

        val targetPx = if (isThumb) 160 else 512
        val quality = if (isThumb) 80 else 88
        var result: ByteArray? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && songId != null) {
            try {
                val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                val bmp = contentResolver.loadThumbnail(songUri, Size(targetPx, targetPx), null)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                result = out.toByteArray()
            } catch (_: Exception) {}
        }

        if (result == null && songId != null) {
            val mmr = MediaMetadataRetriever()
            try {
                val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                mmr.setDataSource(this, songUri)
                val embedded = mmr.embeddedPicture
                if (embedded != null && embedded.isNotEmpty()) {
                    result = decodeAndCompressJpeg(embedded, targetPx, quality)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {}
            }
        }

        if (result == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && albumId != null) {
            try {
                val albumUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                val bmp = contentResolver.loadThumbnail(albumUri, Size(targetPx, targetPx), null)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                result = out.toByteArray()
            } catch (_: Exception) {}
        }

        if (result == null && albumId != null) {
            try {
                val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                contentResolver.openInputStream(artUri)?.use { stream ->
                    val raw = stream.readBytes()
                    if (raw.isNotEmpty()) {
                        result = decodeAndCompressJpeg(raw, targetPx, quality)
                    }
                }
            } catch (_: Exception) {}
        }

        artworkCache.put(key, result ?: ByteArray(0))
        return result
    }

    private fun extractColorsForSong(
        songId: Long,
        albumId: Long,
        fallback1: String,
        fallback2: String
    ): Pair<String, String> {
        albumColorCache[albumId]?.let { return it }
        val bytes = loadArtworkBytes(songId, albumId, isThumb = true)
        if (bytes == null) {
            val pair = Pair(fallback1, fallback2)
            albumColorCache[albumId] = pair
            return pair
        }
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return Pair(fallback1, fallback2)
            val palette = Palette.from(bmp).maximumColorCount(8).generate()
            val c1Int = palette.getVibrantColor(palette.getLightVibrantColor(Color.parseColor(fallback1)))
            val c2Int = palette.getDominantColor(palette.getDarkVibrantColor(Color.parseColor(fallback2)))
            bmp.recycle()
            val pair = Pair(boostHexColor(c1Int, fallback1), boostHexColor(c2Int, fallback2))
            albumColorCache[albumId] = pair
            pair
        } catch (_: Exception) {
            Pair(fallback1, fallback2)
        }
    }

    private fun boostHexColor(colorInt: Int, fallback: String): String {
        val r = Color.red(colorInt) / 255f
        val g = Color.green(colorInt) / 255f
        val b = Color.blue(colorInt) / 255f
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        if (lum < 0.16f) return fallback
        val boost = if (lum < 0.45f) (0.5f - lum) * 1.1f else 0f
        val nr = ((r + boost).coerceIn(0f, 1f) * 255).toInt()
        val ng = ((g + boost).coerceIn(0f, 1f) * 255).toInt()
        val nb = ((b + boost).coerceIn(0f, 1f) * 255).toInt()
        return String.format("#%02X%02X%02X", nr, ng, nb)
    }

    private fun requestAudioPermissions() {
        val perms = mutableListOf<String>()
        val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, audioPerm) != PackageManager.PERMISSION_GRANTED) {
            perms.add(audioPerm)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            loadDeviceSongsIntoPlayer()
        }
    }

    private fun connectMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        future.addListener({
            val mc = future.get()
            controller = mc
            mc.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val posSec = (mc.currentPosition.coerceAtLeast(0L)) / 1000.0
                    val durSec = if (mc.duration > 0) mc.duration / 1000.0 else 0.0
                    evalJs("window.onNativeSync && window.onNativeSync(${mc.currentMediaItemIndex}, $isPlaying, $posSec, $durSec);")
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val durSec = if (mc.duration > 0) mc.duration / 1000.0 else 0.0
                    evalJs("window.onNativeTrackChanged && window.onNativeTrackChanged(${mc.currentMediaItemIndex}, $durSec);")
                }
            })
            if (pendingMediaItems.isNotEmpty() && mc.mediaItemCount == 0) {
                mc.setMediaItems(pendingMediaItems)
                mc.prepare()
            } else {
                loadDeviceSongsIntoPlayer()
            }
            mainHandler.post(syncRunnable)
        }, MoreExecutors.directExecutor())
    }

    private fun loadDeviceSongsIntoPlayer() {
        val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, audioPerm) != PackageManager.PERMISSION_GRANTED) return

        ioExecutor.execute {
            val palettes = listOf(
                Pair("#00f0ff", "#b537f2"),
                Pair("#ff2e93", "#7928ca"),
                Pair("#00ff9d", "#00c3ff"),
                Pair("#ff9a00", "#ff0055")
            )

            val jsonArray = JSONArray()
            val mediaItems = mutableListOf<MediaItem>()
            val remainingForPalette = mutableListOf<Triple<Long, Long, Pair<String, String>>>()

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.IS_MUSIC
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 4000"
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                var idx = 0
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "آهنگ ناشناس"
                    val rawArtist = cursor.getString(artistCol)
                    val artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "Samsung Music" else rawArtist
                    val durMs = cursor.getLong(durCol)
                    val albumId = cursor.getLong(albumCol)

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val sysArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                    val thumbUrl = "https://appassets.androidplatform.net/albumart?songId=$id&albumId=$albumId&q=thumb"
                    val fullUrl = "https://appassets.androidplatform.net/albumart?songId=$id&albumId=$albumId&q=full"

                    val fallback = palettes[idx % palettes.size]
                    val colors = if (idx < 25) {
                        extractColorsForSong(id, albumId, fallback.first, fallback.second)
                    } else {
                        remainingForPalette.add(Triple(id, albumId, fallback))
                        albumColorCache[albumId] ?: fallback
                    }

                    val obj = JSONObject().apply {
                        put("id", id)
                        put("title", title)
                        put("artist", artist)
                        put("duration", (durMs / 1000).coerceAtLeast(1))
                        put("favorite", false)
                        put("c1", colors.first)
                        put("c2", colors.second)
                        put("artThumbUrl", thumbUrl)
                        put("artFullUrl", fullUrl)
                    }
                    jsonArray.put(obj)

                    mediaItems.add(
                        MediaItem.Builder()
                            .setMediaId(id.toString())
                            .setUri(contentUri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setArtworkUri(sysArtUri)
                                    .build()
                            )
                            .build()
                    )
                    idx++
                }
            }

            if (mediaItems.isNotEmpty()) {
                pendingMediaItems = mediaItems
                deviceTracksJson = jsonArray.toString()
                mainHandler.post {
                    controller?.let { mc ->
                        if (mc.mediaItemCount == 0) {
                            mc.setMediaItems(mediaItems)
                            mc.prepare()
                        }
                    }
                    evalJs("window.onNativeTracksLoaded && window.onNativeTracksLoaded($deviceTracksJson);")
                }

                if (remainingForPalette.isNotEmpty()) {
                    val colorMap = JSONObject()
                    for ((songId, albumId, fallback) in remainingForPalette) {
                        val c = extractColorsForSong(songId, albumId, fallback.first, fallback.second)
                        val arr = JSONArray().apply {
                            put(c.first)
                            put(c.second)
                        }
                        colorMap.put(songId.toString(), arr)
                    }
                    val mapStr = colorMap.toString()
                    evalJs("window.onNativeColorsReady && window.onNativeColorsReady($mapStr);")
                }
            }
        }
    }

    private fun evalJs(script: String) {
        mainHandler.post {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    inner class NativeAudioBridge {
        @JavascriptInterface
        fun selectAndPlay(index: Int) {
            mainHandler.post {
                val mc = controller ?: return@post
                if (mc.mediaItemCount == 0 && pendingMediaItems.isNotEmpty()) {
                    mc.setMediaItems(pendingMediaItems)
                    mc.prepare()
                }
                if (index in 0 until mc.mediaItemCount) {
                    mc.seekTo(index, 0L)
                    mc.play()
                }
            }
        }

        @JavascriptInterface
        fun togglePlay() {
            mainHandler.post {
                val mc = controller ?: return@post
                if (mc.mediaItemCount == 0 && pendingMediaItems.isNotEmpty()) {
                    mc.setMediaItems(pendingMediaItems)
                    mc.prepare()
                }
                if (mc.isPlaying) mc.pause() else mc.play()
            }
        }

        @JavascriptInterface
        fun next() {
            mainHandler.post { controller?.seekToNextMediaItem() }
        }

        @JavascriptInterface
        fun previous() {
            mainHandler.post {
                controller?.let { mc ->
                    if (mc.currentPosition > 3000L) mc.seekTo(0L) else mc.seekToPreviousMediaItem()
                }
            }
        }

        @JavascriptInterface
        fun seekToSec(sec: Double) {
            mainHandler.post { controller?.seekTo((sec * 1000.0).toLong()) }
        }

        @JavascriptInterface
        fun setShuffle(enabled: Boolean) {
            mainHandler.post { controller?.shuffleModeEnabled = enabled }
        }

        @JavascriptInterface
        fun setRepeat(enabled: Boolean) {
            mainHandler.post {
                controller?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("OPEN_FULL_PLAYER", false)) {
            evalJs("typeof openFullPlayer === 'function' && openFullPlayer();")
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(syncRunnable)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}