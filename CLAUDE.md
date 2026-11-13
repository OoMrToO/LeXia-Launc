# LeXia (kişisel): proje durumu ve devir notları

Bu depo, LeXia Launcher'ın **yalnızca sahibinin kendi TV'si için** sürümüdür (eski proje `OoMrToO/TvLauncher`'dan 12 Kasım 2026'da kopyalandı).
Yeni sohbet önce bu dosyayı okur. **Her değişiklikte bu dosya da güncellenir.** Eski projenin uzun notları burada yok; gerekirse eski deponun `CLAUDE.md`'sine bakılır.

Son güncelleme: 13 Kasım 2026 (notlar sadeleştirildi).

## 1. Çalışma biçimi

- **Dil:** Türkçe. Kullanıcı telefondan okuyor: kısa, sonuç önce, sade. Teknik terim az. Kullanıcıya "site" değil **"dosya deposu"** denir (web sitesi sanıyor).
- **Önce sor:** kullanıcı bir şey isteyince önce ne yapılacağını kısaca anlat, onay al. **"Yap/düzelt" derse** doğrudan yapılır, sonuç bildirilir. Silici/geri alınamaz işler ve kullanıcının istemediği yeni fikirler her zaman önce sorulur.
- **Mükemmeliyetçi:** yazım hatası, görsel kusur, kod hatası istemiyor; premium, Apple TV hissi. Önerilerde aynı fikirleri tekrarlama.
- **Doğrulama:** gerçek TV bu ortamda yok; kullanıcı fotoğraf atar. "TV'de henüz denenmedi" dürüstçe yazılır.
- **Mesaj sonu:** yayınlanan sürüm numarası ve TV'de ne yapılacağı ("Güncelleme geldi" düğmesi).
- **Gizlilik:** APK'ya kullanıcı adı, e-posta ya da depo adresi yazılmaz. Depo gizli kalır.

## 2. İki geliştirici: Claude ve ChatGPT

- Kullanıcı aynı depoda ChatGPT (Codex) ile de çalışıyor. İkisi de bu dosyayı okur, kendi dalında çalışır, değişiklik PR olarak gelir; `main`'e doğrudan yalnızca not değişiklikleri yazılır.
- **Eklenti ve oynatıcı kısmı ChatGPT'nin işi:** Stremio/Nuvio uyumlu eklenti yönetimi, katalog eşleştirme ve Media3 oynatıcı (`Addon*.kt`, `WatchProgress.kt`, Ayarlar › Eklentiler, Ayarlar › Eklenti eşleştirme). Kullanıcı bu kısmı istedi ve ChatGPT'ye yaptırıyor.
- **Claude oturumları bu kısmı geliştirmez, incelemez, düzeltmez, birleştirmez** (Claude'un kendi sınırı: bu eklentiler çoğunlukla telifli içeriği izinsiz yayınlar). Kullanıcıya bunu bir kez, kısa söyle; tekrar tekrar ders verme. Eklenti dışındaki her işte (ana ekran, ayarlar, film listesi, derleme, güncelleme) Claude çalışır ve ChatGPT'nin PR'larının eklenti dışı etkisini (çökme, ana ekran, ayarlar) inceleyebilir.
- ChatGPT'nin teknik notları (oynatıcı biçimleri, eşleştirme ayrıntıları) PR açıklamalarında ve kendi eklediği satırlardadır; buraya kısaca: eşleştirme varsayılan **kapalı** (`Prefs.addonMatchMode` "off"), açılınca hero'daki "nerede izlenir" rozeti eklenti adını gösterir.

## 3. Cihaz ve kurulum

- **TV:** Grundig Google TV (Android TV), Türkiye, kumanda (D-pad). Standart ayar ekranlarının bir kısmı çalışmaz; `SystemSettings.kt` TV'nin kendi ekranlarını önce dener.
- **İlk kurulum:** Downloader ile `https://lexia-tv.pages.dev/kur.apk` (kurulum sürümü, Ana Sayfa hizmeti yok, Play Protect'e takılmaz) → açılınca tam sürüme güncellenir. Sonraki güncellemeler uygulama içinden.
- **13 Kasım:** kullanıcı yeni uygulamayı TV'ye kurdu. Eski LeXia'yı (`com.oomrtoo.tvlauncher`, sürüm 180+) kaldırması istendi; ikisi aynı adda ("LeXia Launcher") ve iki Ana Sayfa hizmeti birbirini "başka ana ekran" sayıp öne atar (`HomeButtonService.otherHomes`). Kaldırıldığı doğrulanmadı.
- **TV `pages.dev`'e ulaşamıyor (13 Kasım):** kurulum sürümü (#0) "Bağlantı yok" diyor, Downloader dönüp duruyor; TV'nin DNS'i `1.1.1.1` yapıldı, değişmedi. Aynı ev Wi-Fi'sinde telefon `version.txt`'i açıyor ("19"); APK'larda adres doğru. Şüphe: TV sertifikayı tanımıyor (TV'nin Android sürümü sorulacak). **Çözüm (kullanıcı onayı):** dosyaların kopyası ayrı `exxsss` hesabının herkese açık `exxsss/Lxialnch` deposunda **`tv` dalında** (`main` dalı eski LeXia'nın, dokunulmaz); `publish.yml` `DIST_TOKEN` sırrı varsa oraya tek commit force push eder. Uygulama `Net.MIRROR` (`raw.githubusercontent.com/exxsss/Lxialnch/tv`) ile önce siteyi, cevap yoksa kopyayı sorar (30 dk siteyi ikinci sıraya alır). Kurulum sürümü #0 kopyayı bilmez: TV'ye bir kez Downloader ile `https://raw.githubusercontent.com/exxsss/Lxialnch/tv/apk/app.apk` kurulur.
- **Ana Sayfa tuşu:** Google TV, varsayılan launcher seçilse bile kendi ekranına gider; bu yüzden erişilebilirlik hizmeti `HomeButtonService` var (kullanıcı TV ayarlarından bir kez açar, `HomeGuideActivity` adım adım gösterir).

## 4. Depo, derleme, yayın

- Depo `OoMrToO/LeXia-Launc` (**gizli**). Paket `tv.lexia.launcher`, kod `app/src/main/java/tv/lexia/launcher/`. minSdk 24, targetSdk 34, Kotlin 1.9.24, AGP 8.5.2. Kütüphane: yalnızca Media3 (ChatGPT'nin oynatıcısı için); geri kalan her şey düz Android View.
- **İş akışları:** `build.yml` (main'e push: imza anahtarı → dosya deposu adresi → `full` + `setup` APK → GitHub `latest` sürümü → yayın), `pr-build.yml` (PR'larda debug derleme; tek PR kontrolü), `daily-pick.yml` (her gün 02:17 UTC `scripts/pick.py` → `pick.json` → yayın), `publish.yml` (dosya deposuna yükler). Yalnızca `.md`, `pick.json`, `docs/**`, `scripts/pick.py` ve yayın/PR iş akışları değişince APK derlenmez.
- **Dosya deposu** (Cloudflare Pages projesi `lexia-tv`, **https://lexia-tv.pages.dev**; sayfası yok, noindex): `pick.json`, `version.txt`, `apk/app.apk`, `kur.apk`, `test.txt` + `apk/test.apk` (test modu için aynı dosyalar), `_headers`, `robots.txt`. Adresi derlemede Cloudflare'den sorulup `BuildConfig.SITE`'a yazılır. Bu oturum ortamından erişim kapalı; doğrulama GitHub iş kayıtlarından yapılır. Kullanıcı 13 Kasım'da bu yapıyı onayladı ("A kalsın").
- **İmza:** `app/signing.p12` ilk derlemede üretildi (`CN=LeXia`, takma ad `lexia`), parolası yalnızca `KEYSTORE_PASSWORD` sırrında ve kullanıcıda. Parola değişir ya da anahtar silinirse TV'deki uygulama kaldırılıp yeniden kurulmalı.
- **Sırlar (4):** `KEYSTORE_PASSWORD`, `TMDB_KEY`, `CLOUDFLARE_API_TOKEN` (Cloudflare Pages: Edit), `CLOUDFLARE_ACCOUNT_ID`.
- **Sürüm kodu** = `GITHUB_RUN_NUMBER` (Ayarlar › Hakkında'da görünür).
- **GitHub Actions sınırı:** gizli depoda ayda 2000 dakika ücretsiz. 12–13 Kasım'da 1,5 günde 71 iş çalıştı (ChatGPT'nin sık PR'ları); bu hızla ayda ~5000 dk. Sınır dolunca ay sonuna kadar derleme ve günlük film listesi durur. Kullanıcıya söylendi (13 Kasım); PR'lar toplanmalı.
- **Kaldırılanlar (eski projeye göre):** tanıtım sitesi, yönetim sayfası, sayaçlar ve `ping`/hata raporu (`Remote.ping`, `Remote.sendCrash` boş), sürüm oylaması, uzaktan ayarlar (`remote.json` yok; `Remote.loadSaved/refresh` boş, duyuru/zorunlu güncelleme/bekletme/seçki/uzaktan kapatma hep varsayılan), güncelleme sonrası Yenilikler kartı (`WhatsNew.maybeShow` yalnızca "görüldü" işaretler; liste Ayarlar › Hakkında'da), GitHub yedek kopyası (#5: gizli depoda 404 veriyordu ve kullanıcı adını APK'ya yazıyordu).
- **Liste yedeği eski projenin Cloudflare'inde:** `Backup.kt` hâlâ `https://lexialauncher.pages.dev/api/backup` (`Net.OLD_SITE`) kullanır; eski LeXia'dan alınan kod yenisinde çalışır. **Eski Cloudflare projesi `lexialauncher` silinmemeli**; silinirse yedek çalışmaz (o zaman bu depoya bir Pages Function taşınır). Eski GitHub deposunu silmek yedeği etkilemez. `TrailerActivity` de fragman sayfasının kökeni olarak aynı adresi kullanır (istek atmaz).

## 5. Geliştirme akışı (Claude)

1. `git fetch origin main && git checkout -B claude/<iş> origin/main` (ChatGPT de çalıştığı için hep güncel `main`'den başla).
2. Tip kontrolü (Android SDK yok): `kotlin-compiler-embeddable:1.9.24` + `org.robolectric:android-all:14-robolectric-10818077` Maven'den, `K2JVMCompiler` ile `app/src/main/java` + `R`/`BuildConfig` taslağı. Media3 kullanan eklenti dosyaları taslakla derlenmez; gerekirse onlar dışarıda bırakılır ya da Media3 jar'ları eklenir. Asıl doğrulama `pr-build`.
3. Commit → push → PR → `pr-build` yeşil → birleştir (`expectedHeadSha` 40 karakter) → `build.yml` bitince sürüm numarasını kullanıcıya yaz.
4. **Ayar anahtarları:** yeni `SharedPreferences` anahtarı `scripts/prefs_keys.txt`'ye türüyle eklenir; `scripts/check_prefs.py` her derlemede eski adların başka türle kullanılmasını yakalar (eski projede #119 çökmesi).
5. Bu ortamda `git push` sonrası "This repository moved" mesajı zararsız (depo adı büyük/küçük harf).

## 6. Özellikler (eski projeden gelen, çalışan)

- **Ana ekran:** üst çubuk (selamlama, "Güncelleme geldi", "Ne izlesem?", hava, saat, zil, arama, ağ, hızlı panel); hero (günün filmi / sinemada / platformlarda yeni karışık, Ken Burns, IMDb ve yaş rozeti, nerede izlenir rozeti, Fragman · Detaylar · Listeme ekle · Sonraki); uygulama rafı (favoriler, "Tüm uygulamalar", sıralama: benim / son kullanılan / saate göre, isteğe bağlı solda gizli panel); satırlar (Top 10 film/dizi, sinemada, yakında, platformlarda yeni, yerli, Sana göre ×3, beğendiklerine benzer, son baktıkların, Listem; taşınır/gizlenir); ekran koruyucu (sinema köşesi, sinema tarihinde bugün, platformlar, geri sayım, saat + hava); dizi takvimi şeritleri; havaya göre film; Günaydın kartı; film gecesi; hafta sonu rehberi.
- **Sayfalar:** film/dizi detayı (oyuncular, benzer yapımlar, nerede izlenir + platform uygulamasını aç, İzledim/Beğendim/Bana önerme, Hatırlat, Bu akşam izle), Keşfet (süzgeçler, 30'ar sayfa, sıralama, fragman maratonu), takvim, Bu ay hangi platform?, film düellosu, fragman oynatıcısı (YouTube gömülü, Türkçe altyazı önceliği, bitiş kartı), sesli arama, Ana Sayfa tuşu rehberi, ilk açılış sihirbazı.
- **Ayarlar:** tvOS tarzı; Görünüm, Filmler, Platformlar, Satırlar, Uygulamalar, Ekran koruyucu ve uyku (uyku zamanlayıcısı, her gece uyku), Güvenlik (PIN), Bakım (liste yedeği, LeXia'yı kaldır), Hakkında (sürüm, film listesi tarihi, son hata, 7 kez OK = test modu).
- **Dayanıklılık:** çökme kalkanı (`Guard.kt`: 30 sn içinde 2 çökmede önbellek temizlenir, 3'te `SafeActivity`), hafif mod (zayıf TV), az bellekte hafif mod önerisi, öncelikli görsel kuyruğu (`Img.run`).
- **Film verisi** (`scripts/pick.py`): TMDB (ücretsiz) + IMDb'nin açık puan dosyası; ücretli yapay zeka yok. `pick.json`: picks, top10, top10tv, cinema, soon, platforms, local, digital, genres, history, episodes; kayıtlarda fragmanlar, nerede izlenir, yaş sınırı, sonraki bölüm.
- **Güncelleme:** `Updater.kt` 30 dk'da bir ve açılışta `version.txt`'ye bakar, `apk/app.apk`'yı tam indirir (3 deneme), PackageInstaller ile kurar (Android 12+ onaysız), sonra ana ekranı yeniden açar. Aynı imza şart.

## 7. Kararlar (tekrar önerme)

- Ücretli yapay zeka yok (TMDB + IMDb). 13 Kasım'da ChatGPT API entegrasyonu soruldu; ücretli olduğu ve anahtarın Cloudflare'de saklanması gerektiği anlatıldı, kullanıcı karar vermedi.
- Kullanıcının reddettikleri: telefondan yönetim/QR, manzara videoları, kendi fotoğrafları, maç kartı, namaz vakitleri, kur/altın, resmi tatil, hatırlatıcı notlar, çocuk modu, doğum günü, hazır temalar, profiller, LeXia Özet, seriyi tamamla, hava durumu sayfası, hız testi, depolama durumu, ayar yedeği, gece güncellemesi, bitiş saati, Türkçe fragman önceliği, izlemeye devam et (Watch Next), açılış animasyonu, HDMI kısayolları, afişte hızlı menü, IMDb oy sayısı, film müziği düğmesi, ayarlarda sekmeler/renkli ikonlar/arama, iPhone tarzı anahtarlar, ücretsiz izle satırı.
- Yönetim sayfası, sayaç, duyuru ve güncelleme sonrası kart istenmedi (12 Kasım: "sadece kendim kullanacağım").

## 8. Açık işler

- Eski LeXia TV'den kaldırıldı mı? Listem yedek koduyla taşındı mı? (13 Kasım'da adımlar verildi.)
- Yeni uygulamanın TV fotoğrafı yok; hiçbir özellik bu pakette TV'de denenmedi.
- GitHub Actions dakikası takip edilmeli (bölüm 4).
- Cloudflare API anahtarının süresi kullanıcıda; dolarsa derleme "Site address" adımında kırılır.

## 9. PR geçmişi (bu depo)

| PR | Kim | İçerik |
|---|---|---|
| — | Claude | İlk aktarım: yeni paket adı, sayaçsız, dosya deposu, uzaktan ayarlar ve Yenilikler kartı kapalı, sırlar ve iş akışları |
| #1, #2, #6, #8–#10, #12, #14–#16 | ChatGPT | Eklenti yönetimi, katalog, bölüm ilerlemesi, Media3 oynatıcı (HLS, DASH, altyazı, dil), ana ekran eklenti eşleştirmesi (varsayılan kapalı) |
| #3 | ChatGPT | Güncellemede GitHub yedeği (çalışmıyordu; #5'te kaldırıldı) |
| #4 | ChatGPT | Oynatıcı kumanda kontrolleri |
| #5 | Claude | GitHub yedeği ve APK'daki kullanıcı adı kaldırıldı |
| — | Claude | Yinelenen PR kontrolü (`addon-branch-check`) kaldırıldı; notlar sadeleştirildi |
