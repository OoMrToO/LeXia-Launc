package tv.lexia.launcher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Listem's backup, with no account: a code like "LX-7K4P-92QM" is made on the TV. The lists are
 * locked (AES-GCM) with a key from that code and kept on the site under a hash of it, so neither the
 * site nor its owner can read them. The code itself is also kept in a small preferences file that
 * Android's own backup (the TV's Google account) may carry over to a new install.
 * A backup not refreshed for 90 days is removed by the site; using the TV refreshes it weekly.
 */
object Backup {
    private const val FILE = "lexia_backup"
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val ENDPOINT = Net.OLD_SITE + "/api/backup"

    private fun store(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The code, "LX-XXXX-XXXX", or null when backups are off. */
    fun code(ctx: Context): String? = store(ctx).getString("code", null)?.takeIf { valid(it) }

    fun lastAt(ctx: Context): Long = store(ctx).getLong("at", 0L)

    fun valid(code: String) = Regex("^LX-[$ALPHABET]{4}-[$ALPHABET]{4}$").matches(code)

    /** "lx 7k4p 92qm", "LX7K4P92QM" → "LX-7K4P-92QM"; null if it is not a code. */
    fun normalize(raw: String): String? {
        val s = raw.uppercase().filter { it.isLetterOrDigit() }.removePrefix("LX")
        if (s.length != 8) return null
        val c = "LX-" + s.take(4) + "-" + s.drop(4)
        return if (valid(c)) c else null
    }

    private fun newCode(): String {
        val r = SecureRandom()
        val s = (1..8).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        return "LX-" + s.take(4) + "-" + s.drop(4)
    }

    /** Where the backup lives on the site: a hash of the code, so the site never sees the code. */
    private fun id(code: String): String = hex(MessageDigest.getInstance("SHA-256").digest(("lexia-id:" + code).toByteArray()))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun key(code: String): SecretKeySpec {
        val salt = "lexia-backup-v1".toByteArray()
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val k = f.generateSecret(PBEKeySpec(code.toCharArray(), salt, 20000, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun lock(code: String, text: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun unlock(code: String, blob: String): String? = try {
        val all = Base64.decode(blob.trim(), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** What is kept: Listem, reminders, watched, liked, turned down, lately opened, and when each Listem film came in. */
    private fun payload(p: Prefs): String = JSONObject()
        .put("v", 1)
        .put("watch", JSONArray(p.watchlist.map { it.toJson() }))
        .put("remind", JSONArray(p.reminders.map { it.toJson() }))
        .put("watched", JSONArray(p.watched.toList()))
        .put("liked", JSONObject(p.liked.mapValues { JSONArray(it.value) }))
        .put("disliked", JSONObject(p.disliked.mapValues { JSONArray(it.value) }))
        .put("recent", JSONArray(p.recent.map { it.toJson() }))
        .put("added", JSONObject(p.listAdded))
        .toString()

    private fun apply(p: Prefs, text: String): Boolean = try {
        val o = JSONObject(text)
        fun picks(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { Pick.from(a.getJSONObject(it)) } } ?: emptyList()
        fun genres(n: String): Map<String, List<String>> = o.optJSONObject(n)?.let { m ->
            m.keys().asSequence().associateWith { k -> m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
        } ?: emptyMap()
        val added = o.optJSONObject("added")?.let { m -> m.keys().asSequence().associateWith { m.getLong(it) } } ?: emptyMap()
        p.watchlist = picks("watch")
        p.listAdded = added + p.listAdded.filterKeys { it !in added }
        p.reminders = picks("remind")
        p.watched = o.optJSONArray("watched")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        p.liked = genres("liked")
        p.disliked = genres("disliked")
        p.recent = picks("recent")
        true
    } catch (e: Exception) {
        false
    }

    private fun sig(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(16)

    /** Turns backups on with a new code and sends the first copy (off the main thread). */
    fun start(ctx: Context, done: (Boolean) -> Unit) {
        val code = newCode()
        store(ctx).edit().putString("code", code).remove("sig").remove("at").apply()
        Thread { done(upload(ctx)) }.start()
    }

    /** Sends the lists now; true when the site kept them. */
    fun upload(ctx: Context): Boolean {
        val code = code(ctx) ?: return false
        val text = payload(Prefs(ctx))
        val ok = Net.postText("$ENDPOINT?id=${id(code)}", lock(code, text))
        if (ok) store(ctx).edit().putString("sig", sig(text)).putLong("at", System.currentTimeMillis()).apply()
        return ok
    }

    /**
     * On the home screen: a changed list is sent at most once an hour, an unchanged one once a week,
     * which keeps it from being removed after three months. Also, on a fresh install where Android
     * brought the code back, the lists come back by themselves once.
     */
    fun sync(ctx: Context, restored: () -> Unit = {}) {
        val code = code(ctx) ?: return
        val prefs = Prefs(ctx)
        Thread {
            if (!prefs.backupChecked) {
                prefs.backupChecked = true
                val empty = prefs.watchlist.isEmpty() && prefs.watched.isEmpty() && prefs.liked.isEmpty()
                if (empty && restoreNow(ctx, code)) {
                    restored()
                    return@Thread
                }
            }
            val text = payload(prefs)
            val st = store(ctx)
            val age = System.currentTimeMillis() - st.getLong("at", 0L)
            val changed = st.getString("sig", "") != sig(text)
            if ((changed && age > 3600_000L) || age > 7 * 86400_000L) upload(ctx)
        }.start()
    }

    /** Brings the lists of [code] to this TV; they replace what is here. Off the main thread. */
    fun restoreNow(ctx: Context, code: String): Boolean {
        val blob = Net.get("$ENDPOINT?id=${id(code)}") ?: return false
        val text = unlock(code, blob) ?: return false
        if (!apply(Prefs(ctx), text)) return false
        store(ctx).edit().putString("code", code).putString("sig", sig(text)).putLong("at", System.currentTimeMillis()).apply()
        return true
    }

    /** Removes the copy on the site and turns backups off on this TV. Off the main thread. */
    fun delete(ctx: Context): Boolean {
        val code = code(ctx) ?: return true
        val ok = Net.postText("$ENDPOINT?id=${id(code)}&del=1", "")
        if (ok) store(ctx).edit().clear().apply()
        return ok
    }
}
