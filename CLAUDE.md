# LeXia (kişisel Android TV launcher'ı): çalışma kılavuzu

Yalnızca sahibinin kendi TV'si için (Grundig Google TV, kumanda/D-pad, Türkiye). Bu dosya kısa tutulur: kurallar, harita, tuzaklar.
**Geçmiş, her PR'ın ayrıntısı ve eski kararların gerekçesi:** `docs/HISTORY.md` (büyük; bütünü okunmaz, `grep -n "#57\|anahtar" docs/HISTORY.md` ile ilgili satıra gidilir).
**Süren iş:** `PROGRESS.md` (varsa önce o okunur).

## 1. Kullanıcıyla çalışma

- **Türkçe**, kısa, sonuç önce, sade; kullanıcı telefondan okuyor. Teknik terim az. "Site" değil **"dosya deposu"** denir.
- **Önce sor:** istek gelince ne yapılacağı kısaca söylenir, onay alınır. **"Yap/düzelt" derse** doğrudan yapılır. Silici/geri alınamaz işler ve istenmemiş yeni fikirler her zaman önce sorulur. Önerilerde aynı fikir tekrarlanmaz (§7).
- **Mükemmeliyetçi:** premium, Apple TV hissi; yazım hatası, görsel kusur, kod hatası istemez.
- **Doğrulama:** TV bu ortamda yok; kullanıcı fotoğraf atar. Denenmeyen şey için "TV'de henüz denenmedi" yazılır.
- **Mesaj sonu:** yayınlanan sürüm numarası + TV'de ne yapılacağı ("Güncelleme geldi" düğmesi).
- **Gizlilik:** APK'ya kullanıcı adı, e-posta, depo adresi yazılmaz. (Depo 22 Kasım'dan beri herkese açık: Actions dakikası için.)

## 2. Verimli çalışma kuralları

- Görev için **yalnızca ilgili dosyaları** oku; önce §3'teki haritadan dosyayı bul, sonra `Grep` ile satırı bul, dosyanın yalnızca o kısmını oku (`offset/limit`). Büyük dosyalar (`MainActivity.kt` 4300, `MediaSourcePickerActivity.kt`, `TrailerActivity.kt`, `SettingsActivity.kt` 1600+ satır) bütün okunmaz.
- Aramayı önce `app/src/main/java/tv/lexia/launcher/` ile sınırla; `nuvio/` (412 dosya, Nuvio'nun kopyası) yalnızca konu oynatıcı/kaynak/eklenti ise ve yol vererek aranır.
- `.ignore` aramalardan şunları çıkarır: `build/`, `.gradle/`, `*.aar`, `*.apk`, `pick.json` (2,3 MB), `docs/HISTORY.md`, yazı tipleri, `nuvio/src/main/res/raw/`. Gerekirse yoluyla okunur ya da `rg --no-ignore`.
- Küçük işte doğrudan çözüme git; plan yazma. Büyük işi parçalara böl, her parçayı derleyip ilerle. Subagent yalnızca kullanıcı isterse.
- Var olan yapı ve adlandırma korunur; istenmeyen refactor/temizlik yapılmaz.
- Uzun süren, birden çok oturuma yayılan işte `PROGRESS.md` güncellenir (nerede kalındı, ne değişti, sıradaki adım). Tek oturumluk işte dokunulmaz.
- Bu dosya **yalnızca kalıcı bilgi değişince** (yeni modül, yeni kural, yeni tuzak, yeni ret) güncellenir. PR ayrıntısı `docs/HISTORY.md` sonundaki "Yeni kayıtlar" bölümüne 1–3 satır olarak eklenir.

## 3. Mimari haritası

- **İki Gradle modülü:** `app/` (LeXia, paket `tv.lexia.launcher`, düz Android View arayüz, Compose yok) ve `nuvio/` (`namespace com.nuvio.tv`; NuvioTV'nin **değiştirilmeden** kopyalanan kodu + Compose). minSdk 24, target/compile 36, Kotlin 2.3.21, AGP 8.13.2. Media3 = Nuvio'nun 1.8.0 derlemesi (`app/libs/*.aar`). Yalnızca `armeabi-v7a`.
- **`app/src/main/java/tv/lexia/launcher/`** (66 dosya), bölgelere göre:
  - Ana ekran: `MainActivity` (hero, satırlar, üst çubuk, ekran koruyucu, bildirimler), `HeroTrailer` (sessiz fragman), `Apps`/`AppsActivity`, `WatchMarks` (afişte ilerleme), `FocusKeep`, `Housekeeping` (depo yenileme, önceden kaynak), `Surprise` (Ne izlesem/Rastgele), `MovieNight`, `Mood`, `Episodes` (yeni bölüm).
  - Sayfalar: `DetailActivity` (film/dizi sayfası, bölümler, Oynat/Devam et), `ExploreActivity` (Keşfet), `WatchHistoryActivity` + `WatchHistory`, `PersonActivity`, `SearchAll`, `CalendarActivity`, `DuelActivity`, `PlatformMonthActivity`, `WeekendActivity`, `SetupActivity`, `HomeGuideActivity`.
  - Oynatma/kaynak: `MediaSourcePickerActivity` (kaynak sayfası; Nuvio araması, otomatik/favori kaynak, Nuvio oynatıcısını açar, sonuç kodlarını işler), `NuvioSearch`, `NuvioMeta` (bölüm listesi), `MediaAddonRepository` (Stremio eklentileri, altyazı, IMDb kimliği), `NuvioJsRepository` (Nuvio depoları köprüsü), `MediaAddonsActivity` (Ayarlar › Medya kaynakları), `SourceMemory` (kaynak hafızası/önbellek), `MediaPlaybackStore` (kaldığın yer, izlendi), `StreamTags`, `PlayerActivity`+`PlayerSupport`+`PlaybackHttp`+`SubtitleText`+`Lang` (eski oynatıcı; bilerek duruyor, dışarıdan video açma ona bağlı).
  - Fragman: `TrailerActivity`, `TrailerSupport`, `YouTubeStreams` (YouTube akışı, resmî olmayan yol).
  - Veri: `Pick` (yapım modeli), `TmdbDiscover` (TMDB: discover, similar, search, person, complete, creditsScene), `Net` (ağ, dosya deposu + kopya), `Img`, `Prefs` (ayarlar), `Backup`, `Remote` (boş), `Updater`.
  - Ayarlar: `SettingsActivity` (tvOS tarzı sayfalar; `toggle`/`choice` yardımcıları).
  - Altyapı: `App`, `Guard` (çökme kalkanı), `Ui`, `Glass`, `Fx`, `Dialogs`, `Sleep`, `SystemSettings`, `HomeButtonService`, alıcılar.
- **`nuvio/src/main/java/com/nuvio/tv/`:** `LeXia*.kt` dosyaları **LeXia'nın** (köprü): `LeXiaNuvio` (Hilt'in yerine nesneleri kurar; Nuvio ayar deposuna yazan köprüler, `applyPlayerDefaults` sürümlü), `LeXiaPlayerActivity` (Nuvio'nun `PlayerScreen`'ini açar; perde, jenerik paneli, QuickFail, sonuç kodları), `LeXiaCinema` (LeXia ↔ oynatıcı paylaşılan durum), `LeXiaPlayback`, `LeXiaImages`. `ui/screens/player/LeXiaPlayerUi.kt` oynatıcının görünümü (LeXia'nın). Geri kalanı (`core/`, `data/`, `domain/`, `ui/`) Nuvio'nun kodu; başında "LeXia stand-in" yazanlar Nuvio'da olup LeXia'da olmayan parçaların boş dolguları.
- **Testler:** `app/src/test/java/tv/lexia/launcher/` (Robolectric + Roborazzi ekran görüntüleri, `SourceMemoryTest` vb.). Kullanıcı yerelde ağır test istemiyor; doğrulama derleme + `pr-build`.
- **Betikler:** `scripts/pick.py` (günlük film listesi → `pick.json`; TMDB + IMDb), `scripts/check_prefs.py` + `prefs_keys.txt` (ayar anahtarı denetimi), `scripts/dev-setup.sh`, `scripts/mirror.init.gradle`.
- **İş akışları:** `build.yml` (main'e push → imzalı `full` + `setup` APK → yayın; sürüm = çalıştırma numarası), `pr-build.yml` (PR: prefs denetimi, birim testleri, debug derleme), `daily-pick.yml` (02:17 UTC), `publish.yml`. Yalnızca `.md`/`docs/**`/`pick.json`/`scripts/pick.py` değişince APK derlenmez.

## 4. Değişmez kurallar ve tuzaklar

- **Nuvio kodu değiştirilmez.** LeXia'nın eklemeleri `LeXia*.kt` dosyalarına yazılır. İstisna: `PlayerScreen.kt`'de "LeXia:" yorumlu 4 çağrı (kontroller, sarma, duraklatma, saat kapalı). Nuvio yeniden kopyalanırsa bu 4 yer yeniden yapılır; yeni bağımlılık için "LeXia stand-in" dolgu yazılır.
- **Nuvio ekranı/uygulaması asla görünmez** (kullanıcının kesin kararı): Nuvio'nun ana ekranı, menüleri, ayrı pencereler, ayrı uygulama önerilmez. Kaynaklar ve eklentiler LeXia görünümüyle, oynatıcı Nuvio motoru + LeXia görünümüyle.
- **Küçültme (R8):** Nuvio'dan yeni kütüphane/özellik alınınca Nuvio'nun `proguard-rules.pro`'sundaki kuralı `app/proguard-rules.pro`'ya eklenir. Eksik kural sürüm derlemesinde sessizce bozar (Gson, QuickJS, MPV vakaları). Şüphede `dexdump` ile pakete bakılır.
- **Ayar anahtarı:** yeni `SharedPreferences` anahtarı türüyle `scripts/prefs_keys.txt`'ye eklenir. `MediaPlaybackStore` anahtarları `val key = prefix(…)` sonra `"${key}_…"` biçiminde yazılır, yoksa `check_prefs.py` kırılır.
- **Nuvio ayarları** LeXia'dan `LeXiaNuvio` köprüsüyle Nuvio'nun DataStore'una yazılır. Tek seferlik varsayılan için `applyPlayerDefaults` içinde `PLAYER_DEFAULTS` bir artırılır. DataStore nesneleri tekil olmalı (#78 çökmesi).
- **Arayüz:** yeni ekranda kök kutu odaklanabilir yapılmaz (D-pad kilitlenir). Seçili düğme beyaz zemin + koyu yazı. Film rengi `LeXiaCinema.accent` / `Fx.accent`.
- **Yayın altyapısı:** APK > 26 MB olduğu için yalnızca kopyaya gider: `exxsss/Lxialnch` deposu, `qe46fjffumnk` dalı (`Net.MIRROR`). **Depo ve dal adı asla değişmez** (TV ezbere bilir). İlk kurulum: `https://raw.githubusercontent.com/exxsss/Lxialnch/qe46fjffumnk/kur.apk`. Dosya deposu Cloudflare Pages `lexia-tv`. Yedek, eski Cloudflare projesi `lexialauncher`'ı kullanır, **o proje silinmemeli**. `versionCode` = 2000 + çalıştırma (kur.apk 1999 +).
- **Sırlar:** `KEYSTORE_PASSWORD`, `TMDB_KEY`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `DIST_TOKEN`. İmza `app/signing.p12`.

## 5. Geliştirme akışı

1. `git fetch origin main && git checkout -B <dal> origin/main` (oturumun verdiği dal adıyla).
2. Yerel derleme gerekiyorsa: `bash scripts/dev-setup.sh`, sonra `ANDROID_HOME=/opt/android-sdk gradle --no-daemon -q -I scripts/mirror.init.gradle :nuvio:compileDebugKotlin` ve/veya `:app:compileFullDebugKotlin` (hızlı denetim; tam paket gerekirse `:app:assembleFullRelease`). Maven Central bu ortamdan 429 verir, yansıma şart. Ayar değiştiyse `python3 scripts/check_prefs.py`.
3. Commit → push → PR → `pr-build` yeşil → birleştir (squash, `expectedHeadSha` 40 karakter) → `build.yml` bitince sürüm numarası kullanıcıya. Kullanıcı "yayınlama" derse PR açık bekletilir.
4. `git push` sonrası "This repository moved" mesajı zararsız.

## 6. Şu anki durum (kısa)

- Oynatıcı: Nuvio motoru (ExoPlayer + MPV yedeği, FFmpeg ses, libass), LeXia görünümü (#80), perde + logo dolma, kare hızı uyumu ve jenerik paneli (#81).
- Kaynak: Nuvio araması (Stremio eklentileri + Nuvio JS depoları; kullanıcının deposu izlelan), favori kaynak, "Devam et" son kaynakla, 3 saatlik kaynak önbelleği.
- Yok (bilerek): torrent/debrid, CloudStream, Trakt/Simkl, birlikte izleme, Plex/Jellyfin, Dolby Vision dönüştürme, çözünürlük uyumu, 64 bit. IntroDB adresi doğrulanmadı.
- Hiçbir özellik TV'de tam doğrulanmadı; kullanıcı fotoğrafla geri bildirim veriyor.

## 7. Kararlar (tekrar önerme)

- Ücretli yapay zekâ yok (TMDB + IMDb).
- Reddedilenler: telefondan yönetim/QR, manzara videoları, kendi fotoğrafları, maç kartı, namaz vakitleri, kur/altın, resmî tatil, hatırlatıcı notlar, çocuk modu, doğum günü, hazır temalar, profiller, LeXia Özet, seriyi tamamla, hava durumu sayfası, hız testi, depolama durumu, ayar yedeği, gece güncellemesi, Watch Next, açılış animasyonu, HDMI kısayolları, afişte hızlı menü, IMDb oy sayısı, film müziği düğmesi, ayarlarda sekmeler/renkli ikonlar/arama, iPhone tarzı anahtarlar, ücretsiz izle satırı; yönetim sayfası, sayaç, duyuru, güncelleme sonrası kart; hero fragmanını önceden yükleme, açılışı hızlandırma (23 Kasım).
- Sonradan kabul edilenler (artık ret değil): bitiş saati / bu akşamın programı, Türkçe dublajlı fragman önceliği, Yarım kalanlar satırı.
- Önerildi, cevap bekleyenler (23 Kasım): film sayfası sinema açılışı, oynatıcıda oyuncular paneli (X-Ray), sinema altyazısı, ses dengesi, Oscar rozetleri, usta yönetmenler satırları, sevdiğin sahneler, replik ekran koruyucusu, sezon finali rozeti; daha önce: duraklatmada filmden kareler, "Önceki bölümde", Apple TV tarzı Keşfet.
