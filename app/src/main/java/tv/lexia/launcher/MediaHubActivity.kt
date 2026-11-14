package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** TV-friendly entry point for installing addons and opening their catalogs. */
class MediaHubActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: AddonStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AddonStore(this)
        val ui = Ui(this)
        val root = ui.vbox().apply {
            setBackgroundColor(Color.rgb(10, 12, 17))
            setPadding(ui.u(72), ui.u(48), ui.u(72), ui.u(40))
        }
        root.addView(ui.text("LeXia · Medya", 42, C.TEXT, 700))
        root.addView(ui.text("Addon katalogları ve kişisel medya kaynakları", 20, C.TEXT2, 400), ui.ll(ui.wrap, ui.wrap, top = 8))
        val actions = ui.hbox().apply { clipChildren = false }
        val add = ui.pill("+   Addon adresi ekle", 58, 21, primary = true).apply { setOnClickListener { promptAddonUrl() } }
        val search = ui.pill("⌕   Kataloglarda ara", 58, 21).apply { setOnClickListener { promptSearch() } }
        actions.addView(add)
        actions.addView(search, ui.ll(ui.wrap, ui.wrap, left = 16))
        root.addView(actions, ui.ll(ui.wrap, ui.wrap, top = 28))
        status = ui.text("", 18, C.TEXT2, 500)
        root.addView(status, ui.ll(ui.wrap, ui.wrap, top = 18))
        content = ui.vbox().apply { clipChildren = false; clipToPadding = false }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, ui.ll(ui.match, 0, top = 20, weight = 1f))
        setContentView(root)
        render()
        (if (store.installed().isEmpty()) add else search).requestFocus()
    }

    private fun promptAddonUrl() {
        val input = EditText(this).apply {
            hint = "https://example.org/manifest.json"
            singleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(24, 18, 24, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("Addon ekle")
            .setMessage("Yalnızca güvendiğiniz, kullanma izniniz olan addon adreslerini ekleyin.")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Ekle", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val address = input.text.toString().trim()
                        if (!store.isValidManifestUrl(address)) {
                            status.text = "HTTP veya HTTPS ile başlayan geçerli bir addon adresi yazın."
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        install(address)
                    }
                }
                dialog.show()
            }
    }

    private fun install(address: String) {
        status.text = "Addon kontrol ediliyor…"
        worker.execute {
            val result = runCatching {
                val body = AddonHttp.get(address)
                val manifest = AddonManifest.fromManifestUrl(address, body)
                require(manifest.catalogs.isNotEmpty()) { "Bu addon manifestinde katalog bulunamadı." }
                store.save(manifest)
                manifest.name
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                status.text = result.fold({ "${it} eklendi." }, { "Addon eklenemedi: ${it.message ?: "bağlantı hatası"}" })
                render()
            }
        }
    }

    private fun promptSearch() {
        val addons = store.enabledAddons()
        val catalogs = addons.flatMap { addon -> addon.catalogs.filter { it.type == AddonType.SEARCH || it.isSearchOnly }.map { addon to it } }
        if (catalogs.isEmpty()) {
            status.text = "Arama kataloğu sağlayan etkin addon bulunamadı."
            return
        }
        val input = EditText(this).apply {
            hint = "Film veya dizi adı"
            singleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        AlertDialog.Builder(this).setTitle("Kataloglarda ara").setView(input)
            .setNegativeButton("Vazgeç", null).setPositiveButton("Ara") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) openCatalog(catalogs.first().first, catalogs.first().second, query)
            }.show()
    }

    private fun render() {
        content.removeAllViews()
        val ui = Ui(this)
        val addons = store.installed()
        if (addons.isEmpty()) {
            content.addView(ui.text("Henüz addon yok. Bir manifest adresi ekleyerek katalogları burada açabilirsiniz.", 22, C.TEXT2, 400), ui.ll(ui.match, ui.wrap, top = 24))
            return
        }
        addons.forEach { addon ->
            val panel = ui.vbox().apply {
                setPadding(ui.u(22), ui.u(20), ui.u(22), ui.u(20))
                background = ui.round(Color.rgb(24, 27, 35), 20)
                clipChildren = false
            }
            val titleRow = ui.hbox()
            titleRow.addView(ui.text(addon.name, 26, C.TEXT, 700), ui.ll(0, ui.wrap, weight = 1f))
            val toggle = ui.pill(if (addon.enabled) "Etkin" else "Kapalı", 48, 18).apply {
                setOnClickListener { store.setEnabled(addon.normalizedId, !addon.enabled); render() }
            }
            titleRow.addView(toggle)
            panel.addView(titleRow)
            addon.description?.let { panel.addView(ui.text(it, 17, C.TEXT2, 400), ui.ll(ui.match, ui.wrap, top = 6)) }
            panel.addView(ui.text("${addon.catalogs.size} katalog  ·  v${addon.version}", 16, C.TEXT3, 500), ui.ll(ui.wrap, ui.wrap, top = 6))
            if (addon.enabled) addon.catalogs.forEach { catalog ->
                val button = ui.pill("${catalog.name}   ›", 50, 18).apply {
                    setOnClickListener { openCatalog(addon, catalog) }
                }
                panel.addView(button, ui.ll(ui.wrap, ui.wrap, top = 10))
            }
            val remove = ui.pill("Addon'u kaldır", 46, 17).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MediaHubActivity).setTitle("Addon kaldırılsın mı?")
                        .setMessage(addon.name).setNegativeButton("İptal", null)
                        .setPositiveButton("Kaldır") { _, _ -> store.remove(addon.normalizedId); render() }.show()
                }
            }
            panel.addView(remove, ui.ll(ui.wrap, ui.wrap, top = 12))
            content.addView(panel, ui.ll(ui.match, ui.wrap, top = 16))
        }
    }

    private fun openCatalog(addon: AddonManifest, catalog: AddonCatalog, search: String = "") {
        startActivity(Intent(this, MediaCatalogActivity::class.java)
            .putExtra(MediaCatalogActivity.EXTRA_ADDON, addon.toJson().toString())
            .putExtra(MediaCatalogActivity.EXTRA_CATALOG, JSONObjectCompat.catalog(catalog))
            .putExtra(MediaCatalogActivity.EXTRA_SEARCH, search))
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

private object JSONObjectCompat {
    fun catalog(catalog: AddonCatalog): String = org.json.JSONObject()
        .put("id", catalog.id).put("name", catalog.name)
        .put("type", catalog.type.name).put("endpoint", catalog.endpoint)
        .put("searchOnly", catalog.isSearchOnly).put("showInHome", catalog.isHomeVisible)
        .put("requiresQuery", catalog.requiresQuery).toString()
}
