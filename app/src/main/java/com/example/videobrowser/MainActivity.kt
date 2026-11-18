package com.example.videobrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

private const val SCAN_JS =
    "(function(){var vs=document.querySelectorAll('video'),best=null,bs=-1,others=[];" +
        "var vw=window.innerWidth,vh=window.innerHeight;" +
        "for(var i=0;i<vs.length;i++){var v=vs[i];if(!v.__vid)v.__vid=Math.random().toString(36).slice(2);" +
        "var s0=v.currentSrc||v.src||'';" +
        "if(!s0){var srcTag=v.querySelector('source');if(srcTag)s0=srcTag.src||'';}" +
        "var r=v.getBoundingClientRect();" +
        "var w=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0));" +
        "var h=Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));var area=w*h;" +
        "var dur=v.duration||0;" +
        "var playing=!v.paused&&!v.ended;" +
        "var sc=Math.max(area,100)*(playing?3:1);" +
        "if(sc>bs){if(best&&best.src)others.push(best.src);bs=sc;best={id:v.__vid,src:s0,poster:v.poster||'',duration:dur,playing:playing};}" +
        "else if(s0)others.push(s0);}" +
        "var og=document.querySelector('meta[property=\"og:image\"]');" +
        "return JSON.stringify({n:vs.length,a:best,o:others,og:og?og.content:''});})()"

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val MEDIA_REGEX = Regex(
    "\\.(mp4|m4v|webm|mkv|mov|flv|3gp|m3u8|mp3|m4a|aac|ogg|wav)(\\?|#|$)",
    RegexOption.IGNORE_CASE
)

class MainActivity : AppCompatActivity() {

    class Tab(val web: WebView) {
        var title = "علامة تبويب جديدة"
        var url = ""
        var pending = false
        val media = LinkedHashMap<String, MediaItem>()
        val sniffed = LinkedHashMap<String, Long>()
        var sawBlob = false
        var activeId: String? = null
        var isActuallyPlaying = false
        var playStart = 0L
        var poster = ""
        var lastForced = ""
        var resolvedFormats: List<Format>? = null
        var isResolving = false
    }

    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var fab: FloatingActionButton
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var homeBtn: ImageButton
    private lateinit var tabsBtn: ImageButton
    private lateinit var downloadsBtn: ImageButton
    private lateinit var moreBtn: ImageButton

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var customContainer: FrameLayout? = null

    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            cur?.let { scan(it) }
            scanHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }
        setContentView(root)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            id = View.generateViewId()
        }

        backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            background = null
            setOnClickListener { cur?.let { if (it.web.canGoBack()) it.web.goBack() } }
        }
        fwdBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_forward)
            background = null
            setOnClickListener { cur?.let { if (it.web.canGoForward()) it.web.goForward() } }
        }
        homeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_home)
            background = null
            setOnClickListener { cur?.web?.loadUrl(Prefs.home(this@MainActivity)) }
        }

        urlBar = EditText(this).apply {
            hint = "ابحث أو اكتب رابط URL"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    navigate(text.toString())
                    true
                } else false
            }
        }

        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(fwdBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(homeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(urlBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8); marginEnd = dp(2) })

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER
            id = View.generateViewId()
        }

        tabsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_tabs)
            background = null
            setOnClickListener { showTabsDialog() }
        }
        downloadsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_download)
            background = null
            setOnClickListener { startActivity(Intent(this@MainActivity, DownloadsActivity::class.java)) }
        }
        moreBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more)
            background = null
            setOnClickListener { showMoreMenu(it) }
        }

        val btnW = dp(48)
        bottomBar.addView(tabsBtn, LinearLayout.LayoutParams(btnW, btnW, 1f))
        bottomBar.addView(downloadsBtn, LinearLayout.LayoutParams(btnW, btnW, 1f))
        bottomBar.addView(moreBtn, LinearLayout.LayoutParams(btnW, btnW, 1f))

        webContainer = FrameLayout(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_fg) ?: progressDrawable
        }

        fab = FloatingActionButton(this).apply {
            setImageResource(R.drawable.ic_download)
            imageTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.white)
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.holo_red_dark)
            setOnClickListener { onFabClick() }
            visibility = View.GONE
        }

        val layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        layoutParams.topMargin = dp(58)
        layoutParams.bottomMargin = dp(56)

        root.addView(webContainer, layoutParams)

        val topParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        root.addView(topBar, topParams)

        val progParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        progParams.topMargin = dp(54)
        root.addView(progress, progParams)

        val botParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        botParams.gravity = Gravity.BOTTOM
        root.addView(bottomBar, botParams)

        val fabParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fabParams.gravity = Gravity.BOTTOM or Gravity.END
        fabParams.marginEnd = dp(16)
        fabParams.bottomMargin = dp(72)
        root.addView(fab, fabParams)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView()
                    return
                }
                val w = cur?.web
                if (w != null && w.canGoBack()) {
                    w.goBack()
                } else if (tabs.size > 1) {
                    closeTab(cur!!)
                } else {
                    finish()
                }
            }
        })

        val savedTabs = Store.get(this, "tabs")
        if (savedTabs is List<*> && savedTabs.isNotEmpty()) {
            for (item in savedTabs) {
                if (item is Map<*, *>) {
                    val title = item["first"] as? String ?: ""
                    val url = item["second"] as? String ?: ""
                    if (url.startsWith("http")) {
                        newTab(url, false, title)
                    }
                }
            }
        }
        if (tabs.isEmpty()) {
            newTab(Prefs.home(this))
        } else {
            switchTab(tabs[0])
        }
    }

    override fun onResume() {
        super.onResume()
        scanHandler.post(scanRunnable)
    }

    override fun onPause() {
        super.onPause()
        scanHandler.removeCallbacks(scanRunnable)
        val kept = tabs.filter { it.url.startsWith("http") }
        Store.put(this, "tabs", kept.map { it.title to it.url })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun newTab(url: String?, load: Boolean = true, title: String = ""): Tab {
        val w = WebView(this)
        initWebView(w)
        val t = Tab(w)
        if (title.isNotBlank()) t.title = title
        if (url != null) t.url = url
        tabs.add(t)
        webContainer.addView(w, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        switchTab(t)
        if (load && url != null) w.loadUrl(url)
        return t
    }

    private fun switchTab(t: Tab) {
        cur = t
        for (x in tabs) {
            x.web.visibility = if (x === t) View.VISIBLE else View.GONE
        }
        urlBar.setText(t.url)
        updateFab()
    }

    private fun closeTab(t: Tab) {
        webContainer.removeView(t.web)
        t.web.destroy()
        tabs.remove(t)
        if (tabs.isEmpty()) {
            finish()
        } else {
            switchTab(tabs.last())
        }
    }

    private fun navigate(s: String) {
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(" ") || !s.contains(".") -> Prefs.searchUrl(this) + URLEncoder.encode(s, "UTF-8")
            else -> "https://$s"
        }
        cur?.web?.loadUrl(url)
        urlBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.userAgentString = if (Prefs.desktop(this)) DESKTOP_UA else s.userAgentString

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                val host = r.url.host ?: ""
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    v.loadUrl(u, mapOf("X-Requested-With" to ""))
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                t.url = url
                t.sawBlob = false
                t.media.clear()
                t.activeId = null
                t.resolvedFormats = null
                if (t === cur) { urlBar.setText(url); updateFab() }
            }

            override fun onPageFinished(v: WebView, url: String) {
                t.url = url
                t.title = v.title ?: url
                addHistory(t.title, url)
                if (t === cur) urlBar.setText(url)
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (Net.isAdOrPreview(u)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                if ((MEDIA_REGEX.containsMatchIn(u) || u.contains("videoplayback") || u.contains(".m3u8"))) {
                    ui.post { tabOf(v)?.let { rec(it, u) } }
                }
                return null
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (tabOf(v) === cur) {
                    progress.progress = p
                    progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                if (title != null) tabOf(v)?.title = title
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                val decor = window.decorView as FrameLayout
                customContainer = FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                }
                decor.addView(customContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        w.setDownloadListener { url, _, cd, mime, len ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(this, "لا يمكن تنزيل روابط Blob مباشرة", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val fname = URLUtil.guessFileName(url, cd, mime)
            val ext = Formats.extOf(url, mime)
            val f = Format("الملف", "", url, "direct", ext, false, if (len > 0) Util.fmtSize(len) else "")
            showFormatDialog(listOf(f), fname, "")
        }
    }

    private fun hideCustomView() {
        if (customView == null) return
        val decor = window.decorView as FrameLayout
        decor.removeView(customContainer)
        customContainer = null
        customView = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customCb?.onCustomViewHidden()
        customCb = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun addHistory(title: String, url: String) {
        if (!url.startsWith("http")) return
        val l = Store.entries(this, "history")
        l.removeAll { it.url == url }
        l.add(0, Store.Entry(title, url, System.currentTimeMillis()))
        Store.putEntries(this, "history", l)
    }

    private fun rec(t: Tab, url: String) {
        if (Net.isAdOrPreview(url)) return
        if (t.sniffed.containsKey(url)) return
        t.sniffed[url] = System.currentTimeMillis()
        if (t.sniffed.size > 80) t.sniffed.remove(t.sniffed.keys.first())
    }

    private fun addTo(t: Tab, url: String) {
        if (!Net.isAdOrPreview(url)) {
            t.media[url] = MediaItem(url, if (url.contains(".m3u8", true)) "hls" else "direct")
            triggerBackgroundResolve(t)
        }
    }

    private fun scan(t: Tab) {
        if (t.pending) return
        t.web.evaluateJavascript(SCAN_JS) { r ->
            try {
                if (r != null && r != "null") {
                    val s = JSONTokener(r).nextValue() as? String
                    if (s != null) onScan(t, JSONObject(s))
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun onScan(t: Tab, o: JSONObject) {
        val a = o.optJSONObject("a")
        val others = HashSet<String>()
        val oa = o.optJSONArray("o")
        if (oa != null) for (i in 0 until oa.length()) others.add(oa.getString(i))

        if (a == null) {
            t.activeId = null
            t.isActuallyPlaying = false
            t.media.clear()
            t.resolvedFormats = null
            updateFab()
            return
        }

        val id = a.getString("id")
        val playing = a.optBoolean("playing", false)
        t.isActuallyPlaying = playing

        if (id != t.activeId) {
            t.activeId = id
            t.playStart = System.currentTimeMillis()
        }

        val src = a.optString("src")
        t.poster = a.optString("poster").ifEmpty { o.optString("og") }
        t.media.clear()

        if (src.startsWith("http") && !Net.isAdOrPreview(src)) {
            addTo(t, src)
        } else {
            if (src.startsWith("blob:")) t.sawBlob = true
            val cand = t.sniffed.filter { !others.contains(it.key) && !Net.isAdOrPreview(it.key) }
            val recent = cand.filter { t.playStart == 0L || it.value >= t.playStart - 5000 }
            val pick = LinkedHashMap<String, Long>()
            pick.putAll(if (recent.isNotEmpty()) recent else cand)
            for ((u, ts) in cand) if (u.contains(".m3u8", true)) pick[u] = ts
            for (u in pick.keys) addTo(t, u)
        }
        updateFab()
    }

    private fun triggerBackgroundResolve(t: Tab) {
        if (t.isResolving || t.resolvedFormats != null || t.media.isEmpty()) return
        t.isResolving = true
        if (t === cur) updateFab()

        val items = t.media.values.toList()
        val headers = headersFor(t)

        io.execute {
            val list = ArrayList<Format>()
            for (item in items) {
                try { list.addAll(Formats.resolve(item, headers)) } catch (e: Exception) {}
            }
            val clean = list.filter { f -> !Net.isAdOrPreview(f.url) && (f.fromMaster || list.none { it.fromMaster && it.url == f.url }) }
                .distinctBy { f -> 
                    val resKey = f.label.replace(Regex("[^0-9P]"), "").uppercase()
                    if (resKey.isNotEmpty()) resKey else f.url
                }

            val finalFormats = clean.sortedWith(compareByDescending<Format> { it.isAudio }.thenByDescending {
                it.resolution.substringBefore('x').toIntOrNull() ?: 0
            })

            ui.post {
                t.isResolving = false
                t.resolvedFormats = finalFormats
                if (t === cur) updateFab()
            }
        }
    }

    private fun headersFor(t: Tab): Map<String, String> {
        val m = HashMap<String, String>()
        if (t.url.startsWith("http")) {
            m["Referer"] = t.url
            try {
                val u = URL(t.url)
                m["Origin"] = "${u.protocol}://${u.host}"
            } catch (e: Exception) {}
        }
        val ck = CookieManager.getInstance().getCookie(t.url)
        if (ck != null) m["Cookie"] = ck
        m["User-Agent"] = if (Prefs.desktop(this)) DESKTOP_UA else cur?.web?.settings?.userAgentString ?: DESKTOP_UA
        return m
    }

    private fun updateFab() {
        val t = cur
        if (t == null) {
            fab.visibility = View.GONE
            return
        }
        val hasMedia = t.media.isNotEmpty() || (t.resolvedFormats != null && t.resolvedFormats!!.isNotEmpty())
        fab.visibility = if (hasMedia || t.isResolving) View.VISIBLE else View.GONE
    }

    private fun onFabClick() {
        val t = cur ?: return
        val items = t.media.values.toList()
        val headers = headersFor(t)
        val title = if (t.title.isBlank()) Util.host(t.url) else t.title
        val poster = t.poster

        if (t.resolvedFormats != null) {
            showFormatDialog(t.resolvedFormats!!, title, poster)
            return
        }

        val pd = AlertDialog.Builder(this).setMessage("جاري فحص الجودات...").setCancelable(false).create()
        pd.show()

        io.execute {
            val list = ArrayList<Format>()
            for (item in items) {
                try { list.addAll(Formats.resolve(item, headers)) } catch (e: Exception) {}
            }
            val clean = list.filter { f -> !Net.isAdOrPreview(f.url) && (f.fromMaster || list.none { it.fromMaster && it.url == f.url }) }
                .distinctBy { f -> 
                    val resKey = f.label.replace(Regex("[^0-9P]"), "").uppercase()
                    if (resKey.isNotEmpty()) resKey else f.url
                }

            val finalFormats = clean.sortedWith(compareByDescending<Format> { it.isAudio }.thenByDescending {
                it.resolution.substringBefore('x').toIntOrNull() ?: 0
            })

            ui.post {
                pd.dismiss()
                t.resolvedFormats = finalFormats
                showFormatDialog(finalFormats, title, poster)
            }
        }
    }

    private fun showFormatDialog(list: List<Format>, title: String, poster: String) {
        if (list.isEmpty()) {
            Toast.makeText(this, "لم يتم العثور على روابط فيديو", Toast.LENGTH_SHORT).show()
            return
        }
        val headers = headersFor(cur!!)
        val d = BottomSheetDialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val posterImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2C2C2C"))
        }
        val imgSize = dp(64)
        headerLayout.addView(posterImg, LinearLayout.LayoutParams(imgSize, imgSize).apply { marginEnd = dp(12) })

        if (poster.isNotBlank()) {
            ThumbLoader.loadUrl(poster, headers) { b ->
                if (b != null) posterImg.setImageBitmap(b)
            }
        }

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pathView = TextView(this).apply {
            text = "المسار: " + (if (Uri.parse(Prefs.downloadDir(this@MainActivity)) == null) "Download/VideoBrowser" else (Uri.parse(Prefs.downloadDir(this@MainActivity)).lastPathSegment ?: "Download/VideoBrowser"))
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }
        val nameView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        titleCol.addView(pathView)
        titleCol.addView(nameView)
        headerLayout.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(headerLayout)

        val changeDirBtn = TextView(this).apply {
            text = "تغيير"
            textSize = 14f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, dp(4), 0, dp(12))
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, 999)
            }
        }
        sheet.addView(changeDirBtn)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun row(f: Format) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setBackgroundColor(Color.parseColor("#252525"))
            }

            val k = Util.kindOf("x." + f.ext)
            val icon = ImageView(this).apply {
                setImageResource(when (k) {
                    "video" -> R.drawable.ic_video
                    "audio" -> R.drawable.ic_music
                    else -> R.drawable.ic_file
                })
            }
            itemLayout.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })

            val desc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val l1 = TextView(this).apply {
                text = f.label + (if (f.resolution.isNotBlank()) " (${f.resolution})" else "")
                textSize = 15f
                setTextColor(Color.WHITE)
            }
            val l2 = TextView(this).apply {
                val parts = ArrayList<String>()
                if (f.sizeStr.isNotBlank()) parts.add(f.sizeStr)
                if (f.ext.isNotBlank()) parts.add(f.ext.uppercase())
                text = parts.joinToString(" • ")
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
            }
            desc.addView(l1)
            desc.addView(l2)
            itemLayout.addView(desc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val downBtn = Button(this).apply {
                text = "تحميل"
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_download)
                setBackgroundColor(Color.parseColor("#E50914"))
                setOnClickListener {
                    d.dismiss()
                    val name = Util.sanitizeFilename(title) + "." + f.ext
                    if (f.kind == "hls") {
                        val rt = Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers)
                        rt.thumbUrl = poster
                        DownloadCenter.start(this@MainActivity, rt)
                    } else {
                        DownloadCenter.start(this@MainActivity, Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers))
                    }
                    val l = Store.entries(this@MainActivity, "history")
                    l.removeAll { it.url == cur?.url }
                    l.add(0, Store.Entry(title, cur?.url ?: f.url, System.currentTimeMillis()))
                    Store.putEntries(this@MainActivity, "history", l)
                }
            }
            itemLayout.addView(downBtn, LinearLayout.LayoutParams(dp(80), dp(36)).apply { marginStart = dp(8) })

            val playBtn = Button(this).apply {
                text = "تشغيل"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                setOnClickListener {
                    d.dismiss()
                    val name = Util.sanitizeFilename(title) + "." + f.ext
                    if (f.kind == "hls") {
                        val rt = Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers)
                        rt.thumbUrl = poster
                        DownloadCenter.replaceSource(rt, f.url, headers)
                    }
                    val i = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra("uri", f.url)
                        putExtra("title", title)
                        putExtra("headers", headers as java.io.Serializable)
                    }
                    startActivity(i)
                }
            }
            itemLayout.addView(playBtn, LinearLayout.LayoutParams(dp(80), dp(36)).apply { marginStart = dp(8) })

            container.addView(itemLayout)
            val sp = View(this).apply { background = Color.parseColor("#333333").let { android.graphics.drawable.ColorDrawable(it) } }
            container.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }

        val video = list.filter { it.kind == "hls" || Util.kindOf("x." + it.ext) == "video" }
        val audio = list.filter { it.isAudio || Util.kindOf("x." + it.ext) == "audio" }
        val rest = list.filter { !(it.kind == "hls" || Util.kindOf("x." + it.ext) == "video" || it.isAudio || Util.kindOf("x." + it.ext) == "audio") }

        if (video.isNotEmpty()) {
            val tv = TextView(this).apply { text = "الفيديو"; setTextColor(Color.parseColor("#888888")); setPadding(0, dp(8), 0, dp(4)) }
            container.addView(tv)
            video.forEach { row(it) }
        }
        if (audio.isNotEmpty()) {
            val tv = TextView(this).apply { text = "الصوت"; setTextColor(Color.parseColor("#888888")); setPadding(0, dp(8), 0, dp(4)) }
            container.addView(tv)
            audio.forEach { row(it) }
        }
        if (rest.isNotEmpty()) {
            val tv = TextView(this).apply { text = "ملفات أخرى"; setTextColor(Color.parseColor("#888888")); setPadding(0, dp(8), 0, dp(4)) }
            container.addView(tv)
            rest.forEach { row(it) }
        }

        scroll.addView(container)
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)))
        d.setContentView(sheet)
        d.show()
    }

    private fun tabOf(w: WebView): Tab? = tabs.find { it.web === w }

    private fun showTabsDialog() {
        val d = BottomSheetDialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val title = TextView(this).apply {
            text = "علامات التبويب (${tabs.size})"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val addBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            background = null
            setOnClickListener {
                d.dismiss()
                newTab(Prefs.home(this@MainActivity))
            }
        }
        top.addView(addBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        sheet.addView(top)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (t in tabs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setBackgroundColor(if (t === cur) Color.parseColor("#333333") else Color.parseColor("#252525"))
                setOnClickListener {
                    d.dismiss()
                    switchTab(t)
                }
            }

            val tv = TextView(this).apply {
                text = t.title.ifBlank { t.url }
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })

            val close = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                setOnClickListener {
                    d.dismiss()
                    closeTab(t)
                }
            }
            row.addView(close, LinearLayout.LayoutParams(dp(32), dp(32)))

            container.addView(row)
            val sp = View(this).apply { background = Color.parseColor("#333333").let { android.graphics.drawable.ColorDrawable(it) } }
            container.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }

        scroll.addView(container)
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        d.setContentView(sheet)
        d.show()
    }

    private fun showMoreMenu(v: View) {
        val popup = PopupMenu(this, v)
        popup.menu.add("الإعدادات")
        popup.menu.add("السجل")
        popup.menu.add("تحديث")
        popup.menu.add("مشاركة الرابط")
        popup.menu.add("نسخ الرابط")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "الإعدادات" -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                "السجل" -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
                "تحديث" -> { cur?.web?.reload(); true }
                "مشاركة الرابط" -> {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cur?.url ?: "")
                    startActivity(Intent.createChooser(i, "مشاركة"))
                    true
                }
                "نسخ الرابط" -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", cur?.url ?: ""))
                    Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
