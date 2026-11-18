package com.example.videobrowser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        "var dur=v.duration||0;" +
        // استبعاد الفيديوهات القصيرة جداً (مثل الإعلانات والمعاينات)
        "if(dur > 0 && dur < 4) continue;" +
        "var r=v.getBoundingClientRect();" +
        "var w=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0));" +
        "var h=Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));var area=w*h;" +
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
    private lateinit var content: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fab: FrameLayout
    private lateinit var fabIcon: ImageView
    private lateinit var badge: TextView
    private lateinit var tabBtn: TextView
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var desktop = false
    private lateinit var defaultUa: String
    private var pathView: TextView? = null
    private var refreshId: String? = null
    private lateinit var refreshBar: TextView

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            Prefs.setFolder(this, uri.toString())
            pathView?.text = pathText()
        }
    }

    private val listLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        val u = d?.getStringExtra("url")
        if (u != null) {
            if (d.getBooleanExtra("newTab", false)) newTab(u)
            else cur?.web?.loadUrl(u)
        }
    }

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
        desktop = Prefs.desktop(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1e1e1e"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = imgBtn(R.drawable.ic_back) { cur?.let { if (it.web.canGoBack()) it.web.goBack() } }
        val fwd = imgBtn(R.drawable.ic_forward) { cur?.let { if (it.web.canGoForward()) it.web.goForward() } }
        val home = imgBtn(R.drawable.ic_home) { cur?.web?.loadUrl(Prefs.home(this)) }

        urlBar = EditText(this).apply {
            hint = "ابحث أو اكتب رابط URL"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    navigate(text.toString())
                    true
                } else false
            }
        }

        val refresh = imgBtn(R.drawable.ic_refresh) { cur?.web?.reload() }

        top.addView(back, lp(36, 36))
        top.addView(fwd, lp(36, 36))
        top.addView(home, lp(36, 36))
        top.addView(urlBar, lp(0, 42, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        top.addView(refresh, lp(36, 36))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_fg)
            visibility = View.GONE
        }

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1e1e1e"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }

        val historyBtn = imgBtn(R.drawable.ic_history) {
            listLauncher.launch(Intent(this@MainActivity, HistoryActivity::class.java))
        }
        val downloadsBtn = imgBtn(R.drawable.ic_download) {
            startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
        }
        val settingsBtn = imgBtn(R.drawable.ic_settings) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }

        tabBtn = TextView(this).apply {
            text = "1"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.ic_tabs)
            setOnClickListener { showTabsMenu() }
        }

        bottom.addView(historyBtn, lp(40, 40, 1f))
        bottom.addView(downloadsBtn, lp(40, 40, 1f))
        bottom.addView(tabBtn, lp(40, 40, 1f))
        bottom.addView(settingsBtn, lp(40, 40, 1f))

        root.addView(top, lp(MATCH, WRAP))
        root.addView(progress, lp(MATCH, 4))
        root.addView(content)
        root.addView(bottom, lp(MATCH, WRAP))

        val fl = FrameLayout(this)
        fl.addView(root)

        fab = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.oval
                setColor(Color.parseColor("#E50914"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            setOnClickListener { showFormats() }
        }

        fabIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            imageTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.white)
        }

        badge = TextView(this).apply {
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        fab.addView(fabIcon, lp(28, 28, Gravity.CENTER))
        val bp = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        fab.addView(badge, bp)

        val fabContainer = FrameLayout(this)
        val fparams = FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(20)
            bottomMargin = dp(70)
        }
        fabContainer.addView(fab, fparams)
        fl.addView(fabContainer)

        setContentView(fl)

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

        val saved = Store.entries(this, "tabs")
        if (saved.isNotEmpty()) {
            for (e in saved) newTab(e.url, false, e.title)
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
        saveTabs()
    }

    private fun saveTabs() {
        val list = tabs.filter { it.url.startsWith("http") }.map { Store.Entry(it.title, it.url, 0L) }
        Store.putEntries(this, "tabs", list)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(w), dp(h), weight)

    private fun lp(w: Int, h: Int, gravity: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(dp(w), dp(h), gravity)

    private fun imgBtn(res: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(res)
            background = null
            setPadding(0, 0, 0, 0)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { onClick() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String, load: Boolean = true, title: String = ""): Tab {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        defaultUa = s.userAgentString
        s.userAgentString = if (desktop) DESKTOP_UA else defaultUa

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    view.loadUrl(u, mapOf("X-Requested-With" to ""))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val t = tabOf(view) ?: return
                t.url = url
                t.sawBlob = false
                t.media.clear()
                t.activeId = null
                t.resolvedFormats = null
                if (t === cur) {
                    urlBar.setText(url)
                    updateFab()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                val t = tabOf(view) ?: return
                t.url = url
                t.title = view.title ?: url
                addHistory(t.title, url)
                if (t === cur) urlBar.setText(url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                // حظر طلبات الإعلانات وشبكات التتبع وإرجاع استجابة فارغة فوراً
                if (Net.isAdOrPreview(u)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                if ((MEDIA_REGEX.containsMatchIn(u) || u.contains("videoplayback") || u.contains(".m3u8"))) {
                    ui.post { tabOf(view)?.let { rec(it, u) } }
                }
                return null
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabOf(view) === cur) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val t = tabOf(view) ?: return
                if (title != null) t.title = title
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                val decor = window.decorView as FrameLayout
                decor.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
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

        val t = Tab(w)
        if (title.isNotBlank()) t.title = title
        if (url.isNotBlank()) t.url = url
        tabs.add(t)
        content.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        switchTab(t)
        if (load && url.isNotBlank()) w.loadUrl(url)
        tabBtn.text = tabs.size.toString()
        return t
    }

    private fun hideCustomView() {
        if (customView == null) return
        val decor = window.decorView as FrameLayout
        decor.removeView(customView)
        customView = null
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customCb?.onCustomViewHidden()
        customCb = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
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
        content.removeView(t.web)
        t.web.destroy()
        tabs.remove(t)
        tabBtn.text = tabs.size.toString()
        if (tabs.isEmpty()) {
            finish()
        } else {
            switchTab(tabs.last())
        }
    }

    private fun tabOf(w: WebView): Tab? = tabs.find { it.web === w }

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
        m["User-Agent"] = if (desktop) DESKTOP_UA else cur?.web?.settings?.userAgentString ?: DESKTOP_UA
        return m
    }

    private fun updateFab() {
        val t = cur
        if (t == null) {
            fab.visibility = View.GONE
            return
        }
        val count = t.media.size + (t.resolvedFormats?.size ?: 0)
        val has = count > 0 || t.isResolving
        fab.visibility = if (has) View.VISIBLE else View.GONE
        if (count > 0) {
            badge.text = count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun showFormats() {
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
            setBackgroundColor(Color.parseColor("#1e1e1e"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2a2a2a"))
        }
        head.addView(img, lp(64, 64).apply { marginEnd = dp(12) })
        if (poster.isNotBlank()) {
            ThumbLoader.loadUrl(poster, headers) { b ->
                if (b != null) img.setImageBitmap(b)
            }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pathView = TextView(this).apply {
            text = pathText()
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }
        val tvName = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        col.addView(pathView)
        col.addView(tvName)
        head.addView(col, lp(0, WRAP, 1f))
        sheet.addView(head)

        val chg = TextView(this).apply {
            text = "تغيير مجلد التنزيل"
            textSize = 13f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, 0, 0, dp(12))
            setOnClickListener {
                pickFolder.launch(null)
            }
        }
        sheet.addView(chg)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun row(f: Format) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setBackgroundColor(Color.parseColor("#252525"))
            }

            val kind = Util.kindOf("x." + f.ext)
            val ic = ImageView(this).apply {
                setImageResource(when (kind) {
                    "video" -> R.drawable.ic_video
                    "audio" -> R.drawable.ic_music
                    else -> R.drawable.ic_file
                })
            }
            r.addView(ic, lp(24, 24).apply { marginEnd = dp(12) })

            val dsc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val l1 = TextView(this).apply {
                text = f.label + (if (f.resolution.isNotBlank()) " (${f.resolution})" else "")
                textSize = 14f
                setTextColor(Color.WHITE)
            }
            val l2 = TextView(this).apply {
                val parts = ArrayList<String>()
                if (f.sizeStr.isNotBlank()) parts.add(f.sizeStr)
                if (f.ext.isNotBlank()) parts.add(f.ext.uppercase())
                text = parts.joinToString(" • ")
                textSize = 11f
                setTextColor(Color.parseColor("#aaaaaa"))
            }
            dsc.addView(l1)
            dsc.addView(l2)
            r.addView(dsc, lp(0, WRAP, 1f))

            val btnDown = Button(this).apply {
                text = "تحميل"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E50914"))
                setOnClickListener {
                    d.dismiss()
                    val name = Util.sanitizeFilename(title) + "." + f.ext
                    if (f.kind == "hls") {
                        val task = Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers)
                        task.thumbUrl = poster
                        DownloadCenter.start(this@MainActivity, task)
                    } else {
                        DownloadCenter.start(this@MainActivity, Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers))
                    }
                }
            }
            r.addView(btnDown, lp(72, 34).apply { marginStart = dp(6) })

            val btnPlay = Button(this).apply {
                text = "تشغيل"
                textSize = 12f
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#e0e0e0"))
                setOnClickListener {
                    d.dismiss()
                    val name = Util.sanitizeFilename(title) + "." + f.ext
                    if (f.kind == "hls") {
                        val task = Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers)
                        task.thumbUrl = poster
                        DownloadCenter.replaceSource(task, f.url, headers)
                    }
                    val i = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra("uri", f.url)
                        putExtra("title", title)
                        putExtra("headers", headers as java.io.Serializable)
                    }
                    startActivity(i)
                }
            }
            r.addView(btnPlay, lp(72, 34).apply { marginStart = dp(6) })

            container.addView(r)
            val sp = View(this).apply { background = ColorDrawable(Color.parseColor("#333333")) }
            container.addView(sp, lp(MATCH, 1))
        }

        val video = list.filter { it.kind == "hls" || Util.kindOf("x." + it.ext) == "video" }
        val audio = list.filter { it.isAudio || Util.kindOf("x." + it.ext) == "audio" }
        val rest = list.filter { !(it.kind == "hls" || Util.kindOf("x." + it.ext) == "video" || it.isAudio || Util.kindOf("x." + it.ext) == "audio") }

        if (video.isNotEmpty()) {
            container.addView(tvSection("الفيديو"))
            video.forEach { row(it) }
        }
        if (audio.isNotEmpty()) {
            container.addView(tvSection("الصوت"))
            audio.forEach { row(it) }
        }
        if (rest.isNotEmpty()) {
            container.addView(tvSection("ملفات أخرى"))
            rest.forEach { row(it) }
        }

        scroll.addView(container)
        sheet.addView(scroll, lp(MATCH, 380))
        d.setContentView(sheet)
        d.show()
    }

    private fun tvSection(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Color.parseColor("#888888"))
        setPadding(dp(4), dp(8), dp(4), dp(4))
    }

    private fun pathText(): String {
        val f = Prefs.downloadDir(this)
        return "المسار: " + (if (f == null) "Download/VideoBrowser" else (Uri.parse(f).lastPathSegment ?: f))
    }

    private fun showTabsMenu() {
        val d = BottomSheetDialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1e1e1e"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val tv = TextView(this).apply {
            text = "علامات التبويب (${tabs.size})"
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        top.addView(tv, lp(0, WRAP, 1f))

        val add = imgBtn(R.drawable.ic_add) {
            d.dismiss()
            newTab(Prefs.home(this@MainActivity))
        }
        top.addView(add, lp(36, 36))
        sheet.addView(top)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (t in tabs) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setBackgroundColor(if (t === cur) Color.parseColor("#333333") else Color.parseColor("#252525"))
                setOnClickListener {
                    d.dismiss()
                    switchTab(t)
                }
            }
            val titleTv = TextView(this).apply {
                text = t.title.ifBlank { t.url }
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            r.addView(titleTv, lp(0, WRAP, 1f).apply { marginEnd = dp(8) })

            val close = imgBtn(R.drawable.ic_close) {
                d.dismiss()
                closeTab(t)
            }
            r.addView(close, lp(28, 28))

            container.addView(r)
            container.addView(View(this).apply { background = ColorDrawable(Color.parseColor("#333333")) }, lp(MATCH, 1))
        }

        scroll.addView(container)
        sheet.addView(scroll, lp(MATCH, 300))
        d.setContentView(sheet)
        d.show()
    }
}
