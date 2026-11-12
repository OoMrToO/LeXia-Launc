package tv.lexia.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Result of an in-app update install. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
                } catch (e: Exception) {
                    toast(ctx, "Kurulum ekranı açılamadı. Tekrar dene.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> toast(ctx, "Güncellendi")
            PackageInstaller.STATUS_FAILURE_ABORTED -> toast(ctx, "Güncelleme iptal edildi")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> toast(
                ctx,
                "Kurulamadı: TV'deki sürüm farklı bir anahtarla imzalı. Bir kez kaldırıp " +
                    "Downloader ile yeniden kur; sonraki güncellemeler buradan çalışır."
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> toast(ctx, "Kurulamadı: yer yetersiz")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> toast(ctx, "Kurulamadı: cihaz kurulumu engelledi")
            else -> toast(ctx, "Kurulamadı (hata $status). Tekrar dene.")
        }
    }

    private fun toast(ctx: Context, s: String) = Glass.toast(ctx, s, long = true)
}
