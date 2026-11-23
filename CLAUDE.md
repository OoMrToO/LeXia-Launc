# LeXia: Android TV launcher'ı (kişisel)

Sahibinin Grundig Google TV'si için (kumanda/D-pad, Türkiye). Süren iş: `PROGRESS.md` (varsa önce oku). Geçmiş, kararlar, reddedilen/bekleyen öneriler: `docs/HISTORY.md` (bütün okunmaz; `grep -n`).

## Kullanıcı
- Türkçe, kısa, sonuç önce, az teknik terim; telefondan okuyor. "Site" değil "dosya deposu".
- Önce ne yapılacağını söyle, onay al; "yap/düzelt" derse doğrudan yap. Silici işler ve istenmemiş fikirler önce sorulur.
- Öneri yapmadan önce: `grep -n "Reddedilenler\|cevap bekleyenler" docs/HISTORY.md`; aynı fikir tekrarlanmaz. Ücretli yapay zekâ yok.
- Premium/Apple TV hissi; yazım ve görsel kusur kabul edilmez.
- TV burada yok: denenmeyene "TV'de henüz denenmedi" yazılır. Mesaj sonu: sürüm numarası + "Güncelleme geldi" düğmesi.
- APK'ya kullanıcı adı, e-posta, depo adresi yazılmaz.

## Verimli çalışma
- Yalnızca ilgili dosyayı, `Grep` ile bulunan kısmıyla oku. Büyük dosyalar (`MainActivity`, `MediaSourcePickerActivity`, `TrailerActivity`, `SettingsActivity`) bütün okunmaz.
- Arama önce `app/src/main/java/tv/lexia/launcher/`; `nuvio/` yalnızca oynatıcı/kaynak işinde, yolla.
- Refactor/temizlik istenmeden yapılmaz. Subagent yalnızca istenirse.
- PR ayrıntısı `docs/HISTORY.md` sonuna 1–3 satır; bu dosya yalnızca kalıcı kural değişince güncellenir.

## Mimari
- `app/` (paket `tv.lexia.launcher`, düz View, Compose yok) + `nuvio/` (`com.nuvio.tv`, NuvioTV kopyası + Compose). minSdk 24, target 36, Kotlin 2.3.21, AGP 8.13.2, Media3 1.8.0 (`app/libs/*.aar`), yalnızca `armeabi-v7a`.
- Önemli: `MainActivity` (ana ekran), `DetailActivity` (film/dizi), `MediaSourcePickerActivity` (kaynak sayfası, oynatıcıyı açar, sonuç kodları), `SourceMemory`, `MediaPlaybackStore`, `NuvioSearch`, `MediaAddonRepository`, `TmdbDiscover`, `Net`, `Prefs`, `SettingsActivity`, `Updater`, `Guard`. `PlayerActivity` eski oynatıcı, bilerek duruyor.
- `nuvio/.../LeXia*.kt` LeXia'nın köprüleri: `LeXiaNuvio` (nesne kurulumu, ayar köprüleri), `LeXiaPlayerActivity` (oynatıcı, sonuç kodları), `LeXiaCinema`; `ui/screens/player/LeXiaPlayerUi.kt` görünüm.
- İş akışları: `build.yml` (main → imzalı APK → yayın, sürüm = çalıştırma no), `pr-build.yml` (prefs denetimi, test, debug derleme), `daily-pick.yml`.

## Değişmez kurallar
- **Nuvio kodu değiştirilmez**; ekler `LeXia*.kt`'ye. İstisna: `PlayerScreen.kt`'deki 4 "LeXia:" çağrısı. Eksik bağımlılığa "LeXia stand-in" dolgu.
- **Nuvio ekranı/uygulaması asla görünmez**; her şey LeXia görünümüyle.
- **R8:** Nuvio'dan yeni kütüphane gelince kuralı `app/proguard-rules.pro`'ya ekle; yoksa sürüm derlemesi sessizce bozulur.
- **Ayar anahtarı:** yeni anahtar türüyle `scripts/prefs_keys.txt`'ye; `MediaPlaybackStore`'da `val key = prefix(…)` sonra `"${key}_…"`.
- **Nuvio ayarları** `LeXiaNuvio` ile DataStore'a; tek seferlik varsayılan için `PLAYER_DEFAULTS` +1. DataStore nesneleri tekil.
- **Arayüz:** kök kutu odaklanabilir yapılmaz (D-pad kilitlenir). Seçili düğme beyaz zemin + koyu yazı. Film rengi `LeXiaCinema.accent`/`Fx.accent`.
- **Yayın:** APK yalnızca `exxsss/Lxialnch` deposu, `qe46fjffumnk` dalına (`Net.MIRROR`); **ad asla değişmez**. Cloudflare `lexia-tv`; eski `lexialauncher` silinmez. `versionCode` = 2000 + çalıştırma.
- Sırlar: `KEYSTORE_PASSWORD`, `TMDB_KEY`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `DIST_TOKEN`.

## Akış
1. `git fetch origin main && git checkout -B <dal> origin/main`
2. `bash scripts/dev-setup.sh`; `ANDROID_HOME=/opt/android-sdk gradle --no-daemon -q -I scripts/mirror.init.gradle :app:compileFullDebugKotlin` (yansıma şart, Maven 429 verir). Ayar değiştiyse `python3 scripts/check_prefs.py`. Ağır yerel test yok.
3. Commit → push → PR → `pr-build` yeşil → squash birleştir (`expectedHeadSha` 40 karakter) → `build.yml` sürüm no. "Yayınlama" derse PR bekler.
