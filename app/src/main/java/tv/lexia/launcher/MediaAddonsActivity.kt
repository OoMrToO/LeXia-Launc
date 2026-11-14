package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
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
import java.util.concurrent.Executors

/** TV-remote-friendly manager for user-installed Stremio-compatible source add-ons. */
class MediaAddonsActivity : Activity() {

    private val repository by lazy { MediaAddonRepository(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
            isFocusableInTouchMode = true
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
            text = "Uyumlu bir Stremio kaynak eklentisinin manifest adresini ekle. LeXia, manifesti doğrular ve film/dizi kaynaklarını buradan arar."
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
        val add = action("＋  Eklenti ekle", primary = true).apply {
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
        root.requestFocus()
    }

    private fun renderAddons() {
        list.removeAllViews()
        val addons = repository.installedAddons()
        status.text = if (addons.isEmpty()) {
            "Henüz eklenti kurulmadı."
        } else {
            "${addons.size} eklenti kurulu"
        }

        if (addons.isEmpty()) {
            list.addView(
                infoCard(
                    "İlk kaynağını ekle",
                    "Özel veya topluluk tarafından sunulan uyumlu eklentilerin adresini buraya ekleyebilirsin."
                )
            )
        }

        addons.forEach { addon ->
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
                text = "Sürüm ${addon.version}  ·  ${addon.types.joinToString().ifBlank { "tür bilgisi yok" }}"
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
            val remove = action("Eklentiyi kaldır").apply {
                setOnClickListener { confirmRemove(addon.baseUrl, addon.name) }
            }
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            card.addView(remove, p)
            addCardToList(card)
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
            .setMessage("Eklentinin ana adresini veya /manifest.json adresini gir.")
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
                        val addon = repository.addAddon(address)
                        runOnUiThread {
                            busy = false
                            status.text = "${addon.name} eklendi."
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
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) Color.rgb(66, 91, 180) else Color.rgb(34, 39, 51))
            setStroke(dp(1), if (primary) Color.rgb(126, 150, 255) else Color.rgb(62, 71, 91))
        }
        setOnFocusChangeListener { view, focused ->
            view.scaleX = if (focused) 1.04f else 1f
            view.scaleY = if (focused) 1.04f else 1f
            view.alpha = if (focused) 1f else 0.9f
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
