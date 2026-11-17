package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nuvio.tv.LeXiaPluginsActivity
import java.util.concurrent.Executors

/** TV-remote-friendly manager for Stremio add-ons and Nuvio JavaScript scraper repositories. */
class MediaAddonsActivity : Activity() {

    private val repository by lazy { MediaAddonRepository(this) }
    private val nuvioRepository by lazy { NuvioJsRepository(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var addButton: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderAddons()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from Nuvio's plugin screen: its repository and provider counts may have changed.
        renderAddons()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(28), dp(44), dp(28))
            setBackgroundColor(Color.rgb(9, 11, 17))
        }

        val header = TextView(this).apply {
            text = "MEDYA KAYNAKLARI"
            textSize = 13f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(125, 151, 255))
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(header)

        val title = TextView(this).apply {
            text = "Eklentiler"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(title)

        val intro = TextView(this).apply {
            text = "Stremio uyumlu bir eklentinin ya da bir Nuvio JS deposunun manifest adresini ekle. LeXia adresi doğrular; film ve dizi kaynakları bu eklentilerde aranır."
            textSize = 17f
            setTextColor(Color.rgb(174, 182, 197))
            setLineSpacing(0f, 1.2f)
        }
        root.addView(intro)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(12))
        }
        val add = action("+  Eklenti ekle", primary = true).apply {
            setOnClickListener { showAddDialog() }
        }
        controls.addView(add)
        val close = action("Kapat").apply {
            setOnClickListener { finish() }
        }
        val closeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(12) }
        controls.addView(close, closeParams)
        root.addView(controls)

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(170, 180, 198))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
        addButton = add
        // The remote starts on the first button; a focused container would swallow every D-pad press.
        add.requestFocus()
    }

    /**
     * Rebuilds the list. The remote stays on the button it was on (each button carries a name),
     * so switching ten scrapers on or off does not send it back to the top ten times.
     */
    private fun renderAddons() {
        val focusedKey = currentFocus?.tag as? String
        list.removeAllViews()
        val addons = repository.installedAddons()
        val nuvioRepos = nuvioRepository.installedRepositories()
        val nuvioCount = nuvioRepos.sumOf { it.scrapers.size }
        status.text = "${addons.size} Stremio eklentisi  ·  ${nuvioRepos.size} Nuvio deposu  ·  $nuvioCount sağlayıcı"

        // Nuvio's plugin repositories are managed on Nuvio's own screen (the nuvio module).
        val nuvioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(17), dp(22), dp(17))
            background = panelBackground()
        }
        nuvioCard.addView(TextView(this).apply {
            text = "Nuvio eklentileri"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        nuvioCard.addView(TextView(this).apply {
            text = if (nuvioRepos.isEmpty()) "Depo ekle, sağlayıcıları aç ya da kapat."
            else "${nuvioRepos.size} depo  ·  $nuvioCount sağlayıcı. Depo ekle, güncelle, sağlayıcıları aç ya da kapat."
            textSize = 15f
            setTextColor(Color.rgb(174, 181, 199))
            setPadding(0, dp(5), 0, 0)
        })
        nuvioCard.addView(action("Nuvio eklentilerini aç").apply {
            tag = "nuvio:open"
            setOnClickListener { startActivity(Intent(this@MediaAddonsActivity, LeXiaPluginsActivity::class.java)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        addCardToList(nuvioCard)

        addons.forEachIndexed { index, addon ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(17), dp(22), dp(17))
                background = panelBackground()
            }
            val name = TextView(this).apply {
                text = addon.name
                textSize = 21f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(name)
            val version = TextView(this).apply {
                val gives = listOfNotNull(
                    "Kaynak".takeIf { "stream" in addon.resources },
                    "Bölüm listesi".takeIf { "meta" in addon.resources },
                    "Altyazı".takeIf { addon.resources.any { it.startsWith("subtitle", true) } },
                    "Katalog".takeIf { "catalog" in addon.resources }
                ).joinToString(", ").ifBlank { "bilgi yok" }
                text = "STREMIO  ·  Sürüm ${addon.version}  ·  Verdiği: $gives"
                textSize = 14f
                setTextColor(Color.rgb(143, 155, 176))
                setPadding(0, dp(5), 0, dp(4))
            }
            card.addView(version)
            val url = TextView(this).apply {
                text = addon.baseUrl
                textSize = 13f
                setTextColor(Color.rgb(133, 164, 225))
                maxLines = 2
            }
            card.addView(url)
            if (addon.description.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = addon.description
                    textSize = 14f
                    setTextColor(Color.rgb(170, 180, 198))
                    maxLines = 2
                    setPadding(0, dp(6), 0, 0)
                })
            }
            // As in Nuvio: switch on or off, order, the add-on's own settings, remove.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun button(label: String, key: String, primary: Boolean = false, click: () -> Unit) =
                row.addView(action(label, primary).apply {
                    tag = "$key:${addon.baseUrl}"
                    setOnClickListener { click() }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            button(if (addon.enabled) "Açık" else "Kapalı", "on", primary = addon.enabled) {
                repository.setEnabled(addon.baseUrl, !addon.enabled)
                renderAddons()
            }
            if (index > 0) button("Yukarı", "up") {
                repository.move(addon.baseUrl, -1)
                renderAddons()
            }
            if (index < addons.lastIndex) button("Aşağı", "down") {
                repository.move(addon.baseUrl, 1)
                renderAddons()
            }
            if (addon.configurable) button("Ayarlarını değiştir", "conf") { showConfigure(addon) }
            button("Kaldır", "rm") { confirmRemove(addon.baseUrl, addon.name) }
            card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            card.alpha = if (addon.enabled) 1f else 0.6f
            addCardToList(card)
        }

        val target = focusedKey?.let { list.findViewWithTag<View>(it) }
        list.post {
            if (isFinishing) return@post
            if (target != null) target.requestFocus()
            else if (root.findFocus() == null) addButton.requestFocus()
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "https://sunucu-adresi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Kaynak eklentisi ekle")
            .setMessage("Stremio manifest adresini veya Nuvio JS deposunun manifest.json adresini gir.")
            .setView(input)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Doğrula ve ekle", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text?.toString().orEmpty().trim()
                if (address.isBlank()) {
                    input.error = "Adres gerekli"
                    return@setOnClickListener
                }
                if (busy) return@setOnClickListener
                busy = true
                status.text = "Manifest doğrulanıyor…"
                dialog.dismiss()
                worker.execute {
                    try {
                        val addon = try {
                            repository.addAddon(address)
                        } catch (stremioError: Exception) {
                            // Not a Stremio add-on: perhaps a Nuvio JS repository. If it is neither,
                            // both reasons are shown; either may be the one that matters.
                            val nuvio = try {
                                nuvioRepository.addRepository(address)
                            } catch (nuvioError: Exception) {
                                val reasons = listOf(stremioError.message, nuvioError.message)
                                    .filterNotNull().filter { it.isNotBlank() }.distinct()
                                throw IllegalStateException(
                                    "Eklenti eklenemedi. " + reasons.joinToString("  ·  ").ifBlank { "Adres tanınmadı." }
                                )
                            }
                            runOnUiThread {
                                busy = false
                                status.text = "${nuvio.name} eklendi · ${nuvio.scrapers.size} sağlayıcı"
                                renderAddons()
                            }
                            return@execute
                        }
                        runOnUiThread {
                            busy = false
                            status.text = "${addon.name} Stremio eklentisi eklendi."
                            renderAddons()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            busy = false
                            status.text = e.message ?: "Eklenti eklenemedi."
                        }
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(620), LinearLayout.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
    }

    /**
     * The add-on's settings live on its own web page. It is opened on the phone; the address it
     * gives at the end is entered here with "Eklenti ekle", and replaces this one in its place.
     */
    private fun showConfigure(addon: InstalledMediaAddon) {
        AlertDialog.Builder(this)
            .setTitle("${addon.name} · Ayarlar")
            .setMessage(
                "Bu adresi telefonunda aç ve ayarları yap:\n\n${addon.configureUrl}\n\n" +
                    "Sayfanın verdiği yeni eklenti adresini \"Eklenti ekle\" ile buraya gir; eklenti listede aynı yerinde güncellenir."
            )
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun confirmRemove(url: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Eklenti kaldırılsın mı?")
            .setMessage("$name kaynak listesinden kaldırılacak.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaldır") { _, _ ->
                repository.removeAddon(url)
                renderAddons()
            }
            .show()
    }

    private fun infoCard(title: String, description: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = panelBackground()
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = description
            textSize = 16f
            setTextColor(Color.rgb(169, 179, 195))
            setPadding(0, dp(7), 0, 0)
        })
        return box
    }

    private fun addCardToList(view: View) {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        list.addView(view, params)
    }

    private fun action(label: String, primary: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(13), dp(20), dp(13))
        val idle = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) Color.rgb(66, 91, 180) else Color.rgb(34, 39, 51))
            setStroke(dp(1), if (primary) Color.rgb(126, 150, 255) else Color.rgb(62, 71, 91))
        }
        // Focused: white with dark text, so the selected button is unmistakable from the sofa.
        val focusedLook = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        background = idle
        setOnFocusChangeListener { view, focused ->
            view.background = if (focused) focusedLook else idle
            (view as TextView).setTextColor(if (focused) Color.rgb(9, 11, 17) else Color.WHITE)
            view.scaleX = if (focused) 1.04f else 1f
            view.scaleY = if (focused) 1.04f else 1f
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.rgb(20, 24, 34))
        setStroke(dp(1), Color.rgb(42, 49, 65))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
