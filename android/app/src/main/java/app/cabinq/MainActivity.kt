package app.cabinq

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.provider.Settings
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread
import android.content.ContentValues
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/** Hosts the Cabin Q interface (web/index.html) and bridges it to the native EQ engine and the phone's files. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var jsReady = false
    private val pending = ArrayList<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = 0xFF0C0D0F.toInt()
        @Suppress("DEPRECATION")
        run { window.statusBarColor = bg; window.navigationBarColor = bg }

        // Serve the bundled interface from an https origin so AudioWorklet and storage work.
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host == HOST && url.path == "/media") return serveMedia(url.getQueryParameter("u"))
                if (url.host == HOST && (url.path == "/index.html" || url.path == "/version.json")) serveWeb(url.path!!)?.let { return it }
                return assets.shouldInterceptRequest(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                jsReady = false
                // If a downloaded interface never reports in, it is broken: drop it and go back to the built-in one.
                if (servedDownloaded) main.postDelayed({
                    if (!jsReady && servedDownloaded) { File(filesDir, "web").deleteRecursively(); servedDownloaded = false; web.reload() }
                }, 15000)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.host == HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Fallback for plain <input type=file>; the library uses the native pickers below.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(audioPicker(), REQ_INPUT)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        // A new install must never show an interface cached by the previous one.
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getLong("webVersion", -1L) != ver) { web.clearCache(true); prefs.edit().putLong("webVersion", ver).apply() }
        web.addJavascriptInterface(Bridge(), "CabinNative")
        setContentView(web)
        web.loadUrl("https://$HOST/index.html")
        handleOpenIntent(intent)
        checkWebUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    // ---------------------------------------------------------------- files

    private fun audioPicker(): Intent {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        // A chooser lists every app that can provide audio: Files, My Files, Drive, other file managers.
        return Intent.createChooser(get, "בחירת שירים")
    }

    private fun mediaUrl(uri: Uri) = "https://$HOST/media?u=" + Uri.encode(uri.toString())

    private fun serveMedia(u: String?): WebResourceResponse = try {
        val uri = Uri.parse(u)
        val mime = contentResolver.getType(uri) ?: "audio/mpeg"
        val stream = contentResolver.openInputStream(uri) ?: throw IllegalStateException("no stream")
        WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
    } catch (e: Exception) {
        WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun describe(uri: Uri): JSONObject {
        var name = uri.lastPathSegment ?: "Track"
        var size = 0L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return JSONObject().put("name", name).put("size", size).put("url", mediaUrl(uri)).put("uri", uri.toString())
    }

    private fun scanFolder(tree: Uri): JSONObject {
        val cr = contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var folderName = "Music"
        runCatching {
            cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { folderName = it }
            }
        }
        val found = ArrayList<JSONObject>()
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.add(rootId to 0)
        while (stack.isNotEmpty() && found.size < MAX_TRACKS) {
            val (id, depth) = stack.removeLast()
            runCatching {
                cr.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 6) stack.add(docId to depth + 1)
                        } else if (mime.startsWith("audio/") || AUDIO_EXT.matches(name)) {
                            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                            found.add(JSONObject().put("name", name).put("size", size).put("url", mediaUrl(doc)))
                        }
                    }
                }
            }
        }
        found.sortBy { it.optString("name").lowercase() }
        return JSONObject()
            .put("folder", folderName)
            .put("folderUri", tree.toString())
            .put("tracks", JSONArray(found))
            .put("replace", true)
    }

    private fun prefsArray(key: String): JSONArray =
        runCatching { JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "[]")) }.getOrDefault(JSONArray())

    private fun savePrefsArray(key: String, a: JSONArray) =
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, a.toString()).apply()

    /** Remember picked songs whose permission can be kept, so they are still there next time. */
    private var pickKind = "picked"

    private fun rememberPicked(items: List<JSONObject>, key: String = "picked") {
        val saved = prefsArray(key)
        val have = HashSet<String>()
        for (i in 0 until saved.length()) have.add(saved.getJSONObject(i).optString("uri"))
        items.forEach { if (it.optString("uri") !in have) saved.put(it) }
        savePrefsArray(key, saved)
    }

    private fun loadLibrary() = thread(name = "library") {
        val picked = prefsArray("picked")
        if (picked.length() > 0) {
            val ok = JSONArray()
            for (i in 0 until picked.length()) {
                val o = picked.getJSONObject(i)
                val uri = Uri.parse(o.optString("uri"))
                val readable = runCatching { contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false)
                if (readable) ok.put(o)
            }
            savePrefsArray("picked", ok)
            if (ok.length() > 0) push(JSONObject().put("tracks", ok).put("quiet", true))
        }
        val refs = prefsArray("refs")
        if (refs.length() > 0) {
            val ok = JSONArray()
            for (i in 0 until refs.length()) {
                val o = refs.getJSONObject(i)
                val readable = runCatching { contentResolver.openInputStream(Uri.parse(o.optString("uri")))?.close(); true }.getOrDefault(false)
                if (readable) ok.put(o)
            }
            savePrefsArray("refs", ok)
            if (ok.length() > 0) push(JSONObject().put("tracks", ok).put("folder", "בדיקות שמע").put("folderUri", "__ref").put("quiet", true))
        }
        val folders = prefsArray("folders")
        for (i in 0 until folders.length()) {
            val tree = Uri.parse(folders.getString(i))
            runCatching { push(scanFolder(tree).put("quiet", true)) }
        }
    }

    private fun push(payload: JSONObject) = runOnUiThread {
        if (!jsReady) { pending.add(payload); return@runOnUiThread }
        web.evaluateJavascript("window.cabinAddTracks&&window.cabinAddTracks($payload)", null)
    }

    private fun handleOpenIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        if (uris.isEmpty()) return
        i.action = Intent.ACTION_MAIN // don't re-add on rotation
        val items = uris.map { describe(it) }
        push(JSONObject().put("tracks", JSONArray(items)).put("play", true))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
        }
        when (requestCode) {
            REQ_INPUT -> {
                val cb = fileCallback ?: return
                fileCallback = null
                cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            REQ_PICK -> {
                if (uris.isEmpty()) return
                val keep = ArrayList<JSONObject>()
                val items = uris.map { uri ->
                    val o = describe(uri)
                    val persisted = runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
                    }.getOrDefault(false)
                    if (persisted) keep.add(o)
                    o
                }
                val ref = pickKind == "refs"
                pickKind = "picked"
                if (keep.isNotEmpty()) rememberPicked(keep, if (ref) "refs" else "picked")
                val msg = JSONObject().put("tracks", JSONArray(items))
                if (ref) msg.put("folder", "בדיקות שמע").put("folderUri", "__ref")
                push(msg)
            }
            REQ_TREE -> {
                val tree = data?.data ?: return
                runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val folders = prefsArray("folders")
                var exists = false
                for (i in 0 until folders.length()) if (folders.getString(i) == tree.toString()) exists = true
                if (!exists) { folders.put(tree.toString()); savePrefsArray("folders", folders) }
                thread(name = "scan") { push(scanFolder(tree)) }
            }
        }
    }

    // ---------------------------------------------------------------- music on this phone (MediaStore)

    @Volatile private var pendingDevice: String? = null
    private fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPerm() = checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) scanDeviceAudio()
        else deliverDevice(JSONObject().put("denied", true).toString())
    }

    private fun deliverDevice(json: String) = runOnUiThread {
        if (jsReady) web.evaluateJavascript("window.cabinDeviceAudio&&window.cabinDeviceAudio($json)", null) else pendingDevice = json
    }

    private fun scanDeviceAudio() = thread(name = "device-audio") {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val q = Build.VERSION.SDK_INT >= 29
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            if (q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        )
        val sel = "${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND ${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0"
        val items = JSONArray()
        runCatching {
            contentResolver.query(base, cols, sel, null, "${cols[6]} ASC, ${MediaStore.Audio.Media.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext() && items.length() < 8000) {
                    val id = c.getLong(0)
                    val file = c.getString(1) ?: continue
                    val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: file.substringBeforeLast('.')
                    val artist = c.getString(3)?.takeIf { it != "<unknown>" } ?: ""
                    val raw = c.getString(6) ?: ""
                    val path = (if (q) raw else raw.substringBeforeLast('/', "")).trim('/').ifEmpty { "Music" }
                    items.put(JSONObject()
                        .put("name", title).put("artist", artist)
                        .put("dur", c.getLong(4) / 1000.0).put("size", c.getLong(5))
                        .put("path", path).put("url", mediaUrl(ContentUris.withAppendedId(base, id))))
                }
            }
        }
        deliverDevice(JSONObject().put("items", items).toString())
    }

    // ---------------------------------------------------------------- screen

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private fun setFullscreen(on: Boolean) {
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            else 0
        }
    }

    /** Methods the interface calls as window.CabinNative.* (they run off the UI thread). */
    inner class Bridge {
        @JavascriptInterface
        fun ready() = runOnUiThread {
            jsReady = true
            pendingDevice?.let { web.evaluateJavascript("window.cabinDeviceAudio&&window.cabinDeviceAudio($it)", null); pendingDevice = null }
            // tell the page once; after it refreshes, it already is the new version
            if (webUpdateReady) { webUpdateReady = false; web.evaluateJavascript("window.cabinWebUpdate&&window.cabinWebUpdate()", null) }
            pending.forEach { web.evaluateJavascript("window.cabinAddTracks&&window.cabinAddTracks($it)", null) }
            pending.clear()
            loadLibrary()
        }

        /** Downloads the latest APK inside the app and opens the installer. Returns "ok" or "perm". */
        @JavascriptInterface
        fun downloadUpdate(): String {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                runOnUiThread {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }
                return "perm"
            }
            runOnUiThread { startUpdateDownload() }
            return "ok"
        }

        /** 0–100 while downloading, 100 when done, -1 if it failed or nothing is running. */
        @JavascriptInterface
        fun updateProgress(): Int {
            val id = updateId
            if (id < 0) return -1
            val dm = getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return -1
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_FAILED) return -1
                if (status == DownloadManager.STATUS_SUCCESSFUL) return 100
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return if (total > 0) ((done * 99) / total).toInt() else 0
            }
            return -1
        }

        // ---- saving rendered songs: the page streams the WAV in base64 chunks ----
        @JavascriptInterface
        fun saveBegin(name: String): String {
            val token = System.nanoTime().toString()
            val f = File(cacheDir, "render-$token.tmp")
            saves[token] = Triple(f, FileOutputStream(f), name)
            return token
        }

        /** Like saveBegin, for any audio type (MP3, AAC, WAV). */
        @JavascriptInterface
        fun saveBeginMime(name: String, mime: String): String { val t = saveBegin(name); mimes[t] = mime; return t }

        @JavascriptInterface
        fun saveChunk(token: String, b64: String) {
            saves[token]?.second?.write(Base64.decode(b64, Base64.DEFAULT))
        }

        /** Returns where the file ended up, or "" if it failed. */
        @JavascriptInterface
        fun saveEnd(token: String): String {
            val (f, out, name) = saves.remove(token) ?: return ""
            val mime = mimes.remove(token) ?: "audio/wav"
            return try {
                out.close()
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, mime)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Cabin Q")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = contentResolver.insert(col, values) ?: return ""
                    contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                    values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    "Music/Cabin Q"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return ""
                    val dest = File(dir, name)
                    f.copyTo(dest, overwrite = true)
                    dest.absolutePath
                }
            } catch (e: Exception) {
                ""
            } finally {
                f.delete()
            }
        }

        @JavascriptInterface
        fun saveAbort(token: String) {
            saves.remove(token)?.let { (f, out, _) -> runCatching { out.close() }; f.delete() }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @JavascriptInterface
        fun getVersion(): Int = runCatching {
            if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else 0
        }.getOrDefault(0)

        /** "ok" when the app may list the phone's music, "ask" when it still needs permission. */
        @JavascriptInterface
        fun deviceAudioState(): String = if (hasAudioPerm()) "ok" else "ask"

        /** Lists all music on the phone (asks for permission the first time). The result arrives in window.cabinDeviceAudio. */
        @JavascriptInterface
        fun deviceAudio(): String {
            if (!hasAudioPerm()) { runOnUiThread { requestPermissions(arrayOf(audioPerm()), REQ_AUDIO) }; return "perm" }
            scanDeviceAudio(); return "ok"
        }

        @JavascriptInterface
        fun pickRefs() = runOnUiThread {
            pickKind = "refs"
            runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(audioPicker(), REQ_PICK)
            }
        }

        /** Forget one picked song or reference file (by the url the page knows it by). */
        @JavascriptInterface
        fun forgetPicked(url: String) {
            for (key in listOf("picked", "refs")) {
                val arr = prefsArray(key)
                val left = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("url") == url) runCatching {
                        contentResolver.releasePersistableUriPermission(Uri.parse(o.optString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else left.put(o)
                }
                savePrefsArray(key, left)
            }
        }

        @JavascriptInterface
        fun pickFiles() = runOnUiThread {
            pickKind = "picked"
            runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(audioPicker(), REQ_PICK)
            }
        }

        @JavascriptInterface
        fun pickFolder() = runOnUiThread {
            runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
            }
        }

        /** Forget every remembered song and folder (the files themselves stay on the phone). */
        @JavascriptInterface
        fun clearLibrary() {
            for (key in listOf("folders", "picked")) {
                val arr = prefsArray(key)
                for (i in 0 until arr.length()) {
                    val u = if (key == "folders") arr.getString(i) else arr.getJSONObject(i).optString("uri")
                    runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                savePrefsArray(key, JSONArray())
            }
        }

        @JavascriptInterface
        fun removeFolder(uri: String) {
            val folders = prefsArray("folders")
            val left = JSONArray()
            for (i in 0 until folders.length()) if (folders.getString(i) != uri) left.put(folders.getString(i))
            savePrefsArray("folders", left)
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        @JavascriptInterface
        fun getStatus(): String {
            val s = JSONObject(EqEngine.statusJson())
            if (!s.getBoolean("enabled") && EqEngine.enabledPref(this@MainActivity)) s.put("enabled", true)
            return s.toString()
        }

        @JavascriptInterface
        fun setSystemEq(on: Boolean) = runOnUiThread {
            EqEngine.setEnabledPref(this@MainActivity, on)
            if (on) { askNotificationPermission(); EqService.start(this@MainActivity) }
            else EqService.stop(this@MainActivity)
        }

        @JavascriptInterface
        fun setCurve(json: String) = EqEngine.setCurve(json, applicationContext)

        @JavascriptInterface
        fun setFullscreen(on: Boolean) = runOnUiThread { this@MainActivity.setFullscreen(on) }
    }

    // ---------------------------------------------------------------- interface updates (no install needed)
    // The interface (index.html) is published to GitHub Pages on every change. The app downloads a newer copy in
    // the background and uses it from the next load, as long as it does not need a newer Android build.

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var servedDownloaded = false
    @Volatile private var webUpdateReady = false

    private fun myCode(): Long = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)
    private fun bundledMeta(): JSONObject? = runCatching { JSONObject(assets.open("version.json").bufferedReader().readText()) }.getOrNull()
    private fun downloadedMeta(): JSONObject? = runCatching { JSONObject(File(File(filesDir, "web"), "version.json").readText()) }.getOrNull()

    private fun useDownloaded(): Boolean {
        val d = downloadedMeta() ?: return false
        if (!File(File(filesDir, "web"), "index.html").exists()) return false
        if (d.optLong("minNative", 0) > myCode()) return false
        return d.optLong("time", 0) > (bundledMeta()?.optLong("time", 0) ?: 0)
    }

    private fun serveWeb(path: String): WebResourceResponse? {
        if (!useDownloaded()) { if (path == "/index.html") servedDownloaded = false; return null }
        if (path == "/index.html") servedDownloaded = true
        val f = File(File(filesDir, "web"), path.trimStart('/'))
        val mime = if (path.endsWith(".json")) "application/json" else "text/html"
        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), FileInputStream(f))
    }

    private fun httpGet(u: String): String? = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000; c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    private fun checkWebUpdate() = thread(name = "web-update") {
        val t = System.currentTimeMillis()
        val remote = httpGet("$WEB_URL/version.json?t=$t")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@thread
        if (remote.optLong("minNative", 0) > myCode()) return@thread
        val current = maxOf(bundledMeta()?.optLong("time", 0) ?: 0, if (useDownloaded()) downloadedMeta()?.optLong("time", 0) ?: 0 else 0)
        if (remote.optLong("time", 0) <= current) return@thread
        val html = httpGet("$WEB_URL/index.html?t=$t") ?: return@thread
        if (html.length < 100_000 || !html.contains("CABIN") || !html.contains("CabinNative")) return@thread
        val dir = File(filesDir, "web").apply { mkdirs() }
        File(dir, "index.html.tmp").writeText(html)
        File(dir, "version.json.tmp").writeText(remote.toString())
        File(dir, "index.html.tmp").renameTo(File(dir, "index.html"))
        File(dir, "version.json.tmp").renameTo(File(dir, "version.json"))
        webUpdateReady = true
        runOnUiThread { if (jsReady) { webUpdateReady = false; web.evaluateJavascript("window.cabinWebUpdate&&window.cabinWebUpdate()", null) } }
    }

    // ---------------------------------------------------------------- updates

    private val saves = HashMap<String, Triple<File, FileOutputStream, String>>()
    private val mimes = HashMap<String, String>()
    private var updateId = -1L
    private var updateReceiver: BroadcastReceiver? = null

    private fun startUpdateDownload() {
        val dm = getSystemService(DownloadManager::class.java)
        if (updateId >= 0) runCatching { dm.remove(updateId) }
        val req = DownloadManager.Request(Uri.parse(UPDATE_URL))
            .setTitle("Cabin Q")
            .setDescription("מוריד עדכון")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "cabin-q-${System.currentTimeMillis()}.apk")
        updateId = dm.enqueue(req)
        if (updateReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == updateId) installDownloaded(id)
                }
            }
            val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            updateReceiver = r
        }
    }

    private fun installDownloaded(id: Long) {
        val dm = getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(id) ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onDestroy() {
        updateReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    companion object {
        private const val WEB_URL = "https://netchamo.github.io/Cabin---Q"
        private const val UPDATE_URL = "https://github.com/netchamo/Cabin---Q/releases/download/latest/cabin-q.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val HOST = "appassets.androidplatform.net"
        private const val PREFS = "library"
        private const val REQ_INPUT = 41
        private const val REQ_NOTIF = 42
        private const val REQ_PICK = 43
        private const val REQ_TREE = 44
        private const val REQ_AUDIO = 45
        private const val MAX_TRACKS = 3000
        private val AUDIO_EXT = Regex(".*\\.(mp3|m4a|aac|wav|flac|ogg|opus|aiff?|alac|wma)$", RegexOption.IGNORE_CASE)
    }
}
