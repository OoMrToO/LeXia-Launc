# LeXia (kişisel): proje durumu ve devir notları

Bu depo, LeXia Launcher'ın **yalnızca sahibinin kendi TV'si için** sürümüdür (eski proje `OoMrToO/TvLauncher`'dan 12 Kasım 2026'da kopyalandı).
Yeni sohbet önce bu dosyayı okur. **Her değişiklikte bu dosya da güncellenir.** Eski projenin uzun notları burada yok; gerekirse eski deponun `CLAUDE.md`'sine bakılır.

Son güncelleme: 14 Kasım 2026 (PR #39 birleştirildi; Nuvio JS getStreams asenkron çağrısının tamamlanması beklenerek boş sonuç hatası düzeltildi).

## 1. Çalışma biçimi

- **Dil:** Türkçe. Kullanıcı telefondan okuyor: kısa, sonuç önce, sade. Teknik terim az. Kullanıcıya "site" değil **"dosya deposu"** denir (web sitesi sanıyor).
- **Önce sor:** kullanıcı bir şey isteyince önce ne yapılacağını kısaca anlat, onay al. **"Yap/düzelt" derse** doğrudan yapılır, sonuç bildirilir. Silici/geri alınamaz işler ve kullanıcının istemediği yeni fikirler her zaman önce sorulur.
- **Mükemmeliyetçi:** yazım hatası, görsel kusur, kod hatası istemiyor; premium, Apple TV hissi. Önerilerde aynı fikirleri tekrarlama.
- **Doğrulama:** gerçek TV bu ortamda yok; kullanıcı fotoğraf atar. "TV'de henüz denenmedi" dürüstçe yazılır.
- **Mesaj sonu:** yayınlanan sürüm numarası ve TV'de ne yapılacağı ("Güncelleme geldi" düğmesi).
- **Gizlilik:** APK'ya kullanıcı adı, e-posta ya da depo adresi yazılmaz. Depo gizli kalır.

## 2. Medya oynatma ve eklentiler (14 Kasım 2026'da geri geldi, kullanıcı kararı)

- **Geçmiş:** ChatGPT'nin ilk eklenti/Nuvio denemesi (PR #1–#26) 13 Kasım akşamı kullanıcının "temize çek" demesiyle silinmişti (#27). 14 Kasım'da kullanıcı **kararını değiştirdi**: ChatGPT'ye yeniden yazdırdığı #28 ve #29'un birleştirilip yayına alınmasını istedi. Eski "bu kod yeniden eklenmez, Claude dokunmaz" kuralı **geçersiz**; Claude bu kodu da düzeltir ve birleştirir.
- **#28 (ChatGPT):** Media3 1.11.1 (ExoPlayer, HLS, DASH, SmoothStreaming) + OkHttp 4.12.0; `PlayerActivity` (kumanda, ses/altyazı seçimi, kaldığı yerden devam), `MediaAddonRepository` (Stremio uyumlu eklenti manifestleri, ayar dosyası `media_addons`), `MediaAddonsActivity` (Ayarlar › "Medya kaynakları"), `MediaSourcePickerActivity` (film sayfasındaki yeni **"Oynat"** düğmesi; artık ilk odak onda, "Fragmanı İzle" ikinci), `MediaPlaybackStore`. Derleme araçları **AGP 8.13.2 / Kotlin 2.3.21 / Gradle 8.13, compile/target SDK 36** (minSdk 24). Uygulama artık **kütüphaneli**.
- **#29 (ChatGPT):** #28 ile aynı oynatıcıyı daha basit hâliyle ekliyordu; çakıştı. Ondan yalnızca **dışarıdan video açma** kaldı: `PlayerActivity` dışa açık, `video/*`, `audio/*` ve HLS için VIEW isteği alır (adres `intent.data`'dan okunur). Oynatıcı ve Media3 sürümü #28'inki.
- **Claude'un birleştirirken düzelttikleri:** `App.dropAddonData()` kaldırıldı (her açılışta adı "addon" içeren ayarları siliyordu; `media_addons` her açılışta silinecekti); `MediaPlaybackStore` anahtarları `scripts/prefs_keys.txt`'ye eklendi (yoksa `build.yml` kırılıyordu); manifestte tek `PlayerActivity` kaydı.
- **Kumanda düzeltmesi (14 Kasım, sürüm 32'de kullanıcı fotoğrafı):** "Medya kaynakları" ekranında hiçbir şey seçilemiyordu. Neden: ChatGPT üç yeni ekranda kök kutuyu odaklanabilir yapıp odağı ona vermişti; odaklı kök kutudan D-pad hiçbir yere gidemez. Üçünde de kaldırıldı: eklenti ekranı "Eklenti ekle"de, kaynak seçici ilk kaynakta, oynatıcı kendi denetimlerinde başlar (oynatıcı kumandayı önce `PlayerView`'e verir). Seçili düğme artık beyaz zemin + koyu yazı. **Yeni ekran yazılırken kök kutu odaklanabilir yapılmaz.**
- **Kapsam:** manifestli Stremio uyumlu eklentiler ve Nuvio JS scraper'ları; oynatıcıya yalnızca doğrudan HTTPS akışları verilir. Nuvio scraper `fetch`, Nuvio depo/JS indirmeleri ve Stremio eklenti istekleri yalnızca HTTPS kullanır; HTTPS → HTTP yönlendirmelerini izlemez. Nuvio `supportedPlatforms` / `disabledPlatforms` alanları TV uyumluluğunu belirler; boş izin listesi tüm platformlara izin verir. Nuvio akış ve altyazı başlıkları geçerli ad, uzunluk ve satır sonu denetiminden geçirilir. QuickJS giriş noktası `getStreams()` sonucunu okumadan önce asenkron çağrıyı `await` eder. Torrent/debrid, CloudStream eklentileri, MPV yok. **TV'de hiçbiri denenmedi.**
- Kullanıcı başka araçla da çalışır: iş hep güncel `main`'den dal açılarak, PR ile yapılır. Aynı işi yapan iki PR açılırsa çakışır; Claude tek dalda toplar.

## 3. Cihaz ve kurulum

- **TV:** Grundig Google TV (Android TV), Türkiye, kumanda (D-pad). Standart ayar ekranlarının bir kısmı çalışmaz; `SystemSettings.kt` TV'nin kendi ekranlarını önce dener.
- **İlk kurulum:** Downloader ile `https://lexia-tv.pages.dev/kur.apk` (kurulum sürümü, Ana Sayfa hizmeti yok, Play Protect'e takılmaz) → açılınca tam sürüme güncellenir. Sonraki güncellemeler uygulama içinden.
- **13 Kasım:** kullanıcı yeni uygulamayı TV'ye kurdu. Eski LeXia'yı (`com.oomrtoo.tvlauncher`, sürüm 180+) kaldırması istendi; ikisi aynı adda ("LeXia Launcher") ve iki Ana Sayfa hizmeti birbirini "başka ana ekran" sayıp öne atar (`HomeButtonService.otherHomes`). Kaldırıldığı doğrulanmadı.
- **14 Kasım:** TV'de sürüm 30'dan 32'ye uygulama içi güncelleme "Kuruluyor…"da uzun kaldı, sonra kuruldu (nasıl olduğu sorulmadı; tekrarlarsa bakılır). Eklenti ekranı TV'de açılıyor.
- **TV `pages.dev`'e ulaşamıyor (13 Kasım):** kurulum sürümü (#0) "Bağlantı yok" diyor, Downloader dönüp duruyor; TV'nin DNS'i `1.1.1.1` yapıldı, değişmedi. Aynı ev Wi-Fi'sinde telefon `version.txt`'i açıyor ("19"); APK'larda adres doğru. Şüphe: TV sertifikayı tanımıyor (TV'nin Android sürümü sorulacak). **Çözüm (kullanıcı onayı):** dosyaların kopyası ayrı `exxsss` hesabının herkese açık `exxsss/Lxialnch` deposunda **tahmin edilmesi zor adlı bir dalda** (`qe46fjffumnk`, `publish.yml` › `DIST_BRANCH`; ilk kopya `tv` dalındaydı, silindi; `main` dalı eski LeXia'nın, dokunulmaz; depo herkese açık, dal listesinden yine bulunabilir); `publish.yml` `DIST_TOKEN` sırrı varsa oraya tek commit force push eder. Uygulama `Net.MIRROR` (`raw.githubusercontent.com/exxsss/Lxialnch/qe46fjffumnk`) ile önce siteyi, cevap yoksa kopyayı sorar (30 dk siteyi ikinci sıraya alır). Kurulum sürümü #0 kopyayı bilmez: TV'ye bir kez Downloader ile `https://raw.githubusercontent.com/exxsss/Lxialnch/qe46fjffumnk/apk/app.apk` kurulur.
- **Ana Sayfa tuşu:** Google TV, varsayılan launcher seçilse bile kendi ekranına gider; bu yüzden erişilebilirlik hizmeti `HomeButtonService` var (kullanıcı TV ayarlarından bir kez açar, `HomeGuideActivity` adım adım gösterir).

- **Kopya durumu (13 Kasım akşamı):** eski `exxsss/Lxialnch` deposu yoktu; kullanıcı aynı adla yeniden açtı (herkese açık). `DIST_TOKEN` = `exxsss` hesabının fine-grained anahtarı (All repositories, Contents: Read and write, süresiz). Sürüm 21 kopyada doğrulandı (APK iniyor, içinde kopya adresi var). **Depo adı ve dal adı (`DIST_BRANCH`) bir daha değişmemeli**: TV'deki uygulama adresi ezbere bilir, değişirse güncelleme ve film listesi kesilir. Kullanıcıya kurulum linki verildi; TV'de sonuç bekleniyor.

- **Erişilebilirlik ekranı (13 Kasım):** kullanıcının Grundig'inde rehberin "Ayarları aç" düğmesi yalnızca ana ayarları açtı (yol: **Cihaz Tercihleri › Erişilebilirlik**). `SystemSettings.openAccessibility` artık TV ayar uygulamasının dışa açık ekranlarından adında "accessib" geçenleri kendisi bulup önce onları dener (`found`), sonra sabit adları ve standart isteği. TV'de denenmedi; yine açmazsa TV'nin ayar uygulamasındaki ekran adlarını göstermek gerekir.

## 4. Depo, derleme, yayın

- Depo `OoMrToO/LeXia-Launc` (**gizli**). Paket `tv.lexia.launcher`, kod `app/src/main/java/tv/lexia/launcher/`. minSdk 24, targetSdk 36, Kotlin 2.3.21, AGP 8.13.2. Kütüphaneler: Media3 ve OkHttp (yalnızca oynatma için); arayüz yine düz Android View.
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
2. Tip kontrolü (Android SDK yok; Media3 eklendiğinden oynatma dosyaları bu yolla denetlenemez): `kotlin-compiler-embeddable` + `org.robolectric:android-all:14-robolectric-10818077` Maven'den, `K2JVMCompiler` ile `app/src/main/java` + `R`/`BuildConfig` taslağı. Asıl doğrulama `pr-build`.
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

- Oynatma ve eklentiler (#28, #29) TV'de denenmedi: "Oynat" düğmesi, Ayarlar › Medya kaynakları, kumanda, altyazı.

- Eski LeXia TV'den kaldırıldı mı? Listem yedek koduyla taşındı mı? (13 Kasım'da adımlar verildi.)
- Yeni uygulamanın TV fotoğrafı yok; hiçbir özellik bu pakette TV'de denenmedi.
- GitHub Actions dakikası takip edilmeli (bölüm 4).
- Cloudflare API anahtarının süresi kullanıcıda; dolarsa derleme "Site address" adımında kırılır.

## 9. PR geçmişi (bu depo)

| PR | Kim | İçerik |
|---|---|---|
| — | Claude | İlk aktarım: yeni paket adı, sayaçsız, dosya deposu, uzaktan ayarlar ve Yenilikler kartı kapalı, sırlar ve iş akışları |
| #1–#4, #6–#16, #20–#26 | ChatGPT | Eklenti yönetimi, Nuvio sağlayıcıları, Media3 oynatıcı, ana ekran eşleştirmesi; **hepsi kaldırıldı** (bölüm 2). #3'ün GitHub yedeği #5'te kaldırılmıştı |
| #5 | Claude | Çalışmayan GitHub yedeği ve APK'daki kullanıcı adı kaldırıldı |
| #17, #18 | Claude | Dosyaların `exxsss/Lxialnch` kopyası (rastgele adlı dal), uygulama site cevap vermezse kopyadan alır |
| #19 | Claude | Ana Sayfa rehberi TV'nin erişilebilirlik ekranını adından bulur |
| #27 | Claude | Eklentiler ve Nuvio ile ilgili her şey silindi, derleme araçları eski sürümlerine döndü |
| #28, #29 | ChatGPT | Media3 oynatıcı, Stremio uyumlu eklentiler, kaynak seçici, dışarıdan video açma (bölüm 2) |
| #30 | Claude | #28 ve #29 tek dalda birleştirildi; çakışmalar, açılıştaki eklenti temizliği ve ayar anahtarları düzeltildi |
| #31 | Claude | Yeni ekranlarda kumanda odağı düzeltildi (eklentiler, kaynak seçici, oynatıcı) |
| #33–#38 | ChatGPT | Nuvio JS desteği, kaynak gruplama, TMDB kimlikleri, zaman aşımı, depo/scraper ayarları ve TV yönetimi |
| #39 | ChatGPT | Nuvio Beta parity faz 2: fetch sıkıştırma/yanıt sınırı, akış metadata'sı; HTTPS düşürme yönlendirmeleri, TV platformu filtreleri, başlık denetimi ve iptal davranışı düzeltildi |
| sonraki PR | ChatGPT | Nuvio `getStreams()` asenkron çağrısının sonucu beklenerek kaynak aramasında erken boş liste dönmesi düzeltildi |
