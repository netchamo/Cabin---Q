package app.cabinq

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

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
                return assets.shouldInterceptRequest(url)
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
        web.addJavascriptInterface(Bridge(), "CabinNative")
        setContentView(web)
        web.loadUrl("https://$HOST/index.html")
        handleOpenIntent(intent)
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
    private fun rememberPicked(items: List<JSONObject>) {
        val saved = prefsArray("picked")
        val have = HashSet<String>()
        for (i in 0 until saved.length()) have.add(saved.getJSONObject(i).optString("uri"))
        items.forEach { if (it.optString("uri") !in have) saved.put(it) }
        savePrefsArray("picked", saved)
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
                if (keep.isNotEmpty()) rememberPicked(keep)
                push(JSONObject().put("tracks", JSONArray(items)))
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
            pending.forEach { web.evaluateJavascript("window.cabinAddTracks&&window.cabinAddTracks($it)", null) }
            pending.clear()
            loadLibrary()
        }

        @JavascriptInterface
        fun getVersion(): Int = runCatching {
            if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else 0
        }.getOrDefault(0)

        @JavascriptInterface
        fun pickFiles() = runOnUiThread {
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

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val PREFS = "library"
        private const val REQ_INPUT = 41
        private const val REQ_NOTIF = 42
        private const val REQ_PICK = 43
        private const val REQ_TREE = 44
        private const val MAX_TRACKS = 3000
        private val AUDIO_EXT = Regex(".*\\.(mp3|m4a|aac|wav|flac|ogg|opus|aiff?|alac|wma)$", RegexOption.IGNORE_CASE)
    }
}
