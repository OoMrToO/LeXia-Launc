package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Catalog results and stream selection for one installed addon catalog. */
class MediaCatalogActivity : Activity() {
    companion object {
        const val EXTRA_ADDON = "lexia.media.ADDON"
        const val EXTRA_CATALOG = "lexia.media.CATALOG"
        const val EXTRA_SEARCH = "lexia.media.SEARCH"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var addon: AddonManifest
    private lateinit var catalog: AddonCatalog
    private lateinit var itemsBox: LinearLayout
    private lateinit var message: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            addon = AddonManifest.fromJson(JSONObject(intent.getStringExtra(EXTRA_ADDON) ?: "{}"))
            catalog = AddonCatalog.fromJson(JSONObject(intent.getStringExtra(EXTRA_CATALOG) ?: "{}"))
            query = intent.getStringExtra(EXTRA_SEARCH).orEmpty()
        } catch (_: Exception) {
            finish(); return
        }
        val ui = Ui(this)
        val root = ui.vbox().apply {
            setBackgroundColor(Color.rgb(10, 12, 17))
            setPadding(ui.u(72), ui.u(48), ui.u(72), ui.u(36))
        }
        root.addView(ui.text(catalog.name, 40, C.TEXT, 700))
        root.addView(ui.text(addon.name + if (query.isNotBlank()) "  ·  $query" else "", 18, C.TEXT2, 400), ui.ll(ui.wrap, ui.wrap, top = 8))
        message = ui.text("Katalog yükleniyor…", 18, C.TEXT2, 500)
        root.addView(message, ui.ll(ui.wrap, ui.wrap, top = 20))
        itemsBox = ui.vbox().apply { clipChildren = false; clipToPadding = false }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(itemsBox)
        }
        root.addView(scroll, ui.ll(ui.match, 0, top = 12, weight = 1f))
        setContentView(root)
        loadCatalog()
    }

    private fun loadCatalog() {
        worker.execute {
            val result = runCatching {
                val url = AddonHttp.catalogUrl(addon, catalog, query)
                MediaCatalogItem.parseList(AddonHttp.get(url), catalog.type)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val values = result.getOrElse {
                    message.text = "Katalog açılamadı: ${it.message ?: "bağlantı hatası"}"
                    return@runOnUiThread
                }
                message.text = if (values.isEmpty()) "Bu katalogda içerik bulunamadı." else "${values.size} yapım"
                render(values)
            }
        }
    }

    private fun render(items: List<MediaCatalogItem>) {
        itemsBox.removeAllViews()
        val ui = Ui(this)
        items.forEach { item ->
            val card = ui.vbox().apply {
                isFocusable = true
                isClickable = true
                setPadding(ui.u(22), ui.u(18), ui.u(22), ui.u(18))
                background = ui.round(Color.rgb(24, 27, 35), 18)
                setOnFocusChangeListener { view, focused ->
                    view.scaleX = if (focused) 1.025f else 1f
                    view.scaleY = if (focused) 1.025f else 1f
                    view.background = ui.round(if (focused) Color.rgb(45, 50, 62) else Color.rgb(24, 27, 35), 18)
                }
                setOnClickListener { showDetails(item) }
            }
            card.addView(ui.text(item.title, 24, C.TEXT, 700))
            val line = listOfNotNull(item.year.takeIf(String::isNotBlank), if (item.isSeries) "Dizi" else "Film").joinToString("  ·  ")
            card.addView(ui.text(line, 16, C.TEXT3, 500), ui.ll(ui.wrap, ui.wrap, top = 5))
            if (item.overview.isNotBlank()) card.addView(ui.text(item.overview, 16, C.TEXT2, 400).apply { maxLines = 2 }, ui.ll(ui.match, ui.wrap, top = 8))
            itemsBox.addView(card, ui.ll(ui.match, ui.wrap, top = 10))
        }
        itemsBox.getChildAt(0)?.requestFocus()
    }

    private fun showDetails(item: MediaCatalogItem) {
        val details = buildString {
            append(item.title)
            if (item.year.isNotBlank()) append("\n${item.year}")
            if (item.overview.isNotBlank()) append("\n\n${item.overview}")
        }
        AlertDialog.Builder(this).setTitle("Yapım bilgisi").setMessage(details)
            .setNegativeButton("Kapat", null)
            .setPositiveButton(if (item.streamUrl != null) "Oynat" else "Kaynakları bul") { _, _ ->
                item.streamUrl?.let { MediaPlayerActivity.open(this, it, item.title) } ?: loadStreams(item)
            }.show()
    }

    private fun loadStreams(item: MediaCatalogItem) {
        message.text = "Oynatma kaynakları aranıyor…"
        worker.execute {
            val result = runCatching { AddonHttp.streams(AddonHttp.get(AddonHttp.streamUrl(addon, item))) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val sources = result.getOrElse {
                    message.text = "Kaynaklar alınamadı: ${it.message ?: "bağlantı hatası"}"
                    return@runOnUiThread
                }
                if (sources.isEmpty()) {
                    message.text = "Bu addon bu içerik için oynatma kaynağı sunmadı."
                    return@runOnUiThread
                }
                AlertDialog.Builder(this).setTitle("Kaynak seç · ${item.title}")
                    .setItems(sources.map { it.first }.toTypedArray()) { _, which ->
                        MediaPlayerActivity.open(this, sources[which].second, item.title)
                    }.setNegativeButton("İptal", null).show()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
