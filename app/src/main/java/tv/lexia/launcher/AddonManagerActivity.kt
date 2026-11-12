package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** TV-friendly manager for HTTPS addon repositories and their metadata catalogues. */
class AddonManagerActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 24).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(android.graphics.Color.rgb(14, 16, 20))
        }
        val title = TextView(this).apply {
            text = "EKLENTİLER"
            textSize = 26f
            setTextColor(-1)
        }
        root.addView(title)
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFB8BEC9.toInt())
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(status)
        val add = Button(this).apply {
            text = "Eklenti adresi ekle"
            setOnClickListener { askUrl() }
        }
        root.addView(add)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        add.requestFocus()
        render(list)
    }

    private fun render(list: LinearLayout) {
        list.removeAllViews()
        val urls = Addons.urls(this)
        status.text = if (urls.isEmpty()) "Henüz eklenti eklenmedi." else "${urls.size} depo kayıtlı"
        urls.forEach { address ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 14, 18, 14)
                setBackgroundColor(0xFF252A33.toInt())
            }
            val label = TextView(this).apply {
                text = address
                textSize = 15f
                setTextColor(-1)
            }
            card.addView(label)
            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            val inspect = Button(this).apply {
                text = "Katalogları yükle"
                setOnClickListener { loadCatalogs(address) }
            }
            val remove = Button(this).apply {
                text = "Kaldır"
                setOnClickListener {
                    Addons.remove(this@AddonManagerActivity, address)
                    render(list)
                }
            }
            actions.addView(inspect)
            actions.addView(remove)
            card.addView(actions)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        }
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://ornek.com"
            singleLine = true
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Eklenti deposu")
            .setMessage("Manifest adresini veya depo adresini gir.")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Ekle") { _, _ ->
                try {
                    Addons.add(this, input.text.toString())
                    recreate()
                } catch (e: Exception) {
                    status.text = e.message ?: "Adres eklenemedi."
                }
            }.show()
    }

    private fun loadCatalogs(address: String) {
        status.text = "Manifest ve kataloglar yükleniyor…"
        worker.execute {
            try {
                val addon = Addons.fetchManifest(address)
                val summary = buildString {
                    append(addon.name)
                    if (addon.version.isNotBlank()) append(" · v${addon.version}")
                    append("\n")
                    if (addon.catalogs.isEmpty()) {
                        append("Katalog bulunamadı.")
                    } else {
                        append("${addon.catalogs.size} katalog bulundu.")
                        addon.catalogs.forEach { catalog ->
                            val items = runCatching { Addons.fetchCatalog(addon, catalog) }.getOrNull()
                            append("\n\n• ${catalog.name}: ")
                            if (items == null) {
                                append("katalog okunamadı")
                            } else {
                                append("${items.size} kayıt")
                                items.take(12).forEach { item ->
                                    append("\n   – ${item.name.ifBlank { item.id }}")
                                }
                            }
                        }
                    }
                    append("\n\nYalnızca katalog/metadata okunur; yayın kaynağı uçları çağrılmaz.")
                }
                runOnUiThread { status.text = summary }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Eklenti okunamadı." }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
