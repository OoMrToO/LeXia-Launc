package tv.lexia.launcher

import android.app.Activity
import android.widget.Toast

/** Asks for a city for the weather: "Otomatik" or a search through Open-Meteo's place names. */
object CityPicker {
    fun choose(a: Activity, prefs: Prefs, done: () -> Unit) {
        Dialogs.choose(a, "Konum", listOf("Otomatik", "Şehir ara…")) { i ->
            if (i == 0) {
                prefs.city = ""
                done()
                return@choose
            }
            Dialogs.input(a, "Şehir adı", "") { q ->
                if (q.isEmpty()) return@input
                Thread {
                    val body = Net.get(
                        "https://geocoding-api.open-meteo.com/v1/search?count=6&language=tr&name=" +
                            java.net.URLEncoder.encode(q, "UTF-8")
                    )
                    val found = ArrayList<Triple<String, Double, Double>>()
                    try {
                        val arr = org.json.JSONObject(body ?: "{}").optJSONArray("results")
                        for (k in 0 until (arr?.length() ?: 0)) {
                            val o = arr!!.getJSONObject(k)
                            val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country"))
                                .filter { it.isNotEmpty() }.distinct().joinToString(", ")
                            found.add(Triple(label, o.getDouble("latitude"), o.getDouble("longitude")))
                        }
                    } catch (e: Exception) {
                    }
                    a.runOnUiThread {
                        if (found.isEmpty()) {
                            Glass.toast(a, "Şehir bulunamadı")
                        } else {
                            Dialogs.choose(a, "Şehrini seç", found.map { it.first }) { j ->
                                val c = found[j]
                                prefs.city = c.first.substringBefore(",")
                                prefs.cityLat = c.second.toFloat()
                                prefs.cityLon = c.third.toFloat()
                                done()
                            }
                        }
                    }
                }.start()
            }
        }
    }
}
