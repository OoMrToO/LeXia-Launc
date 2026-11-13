# LeXia (kişisel): proje durumu ve devir notları

Bu depo, `OoMrToO/TvLauncher`'daki LeXia Launcher'ın **yalnızca sahibinin kendi TV'si için** kopyasıdır.
**Her değişiklikte bu dosya da güncellenir.** Yeni sohbet önce bu dosyayı okur.

Son güncelleme: 13 Kasım 2026 · ana ekran eklenti katalog eşleştirmesi

## 0. Bu depo eskisinden neyle farklı

- **Paket adı `tv.lexia.launcher`** (eskisi `com.oomrtoo.tvlauncher`): TV'de eski LeXia'nın yanına ayrı uygulama olarak kurulur. Kod `app/src/main/java/tv/lexia/launcher/`.
- **Yeni imza anahtarı:** `app/signing.p12` ilk derlemede iş akışı tarafından üretilir (`CN=LeXia`, takma ad `lexia`), `KEYSTORE_PASSWORD` sırrıyla kilitlenir ve depoya işlenir. Sır sonradan değiştirilirse derleme bozulur; anahtar silinirse TV'deki uygulama kaldırılıp yeniden kurulmalı.
- **Site yok:** tanıtım sitesi, sayaçlar, yönetim sayfası, kurulum yardımı, GitHub kopyası taşınmadı. Yalnızca bir **dosya sitesi** var (Cloudflare Pages projesi `lexia-tv`): `pick.json`, `version.txt`, `test.txt`, `apk/app.apk`, `apk/test.apk`, `kur.apk` (kurulum sürümü). Adresi derlemede Cloudflare'den sorulup uygulamaya yazılır (`BuildConfig.SITE`, ortam değişkeni `LEXIA_SITE`); ad alınmışsa Cloudflare sonuna ek koyar, uygulama yine doğru adresi bilir. Adres her derlemenin "Site address" adımında yazar.
- **Hiçbir şey sayılmaz/gönderilmez:** `Remote.ping` ve `Remote.sendCrash` boş; sürüm oylaması kalktı. Hatalar yalnızca TV'de (Ayarlar › Hakkında › Son hata).
- **Liste yedeği eski sitede kalır:** `Backup` hâlâ `lexialauncher.pages.dev/api/backup`'ı kullanır (`Net.OLD_SITE`), böylece eski LeXia'dan alınan kod yeni uygulamada geri yüklenir. Eski Cloudflare projesi silinirse yedek çalışmaz; o zaman bu depoya küçük bir Pages Function taşınmalı.
- **Uzaktan ayarlar yok** (kullanıcı, 12 Kasım: "yönetim, sayaç, güncelleme ekranı duyurusu gerek yok"): `remote.json` silindi, `Remote.loadSaved/refresh` boş; duyuru, zorunlu güncelleme ekranı, sürüm bekletme, seçki, uzaktan gizleme/kapatma çalışmaz (hep varsayılan). **Yenilikler kartı** güncellemeden sonra çıkmaz (`WhatsNew.maybeShow` yalnızca "görüldü" işaretler; Ayarlar › Hakkında'da liste hâlâ var), `whatsnew.txt` her yayında yazılmak zorunda değil.
- **Oynatıcı (13 Kasım 2026):** Media3 ExoPlayer MP4/progressive, HLS ve MPEG-DASH (`.mpd`) biçimlerini doğrudan HTTPS kaynaklardan oynatır. Menü/INFO tuşuyla ses dili, altyazı ve hız seçilir; medya kumanda tuşları oynat/duraklat ve ±10 saniye sarma yapar. Tamponlamada yükleniyor göstergesi görünür. Geçici ağ bağlantısı ve HTTP 408/429/5xx hatalarında artan beklemeyle en çok üç yeniden deneme yapılır; HTTP 401/403/404 gibi hatalarda açıklayıcı mesaj gösterilir. Kaynak kullanıcı tarafından sağlanır veya yetkili sağlayıcının belgelenmiş API'sinden alınır; scraper/resolver yoktur.
- **Ana ekran eklenti eşleştirmesi (13 Kasım 2026):** Ayarlar › Eklenti eşleştirme: kapalı / tüm depolar / favori depo. `AddonMatcher` ana ekrandaki günlük film/dizi başlıklarını yalnızca addon katalog ve meta metadata uçlarında ad + yayın yılıyla eşleştirir. Manifest kataloglarında `extra.search` ilan edilmişse arama parametresi kullanılır; ilan edilmemiş kataloglarda katalog başına en çok 3 sayfa (100 kayıt/sayfa) tarama yedeği korunur. Bulunan eşleşme hero bilgi rozetinde görünür; rozete basınca metadata yeniden yüklenir ve mevcut güvenli doğrudan HTTPS kaynak girişiyle oynatma akışına geçilir. Kaynak/stream uçları taranmaz veya çözülmez. Eşleştirme arka planda yapılır ve sonuç cihazda önbelleğe alınır; gerçek TV'de ağ/performans ve kumanda testi gerekir.
- **Eklenti kapsamı (12 Kasım 2026, güncellendi):** Kullanıcı Nuvio entegrasyonu kararını değiştirdi. Hedef, Nuvio'nun addon/repo mimarisini mümkün olduğunca yakından uyarlamak; depo/manifest yönetimi, katalog ve standart meta uçlarından detay sayfası, bölüm listeleri, izleme ilerlemesi ve oynatıcı altyapısını tamamlamaktır. Kaynak sağlayıcıları yalnızca belgelenmiş API'ler veya kullanıcının kullanım hakkına sahip olduğu yayın uçları üzerinden bağlanır; yetkisiz üçüncü taraf yayınlarını kazıyan/çözen scraper-resolver kodu taşınmaz. Mevcut eklenti akışı metadata/katalog/bölüm listesi ve doğrudan HTTPS medya adresiyle sınırlıdır; kaynak uçları çözülmez. Media3 oynatıcı ilerlemeyi kaydeder, yeniden açılışta kaldığı saniyeden devam eder; bölüm listesi ilerlemeyi gösterir ve son tamamlanmamış bölüme hızlı devam sunar. CI geçse bile gerçek TV kumandası ve oynatma akışı ayrıca cihazda denenmelidir.
- **İş akışları:** `build.yml` (main'e push: anahtar → site adresi → iki APK → `latest` sürümü → yayın), `daily-pick.yml` (02:17 UTC film listesi → yayın), `publish.yml` (dosya sitesini kurar).
- **Depo sırları (4):** `KEYSTORE_PASSWORD` (kullanıcının seçtiği yeni parola), `TMDB_KEY`, `CLOUDFLARE_API_TOKEN` (Pages: Edit; Kasım 2027'de bitiyor), `CLOUDFLARE_ACCOUNT_ID`.
- **İlk kurulum:** TV'de Downloader ile `<site adresi>/kur.apk`; sihirbaz tam sürüme günceller. Sonraki güncellemeler uygulama içinden.
- **Dosya deposu kalıyor (13 Kasım, kullanıcı onayı "A kalsın"):** kullanıcı "site" sözünden web sitesi sandı; bunun sayfasız bir dosya deposu olduğu anlatıldı. Kullanıcıya "site" değil "dosya deposu" denir. Seçenekler (depo yok: film listesi ve otomatik güncelleme yok; herkese açık GitHub kopyası: kullanıcı adı görünür) reddedildi.
- **Dosya sitesi adresi: https://lexia-tv.pages.dev** (kurulum: `/kur.apk`). Bu oturum ortamından erişim kapalı (proxy reddediyor), dosyalar buradan doğrulanamaz.
- **Durum (12 Kasım gecesi):** dört sır eklendi; ilk derleme (#3) ve film listesi yeşil, imza anahtarı oluştu, site yayınlandı. TV'ye kurulum **henüz denenmedi**.
- **GitHub yedeği yok (13 Kasım):** #3 güncellemeyi GitHub sürümünden almaya çalışıyordu; depo gizli olduğu için dışarıdan indirilemez (404) ve kullanıcı adı APK'ya giriyordu, kaldırıldı. Güncelleme ve film listesi yalnızca dosya sitesinden. APK'ya kullanıcı adı ya da depo adresi yazılmaz.
- **Dikkat (eski notlardan):** kullanıcının ev Wi-Fi'sinin DNS'i `pages.dev`'i açmıyordu (telefonda DNS 1.1.1.1 yapınca düzeldi); eski uygulama bunun için herkese açık GitHub kopyasına düşüyordu, burada kopya yok. TV ulaşamazsa: TV'nin DNS'ini 1.1.1.1 yapmak ya da kendi alan adı.

Aşağısı eski projenin notlarıdır (çalışma biçimi, özellikler, kararlar geçerli; site, kurulum adresleri, arkadaşlar, PR numaraları **tarihçe**; dosya yollarındaki `com/oomrtoo/tvlauncher` burada `tv/lexia/launcher`).

---

# LeXia Launcher: proje durumu ve devir notları

Bu dosya, yeni bir sohbete geçildiğinde hiçbir şeyin kaybolmaması için tutulur.
**Her değişiklikte (özellik, düzeltme, karar) bu dosya da aynı PR'da güncellenir.**
Bağlam başka bir oturumda sıfırlanırsa önce bu dosya okunur.

Son güncelleme: 12 Kasım 2026 · son PR: #125

---

## 0. LeXia herkese KAPALI (12 Kasım 2026 akşamı, kullanıcı kararı)

Kullanıcı: "Kapat her şeyi, kimseye haber verme; başka bir repo açıp sadece kendime yapacağım." #125 ile:
- Tanıtım sitesi ve `/anasayfa/` kalktı (ana sayfa boş "yayında değil", her şey noindex); `/kur` ve `/dl` 410; sitede ve GitHub kopyasında (`exxsss/Lxialnch`) kurulum APK'sı, herkese açık APK ve `version.txt` yok. Duyuru yapılmadı.
- **Kalan (kullanıcının kendi TV'si çalışsın diye):** `pick.json`, `remote.json`, `test.txt` + `apk/test.apk` (test cihazı güncellemesi), `/admin/`, `/api/*`. Yani başkalarının kurulu TV'leri de film listesi almaya devam ediyor ama güncelleme alamıyor; kullanıcı isterse bu da kesilir (o zaman kendi TV'si de yeni deposuna geçene kadar listesiz kalır).
- `remote.json`: `minVersion` 0 (eski sürümdekiler "Güncelleme gerekli" ekranında kilitli kalmasın), `dlcode` yok. Downloader kodu 7036535 artık ölü adrese gidiyor.
- **Cloudflare projesi ve kopya depo silinmedi** (ad boşa çıkarsa başkası alıp kurulu TV'lere içerik verebilir); silmek kullanıcının işi ve önerilmez.
- Geri açmak: #125'i geri al, site yeniden yayınlanır.
- **Nuvio entegrasyonu kararı (12 Kasım 2026, güncellendi):** Önceki “Nuvio entegrasyonu yapılmayacak” kararı kaldırıldı. `feature/nuvio-addon-base` dalında Nuvio'nun addon/repo, katalog, metadata, bölüm ve izleme mimarisi mümkün olduğunca yakından uyarlanacak; yetkili sağlayıcılar için kaynak arayüzü ve Media3 oynatıcı akışı eklenecek. Üçüncü taraf sitelerden izinsiz yayın bulma, sayfa kazıma veya gizli stream URL çözme işlevleri kapsam dışıdır. Bu sınır, proje notlarında açıkça korunur. Diğer işlerde çalışmadan önce `origin/main` alınır.
- Aşağıdaki bölümlerde site, kurulum, arkadaşlar ve forumla ilgili notlar artık tarihçe.

## 1. Çalışma biçimi (kullanıcıyla anlaşmalar)

- **Dil:** Türkçe. Kullanıcı telefondan okuyor: kısa, sonuç önce, sade mesajlar. Teknik terimleri azalt.
- **Önce sor (12 Kasım, kullanıcının yeni kuralı):** "Sorduğum şeylere başlamadan önce sor her zaman: fikir, öneri, onay."
  Kullanıcı bir şey istediğinde ya da bir sorun bildirdiğinde **önce ne yapacağını kısaca anlat ve onay al**, sonra başla.
  Kod değişikliği, birleştirme ve herkese yayın dahil. (Eski "sorma, yayınla" kuralının yerine geçer.) Onaylanan iş bitince
  derleme yeşilse birleştirilir; test düzeni açıksa önce test cihazlarına gider.
- **"Yap" denince sorma (12 Kasım akşamı, kullanıcı):** "Her seferinde bana sorma; ben yap diyorsam direkt gir." Kullanıcı bir işi isteyince ya da bir hatayı bildirip "yap/düzelt" deyince doğrudan yapılır ve **test cihazına** gönderilir, sonuç bildirilir. Yine önce sorulanlar: herkese yayın (`hold` değişimi), geri alınamaz/silici işler, kullanıcının istemediği yeni fikirler (öneri olarak sunulur). Hata bildirimi "yap" içermiyorsa kısa teşhis + düzeltme aynı mesajda yapılabilir.
- **Mükemmeliyetçi kullanıcı:** Yazım hatası, görsel kusur, kod hatası istemiyor. Premium, Apple TV hissi istiyor.
  Önerilerde aynı fikirleri tekrarlama; kullanıcı bunu fark ediyor ve sıkılıyor.
- **Güvenlik:** Uygulamayı sadece kendisi kullanıyor, ama güvenlik riski istemiyor.
- **Doğrulama:** Gerçek TV bu ortamda yok. Kullanıcı TV'nin fotoğrafını atıyor; görsel hatalar böyle bulunuyor.
  Mesajlarda "TV'de henüz denenmedi" notu dürüstçe düşülür.
- **Abisinin TV'si:** Kurulum denendi, kurulmadı, **bırakıldı** (nedeni bulunamadı).
- **Arkadaşlar (9 Kasım):** Kullanıcı artık **lexialauncher.pages.dev**'i paylaşıyor (depo gizli); başka marka TV'lerde de kurulacak.
  Değişiklikler yalnızca Grundig'e göre değil, genel Google TV / Android TV'ye göre de düşünülür. README'deki kurulum
  adımları teknik bilgisi olmayan biri için yazıldı (Downloader → kurulum APK'sı → sihirbaz); kurulum akışı değişirse
  README de aynı PR'da güncellenir.
- **Yenilikler kartı:** Her yayında `app/src/main/assets/whatsnew.txt` o sürümün yeniliklerine göre yeniden yazılır
  (kısa, kullanıcı dilinde, en fazla 9 satır). Güncellemeden sonra TV'de bir kez gösterilir. Kullanıcı arada sürüm
  atlayacaksa (TV'ye henüz kurmadıysa) liste, TV'deki sürümden bu yana gelen her şeyi kapsar; sadece görünmez
  düzeltmeler içeren yayında liste değiştirilmez.
- **Her yayında Yenilikler kartı (12 Kasım, kullanıcı isteği):** küçük düzeltmeler de dahil her APK yayınında `whatsnew.txt` güncellenir, kart her güncellemede çıkar (yukarıdaki "liste değiştirilmez" istisnasının yerine geçer). **Siteyle ilgili değişiklikler kartta asla yazılmaz** (kullanıcı, 12 Kasım); kartta yalnızca TV'de değişenler.
- **Açık (12 Kasım):** #113 ve #116 yalnızca test cihazında (`hold` 187); kullanıcı TV'de deneyip fotoğraf atacak, sonra herkese yayınlanacak. Downloader kodu 7036535 gerçek TV'de denenmedi (hedef `kur.apk` doğrulandı). Ücretsiz izle #117'de tamamen kaldırıldı (TMDB'de TR ücretsiz bilgisi çoğunlukla JustWatch TV Beta, Artiflix gibi bilinmeyen servisler; tabii yok, puhutv 1 film).
- **Nuvio/korsan (12 Kasım, tekrar):** kullanıcı Nuvio eklentisi, "Nuvio'da ara" düğmesi ve bağlantısını ısrarla istedi; **yapılmadı, yapılmayacak** (sadece kendi TV'si için de). Para tasarrufu hedefi yasal yoldan karşılandı (#116).
- **Mesaj sonu:** Yayınlanan sürüm numarası ve kullanıcının TV'de ne yapacağı (genelde "Güncelleme geldi" düğmesi) yazılır.

## 1a. Açık işler (yeni sohbet buradan başlasın)

- Kullanıcı kısa kurulum adresini (`lexialauncher.pages.dev/kur`) **gerçek TV'de Downloader ile henüz denemedi**; paylaşmadan
  önce denemesi istendi (kurulu sürüm daha yeni olduğu için sonda "yüklenmedi" çıkması normal).
- Cloudflare API anahtarı **Kasım 2027'de** sona eriyor; yenilenip GitHub sırrı `CLOUDFLARE_API_TOKEN` güncellenmeli.
- Yönetim sayfasının adresi artık **lexialauncher.pages.dev/admin/** (eski oomrtoo.github.io adresi depo gizli olduğu için 404).
- Yönetim sayfası anahtarı (GitHub fine-grained, 90 gün, Only select repositories › TvLauncher) **Şubat 2027 civarı**
  yenilenmeli; izinler: **Contents: Read and write** + **Actions: Read and write** (durum ve "Film listesini şimdi yenile" için).
- **GitHub kopyası çalışıyor (#75, #76):** `exxsss/Lxialnch` her site yayınında güncellenir (10 Kasım'da doğrulandı: raw adresi 200).
  Eski TV'ler için kurulum adresi `raw.githubusercontent.com/exxsss/Lxialnch/main/kur.apk`, kurulu ama güncellenemeyen TV'ler için
  `…/main/app.apk` (tam sürüm, üstüne kurulur). Kullanıcının TV'si (#97) ve arkadaşların takılı TV'leri bunu **henüz denemedi**.
  GitHub Pages (`exxsss.github.io/Lxialnch/`, daha kısa adres) açıldı mı bilinmiyor: bu ortamdan github.io'ya erişim yok; kullanıcı
  telefondan doğrularsa sitedeki adres kısaltılabilir. Anahtar `DIST_TOKEN` yeni hesabın fine-grained anahtarı (süresi kullanıcıda).
- **İki sohbet aynı anda çalıştı (10 Kasım):** diğer oturum #74'te ana depoyu yeniden herkese açık yapmaya hazırlandı; kullanıcı
  bu sohbette ayrı hesap/depo yolunu seçti (ana depo **gizli kalıyor**). Depoyu açmak artık gerekmiyor.
- **Test yayını açık (hold 141 → 183):** kullanıcı TV'sini test cihazı yaptı mı bilinmiyor; uygulama içi fragman yalnızca test cihazında.
- **Test yayını (#82):** yönetim › Yayın › "Yeni sürümler önce test cihazlarına gitsin". Açıkken `hold` = onaylanan sürüm; yeni
  derlemeler yalnızca test TV'lerine gider, "#N'i herkese yayınla" `hold`'u N yapar. Kullanıcının TV'si test cihazı yapılmalı
  (Ayarlar › Hakkında › Yüklü sürüm'de OK'e 7 kez). İlk deneme henüz yapılmadı.
- **Evdeki Wi-Fi'de pages.dev açılmıyordu (10 Kasım):** kullanıcının telefonunda site mobil veride açıldı, ev Wi-Fi'sinde açılmadı;
  telefonun DNS'i `1.1.1.1` / `8.8.8.8` yapılınca düzeldi → internet sağlayıcısının DNS'i `pages.dev`'i engelliyor/yanlış çözüyor
  (sağlayıcı adı bilinmiyor). Aynı sağlayıcıdaki TV'ler de siteye ulaşamaz; uygulama #75'ten beri GitHub kopyasına düşer.
  Kurulumda `/kur` yerine kopya adresi verilir. Kalıcı çözüm yine kendi alan adı.
- **Eski TV'ler (Android 7–13) lexialauncher.pages.dev'i açamaz** (sertifika ISRG Root X2'ye bağlı; #73). Uygulama #73'ten sonra kendisi tanır; Downloader/tarayıcı tanımaz → yeni kurulum ve #73 öncesinden güncelleme bu TV'lerde siteyle olmaz (USB/telefondan aktarım). Kalıcı çözüm bekliyor: alan adı ya da GitHub organizasyonu.
- **#102'den eski sürümler uygulama içinden güncellenemez** (GitHub'dan arar, depo gizli): Downloader ile `lexialauncher.pages.dev/apk/tvlauncher.apk`. Kullanıcının TV'si 10 Kasım akşamı #97'deydi.
- **11 Kasım akşamı herkese yayın (#106, #107):** #183 herkese, **zorunlu** (`minVersion` 183) ve test düzeni **açık** (`hold` 183: sonraki derlemeler önce test cihazlarına). Kullanıcı yönetimden yanlışlıkla test düzenini ve zorunluluğu kapatmıştı; #107'de geri açıldı. Bekleyen site metni uygulandı, patch silindi.
- **Erişilebilirlik kilidi (Android 13/14, `ACCESS_RESTRICTED_SETTINGS`):** Homatics Box R 4K Plus (Android 14) kullanıcısı TV menüsünde "Kısıtlanmış ayarlara izin ver" bulunamadığını, `adb shell cmd appops set com.oomrtoo.tvlauncher ACCESS_RESTRICTED_SETTINGS allow` ile çözdüğünü bildirdi. Önerilen: rehbere ve /anasayfa'ya son çare olarak bilgisayar yolu (IP'yi LeXia gösterir). Kullanıcı **"şimdilik kalsın"** dedi (11 Kasım).
- **#98** (Hafta sonu rehberi, Film gecesi, uygulama kartları, filme göre renk, yaylı seçim) TV'de denenmedi; cila turu için TV fotoğrafları istenecek.
- **#93 Ana Sayfa rehberi** (158+, test) TV'de denenmedi; yayınlanınca site metnine "adım adım gösterir" eklenebilir.
- TV'de henüz denenmeyenler: #80 (açılışta LeXia, tüm uygulamalar, favoriler, solda gizli uygulamalar, açılışta farklı film); #94–#102 özellikleri (zil, takvim, saate göre sıra, tarihte bugün, uzaktan ayarlar).

## 1b. Ad ve marka

- **Ad: LeXia Launcher** (kullanıcının seçimi, 9 Kasım). Kısa adı "LeXia"; Türkçe ekler ünlüyle: LeXia'ya, LeXia'yı, LeXia'nın.
  Uygulama adı (TV'de görünen) "LeXia Launcher", ayarlar "LeXia Ayarları", erişilebilirlik hizmeti "LeXia Ana Sayfa tuşu".
- **Amblem:** koyu yuvarlak kare içinde çaprazlanan iki ışık hüzmesi (sıcak: turuncu→pembe, soğuk: mavi→mor), kesişimde
  parlak beyaz nokta = "X". Yazıda L ve X aynı degrade (kullanıcı isteği) (#FFB36B → #FF4D8D → #8A6CFF → #6FB7FF). Kaynak: `docs/brand/brand.html`,
  üretim: `node docs/brand/render.js` → `res/drawable-nodpi/ic_launcher.png` (512, şeffaf köşe), `banner.png` (640×360,
  TV uygulama listesi), `docs/brand/emblem.png`, `docs/brand/social-preview.png` (1280×640).
- **Repo kapak görseli** API ile yüklenemez: kullanıcı GitHub › Settings › General › Social preview'dan
  `docs/brand/social-preview.png`'yi yükler.
- **Depo adı `TvLauncher` ve paket adı `com.oomrtoo.tvlauncher` değişmez:** güncelleme adresleri ve kurulu uygulamalar
  bunlara bağlı (paket adı değişirse TV'de ayrı bir uygulama olur). APK dosya adları da (`tvlauncher*.apk`) aynı kalır.

## 1c. Cloudflare'e geçiş (9 Kasım, tamamlandı)

**Durum:** A tamam (site: **https://lexialauncher.pages.dev**; "lexia" adı alınmıştı). B tamam (#42: uygulama film listesi,
`remote.json`, `version.txt` ve APK'yı önce siteden alır: `Net.SITE`, `Net.firstOf`; güncelleme indirmesi `/dl?v=N`;
GitHub adresleri yedek). **C tamam:** kullanıcı #102'yi kurdu ve depoyu **Private** yaptı (GitHub Pages kapandı).
Artık her şey **lexialauncher.pages.dev**'den: tanıtım sitesi, kısa kurulum adresi `/kur`, yönetim sayfası `/admin/`.
Depo gizli olduğu için raw.githubusercontent / jsDelivr / release yedekleri dışarıdan çalışmaz; tek kaynak site.
GitHub Actions gizli depoda ayda 2000 dakika ücretsiz (bir derleme ~2–3 dk). README ve `docs/admin` artık
yalnızca depo içi; kullanıcılar için kaynak `site/public`. Eski deneme kopyası "lexia" (lexia-6p5)
`cleanup-old-site.yml` ile silindi (elle çalıştırılır). Kullanıcı /kur'u gerçek TV'de Downloader ile **henüz denemedi**.

Kullanıcı profilinin ve diğer depolarının görünmesini istemiyor → dağıtım Cloudflare Pages'e taşınıyor, GitHub deposu
sonra **gizli** yapılacak. Plan: (A) site + sayaç + otomatik yükleme (#39) → (B) uygulamaya yeni adresi öğreten güncelleme
(yeni adres önce, GitHub yedek) → kullanıcı TV'ye kurar → (C) depo Private, GitHub Pages kapanır, README önemini yitirir.
- `site/public/`: tanıtım sitesi (`index.html`: amblem, ekran görselleri, özellikler, 4 adım kurulum + kısa adres `/kur`,
  sorun giderme, gizlilik; en üstte döngülü tanıtım: ana ekran → satırlar (seçim çerçevesi) → afişten film sayfasına büyüme →
  ekran koruyucu; uyumlu TV'ler; sık sorulanlar; link önizlemesi için tam adresli og:image) ve `admin/` (yönetim sayfası; sayılar `/api/stats`, ayarlar hâlâ GitHub API ile `remote.json`).
  `_headers` (admin noindex, önbellek süreleri), `robots.txt`.
- `_headers`'ta tüm siteye CSP var (yalnızca kendi dosyaları + api.github.com, görseller için image.tmdb.org); sayfaya dış adresten bir şey eklenirse oraya da yazılmalı.
- `site/functions/` (Pages Functions, KV bağlaması `STATS`): `/kur` (kurulum sayar: `install`, `install:gün`, `install:h:<UTC saat>` 3 gün, `install:src:<tv|phone|pc|other>:gün` (UA'dan tahmin); kurulum APK'sına yönlendirir),
  `/dl?v=N` (uygulama güncellemesi; `update:N`, `upd:gün` ve `updv:gün:N` sayar, en yeni tam APK'yı verir), `/api/hit` (ana sayfa ziyareti: `visit`, `visit:gün`, `visit:h:<UTC saat>`, `visit:src:<tür>:gün` + gelinen
  site adı; yalnızca `Origin` sitenin kendisiyse sayar), `/api/ping?v=N&d=1&w=1&m=1` (TV günde/haftada/ayda bir kez: `tv:gün`, `tv:gün:N`, `tvw:YYYY-Www`, `tvm:YYYY-MM`, `tvm:YYYY-MM:N`; `d` yoksa eski sürüm, günlük sayılır; `&h=b|d|a` ana ekran durumu → `home:gün:x`, `homem:ay:x`; ilk kez `&n=1` → `new:gün`; kurulum sürümü bir kez `?s=setup` → `setup:gün`; 60 gün sonra silinir),
  `/api/report` (sitedeki kurulum yardımının sonucu: `fix:<adım>:<tür>:y|n` toplamları, metin yok; POST yalnızca sitenin kendi sayfasından, `repn:gün` sınır 300; DELETE eski `rep:` yazılı bildirimleri siler),
  `/api/crash?v=N` (uygulama kapanınca bir sonraki açılışta hata metni; `err:<zaman>` 30 gün, `errs:gün`, günde en çok 100; TV/kişi bilgisi yok), `/api/stats` (sadece depoya yazma izni olan GitHub anahtarıyla; anahtar GitHub API'de doğrulanır).
- `.github/workflows/site.yml`: main'de site/remote.json/görsel değişince, "Build APK" ve "Daily pick" bitince ve elle;
  APK'ları `apk` dalından, `pick.json`, `remote.json`, görselleri ve yazı tiplerini toplar, KV'yi (`lexia-stats`) ve
  `lexialauncher` projesini ilk seferde oluşturur ("lexia" alınmıştı → lexia-6p5.pages.dev oldu, kullanıcı lexialauncher'ı seçti), `wrangler pages deploy` eder. Sırlar: `CLOUDFLARE_API_TOKEN` (Pages: Edit,
  Workers KV Storage: Edit; **1 yıl geçerli, Kasım 2027'de yenilenmeli**), `CLOUDFLARE_ACCOUNT_ID`.
- **Kimlik yok:** TV başına kimlik/liste tutulmaz (kullanıcı kararı, 10 Kasım: "özele girer"); her şey toplam sayı.
- **Sayım kuralları:** robotlar/önizlemeler/test araçları (UA'ya göre) ve sahibin tarayıcısı sayılmaz; testte `curl` kullanmak sayacı bozmaz. Sayılar karışırsa yönetim sayfasında "Sayaçları sıfırla".
- **Ölçek (Cloudflare ücretsiz plan):** statik dosyalar (APK, film listesi, remote.json, site) sınırsız. Functions günde 100.000
  istek (TV başına günde ~1–3). **Asıl sınır KV: günde 1.000 yazma** (TV başına günlük ~3, indirme ~4, ziyaret ~4 yazma) →
  günde ~200–250 aktif TV'den sonra sayılar eksik kalır, uygulama ve indirme etkilenmez. Çözüm: Workers Paid ($5/ay, ayda
  1 milyon yazma) ya da sayımları seyreltmek. KV okuma günde 100.000, listeleme 1.000 (`/api/stats` 2 dk önbellekli).
  GitHub Actions gizli depoda ayda 2.000 dk (derleme ~3 dk + günlük film listesi ~1 dk); TMDB tek sefer sunucuda, kullanıcı sayısından bağımsız.
- Yalnızca site/iş akışı değişen birleştirmelerde APK derlenmez (`site/**` build.yml'de yok sayılır).

## 2. Cihaz ve ortam

- **TV:** Grundig Google TV (Android TV tabanlı), Türkiye. Kumandayla (D-pad) kullanılıyor.
- **Kurulum:** Play Store dışı (yan yükleme), Downloader uygulamasıyla ya da uygulama içi güncellemeyle.
- **Play Protect:** Erişilebilirlik hizmeti içeren APK'yı ilk kurulumda engelliyor. Çözüm: "kurulum APK'sı" (bkz. 5).
- **Grundig'e özgü:** Standart Android ayar intent'lerinin bir kısmı çalışmıyor (erişilebilirlik, Bluetooth, ekran/ses,
  "bilinmeyen kaynaklar" izni tarayıcıya gidiyordu). `SystemSettings.kt` TV ayar uygulamasının kendi ekranlarını
  önce, standart intent'i sonra, ana ayarları en son dener.
- **Google TV'nin kendi ekran koruyucusu** (Google görselleri) bizimkinin yerine geçiyordu → `FLAG_KEEP_SCREEN_ON` ile
  süreli engelleniyor (bkz. 4.6).
- **Ana Sayfa tuşu:** Google TV, varsayılan launcher seçilse bile Ana Sayfa'yı kendi ekranına gönderiyor. Bu yüzden
  `HomeButtonService` (erişilebilirlik hizmeti) var; kullanıcı onu TV ayarlarından bir kez açtı.

## 3. Depo ve dosya haritası

Depo: `OoMrToO/TvLauncher` (**gizli**, 9 Kasım'dan beri; GitHub Pages kapalı). Çalışma dalı: oturumun verdiği `claude/...` dalı (oturuma göre değişir; her seferinde `origin/main`'den yeniden başlatılır).
Paket adı: `com.oomrtoo.tvlauncher`. minSdk 24, targetSdk 34, Kotlin 1.9.24, AGP 8.5.2, **hiç kütüphane yok** (düz Android View'lar).

`app/src/main/java/com/oomrtoo/tvlauncher/`

| Dosya | Görevi |
|---|---|
| `MainActivity.kt` | Ana ekran: üst çubuk, hero (günün filmi), uygulama rafı, alt satırlar, ekran koruyucu, menüler, hızlı panel |
| `DetailActivity.kt` | Film/dizi detay sayfası (oyuncular, benzer yapımlar, fragman, Listem, hatırlat) |
| `SettingsActivity.kt` | tvOS tarzı ayarlar; sayfalar: ana, görünüm, filmler, satırlar, uygulamalar, ekran koruyucu, güvenlik, bakım, hakkında, Google TV kurulum |
| `SetupActivity.kt` | İlk açılış sihirbazı |
| `HomeGuideActivity.kt` | Ana Sayfa tuşu rehberi (`HomeGuide.needed`: tam sürüm, hizmet kapalı ve LeXia varsayılan değil ya da Google'ın ana ekranı duruyor); Android 13+'ta 3, öncesinde 2 adım; ayar açılınca ✓ "Hazır" ve kapanır |
| `WeekendActivity.kt` | Hafta sonu rehberi: hikâye gibi sayfalar (Bu hafta vizyonda, Platformlara yeni gelenler, Hafta sonu yeni bölümler, Hafta sonu önerimiz); arka planda film görseli, afiş şeridi, Listeme ekle / Fragman / Detaylar / Sıradaki. Cuma 17:00'den pazar sonuna ilk açılışta haftada bir kendiliğinden (`WeekendActivity.due`, `Prefs.weekendSeen`, hafta pazartesi başlar), zilde Takvim'in altında her zaman |
| `MovieNight.kt` | Film gecesi: detayda "Bu akşam izle" (19:00–23:00 dilimleri, geçtiyse yarın), `Prefs.movieNight` {at, film}; üst çubukta "Film gecesi 21:00: …", zilde bildirim, ekran koruyucuda "Film gecesine 2 sa 10 dk"; saatinde tam ekran kart (görsel, platform uygulaması, Fragman, Detaylar, Kapat; `movieNightShown`), 3 saat sonra plan silinir. Ana ekran 30 sn'de bir bakar (`nightTick`) |
| `Updater.kt` | Uygulama içi güncelleme (kontrol, indirme, PackageInstaller) |
| `InstallReceiver.kt`, `UpdatedReceiver.kt` | Kurulum sonucu ve güncelleme sonrası ana ekranı yeniden açma |
| `HomeButtonService.kt` | Ana Sayfa tuşunu yakalayan erişilebilirlik hizmeti |
| `Prefs.kt` | Tüm ayarlar (SharedPreferences), Listem, hatırlatıcılar, satır sırası |
| `Pick.kt` | Film/dizi veri modeli (JSON okuma/yazma, URL'ye giren değerlerin doğrulaması) |
| `Apps.kt` | Yüklü TV uygulamalarını bulma ve sıralama |
| `Net.kt`, `Img.kt` | HTTP, disk önbelleği (80 MB), görsel yükleme, bellek sınırlı bitmap önbelleği |
| `Ui.kt` | Ölçü (`u()` 1920 px tasarım ızgarası), renkler, **`D` ölçü seti** (köşe: BADGE 6, CARD 16, PLATE 18, PANEL 34; süre: FAST 200, MED 300, SLOW 650), yazı tipi (Inter), `label()` (satır başlığı), pill/iconPill yardımcıları. Yeni kodda sayı yerine `D` kullanılır |
| `Fx.kt` | Parallaks eğim, ışık süpürmesi, kenar ışığı, bulanıklık, kayan saat (`RollingClock`), yağmur/kar (`WeatherLayer`) |
| `Surprise.kt` | "Ne izlesem?" (tür → süre → IMDb ağırlıklı rastgele) |
| `Sleep.kt` | Uyku zamanlayıcısı (bitiş saati, 1 dk önce uyarı, tuşla 30 dk erteleme) ve `NightSleep` (her gece uyku, AlarmManager) |
| `Episodes.kt` | Dizilerin yeni bölüm tarihleri (`pick.json` › `episodes`), afiş şeridi ve detay satırı metinleri |
| `Preview.kt` | Ayarların solundaki canlı önizlemeler: ekran koruyucu ve küçük ana ekran |
| `Sound.kt` | Sesler (`Prefs.soundSet` soft/classic, `navSound` kapalı): gezinme tıkı (`tick_soft.wav` / eski `tick.wav`), `Sound.event(OPEN/BACK/ADD)` → `whoosh.wav` (film sayfası açılır), `back.wav` (geri), `ding.wav` (Listem'e ekle); wav'lar Python ile sentezlendi. Aynı dosyada **`Press`**: `Sound.attach` pencere geri çağrısını sarar; OK'e basınca odaklı görünüm %5 içe göçer, basılı tutulabilen (`isLongClickable`) şeyde 170 ms sonra dolan halka (`RingDrawable`, decorView overlay, uzun basma süresiyle biter) |
| `WhatsNew.kt` | Güncelleme sonrası "Yenilikler" kartı (`assets/whatsnew.txt`) |
| `CalendarActivity.kt` | Takvim: 14 gün içinde TR vizyonları (`soon`) ve yeni bölümler (`Episodes.upcoming`), gün gün satırlar; üstte seçili kartın görseli ve bilgisi. Kaydedilmiş `pick.json`'u kendisi okur |
| `Remote.kt` | Sahibin uzaktan ayarları: `remote.json` (`message` {id, title, text, until}, `minVersion`, `off` [bell, calendar, mood, history, genres, sound, surprise, weekend, night], `hold` (bu sürümden yenisine güncelleme yok), `pin` {k "movie:ID", t, note, until} (hero'da ilk, etiket "LEXIA SEÇKİSİ"), `hide` [{k, t}] (her listeden, takvimden çıkar), `filter` {adult, minImdb} (`isHidden` içinde), `rowsOff` [Prefs.ROWS kimlikleri],
`defaults` {saverMin, saverContent, saverSec, appSort, ambience, weather, navSound, lite, autoUpdate} (yalnızca ilk 2 günlük kurulumlara bir kez; `defaultsDone`),
`site` {text, until} (tanıtım sitesinin üst şeridi; uygulama okumaz), `dlcode` (Downloader kısa kodu, 4–8 hane; yalnızca site okur). `message`/`pin` için `from` "yyyy-MM-ddTHH:mm" (TV saati); ana ekran 5 dk'da bir `remoteTick` ile bakar). Film listesiyle birlikte çekilir, TV'de kopyası tutulur (`loadSaved`), değerler doğrulanır. Günde bir kez `/api/ping?v=N` (yalnızca sürüm no) |
| `Mood.kt` | Havaya göre film: Open-Meteo kodu + sıcaklık + günün saati → tür ve cümle ("Yağmurlu bir akşam için sıcacık bir dram.") |
| `SystemSettings.kt` | Grundig uyumlu sistem ayar ekranı açıcı |
| `CityPicker.kt`, `Dialogs.kt` | Şehir arama; pencereler: `list` (LeXia kartı: ikon, başlık, açıklama, satırlar + sağda değer, ✓; `choose`/`chooseOne` buna gider), `confirm` (aynı kart, Evet/Vazgeç), `input`/`pin` (hâlâ sistem penceresi, klavye için) |
| `App.kt` | Disk önbelleği kurulumu, çökme günlüğü (`Ayarlar › Hakkında › Son hata`) |
| `Guard.kt` | **Çökme kalkanı** (`lexia_guard` dosyası): açılıştan sonraki 30 sn'de çökme sayılır; 2'de bir sonraki açılış yeniden kurulabilen verileri siler (`pick.json`, `remote.json`, önbellek, küçük "görüldü" anahtarları; Listem/PIN/seçimler kalır), 3'te ana ekran yerine `SafeActivity` (sade sayfa: Güncellemeyi denetle / Yeniden dene / TV ayarları). Ana ekran 20 sn açık kalınca sayaç sıfır; yeni derleme her zaman temiz başlar |

Diğer: **`docs/admin/index.html`: LeXia Yönetim sayfasının eski kopyası** (GitHub Pages kapandı; kullanılan sürüm `site/public/admin/`,
adres `lexialauncher.pages.dev/admin/`). Sahibin fine-grained GitHub anahtarıyla (sadece bu depo;
Contents: Read and write, Administration: Read-only) çalışır, anahtar yalnızca tarayıcıda (oturum ya da "hatırla" ile localStorage).
Gösterir: son sürüm, kurulumlar, bugün açılan TV ve TV'lerin sürümleri, 14 günlük grafikler (açılan TV, kurulum, ziyaret),
derleme/film listesi/site yayını durumu, sürüm başına güncelleme indirmeleri, gelinen siteler, `remote.json` değişiklik geçmişi.
Değiştirir: `remote.json` (duyuru + TV önizlemesi, seçki filmi, film gizleme, güncelleme bekletme, zorunlu güncelleme, özellik
kapatma); "Film listesini şimdi yenile" ve "Siteyi yeniden yayınla" (Actions workflow_dispatch). Afişler image.tmdb.org'dan (CSP'de izinli). claude.ai Artifact olamadı: sayfalar dış adrese
bağlanamıyor ve kullanıcının GitHub bağlayıcısı yok. `README.md` (tanıtım sayfası; görseller `docs/img/*.jpg`), `docs/screens.html` + `docs/render.js` (README görsellerini
uygulamanın ölçüleriyle çizen sayfa; `node docs/render.js` ile yeniden üretilir. Filmler kurgusal, görseller SVG çizimi:
herkese açık sayfada gerçek film görseli kullanılmaz; ayrıca bu ortamdan image.tmdb.org'a erişim kapalı),
`docs/promo/promo.html` + `render.js` (başka sitelerde tanıtım için, sitedekilerden **farklı** görseller: `ozet`, `secki`, `zil`,
`takvim`, `neizlesem`, `tarihte`; `node docs/promo/render.js`; `docs/promo/anasayfa/` = Ana Sayfa tuşu adım adım paylaşım görselleri (giriş, 3 adım, hazır; `node docs/promo/anasayfa/render.js`, TV ayar ekranları temsili çizim); ikon çiziminde grup dönüşleri de uygulanır), `scripts/pick.py` (günlük film verisi), `pick.json` (çıktısı, main'de), `.github/workflows/` (build.yml, daily-pick.yml),
`app/src/setup/AndroidManifest.xml` (kurulum sürümü), `app/signing.p12` (şifreli imza anahtarı), `app/src/main/assets/fonts/` (Inter, OFL).

## 4. Özellikler (şu an çalışan)

### 4.1 Ana ekran
- Üst çubuk (soldan sağa): selamlama + tarih · güncelleme düğmesi ("Güncelleme geldi", nabız atar) · "Ne izlesem?" ·
  hava ikonu + derece · kayan saat · **zil** · arama · ağ (Wi-Fi/kablo, çevrimdışıyken soluk) · dişli (hızlı panel).
- **Bildirimler (zil):** sağdan kayan buzlu panel; en üstte "Takvim", sonra bildirimler: Listem dizilerinde bugün/yarın yeni
  bölüm, hatırlatılan filmlerin vizyonu (−3…+3 gün), 3 gün içinde vizyona girecek filmler, yeni sürüm. Görülmemiş varsa zilde
  pembe nokta; panel açılınca hepsi görüldü sayılır (`Prefs.noticesRead`).
- **Hero:** günün filmi (tam ekran görsel, Ken Burns, film logosu ya da başlık, IMDb rozeti, yaş sınırı rozeti, yıl · süre · tür, öneri cümlesi,
  özet), düğmeler: Fragmanı İzle · Detaylar · Listeme Ekle · Sonraki; tek ince ilerleme çubuğu + "3 / 20" sayacı.
  Filmler 15 sn'de bir değişir (ayarlanır), arada sinematik kararma.
- **Uygulama rafı:** sadece TV (leanback) uygulamaları, kendi banner görselleriyle. Sıralama (`appSort`): Benim sıram /
  Son kullanılan önde / **Saate göre** (her açılış sabah 5–12, öğle 12–17, akşam 17–22, gece dilimine sayılır, `Prefs.usage`;
  o dilim + komşu dilimlerin %30'u). Eski `recentFirst` ayarı `appSort`'a taşınır. Seçilince büyür, parallaks eğilir, kenar ışığı,
  altında ad + "Dün açıldı" gibi son açılış. Arka plana uygulamanın rengi yansır. Basılı tut: Taşı · Uygulama bilgisi · Kaldır (PIN'li).
- **Alt satırlar** (sırası ve gizlenmesi kullanıcıya ait, `Prefs.rowOrder`/`hiddenRows`): Top 10 film · Top 10 dizi ·
  Şu an sinemalarda · Yakında vizyonda · Sana göre ×3 (`g1`–`g3`) · Listem. Yeni eklenen satır kimlikleri eski kayıtlı sırada
  varsayılan yerine (Listem'den önce) girer. **Sana göre:** `pick.json` › `genres` (13 tür × 10 film); önce Ayarlar'da seçilen
  türler, sonra en çok beğenilen türler, sonra her gün değişen türler; izlenenler çıkar, en az 4 film kalan tür gösterilir. Ekran tvOS gibi kayar; sadece odaklı satır ve üstündeki görünür, diğerleri solar.
  Afişte basılı tut: Satırı taşı (▲▼) / Satırı gizle.
- **Ekran koruyucu:** 5 dk (ayarlanır) tuşa basılmazsa yazılar solar. İçerik (`saverContent`): **Karışık** (varsayılan: iki sinema
  filminde bir "Sinema tarihinde bugün"), Sinema köşesi, Sinema tarihinde bugün, Günün filmleri. Tarihteki film: etiket "SİNEMA
  TARİHİNDE BUGÜN", "25 yıl önce bugün gösterime girdi". Sinemadaki ve yakında vizyona girecek filmler karışık akar
  (ad, IMDb, yıl, tür; filmin sloganı büyük ince yazıyla, slogan yoksa özeti), saat (Büyük/Normal/Gizli) ve yanında hava.
  Film değişim hızı ayrı (`saverSec`). İlk tuş sadece uyandırır. "Kumandaya basılana kadar" seçeneği var.
  Ayarlardaki "Şimdi önizle" `Saver.previewRequested` ile ana ekrana dönüp hemen başlatır.
- **Dizi takvimi:** Top 10 dizi ve Listem'deki dizilerde afişin altında beyaz şerit ("Yeni bölüm · Cuma"); bölüm günü
  ana ekranda "Bugün yeni bölüm: …" düğmesi (Listem dizileri için, açılınca bir daha çıkmaz: `episodesSeen`).
- **İzledim / Beğendim** (detay sayfası, ikonlu düğmeler): izlenen film hero'dan, "Ne izlesem?"den ve Sana göre satırlarından
  çıkar, Top 10/sinema afişlerinde sağ üstte ✓; Listem'deyse oradan çıkar. Beğenmek izlendi de sayar; beğenilen türler
  (`Prefs.liked`, film → türler) hero'da ve satır seçiminde öne gelir.
- **Havaya göre:** hava bilinince `Mood` bir film seçer (gün boyu aynı, izlenmemiş, beğenilen tür önce) ve hero'da mevcut
  filmin hemen arkasına girer, etiketi "HAVAYA GÖRE", öneri cümlesi havaya göre. Görünüm › Hava durumu kapalıysa yok.
- **Akıcı geçiş:** afişe basınca görsel büyüyüp ekranı kaplar, kararır, detay sayfası açılır (`Fx.zoomOpen`); dönüşte siyah
  kat yavaşça kalkar (`Fx.zoomReset`). Hafif modda yalnızca kararma.
- **Gezinme sesi** (varsayılan açık) ve **hafif mod** (`Fx.lite`: eğilme, ışık süpürmesi, bulanık cam, Ken Burns, yağmur/kar kapalı).
- **Yenilikler kartı:** güncellemeden sonraki ilk açılışta bir kez (`seenBuild`), yalnızca liste değiştiyse (`seenNews` parmak izi;
  #102'nin listesi `SHOWN_IN_102` olarak kodda); yeni kurulumda gösterilmez.
- **Uzaktan ayarlar** (`Remote`): duyuru ana ekranda bir kez kart (`WhatsNew.card`, `remoteSeen`) ve bitişe kadar zilde en üstte;
  `minVersion` üstündeyse tam ekran "Güncelleme gerekli" (ana ekran, ekran koruyucu, geri tuşu kapalı; "Şimdi güncelle");
  `off` listesindeki özellikler herkeste kapanır.
- **Hızlı panel (dişli):** Wi-Fi, Bluetooth, Ekran ve ses, Tüm Android ayarları, Launcher ayarları. Arkada buzlu cam.
- Hava ambiyansı: yağmur/karda çok silik damlalar/kar taneleri. Hava durumu Open-Meteo, konum IP'den ya da seçilen şehirden.
- Hatırlat: yakında vizyona girecek filmde "Hatırlat"; vizyon günü ana ekranda "Bugün vizyonda: …" düğmesi.

### 4.2 Detay sayfası
Tam ekran görsel, slogan, IMDb, yaş sınırı, yıl/süre/sezon/tür, dizide "Yeni bölüm: Cuma, 14 Kasım · 2. sezon 5. bölüm", tam konu, yönetmen (dizide yaratıcı), fotoğraflı oyuncular, benzer yapımlar
(her biri kendi sayfasını açar), fragman, Listeme Ekle, Hatırlat (yakında olanlarda), "Başka öner" ("Ne izlesem?"den gelince).

### 4.3 Ayarlar (Launcher ayarları)
Satır tipleri: `toggle` (Açık/Kapalı) ve `choice` (adlı değerler). Seçili satırda değer `‹ 5 dk ›` görünür; **sağ/sol** değeri
değiştirir (uçta küçük sekme), OK iki seçenekte çevirir, fazlasında işaretli liste açar (`Dialogs.chooseOne`). Seçili satır
beyaz plaka + koyu, kalın yazı. Her sayfanın (Görünüm, Filmler, Satırlar, Uygulamalar, Ekran koruyucu) altında
"Bu sayfayı varsayılana döndür" (`Page.keys` → `Prefs.resetKeys`; ad, şehir, Listem, PIN korunur).
Sayfalar: Ana ekran (varsayılan launcher, Ana Sayfa tuşu, Google TV kurulum rehberi) · Görünüm (solda canlı küçük ana ekran;
arka plan, kart boyutu, karşılama + ad, saat biçimi, hava durumu, hava ambiyansı, şehir, gezinme sesi, hafif mod) ·
Filmler (öneriler, otomatik geçiş + süre, sinema köşesi, sevilen türler) · Ana ekran satırları · Uygulamalar ·
Ekran koruyucu ve uyku (solda canlı önizleme; Şimdi önizle, başlama süresi, içerik, film değişim hızı, saat, hava durumu,
açık kalma süresi, uyku zamanlayıcısı "Kapanış 23:40 · 52 dk kaldı", her gece uyku 22:30–02:00) · Güvenlik (4 haneli PIN) ·
Güncellemeleri denetle · Otomatik güncelleme · Bakım · Hakkında (sürüm, bu sürümde neler var, film listesi tarihi, en yeni sürüm,
kurulum sihirbazı, son hata).

Uyku: son dakikada uyarı (Toast). O sırada herhangi bir tuş 30 dk erteler; başka uygulamadayken tuşu `HomeButtonService`
yakalar (kurulum sürümünde yalnızca ana ekranda). Her gece uyku: uygulama içi zamanlayıcı tam dakikada tetikler; Android 12+
`setWindow` alarmı 10 dk'ya kadar gecikebildiği için yalnızca yedektir (ikisi aynı geceyi iki kez başlatmaz: `handled`).
TV kapalıyken birikmiş alarm sabah açılışta uyutmasın diye 15 dk'dan geç gelen ve ekran kapalıyken gelen tetik yok sayılır.
Ekran koruyucudaki hava durumu, Görünüm › Hava durumu açıkken görünür (hava verisi üst çubuk için çekiliyor).

### 4.4 Film verisi (`scripts/pick.py`, her gün 02:17 UTC, elle de çalıştırılır; GitHub zamanlı işleri saatlerce geciktirebilir)
TMDB (ücretsiz) + IMDb'nin herkese açık puan dosyası. **Ücretli yapay zeka kullanılmıyor** (kullanıcı reddetti).
`pick.json` alanları: `picks` (20 film, IMDb ≥ 6,5, popülerliğe göre), `top10`, `top10tv`, `cinema` (Türkiye'de vizyonda),
`soon` (60 gün içinde, TR tarihli). Her kayıtta: başlık, afiş, görsel, logo, fragman (dizide tanıtım videosu de olur), puan, süre,
türler, oyuncular, yönetmen, benzer yapımlar, `release` (TR vizyon tarihi), `id`, `cert` (yaş sınırı: TR, yoksa ABD'ninki
Türk bandına çevrilir: "Genel", "7+", "13+", "18+"), dizide `next_ep`. Ayrıca `episodes`: bu haftanın Top 10 dizileri + son
120 günde Top 10'da görünmüş, bitmemiş diziler için sonraki bölüm tarihi (önceki `pick.json`'dan devralınır; böylece Listem'e
eklenen dizi listeden düşse de takvimi sürer). `history`: bugünün ay-gününde son 60 yılda (5 yıldan eski) gösterime
girmiş, TMDB'de ≥ 1500 oyu olan filmler; yuvarlak yıl dönümleri (5'in katı) önce, IMDb < 6,5 atılır, en çok 8 film, her kayıtta `years`. `genres`: 13 tür için TMDB discover (popüler, TMDB ≥ 6,8, ≥ 800 oy; sayfa
güne göre 1–4 döner), IMDb < 6,5 olanlar atılır. Dosya artık sıkıştırılmış JSON (boşluksuz). Repo sırrı: `TMDB_KEY` (daily-pick için).

### 4.5 Güncelleme zinciri
`Updater.kt`: sitenin `version.txt`'si (olmazsa 2 sn sonra bir kez daha), indirme `/dl?v=N` (sürüm başına sayılır), olmazsa
`/apk/tvlauncher.apk`. GitHub adresleri #53'te kaldırıldı (depo gizli, kullanıcı adı APK'da görünmesin); film listesi ve `remote.json` de yalnızca siteden. Güvenlik: her şey HTTPS; Android yalnızca
aynı anahtarla imzalı APK'yı güncelleme olarak kabul eder. Önce tam indirilir (3 deneme), sonra PackageInstaller'a
verilir. Android 12+ için `USER_ACTION_NOT_REQUIRED` istenir. Ana ekran açıkken 30 dakikada bir yeni sürüm aranır.
İzin yoksa tarayıcı yerine yol tarifi penceresi çıkar. Güncellemeden sonra ana ekran yeniden açılır.

### 4.6 Ekran koruyucu ve Google'ın ekranı
`holdScreen()`: film koruyucusu çalışabilecekken `FLAG_KEEP_SCREEN_ON`. En fazla `saverHoldMin` dk (15/30/60/120; varsayılan 60),
`0` = TV ayarına bırak, `-1` = kumandaya basılana kadar (TV kendiliğinden uyumaz). Başka uygulamaya geçince bırakılır.

## 5. Derleme, yayın, imza

- **build.yml:** `assembleFullRelease assembleSetupRelease` (#53'ten beri release: debuggable değil; lint release kontrolü kapalı); `main` ve `claude/**` dallarına her push'ta derler (yalnızca `.md`, `.gitignore`, `pick.json`, `docs/**`, `scripts/**`, `remote.json` değişince derlenmez; böylece not güncellemeleri TV'ye sahte "Güncelleme geldi" göstermez). İki APK:
  `full` (Ana Sayfa hizmetli) ve `setup` (hizmetsiz, sürüm kodu 1, derleme no 0; açılınca tam sürüme güncellenir).
  `main`'de ayrıca: `latest` sürümüne `tvlauncher-N.apk`, `tvlauncher.apk`, `tvlauncher-kurulum.apk` yüklenir ve **`apk` dalına**
  (force push, tek commit) `tvlauncher.apk`, `tvlauncher-kurulum.apk`, `version.txt` konur.
- **İndirme bağlantıları** (depo gizli; tek kaynak site):
  - Kurulum (kısa adres, sayılır): `https://lexialauncher.pages.dev/kur` → `/apk/tvlauncher-kurulum.apk`
  - En yeni tam APK: `https://lexialauncher.pages.dev/apk/tvlauncher.apk`; uygulama içinden `/dl?v=N`
- **İmza:** `app/signing.p12` (PKCS12, AES-256, 4096 bit RSA, takma ad `tvlauncher`). Parolası **yalnızca** GitHub secret'ı
  `KEYSTORE_PASSWORD`'da ve kullanıcıda; depoya/mesajlara yazılmaz. Secret yoksa CI bilerek hata verir. İmza değişirse TV'deki
  uygulama kaldırılıp yeniden kurulmalı. (Eski herkese açık anahtar kaldırıldı; #35'ten önceki sürümler eski anahtarla imzalıydı.)
- **Sürüm kodu** = `GITHUB_RUN_NUMBER`. Derleme no. `Ayarlar › Hakkında`da görünür.
- **Sırlar:** `TMDB_KEY`, `KEYSTORE_PASSWORD`. `ANTHROPIC_API_KEY` artık kullanılmıyor.

## 6. Geliştirme akışı (bu ortamda)

1. `git fetch origin main && git checkout -B <oturum dalı> origin/main`
2. Değişiklik → **tip kontrolü** (Android SDK yok, o yüzden): Kotlin derleyicisi (`kotlin-compiler-embeddable:1.9.24`) +
   `org.robolectric:android-all:14-robolectric-10818077` Maven'den indirilir (`mvn dependency:copy-dependencies`), `K2JVMCompiler`
   ile `app/src/main/java/**/*.kt` + `BuildConfig`/`R` taslakları derlenir. Hata yoksa devam.
3. `git commit` (trailer'lar ortam talimatına göre) → `git push -u origin <oturum dalı>`
4. PR aç → build yeşil mi bak (~1 dk) → `mcp__github__merge_pull_request` (`expectedHeadSha` 40 karakter olmalı)
5. `apk/version.txt` yeni numarayı gösterene kadar bekle, kullanıcıya haber ver.
6. Film verisi etkilendiyse `daily-pick.yml`'i `workflow_dispatch` ile çalıştır.
- Çıktıyı doğrularken `curl` ile indirme/JSON kontrolü yapılır; vektör ikonlar Playwright ile PNG'ye çizilip bakılır.
- Python önbellek dosyaları (`__pycache__`) depoya girmesin: `.gitignore`'da.
- **Ayar anahtarı kontrolü (#120):** `build.yml` her derlemede `scripts/check_prefs.py` çalıştırır; `scripts/prefs_keys.txt` LeXia'nın kullandığı her `SharedPreferences` adını türüyle listeler (koddan kalkanlar dahil). Yeni anahtar eklenince bu dosyaya da eklenir; eski bir ad başka türle kullanılırsa derleme kırmızı olur (#119'daki çökme).
- Bu oturumların ortamı: kişisel **TvLauncher** ortamı (lexialauncher.pages.dev'e erişim açık; siteyi canlı test etmek için).

## 7. Bilinen sınırlar ve dikkat edilecekler

- GitHub sürüm indirmeleri zaman zaman 504 verir → ayna dal ve yedek adresler bunun için.
- Play Protect: erişilebilirlik hizmetli APK ilk kurulumda engellenir → kurulum APK'sı. Kullanıcı bir kez Downloader ile
  kurduktan sonra güncellemeler uygulama içinden sorunsuz çalışıyor.
- Ana Sayfa hizmeti Google'ın launcher'ındaki ayar panellerini "ana ekran" sanıp geri atıyordu → bizim açtığımız ayar
  ekranlarında hizmet duraklatılır (`HomeButtonService.pause()`); ana ekran açılınca devam eder.
- Buzlu cam gerçek bulanıklık değil, ana ekranın küçültülmüş bulanık kopyası. Zayıf TV'ler için "hafif mod" var (#29).
- Depo gizli, yine de dosyalarda parola/anahtar yok (imza anahtarı şifreli, parolası GitHub sırrında).
- **Kod karıştırma (#54):** release APK R8 ile küçültülür ve sınıf/fonksiyon adları karıştırılır. "Son hata"daki adlar
  okunmazsa o derlemenin Actions ekindeki `mapping-N` dosyasıyla çözülür (retrace; #74'ten önce `apk` dalındaydı). Kodda yansıma (reflection) yok; eklenirse keep kuralı gerekir.
- **Kimlik izleri (değiştirilemez):** paket adı `com.oomrtoo.tvlauncher` ve imza sertifikası (`O=OoMrToO, CN=TV Launcher`) APK'yı
  inceleyen birine kullanıcı adını gösterir. Değiştirmek yeni uygulama demek (herkes kaldırıp yeniden kurar); 10 Kasım'da kullanıcıya soruldu.
- Görsel/ölçü tutarlılığı (boşluk, yazı boyutu, köşe yuvarlaklığı, animasyon süreleri) tek bir sisteme **henüz oturtulmadı**
  ("cila turu": #31'de `D` ölçü seti kuruldu; kartlar, rozetler, satır başlıkları ve odak/solma süreleri ona bağlandı).

## 8. Karar günlüğü (neden böyle?)

- **Korsan izleme yok:** Kullanıcı Nuvio benzeri "bas, izleme sitesinden (ör. hdfilmcehennemi) çekip başlat" eklentisi istedi;
  telif ihlali olduğu için **yapılmadı ve yapılmayacak** (kullanıcı da yasal olmadığını biliyor, ısrar etmedi). Yasal
  alternatifler (Nerede izlenir + yüklü uygulamada aç, tabii/YouTube ücretsiz, telifsiz klasikleri uygulama içi oynatma)
  önerildi; kullanıcı "alakasız" dedi, **tekrar önerme**. Sitedeki SSS'de "LeXia film izletir mi? Hayır" yazıyor.
- Yapay zeka önerileri **kaldırıldı**: kullanıcı ücretli API istemedi → TMDB + IMDb puan dosyası.
- Diziler önce kaldırıldı, sonra yalnızca **Top 10 dizi satırı** olarak geri geldi. "Ne izlesem?" ve günün önerileri sadece film.
- Ayarlar sade tutulur ama bölümlere ayrılmış; kullanıcı sonradan "hepsini kur" dedi.
- Ana Sayfa hizmeti: kaldırıldı (#8, Play Protect) → geri getirildi (#9; kullanıcı istedi, güncelleme içeriden kuruldu).
- Kullanıcı reddettiği öneriler (tekrar önerme): telefondan yönetim/QR, havadan manzara videoları, kendi fotoğrafları, maç kartı,
  namaz vakitleri, kur/altın şeridi, resmi tatil notları, hatırlatıcı notlar, çocuk modu/süre sınırı, doğum günü, hazır temalar.
  (Seçilenler ve yapılanlar bölüm 4'te.)
- Henüz yapılmayan ama konuşulan fikirler: "Nerede izlenir" (TMDB watch providers), gece modu, hareketli fragman arka planı,
  kumanda kısayolları, uygulama klasörleri, oyuncuya göre keşif, Top 10 değişim işareti, puan halkası,
  kod deposunu gizli yapıp ikinci depodan dağıtma, klasikler satırı (9 Kasım'da önerildi, seçilmedi).
- 9 Kasım (2. tur) önerilip seçilmeyenler: profiller (aile üyeleri), LeXia Özet (aylık/yıllık izleme özeti + kendi puanın).
- 9 Kasım'da önerilip **seçilmeyenler** (kullanıcı istemedikçe tekrar önerme): seriyi tamamla (koleksiyon), hava durumu
  sayfası, internet hız testi, depolama durumu, ayar yedeği, gece güncellemesi, bitiş saati ("23:40'ta biter"), Türkçe
  fragman önceliği, izlemeye devam et (Watch Next), açılış animasyonu, sıradaki film önizlemesi, HDMI/Canlı TV kısayolları,
  uygulama açılış (büyüyen kart) animasyonu, afişte hızlı menü, IMDb oy sayısı, film müziği düğmesi.

## 9. PR geçmişi (özet)

| PR | İçerik |
|---|---|
| #125 | **LeXia herkese kapatıldı** (bölüm 0): boş ana sayfa, `/kur` ve `/dl` 410, kurulum/herkese açık APK'lar ve `version.txt` siteden ve kopyadan kalktı, `minVersion` 0, `dlcode` silindi; `site.yml` yalnızca film listesi, `remote.json`, test derlemesi ve yönetim sayfasını yayınlar |
| #122 | **Yalnızca test.** (1) **Afiş sırası:** #120'nin tek ortak havuzunda öndeki ekranın afişleri arkadaki ana ekranın yüz afişinin arkasında kalıyordu (kullanıcı fotoğrafı: Hafta sonu rehberinde mor kutular) → `Img.run(activity, job)`: 6 iş parçacığı, öncelik kuyruğu; en son açılan etkinlik önce, etkinlik içinde istek sırasıyla. (2) **Dizilerin boş sayfası:** takvim/hafta sonu rehberindeki `episodes` kayıtlarında yalnızca ad + görsel vardı → `pick.py` `episodes(…, names)`: Top 10'dan düşmüş ama bölümü yaklaşan dizilere tam kayıt (`info`: `entry(…, True)`, IMDb dahil); uygulamada `Episodes.finder(o, listem)` diziyi önce günün listelerinde (top10tv, platforms, local, digital, Listem; id ya da görselle) arar, sonra `Show.info`, en son çıplak kayıt. (3) Zilde "Netflix: 4 yeni yapım" artık hepsini listeler (`showPlatformNews`; uygulama kuruluysa ilk satır "Netflix'i aç"; tek yapımsa doğrudan sayfası). TV'de denenmedi |
| #121 | **Yalnızca test.** Keşfet (kullanıcı: "birden 200 ne çekiyosun, seçim yapılabilir olsun"): sayfa 30 yapımla açılır, sonda "N daha göster" kartı sıradaki 30'u ekler (sayfa yeniden kurulmaz, odak ilk yeni karta), sayaç "30 / 112 yapım"; yeni filtre **Sırala**: En yüksek puan / En yeni çıkanlar / En yakında çıkacaklar / Ada göre (A–Z, Türkçe sıralama). TV'de denenmedi |
| #120 | **Yalnızca test.** Çökme kalkanı (`Guard.kt`, `SafeActivity`) ve derlemede ayar anahtarı kontrolü. **Hız** (kullanıcı: kasma, Keşfet uzun bekletiyor, sekmelerden atıyor): Keşfet listeyi arka planda okur, en çok 30 yapım (en yüksek IMDb; "En iyi 30 · toplam N"), afişler 4'lü havuzdan RGB_565; `Img.load` ve küçük resimler artık 5'li ortak havuzda (`Img.pool`; önceden her afişe bir iş parçacığı), büyük görseller (`original`/`w1280`) beklemez; `Fx.Shimmer` tek degrade + matris, ~30 kare/sn, 6 sn sonra durur (önceden her karede yeni degrade, sonsuz). Az bellekli TV'de (≤ ~2,5 GB ya da low-RAM) bir kez "Hafif mod açılsın mı?" (`Prefs.liteAsked`; kendiliğinden açmaz). Zilde bildirime gelince pembe nokta söner. Ana Sayfa rehberi "Ayarları aç": önce TV ayar uygulamasının ekranları, 1,5 sn sonra LeXia hâlâ öndeyse sıradaki yol, sonunda ana ayarlar + yol yazısı (Grundig standart isteği sessizce yok sayıyordu). Gün ışığı/okunurluk katmanları birleştirilmedi (alpha 0 iken zaten çizilmiyor); ekrandan çıkan satırların görsellerini bırakma yapılmadı. TV'de denenmedi |
| #119 | **Çökme düzeltmesi (yalnızca test).** Test derlemeleri 188–196 "Son baktıkların" listesini eski `recentFirst` anahtarının adıyla (`"recent"`, Boolean) yazıyordu; bir film sayfası açıldıktan sonra `appSort` → `getBoolean("recent")` her açılışta `ClassCastException` (kullanıcının test TV'si açılmadı). Liste artık `recentFilms`; eski metin ilk okumada oraya taşınır, `recentFirst` yalnızca Boolean'ı okur. Açılmayan TV kendini güncelleyemez: Downloader ile `lexialauncher.pages.dev/apk/test.apk` üstüne kurulur. **Ders:** yeni ayar anahtarı eklerken eski `Prefs` anahtarlarıyla çakışmayı kontrol et |
| #1 | Apple TV tarzı yeniden tasarım, ayarlar, uygulama içi güncelleme, sabit adlı APK |
| #2 | Sade ana ekran, kısa ayarlar, IMDb'li günlük öneri (ücretli yapay zeka çıkarıldı) |
| #3 | Sadece film, sinematik hero, Inter yazı tipi, çalışan uygulama içi güncelleme |
| #4 | Listem, ekran koruyucu, renkli arka plan, hızlı panel, hava + ağ, tam ayarlar, Ana Sayfa hizmeti |
| #5 | Görsellerin kaybolması düzeltildi, Grundig ayar açıcıları, güvenlik doğrulamaları |
| #6 | Gizli imza anahtarı, düzgün hata mesajları, koyu logo atlama |
| #7 | Film detay sayfası, Top 10 film, arama düğmesi |
| #8 / #9 | Erişilebilirlik hizmeti kaldırıldı / geri getirildi |
| #10 | "Ne izlesem?", güncelleme izni penceresi |
| #11 | "Ne izlesem?": tür seçimi, IMDb ağırlıklı seçim, "neden bu film" |
| #12 | Top 10 dizi satırı |
| #13 / #14 | Ekran koruyucuda Google görsellerini engelleme; 5 dk başlangıç |
| #15 | Parallaks, kenar ışığı, buzlu cam, kayan saat, hava ambiyansı, sinema köşesi, hatırlat |
| #16 | "Kumandaya basılana kadar", görünür güncelleme düğmesi, 30 dk kontrol |
| #17 | Satırları taşı/gizle |
| #18 / #20 | İndirme yeniden denemesi / `apk` ayna dalı ve yedek adresler |
| #19 | Ana Sayfa hizmeti ayar panellerini kapatmasın |
| #21 / #23 | Ekran koruyucuda sinema filmleri / filmin kendi özeti |
| #22 | Onay penceresiz kurulum, güncelleme sonrası ana ekran |
| #24 | Kurulum APK'sı, ilk açılış sihirbazı, çevrimdışı açılış, bellek sınırı, hata günlüğü |
| #25 | TV fotoğraflarından cila: selamlama, satırlar, ilerleme çubuğu, düğmeler |
| #26 | Ayarlar cilası: güncelleme satırı, Hakkında, pürüzsüz cam, ikonlar |
| #27 | Bu not dosyası; yalnızca not/film listesi değişince derleme yapılmaz |
| #28 | Hakkında › En yeni sürüm: OK'e basınca indirme ilerlemesi görünür |
| #118 | **Yalnızca test.** Ayarlar sadeleşti (kullanıcının seçtiği "A", iPhone anahtarları **istemedi**: Açık/Kapalı yine yazı): satır artık dikey (`line` 88 + seçiliyken altında açıklama `desc`; soldaki uzun açıklama `descText` gizli), seçili satırda 3–4 kısa seçenek yan yana (`seg`, seçili olan koyu hap; toplam ≤ 34 harf), beyaz plaka satırdan satıra kayar ve boyunu alır (`plate` + `listBox`, `glideTo`, 220 ms; hafif modda atlar; satırlarda `lift` yok), değer değişince yenisi aşağıdan kayarak gelir, her sayfanın solda hafif kendi tonu (`tone`: Görünüm mor, Filmler kırmızı, Platformlar mavi, Uygulamalar amber, Ekran koruyucu lacivert, Güvenlik yeşil, Satırlar pembe; ana sayfa tonsuz). Sayfa kayması artık `listBox` ile |
| #117 | **Yalnızca test.** Kullanıcı "Ücretsiz izle"yi **tamamen kapattı**: `pick.py`'den `free` alanı ve `free_titles` çıktı; uygulamada ücretsiz satırı, yeşil rozet, detay düğmesi, Keşfet "Yalnızca ücretsiz" kalktı (`Pick.free` okunur ama kullanılmaz; "SENDE VAR" etiketi ve Keşfet › Nasıl izlerim › Sende var kaldı). **Görsel paket** (kullanıcı 1, 2, 3, 4, 6'yı seçti): `Glass.toast` (bütün `Toast.makeText` çağrıları; etkinlik penceresinin altına cam şerit, degrade nokta, 2,2/3,6 sn; etkinlik yoksa Android'in kutusu), `Fx.decorate` odakta kartın gölgesi görselin `accent` rengine (API 28+ `outlineSpot/AmbientShadowColor`, hafif modda yok), `Fx.shine` (logo ya da başlıkta bir kez ince ışık; donanım katmanında SRC_ATOP bant), Ayarlar sayfa geçişi (`slideOut` + `slideDir`: alt sayfa sağdan, geri soldan; başlık da kayar), ilk açılışta `pick.json` yokken hero yerinde `Fx.Shimmer` iskelet (`skeletonView`, liste gelince `hideSkeleton`). Ayarlar için 6 öneri (sekmeler, renkli ikonlar, arama, değişenler işareti, önizlemeler, film görseli arka planı) kullanıcı **beğenmedi** ("TV'de hoş durmaz"); kartlı ana sayfa (B) da seçilmedi |
| #116 | **Para tasarrufu paketi** (yalnızca test; kullanıcı seçti, abonelik hatırlatıcısını çıkardı). **Bu ay hangi platform?** (`PlatformMonthActivity`, `tally()`: Listem'deki izlenmemiş filmler bugünkü listeye göre platformlara; en çok olan "Bu ay Netflix yeter", afişler, X'i aç / Filmleri göster / Sonraki ay, sağda çubuklar, aylara göre önerilen sıra, ücretsiz ve bilinmeyen sayısı; zilde, Listem basılı tut menüsünde, Ayarlar › Platformlar'da). **Ücretsiz izle:** `pick.py` `details_of` › `free` (TR `free` + `ads` sağlayıcıları) ve `free_titles` (discover `with_watch_monetization_types=free|ads`) → `pick.json` › `free` (en çok 16, IMDb ≥ 6,0, yalnızca `free` bilgisi olanlar); `Pick.free`; hero'da yeşil "ÜCRETSİZ · TABİİ" (`Ui.setFree`; sıra: platformda yeni → sinemada → ücretsiz → abonelik), detayda yeşil satır + "tabii'de ücretsiz izle" düğmesi (uygulama yoksa mağaza), afişlerde "ÜCRETSİZ" / "SENDE VAR" etiketi (`addMoneyTag`, `mine` = uygulaması kurulu platform adları, arka planda), yeni satır `free` "Ücretsiz izleyebileceklerin" (`Prefs.ROWS`), Keşfet › "Nasıl izlerim" (Her yol / Yalnızca ücretsiz / Sende var). Günlük işte `Ücretsiz platformlar:` satırı yazılır. **Saate göre:** `shortEvening()` (pazar–perşembe 21:00–03:00) → hero'da ≤ 105 dk filmler önce, etiket "BU AKŞAM İÇİN KISA", cümle "Yarın iş var: bu akşam bir buçuk saatte biten bir film."; `nightTick` 30 sn'de bir `applyGenres` (imza `shortEvening` içerir). **Film düellosu** (`DuelActivity`): "Ne izlesem?" penceresinin ilk satırı; IMDb ağırlıklı 6 film, 5 tur, kazanan ekranı (Fragman, Detaylar, Yeniden oyna). **Liste yedeği** (`Backup.kt`): kod `LX-XXXX-XXXX` (31 harfli alfabe), PBKDF2(SHA1, 20000) → AES-GCM; sitede `bk:<sha256("lexia-id:"+kod)>` (`/api/backup` GET/POST, `&del=1`; 90 gün TTL, günde en çok 300 yazma `bkn:gün`); değişince saatte en çok bir, değişmese haftada bir yenilenir; içerik Listem, hatırlatmalar, izlenen, beğenilen, önerilmeyen, son baktıkların, eklenme tarihleri. Kod ayrı `lexia_backup` dosyasında; `allowBackup` artık açık ama yalnızca bu dosya (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) → Google yedeğiyle yeniden kurulumda `Backup.sync` boş TV'de bir kez geri yükler (`Prefs.backupChecked`). Ayarlar › Bakım › LİSTE YEDEĞİ (yedekle/kodu göster, geri yükle, sil; "LeXia 3 ay hiç açılmazsa yedek silinir"). `/api/stats` yedekleri okumaz (yalnızca `backups` sayısı), `/api/reset` silmez. Yenilikler kartı 187'den beri her şeyi kapsar. TV'de denenmedi |
| #115 | **Yönetim sayfası** (kullanıcı onayladı): yeni sitenin görünümü (`</style>` öncesinde ayrı katman: gece mavisi + pembe/mor ışıklı arka plan, buzlu sol menü, seçili bölümde degrade çizgi, telefonda seçili sekme beyaz, 18 px köşeli kartlar, oval düğmeler, degrade grafik ve anahtarlar); telefonda "#N'i herkese yayınla" kutusu sıkışmıyor (düğme alta iner). **Hazır duyuru metinleri** (İçerik › Duyuru, `TEMPLATES`): Yeni sürüm, Zorunlu güncelleme (yalnızca eski TV'ler), Kısa bakım, Hafta sonu önerisi (seçki filmi ya da günün filmi), Yeni özellikler, Ana Sayfa tuşu, Teşekkür; dokununca başlık, metin, bitiş tarihi ve TV önizlemesi dolar, gönderme yine "Duyuruyu yayınla" ile. Kullanıcının seçmediği yönetim önerileri: 1–5, 7–18, 20–22 (alt menü, Yapılacaklar kutusu, sürüm günlüğü Türkçe, yeni özellikleri kapatma, anahtar süresi uyarısı, sürüm sağlık göstergesi, hata grupları, vitrin, haftalık özet, PIN kilidi, PWA, kademeli yayın, film listesi kontrolü, KV sınır göstergesi, kurulum yardımı özeti, tek arama, seçki önizlemesi, özellik kullanımı, dışa aktar) |
| #114 | **Site yeni görünüm** (kullanıcı onayladı): yapışkan üst menü (kayınca buzlu cam, sağda "Kur"), giriş iki sütun (başlık "Ana ekranın artık bir sinema.", "Hemen kur" + Downloader kodu çipi `heroCode`, alt bağlantılar; sağda TV çerçeveli tanıtım ve ışık yansıması), Ekranlar 3 görsel, Özellikler 6 görselli kart (telefonda yana kayar; görseller `docs/img` ve `docs/promo` → `site.yml` `/img/promo/`'ya kopyalar) + kısa özellik çipleri, Kurulum: uyumluluk çipleri, 4 adım yan yana (3. adım degrade çerçeve, kod `#codeNum` span), yedek adresler + bütün sorun giderme + `#bildir` tek "Olmadı mı?" kutusunda (`#olmadi`; `openReport` ikisini de açar), SSS ve gizlilik açılır satırlar yan yana; `.rv` kaydırınca belirme (IntersectionObserver, azaltılmış hareket saygılı). Telefonda sayfa ~10.800 → ~6.500 px. İşlevler aynı |
| #113 | **Yalnızca test cihazına** (hold 187 kalıyor). Kullanıcı premium 1–6, 8, 10, 11, 13; oynatıcı 1–7; uygulama 1–5'i seçti. **Görsel:** `Fx.Shimmer` (yüklenen afiş yerine kayan ışık) + `Fx.fadeIn` (çapraz geçiş; sonra düz bitmap, zoom kopyalayabilsin), hero önce `w300` kopyayı `Fx.soften` ile bulanık gösterir, net olan kesmeden üstüne gelir (`softToken`, `reveal(keepMotion)`); `Fx.zoomBack`: detaydan dönünce görsel karta küçülerek döner (`zoomFrom`); hero metni çocuk çocuk 85 ms arayla süzülür (hafif modda eski toplu geçiş); `Press` (basma + halka); detayda paralaks (`back` y×0,22, %5 büyür) ve kayınca üstte ince başlık (`stickyTitle`); `daylight` katmanı (sabah serin mavi, 17–21 amber, gece lacivert, 4 sn geçiş, `nightTick`'te); `boost` katmanı: `Fx.brightness` görselin sol-alt bölgesi açıksa ek gölge (`boostLevel`); boş durumlar `Ui.emptyState` (zil, takvim + "Ana ekrana dön", Listem boşken "Listen henüz boş" kartı → OK Top 10'a gider); `wakeUp`: ekran koruyucudan dönerken büyük saat üst çubuktaki saate uçar (`barClock`), içerik aşağıdan yükselir; ses ailesi. **Oynatıcı:** iki yarım siyah perde ilk oynatmada açılır, kapanışta kapanır; ses JS `fadeTo` ile 0→100 (1,6 sn) ve kapanışta 0'a; `pick.py` › `trailers_of` (tr + en, Trailer/Teaser, en çok 4; `trailers` [{k, n: "Türkçe dublaj fragman" / "Türkçe altyazılı fragman" / "Orijinal fragman" / "… kısa tanıtım"}]) → `Pick.trailers` (`Video`), ▲ "Fragmanlar · 3", bilgi satırında dil rozeti (TÜRKÇE DUBLAJ / ALTYAZILI / ORİJİNAL DİL); `Prefs.trailerAt` (son 30) kaldığın yer, açılışta "Kaldığın yerden?" (8 sn'den sonra, son 10 sn hariç; maratonda sorulmaz); maratonda ikinci gizli YouTube oynatıcısı sıradakini hazırlar (`prep`, `play` takas eder); Keşfet › Fragman maratonu artık tema listesi (bu seçim, sinemada, yakında, Korku gecesi, Gülme garantili, Aksiyon dolu, en çok yapımlı 3 platform "Netflix'te yeni", yerli, IMDb 8+; en az 2 fragman); ▲ "Oyuncular" sağda fotoğraflı kadro (Geri kapatır); çubuk ve tutamak filmin renginde. `TrailerActivity.locative` ("Netflix'te", "Prime Video'da"). `Dialogs.list` artık `cancel` alır. **Uygulama:** zilde "Artık Netflix'te: …" (Listem/hatırlatılan, kaydederken platformu olmayan, son 240 günde vizyona girmiş film bugünkü listede platformdaysa; `Prefs.nowStreaming` "key|platform|gün", 3 gün); uygulama kartında pembe "3 yeni" (`appNews`, `newsFor` arka planda; ilk basış o günün yapımlarını listeler, `platformSeen`); **Günaydın kartı** (5–12 arası günün ilk açılışı, 9 sn ya da bir tuş; hava, bugün bölüm, vizyon, film gecesi, platform; Ayarlar › Görünüm › Günaydın kartı, `morningSeen`); **Son baktıkların** satırı (`Prefs.recent`, en çok 12, detay açılınca; Listem'deki ve izlenenler hariç; `ROWS` "recent"); **Listem hatırlatıcısı** (cuma 17:00–pazar, 2+ haftadır bekleyen, haftada bir zil; `Prefs.listAdded` watchlist setter'ında); Listem basılı tut › "İzlediklerimi çıkar (n)" / "Bir aydan eskileri çıkar (n)". TV'de denenmedi |
| #112 | Sitede 3. adım: `dlcode` varsa `/kur` kutusunun yerine büyük degrade çerçeveli "DOWNLOADER KODU" kutusu (iri sayı, Kopyala), altında "Kod çalışmazsa adresi yaz: …/kur"; sorun gidermede "İndirme başlamıyor" önce kodu söyler. Kod silinirse eski `/kur` kutusu döner |
| #111 | `remote.json` › `dlcode` 7036535 (kullanıcının aldığı Downloader kodu, hedef kopya `kur.apk`; TV'de henüz denenmedi) |
| #110 | #187 herkese (`hold` 187; zorunlu sürüm 183'te kalıyor): #109'un tarih ve Yenilikler kartı düzeltmeleri |
| #109 | Forumdan (Android TV kutu, küçük TV): selamlamanın altındaki tarih alttan kesiliyordu (#102'den sonra fark edildi). Üst çubuk sabit 76 yükseklikteydi; büyük yazı ve küçük ekranlarda selamlama + tarih sığmıyordu → yükseklik artık içeriğe göre, en az 76 (`minimumHeight`). Ayrıca **Yenilikler kartı** 9 uzun maddeyle ~1100 px oluyordu (ekran 1080): "Tamam" ve oy düğmeleri ekran dışında kalabiliyordu → kart 1100 → 1400 genişlik, yazı 24 → 23, aralık 20 → 16; yine taşarsa kaydırılır (`ScrollView`, ortalanmış) |
| #108 | Forumda bir kullanıcı: bağlantı tıklanmıyor (https:// yoktu), tarayıcı "yine de indir" istiyor, Play Protect kapatılmalı sanıyor; `app.apk`'yı ilk kurulumda kullanmış. Sitedeki yedek kutu ikiye ayrıldı: **İlk kez kuruyorsan** (`kur.apk`, Play Protect'e takılmaz) ve **LeXia kurulu ama güncelleme alamıyorsan** (`app.apk`, üstüne kurulur); adresler tam `https://` ile, her birinde Kopyala / Bu cihaza indir; "Play Protect'i kapatmana gerek yok, Yine de yükle" |
| #107 | `remote.json`: hold 183, minVersion 183 (kullanıcı yönetimde yanlışlıkla kapatmıştı) |
| #106 | **Herkese yayın:** kullanıcı yönetimden #183'ü herkese verdi, test düzenini ve zorunlu güncellemeyi kapattı; site: özellikler bölümü (Keşfet, nerede izlenir, hafta sonu rehberi, film gecesi, filmin rengi, sesli arama, fragman oynatıcısı), SSS "Fragmanlar LeXia'nın kendi oynatıcısında", Ana Sayfa rehberi "adım adım gösterir" |
| #105 | Kullanıcının TV fotoğrafı: detaydaki platform düğmesinde "com.mstar.netflixo" yazıyordu (Grundig'in adı paket adı olan Netflix yardımcısı). `Apps.forPlatform` önce platformun kendi paketini arar (`platformPackages`: com.netflix.ninja, com.disney.disneyplus, Prime Video, Apple TV, MUBI, Crunchyroll, Max), sonra adı eşleşen TV uygulamalarını; adı paket adına benzeyen uygulamaları (`packageLike`) asla saymaz. Düğmeler uygulamanın adını değil platformun adını yazar ("Netflix'i aç"): detay, film gecesi kartı, oynatıcı. Bütün cihazlar için `Apps.nameOf`: adı paket adı olan uygulamada önce uygulamanın kendi adı denenir; yoksa telefon tipi yardımcı listelerden çıkar, TV uygulaması son kelimesiyle görünür. Ayrıca **fragman oynatıcısı yeniden tasarlandı** (kullanıcının onayladığı çizim, Apple TV ayarında): sol üstte etiket (FRAGMAN / FRAGMAN MARATONU · 2 / 12), film adı 52, rozet + IMDb + yıl/tür (vizyona girmemişse "14 Kasım'da vizyonda"); altta düğmeler (Listeme ekle ya da Çıkınca haber ver, Detaylar, "Netflix'i aç" / "Netflix uygulamasını bul"; Türkçe -i hâli `accusative`), sağda maratonda "SIRADAKİ · ▼" kartı (görsel + ad); kalın çubuk + yuvarlak tutamak + üstünde anlık süre, iki uçta 0:00 ve kalan süre, tuş ipucu. Tuşlar: OK oynat/durdur, ◀ ▶ sar (basılı 30 sn, balon), ▲ düğmelere geçer (◀ ▶ arasında gezilir, ▼/Geri döner), ▼ sıradaki. Durunca perde %60 + ortada cam ▶. **Bitiş:** filmin kendi görseli soluk arka planda, "FRAGMAN BİTTİ", büyük ad, rozetler, tek cümle, düğmeler (Listeme ekle/Çıkınca haber ver, platform, Tekrar izle, Kapat); maratonda sağda "SIRADAKİ FRAGMAN" kartı 5 sn halka ile kendiliğinden başlar (herhangi bir tuş geri sayımı durdurur) |
| #104 | **Keşfet** yeni süzgeçler (kullanıcı: "vizyon tarihi, yakında, platforma göre tarih"): "Nerede" (Her yerde / Sinemada şimdi / Yakında vizyonda / Platformlarda / Yerli), "Tarih" (Son bir hafta / Son bir ay / Bu yıl / Henüz çıkmadı; tarih = TR vizyon, yoksa dijital çıkış; platform yapımında da `release`), `soon` listesi de Keşfet'te; afiş altı "14 Kasım'da çıkıyor" (ay ekine göre 'da/'ta/'de), "Sinemada", platform adı. **Eksik platform uygulaması:** detayda ve fragman ▲ menüsünde uygulama kurulu değilse "TOD uygulamasını bul" → TV'nin uygulama mağazasında arama (`Apps.findInStore`). Ayrıca **premium fragman oynatıcısı** (kullanıcı fikirleri onayladı): durunca ortada cam daire içinde büyük ▶ (`center`), altta geçen süre ve sağda kalan "−1:18"; ◀ ▶ 10 sn, basılı tutunca 30 sn ve "+40 sn" balonu (`skip`, `bubble`); ▲ seçenekler satırı (Listeme ekle ya da vizyona girmemişse "Çıkınca haber ver" (`prefs.reminders`), Detaylar (fragman durur), platform uygulaması); tek fragman ya da maratonun sonu bitince kapanmak yerine **bitiş kartı** ("FRAGMAN BİTTİ", Listeme ekle / Çıkınca haber ver, Tekrar izle, Detaylar, Kapat). Filmi bilmek için `TrailerActivity.open(a, Pick)` / `marathon(a, List<Pick>)` (`pendingFilms`, aynı süreçte; eski id+başlık çağrısı hâlâ çalışır, menüsüz). Ayrıca hero'da **nerede izlenir rozeti** (kullanıcı: "renkli, dikkat çekici"): başlığın altında IMDb'den önce platformun kendi renginde "▶ NETFLIX" (kırmızı), "▶ DISNEY+" (mavi), Prime Video açık mavi, Apple TV beyaz, Max, MUBI, Crunchyroll, Exxen, TOD, BluTV; sinemadakiler pembe "SİNEMADA"; bilinmiyorsa gizli (`Ui.whereBadge/setWhere`). Kaynak sırası: `provider` → sinema listesinde → `where` ilk platform. Platform filminin üst etiketi artık "PLATFORMLARDA YENİ" (ad rozette) |
| #103 | Kullanıcı: ekran koruyucuda da platformlar olsun, platformlar ayarlarda ayrı yer olsun. **Ekran koruyucu:** `saverContent` yeni seçenek `platform` ("Platformlarda yeni"); "Karışık" artık her iki filmden sonra bir platform yapımı da ekler (`streamingFirst`, Platformlarım'a uyar), etiket "YENİ · NETFLIX"; önizleme ve yönetim sayfasındaki yeni kurulum ayarı da biliyor. **Ayarlar › Platformlar** (ana sayfada Filmler'in altında, değer "Hepsi" / "3 seçili"): açıklama, Ana ekrandaki filmler seçimi, platform listesi (uygulaması kuruluysa yazar); Filmler sayfasındaki Platformlarım bölümü buraya taşındı |
| #102 | Üst çubuk sağdaki düğmeler biraz büyüdü (kullanıcı: "bi tık"): simge düğmeleri 54 → 60, Ne izlesem / Güncelleme geldi / hatırlatma / Ana Sayfa düğmeleri 50 → 56 (yazı 19 → 20), zil noktası 14 → 15 |
| #101 | `Prefs.heroSource` varsayılanı **mix** (kullanıcı: "bilmeyen biri nasıl bulacak"): ayarı hiç değiştirmemiş herkeste ana ekranda günün filmi, sinemadaki ve platforma yeni gelen sırayla; seçenek Ayarlar › Görünüm › Ana ekrandaki filmler. `whatsnew.txt` satırı düzeltildi (ayar Filmler'de değil Görünüm'de) |
| #100 | Kullanıcı TV fotoğrafı: fragmanda YouTube başlığı, paylaş, "Diğer videolar", logo ve İngilizce altyazı görünüyordu. Oynatıcı çerçevesi ekrandan uzun (genişlik aynı, yükseklik %140; #104'te %12 büyütme kaldırıldı, görüntü netleşti), YouTube'un üst ve alt kenara yapışan yazıları ekran dışında kalır (`pointer-events:none`), altyazı yalnızca Türkçe (#104: videoda Türkçe iz varsa o seçilir, yoksa altyazı tamamen kapanır; `subs()` hazır olunca, `onApiChange`'de ve oynayınca çağrılır). Fragmanın görüntüsüne gömülü altyazı kaldırılamaz; `pick.py` zaten önce TMDB'nin Türkçe fragmanını seçiyor, oynamaya başlayana kadar ve durdurulunca siyah perde (`shield`, %94) + LeXia bilgi katmanı açık kalır; YouTube'un duraklatma ekranı görünmez |
| #99 | Sitede yedek kurulum adresi (GitHub kopyası `kur.apk`) belirgin turuncu kutu: adres, "Kopyala" ve "Bu cihaza indir" düğmeleri (kullanıcı: "indiremeyen onu denesin") |
| #98 | Kullanıcı 2, 3, 15, 16, 18'i seçti. **Hafta sonu rehberi** (`WeekendActivity`) ve **Film gecesi** (`MovieNight`), ikisi de yönetimde kapatılabilir (`off`: weekend, night). **Şık uygulama kartları** (`Apps.iconCard`): banner'ı olmayan uygulamalar simgenin renginden degrade zemin, büyük simge, altta ad (ana ekran, Tüm uygulamalar, ayar önizlemesi; simge şekillerinde adsız). **Filme göre renk** (`Fx.accent`: görselin en renkli tonu): ilerleme çubuğu bu tona çalan beyaz, hero düğmeleri seçiliyken oda ışığı (`tintTo(filmTint)`). **Cila:** `Ui.lift` seçimde hafif yaylı (`OvershootInterpolator` 1.3, `D.SPRING` 320 ms; hafif modda düz), kalan sabit süreler `D`'ye, menü plakaları `D.PLATE`. 4K: hero görselleri zaten 1280 px üstü ekranlarda `original` (öneri 17 gereksizdi) |
| #97 | Düğmenin adı "LeXia'yı ana ekran yap" (kullanıcı seçti) |
| #96 | Sitenin en üstüne "Ana Sayfa tuşu ayarı" düğmesi ve kurulumda ikinci şerit (kullanıcı: "hani nerde"; bağlantılar yalnızca aşağıdaydı) |
| #95 | Sitede ayrı sayfa **`/anasayfa/`** (Ana Sayfa tuşu ayarı, görsellerle adım adım; görseller `docs/promo/anasayfa/*.jpg`, `site.yml` `/anasayfa/img/`'e kopyalar; link önizlemesi giriş görseli). Ana sayfada SSS iki maddesi, kurulum yardımının "Ana Sayfa" çözümü ve altbilgi oraya bağlanır. #94: 1. adım görselinde "Android 12 ya da eskiyse atla" |
| #93 | Kullanıcı: "insanlar hâlâ ana ekran olarak kullanamıyor" (yönetimde çoğu TV "a"). **Ana Sayfa tuşu rehberi** (`HomeGuideActivity`): numaralı cam kartlar, "Ayarları aç", Android 13+'ta "Uygulama bilgisi" (kısıtlanmış ayarlar), dönünce kendisi algılar. Açıldığı yerler: sihirbazın Ana Sayfa adımı ("Adım adım göster"; adım artık `HomeGuide.needed`'e göre), Ayarlar › Ana Sayfa tuşunu yönlendir, üst çubukta **"Ana Sayfa tuşunu ayarla"** düğmesi (en çok 5 gün, `Prefs.homeNudgeDays`; basılı tut → kapat `homeNudgeOff`), zilde bildirim. Site: sorun giderme maddesi adım adım + SSS "LeXia neden ana ekran olmuyor?" (yalnızca 141'de de doğru olan yollar). `whatsnew.txt`'ye satır. Sonuç yönetim › Kullanım › ana ekran kullanımında ("b" oranı) izlenir |
| #92 | Güvenlik kontrolü (11 Kasım): uygulama (dışa açık ekranlar, HTTPS, fragman kimliği doğrulaması, PIN), site (yönetim sayfası yalnızca `textContent`, CSP, `/api/stats`/`reset` anahtar doğrulaması, sabit yönlendirmeler), iş akışları ve depo (sır yok) temiz. Tek düzeltme: `/api/crash` günde en çok 100 rapor (herkes çağırabildiği için KV'nin günlük 1.000 yazmasını tüketmesin) |
| #91 | Fragman oynatıcısında bilgi katmanı (kullanıcı: "kontrol nasıl görülecek"): tuşa basınca üstte "FRAGMAN (MARATONU)" + film adı + "2 / 12", altta süre "1:12 / 2:30", "Durduruldu", ilerleme çubuğu ve tuş ipucu; 4 sn sonra kaybolur. Süre `evaluateJavascript` dönüş değeriyle okunur (JS köprüsü yok), bilgi açıkken saniyede iki kez |
| #90 | **Uygulama içi fragman herkese açıldı** (test kilidi kalktı; `Prefs.trailerInApp` varsayılan açık, Ayarlar › Filmler › FRAGMANLAR; fragman maratonu da). Kullanıcı on uygulama önerisinin hepsini istedi (test düzeni açık). **Nerede izlenir:** `details_of` `watch/providers` (TR, abonelik) → kayıtta `where`; detayda "İzlenebileceği yer" satırı, kurulu ilk platform uygulaması düğmesi. **Platform bildirimi:** zilde platform başına günde bir ("Netflix: 3 yeni yapım"), Platformlarım'dakiler, seçilmediyse uygulaması kurulu olanlar (`myPlatform`). **Sesle uygulama:** sesli aramada uygulama adı ("YouTube", "Netflix'i aç") uygulamayı açar. **Geri sayım:** hatırlatılan ve 1–30 gün içinde vizyona girecek filmler ekran koruyucuda önce, büyük "Vizyona 4 gün" (`countdownFilms`; "Günün filmleri" içeriğinde yok). **Sezon finali / yeni sezon:** `next_ep.kind` (TMDB `episode_type` finale; 1. bölüm ve sezon > 1 = premiere) → `Episodes.what/badge/line`, bildirim ve takvim. **Yerli yapımlar** satırı (`local`: TR kökenli son 3 yıl film + dizi, IMDb ≥ 6,0, en çok 12) ve **Beğendiklerine benzer** satırı (`like`: beğenilen filmlerin `similar` listeleri, en az 4); `Prefs.ROWS`'a eklendi (Listem'den önce), yönetimde satır kapatma listesinde. **Keşfet** (`ExploreActivity`, üst çubuk film simgesi): günün bütün yapımları, filtreler tür / film-dizi / puan / süre / platform, afiş ızgarası. **Fragman maratonu** (Keşfet'te): `TrailerActivity.marathon`, bitince ya da hata verince sonraki, ▼ atlar. **Takvimde dijital çıkış** (`digital`: TR dijital çıkış tarihi `release_dates` type 4, 45 gün; platform biliniyorsa yazılır, çoğu zaman bilinmez) |
| #89 | Kullanıcı yönetim önerilerinden 2, 8 ve sonra yeni 1, 2'yi seçti. **Sürüm beğenisi:** güncelleme sonrası "Yenilikler" kartında "Bu sürümü beğendin mi?" Beğendim/Beğenmedim (`Prefs.votedBuild`, sürüm başına bir kez) → `/api/vote?v=N&r=up|down` (`vote:N:up/down` 400 gün, günde en çok 500 oy `voten:gün`); yönetim › Yayın › Sürüm günlüğünde ♥/✕. Kart yalnızca liste değişince çıktığı için oylar listesi değişen ilk sürümden itibaren gelir. **Seçki listesi:** `remote.json` › `pin` = ilk film + `more` [{k,t,note}] (en çok 10; eski TV'ler yalnızca ilkini okur); ana ekranda hepsi sırayla başta, etiket "LEXIA SEÇKİSİ" (`pinPicks`, `pinFilmKeys`); yönetimde ↑ ↓ ✕, "Seçkiye ekle", gizlenen film seçkiden düşer. **Sürüme göre duyuru:** `remote.json` › `message.below` (yönetim › İçerik › Duyuru › "Kimler görsün": bütün TV'ler / "#N'den eski TV'ler"); `Remote.activeMessage()` yalnızca `1 ≤ derleme < below` olan TV'de gösterir (bu alanı tanımayan eski sürümler duyuruyu her durumda görür). **Sürüm günlüğü:** yönetim › Yayın; `build.yml`'in main'deki son 12 başarılı push çalışması (sürüm no, tarih, PR başlığı), o commit'teki `whatsnew.txt` (bir öncekiyle aynıysa "görünmeyen düzeltmeler"), ulaşan TV (`update:N` indirme + bu ay `tvm:ay:N`), etiket herkeste/test/bekliyor |
| #88 | **Platformlarım** (Ayarlar › Filmler): bugünkü `pick.json` › `platforms` içindeki platformlar (kaydedilmiş dosyadan) açılıp kapatılır (`Prefs.platforms`; boşsa hepsi, kurulu uygulamalar önce). Yeniden çalıştırmada: 24 yapım, en düşük IMDb 6,7, adlar düzgün (Disney+, TV+, Netflix, Prime Video, TOD, MUBI, Crunchyroll) |
| #87 | Platform listesi cilası: IMDb < 6,5 atılır (platform başına 6 film + 4 dizi aday, en çok 3 + 2 kalır), adlar düzeltilir (`PLATFORM_NAMES`: Disney+, Prime Video, Apple TV, TOD, Max). İlk çalıştırmada 8 platformdan 32 yapım geldi (Netflix, Prime Video, Disney+, TV+, TOD, Apple TV, MUBI, Crunchyroll) |
| #86 | Testçinin iki yorumu (kullanıcı "yapalım"). **Uygulama şekli** (`Prefs.appShape`: card/sharp/circle/square; `Apps.radius`, `Apps.iconShape`): köşeli banner ya da afiş yerine uygulama simgesi (yuvarlak/kare, banner yüksekliğinde kare); "Tüm uygulamalar" kartı ve ayarlardaki küçük önizleme de uyar. **Ana ekrandaki filmler** (`Prefs.heroSource`: picks/cinema/platform/mix, `heroPool()`): `pick.py` › `platforms()` TMDB'nin Türkiye platform listesinden (gösterim önceliğine göre, yalnızca abonelikli içerik verenler) en çok 8 platform × 3 film + 2 dizi (`pick.json` › `platforms`, kayıtta `provider`); TV'de uygulaması kurulu platformlar önce (`streamingFirst`, ad kelimesi eşleşmesi); hero etiketi "YENİ · NETFLIX" / "ŞU AN SİNEMADA"; havaya göre film yalnızca picks/mix'te. Detayda platform uygulaması kuruluysa adıyla düğme (`Apps.forPlatform`) açar; film oynatılmaz. Sesli aramaya platform filmleri eklendi |
| #85 | Kullanıcı "hepsi" dedi (5 öneri; test düzeni açık, önce test cihazına): **Bana önerme** (detay, `Prefs.disliked` film→türler; `skipNow` = izlenen + önerilmeyen her öneriden çıkar, `dislikedGenres()` hero ve tür satırlarında sona; beğenmek bunu kaldırır). **Sesli arama** (üst çubuk büyüteç: `RecognizerIntent` tr-TR, yoksa yazarak; bugünkü listede başlık arar, tek sonuç detay açar, çoksa liste, yoksa "TV'nin kendi aramasında ara"; basılı tut = eski sistem araması). **Büyük yazı** (`Prefs.bigText`, `Ui.text` ×1,12; değişince ana ekran `recreate`). **Cam** (`Prefs.glass` Koyu/Orta/Şeffaf → `Ui.panelAlpha/dimAlpha`: yan panel, hızlı panel, zil, menüler, Tüm uygulamalar). Tüm uygulamalar hafif modda (bulanıklık yok) film görselinin kendisinin üstünde açılır. **Kurulumda tür seçimi** (sihirbazda `TASTE` adımı, `Prefs.GENRES` 18 tür, `tasteAsked`; yalnızca yeni kurulum ve tür seçilmemişse). Detay sayfası düğmesi "Bana önerme" (kısa olsun diye) |
| #84 | **Uygulama içi fragman (yalnızca test cihazı):** `TrailerActivity` YouTube'un gömülü oynatıcısını (iframe API, `loadDataWithBaseURL` adresi site) tam ekran açar; kumanda: OK durdur/oynat, sağ/sol ±10 sn, Geri çık; sayfa durumu başlıkla bildirir (`p`, `end`, `e:<kod>`; JS köprüsü yok, R8 sorunu olmasın). Gömmeye izin yoksa, oynatıcıdan bağlantıya basılırsa ya da 15 sn'de oynamazsa YouTube uygulaması açılır. Yalnızca `Prefs.tester && trailerInApp` (Ayarlar › Filmler › TEST, test cihazında görünür); diğer herkeste eskisi gibi YouTube. Bu PR'la `remote.json` › `hold` 141 yapıldı: **"önce test cihazları" açık**, 142+ yalnızca test TV'lerine gider |
| #83 | Testçinin yorumları (Goldmaster Android 9; H313 ve Alpsmart S905W2 Android 11: ana başlatıcı olarak sorunsuz). **Fare:** ayarlarda satırlar yalnızca yön tuşuyla seçiliyordu → ayar satırları, seçim pencereleri, Tüm uygulamalar kartları üstüne gelince seçilir; ana ekran, detay ve takvimde `Pointer` (yalnızca seçili satır boyunca seçer, satırlar kaydığı için çapraz seçim kaçardı; tekerlek = yukarı/aşağı). **Ana ekran olarak kalmıyordu** (Android 9 kutu kendi launcher'ını geri getiriyor): `HomeButtonService` artık TV'deki bütün HOME uygulamalarını (ayarlar paketi hariç) "başka ana ekran" sayar ve LeXia'yı öne getirir; ama LeXia'dan açılan uygulamalar (`HomeButtonService.opened`, ör. uydu/canlı TV oynatıcısı) asla geri atılmaz. Açılışta hizmet 1,5 sn sonra açar (önce 3 sn). **Türler:** ayardaki 18 türün 5'inin (Belgesel, Tarih, Müzik, Savaş, Vahşi Batı) satırı yoktu → `pick.py` GENRE_ROWS'a eklendi (küçük türlerde sayfa 1'e döner); satırlar sayfasında "Sana göre" açıklaması. **Kaldırma:** Ayarlar › Bakım › "LeXia'yı kaldır": LeXia varsayılansa önce TV'nin ana ekran ayarı açılır, ikinci basışta PIN + kaldırma. Sonraya kalan testçi istekleri: solda gizli paneli uygulama görselleriyle zenginleştirmek, Tüm uygulamalar arka planını film görseli + şeffaflık ayarıyla yapmak, uygulama içi fragman (kullanıcı karar vermedi) |
| #82 | **Yönetim sayfası yeniden tasarlandı** (kullanıcı: "karmaşa, tekrar, profesyonel değil"): solda menü (telefonda üstte kayan), 6 bölüm: Genel bakış (durum şeridi: herkesteki sürüm, test, film listesi, işler; yalnızca sorun varsa uyarılar; 4 ana sayı düne göre; tek 14 gün grafiği, düğmeyle TV/indirme/ziyaret) · Yayın · Kullanım (indirme/ziyaret tek tabloda, aktif TV, 24 saat, kurulum hunisi, cihaz, ana ekran kullanımı, kurulum yardımı, gelinen siteler) · İçerik (duyuru + TV önizlemesi, seçki, gizle, filtre, satırlar, özellikler) · Site (site duyurusu, Downloader kodu: hedef artık GitHub kopyası `kur.apk`, yeni kurulum ayarları) · Sistem (işler, ayar geçmişi + geri dön, hatalar, sayaçlar). **Test yayını:** `site.yml` en yeni derlemeyi `test.txt` + `apk/test.apk` olarak, herkese `remote.json` › `hold` sürümünü (`gh release download latest tvlauncher-N.apk`, iş akışına `contents: read`) `version.txt` + `apk/tvlauncher.apk` olarak koyar (`/dl`, `/kur`'un yönlendirdiği tam sürüm, kopyadaki `app.apk` da). `hold` 0 ya da en yeniyse herkese en yeni. Kurulum APK'sı her zaman en yeni derleme (açılınca onaylanana güncellenir). Uygulama: `Prefs.tester` (Hakkında › Yüklü sürüm'e 7 kez OK), test TV'si `test.txt`/`apk/test.apk`'ya bakar ve `hold`'u yok sayar; ping `&t=1` → `test:gün`. Zorunlu güncelleme herkesteki sürümden yüksek olamaz (TV'ler kilitlenmesin) |
| #80 | Testçi geri bildirimi (Goldmaster Explorer, Android 9 uydu alıcılı kutu), kullanıcı 1-2-3-4-6'yı seçti: **(1)** `BootReceiver` (BOOT_COMPLETED) ve `HomeButtonService` başlarken açılıştan sonraki ilk 5 dk'da LeXia'yı bir kez öne getirir (`Prefs.bootOpen`, Ayarlar › Uygulamalar › "TV açılınca LeXia açılsın"; Android 10+'da yalnızca hizmet açıkken çalışır). **(2–3)** Telefon tipi uygulamalar da listede (`Apps.all` LEANBACK + LAUNCHER, `AppEntry.tv`); ana ekrandakiler artık **favoriler** (`Prefs.favorites`, ilk seferde gizlenmemiş TV uygulamaları; sonradan kurulan TV uygulaması kendiliğinden eklenir: `knownTv`). Satırın sonunda "Tüm uygulamalar" kartı → `AppsActivity` (ızgara, kalp = ana ekranda, basılı tut: ekle/çıkar, bilgi, kaldır). Uygulamada basılı tut › "Ana ekrandan çıkar". `hidden` yalnızca ilk geçişte okunur. **(4)** Her açılışta hero başka filmle başlar (`Prefs.heroTurn`; seçki filmi varsa o önce). **(6)** Ayarlar › Uygulamalar › "Uygulamaların yeri": Filmin altında / **Solda gizli** (`Prefs.appsSide`): satır kalkar, solda küçük tutamak; en soldaki kart/düğmede sola basınca (ya da fare tutamağa gelince) soldan panel açılır, sağ/geri kapatır. Boyut ayarı zaten vardı (Görünüm › Uygulama kartları), kullanıcı istemedi |
| #79 | Uzaktan ayarlar (`remote.json`: zorunlu güncelleme, bekletme, duyuru, kapatılan özellikler) artık her güncelleme denetiminde okunur (`Updater.check` → `Remote.refresh`): açılışta ve 30 dk'da bir; önceden film listesiyle 3 saatte bir. Ana ekran `Remote.sig()` değişince yeniden uygular. Yönetim sayfası "en geç yarım saat" diyor. Kaynak: testçi geri bildirimi (Goldmaster Explorer, Android 9) ve kullanıcı ("birkaç saat ne anlamı kalıyor") |
| #78 | Kullanıcının TV'sinde "Güncellemeleri denetle" uzun sürdü (site cevap vermeyince bekleyip sonra kopyaya geçiyordu). `Net.hosts()`: site cevap vermezse 30 dk boyunca önce kopya sorulur (`Net.answered`, `Net.fromHosts`); sürüm denetimi, film listesi, `remote.json` ve APK indirme bu sırayı kullanır. Kullanıcı 130'u TV'sine kurdu (10 Kasım akşamı) |
| #77 | Dağıtım hesabının adı `OoMrToOO` → **`exxsss`** (kullanıcı değiştirdi: ana hesabına benzemesin). Uygulama (`Net.MIRROR`), `site.yml`, site, telefon sayfası ve README yeni adrese geçti. **128 sürümü eski adı biliyor**: GitHub eski adresi yönlendirdiği sürece çalışır, ad başkasınca alınırsa 128'in kopyadan güncellemesi kesilir (128 kısa süre yayında kaldı). Hesap adı bir daha değişmemeli |
| #76 | Sitede, telefon sayfasında ve README'de yedek kurulum adresi artık yeni depo (`…/exxsss/Lxialnch/main/kur.apk`; #74'teki ana depo adresi depo gizli olduğu için çalışmıyordu). Kopyaya `app.apk` (tam sürüm) eklendi: güncellenemeyen TV'ler üstüne kurar; SSS maddesi buna göre |
| #75 | **GitHub kopyası (eski TV'ler için):** kullanıcının ve arkadaşlarının TV'leri siteye ulaşamıyor, güncelleme alamıyor. Kullanıcı ayrı bir hesapta herkese açık depo açtı: `exxsss/Lxialnch` (ana hesabın profili görünmesin diye; oturumun bu depoya yazma izni yok, iş akışı `DIST_TOKEN` sırrıyla iter). `site.yml` son adımda `kur.apk`, `apk/*.apk`, `version.txt`, `pick.json`, `remote.json` ve küçük bir sayfayı (`site/mirror/index.html`) oraya tek commit olarak force push eder (sır yoksa atlar). Uygulama: `Net.MIRROR` (raw.githubusercontent); film listesi, `remote.json`, sürüm ve APK önce siteden, olmazsa kopyadan. Sayaçlar (`/kur`, `/dl`, ping) kopyada yok |
| #74 | Depo yeniden **herkese açık** olacak (kullanıcı kararı: eski TV'ler GitHub'ın sertifikasıyla kurabilsin, #97 gibi eski sürümler kendini güncelleyebilsin). Öncesinde: `mapping.txt` artık `apk` dalına değil yalnızca derleme eki olarak; sitede ve telefon sayfasında yedek kurulum adresi `raw.githubusercontent.com/OoMrToO/TvLauncher/apk/tvlauncher-kurulum.apk` |
| #73 | **Sertifika sorunu:** pages.dev sertifikası Let's Encrypt YE1 → Root YE → **ISRG Root X2** zinciri; X2'yi yalnızca Android 14+ tanır → Android 7–13 TV'ler siteye bağlanamıyor (Downloader dahil; telefonlar açıyor). Uygulamaya `network_security_config` ile ISRG Root X2 + X1 eklendi (`res/raw`): kurulu LeXia eski TV'lerde de siteye ulaşır. **Kurulum (Downloader) eski TV'lerde hâlâ açılmaz** → kalıcı çözüm: kendi alan adı (Google Trust Services sertifikası) ya da herkese açık GitHub organizasyonundan dağıtım; kullanıcıya soruldu |
| #72 | `/kur`: Android WebView (`; wv)`, Downloader'ın tarayıcısı) her zaman TV sayılır; açıklama sayfası yalnızca telefonun gerçek tarayıcısına |
| #71 | Kullanıcının TV'si aslında **#97**'deydi: #102'den eski sürümler güncellemeyi GitHub'dan arar, depo gizli olduğu için "Bağlantı yok" → bir kez Downloader ile `/apk/tvlauncher.apk` elle kurulur (aynı imza, ayarlar kalır). Sitede SSS maddesi |
| #70 | `/api/stats` cevabı 2 dk önbellekte (Cache API; `?fresh=1` ile taze), yönetim sayfası 5 dk'da bir yenilenir: KV ücretsiz okuma/listeleme sınırı aşılmasın. Ölçek notları bölüm 7'de |
| #69 | `/kur` telefon/bilgisayardan açılırsa dosya yerine "TV'de Downloader ile kur" sayfası (`kurwrong:<tür>:gün`; `?yine=1` yine indirir). `remote.json` › `dlcode` (Downloader kısa kodu; yönetim › Yönet'ten girilir, sitede adresin yanında, kurulum yardımında ve telefon sayfasında görünür) |
| #68 | Özet'teki "Bugün" kartı kalktı; en üstte İndirmeler ve ziyaretler (kullanıcı isteği) |
| #67 | Yönetim › Özet sırası: Bugün → İndirmeler ve ziyaretler (bugün/dün/7/30 gün/toplam, son 24 saat, cihaz türü, ziyaret→indirme oranı) → Aktif kullanıcılar. `/api/hit` artık `visit:h:<UTC saat>` (3 gün) ve `visit:src:<tür>:gün` da sayar |
| #66 | Kurulum yardımı: çözüm sorun seçilir seçilmez çıkar (TV türü isteğe bağlı, yoksa genel yollar); ana sayfa `no-cache` (eski sayfa kalmasın) |
| #65 | Yönetimde "Sorunlar" sekmesi kalktı: kurulum yardımı sayıları Kullanım'a, hatalar Sistem'e (hata varsa Sistem sekmesinde kırmızı nokta). Sekmeler: Özet · Kullanım · Yönet · Sistem |
| #64 | Yazılı form kalktı (kullanıcı: "bana yönlendirme, çözüm sun"): sitede **kurulum yardımı** (sorun × TV türü → adım adım çözüm; Samsung/LG için kutu önerisi), sonunda "Çözüldü / Hâlâ olmadı" → `/api/report` yalnızca `fix:<adım>:<tür>:y|n` sayar; yönetim › Sorunlar › Kurulum yardımı tablosu |
| #63 | Sorun bildir formu görünür: en üstte "Kuramadın mı?" düğmesi, Kurulum'da renkli "Kurulumda takıldın mı?" şeridi, altbilgide bağlantı; hepsi formu açıp kaydırır. Doğrudan adres: `lexialauncher.pages.dev/#bildir` |
| #62 | Sitede "Kuramadım, sorunu bildir" formu (`/api/report`: adım, TV markası, not, isteğe bağlı iletişim; 90 gün, günde en çok 100; yönetimde silinir) ve daha net sorun giderme maddeleri; yönetimde "Sorunlar" sekmesi (bildirimler + gruplanmış hatalar), "TV'ye indirilip açılmayan kurulum" uyarısı |
| #61 | Yönetim › Kullanım: "Bugün güncelleyenler" (`/dl` → `updv:gün:N`). Sürüm listesi TV'nin günün ilk açılışındaki sürümü; gün içinde güncelleyen ertesi gün yeni sürümle görünür (aylık liste ayın ilk açılışı) |
| #60 | Ana ekran olarak kullananlar: ping'e `h=b` (Ana Sayfa tuşu hizmeti açık) / `d` (yalnızca varsayılan launcher) / `a` (uygulama olarak); `home:gün:x`, `homem:ay:x`; yönetimde Özet › Aktif kullanıcılar altında |
| #59 | Aktif kullanıcılar: TV günde/haftada/ayda bir kez sayılır (`d`/`w`/`m` bayrakları, kimliksiz) → yönetimde Bugün · Bu hafta · Bu ay (kurulu cihazın en iyi tahmini) · İlk açılış toplamı; bu ay sürümlere göre |
| #58 | Yönetim: indirme ayrıntısı (son 24 saat saat saat, cihaz türü TV/telefon/bilgisayar, günlük güncelleme indirmeleri), içerik filtresi (18+ gizle, en düşük IMDb), satırları herkeste kapatma, yeni kurulum ayarları, zamanlanmış duyuru/seçki (`from`), değişikliği geri alma, site duyuru şeridi. TV'ler 5 dk'da bir zamanlamaya bakar |
| #57 | Yönetim sayfası düzeni: sekmeler (Özet · Kullanım · Hatalar · Yönet · Sistem, seçim hatırlanır, hata varsa kırmızı nokta), "Bugün" kartı (düne göre ↑↓), grafikte güne dokununca o günün tüm sayıları ve sürümleri, kurulum oranları (%), başından beri toplamlar (`new` toplam sayacı), açıkken dakikada bir yenileme |
| #56 | Yönetim sayfası: kurulum hunisi (indirdi → kurulumu açtı → LeXia açıldı), hata raporları (isimsiz: sürüm + hata satırları, 30 gün), "Hata (7 gün)" kutusu. Kullanıcı TV listesi **istemedi** (özele girer): yalnızca toplamlar |
| #55 | Yönetici profil resimleri (`docs/brand/pp`: amblem, yazılı, ADMIN rozetli; 1024 px, yuvarlak kırpmaya uygun; `node docs/brand/pp/render.js`) |
| #54 | Fikir/kod koruması: release'te R8 küçültme + ad karıştırma (`app/proguard-rules.pro`, satır numaraları korunur); `mapping.txt` gizli `apk` dalında (sitede yok); Hakkında ve site altında telif notu |
| #53 | Paylaşım öncesi kontrol: APK artık **release** (debuggable değil, aynı anahtar); uygulamadan GitHub yedek adresleri çıktı (yalnızca site); yönetim sayfasında depo adı yazılı değil (bir kez girilir, tarayıcıda kalır); gizlilik metni: ipapi.co/geojs.io, YouTube, günlük TV sayımı |
| #52 | Site SSS: en düşük sürüm Android (TV) 7.0 (`minSdk 24`), Samsung/LG'ye kurulmaz (Chromecast/kutu ile olur); uyumlu TV'lerde sürüm notu |
| #51 | Seçim pencereleri LeXia tasarımında (`Dialogs.list`: ikon + başlık + açıklama, satırda sağda değer, beyaz plaka, seçilide ✓); "Ne izlesem?" "7 film" gibi sağda sayılarla; onay penceresi de aynı kartta; bildirim paneli tanıtım görseliyle birebir (cam #DB, 12 px nokta, plaka köşesi 18) |
| #50 | Tanıtım görselleri (`docs/promo`): sitedekilerden farklı 6 görsel (özet, seçki, zil, takvim, Ne izlesem?, tarihte bugün) |
| #49 | Sayaçlar temiz: `/kur` ve `/dl` robotları, link önizlemelerini ve curl gibi test araçlarını saymaz (yalnızca GET); `/api/hit` robotları ve sahibin tarayıcısını (`lexia.me`, yönetim sayfasına girince konur) saymaz; yönetim sayfasında "Sayaçları sıfırla" (`/api/reset`, aynı anahtar doğrulaması) |
| #48 | `/api/ping` sürüm 0'ı saymaz (10 Kasım'daki bir deneme isteği o günün TV sayısını 1 artırdı) |
| #47 | Yönetim sayfası genişledi: açılan TV sayısı ve sürümleri, 14 günlük grafikler, derleme durumu, film listesini elle yenileme, seçki filmi, film gizleme, güncelleme bekletme, duyuru önizlemesi, değişiklik geçmişi, "Ne izlesem?" kapatma; uygulama bunlara uyar. Sayfada bağlandıktan sonra kalan "Bağlan" kutusu düzeltildi |
| #46 | Yönetim sayfası: anahtar depoyu göremezse (404) ne yapılacağını söyler; eski GitHub Pages adresi artık 404 |
| #45 | Güvenlik ve sağlık kontrolü: sitede içerik güvenlik kuralı (CSP), ziyaret sayacı yalnızca sitenin kendi sayfasından, Yenilikler kartı aynı listeyi ikinci kez göstermez, hata mesajında "GitHub" yerine "Sunucu", film listesi saati 02:17, eskimiş notlar |
| #44 | Notlar: açık işler, korsan izleme kararı |
| #43 | Notlar: geçiş tamamlandı, depo gizli |
| #42 | Uygulama siteden okur (B aşaması); sitede tanıtım animasyonu, uyumlu TV'ler, SSS, link önizlemesi; eski kopyayı silme işi |
| #41 | Site: görseller telefonda ezilmesin |
| #40 | Site adresi lexialauncher |
| #39 | Cloudflare sitesi (A aşaması): tanıtım sitesi, yönetim sayfası, indirme/ziyaret sayacı, otomatik yükleme |
| #38 | LeXia Yönetim sayfası (GitHub Pages) ve uzaktan ayarlar: duyuru, zorunlu güncelleme, özellik kapatma; README kurulum linki sayılan adrese |
| #37 | Sinema tarihinde bugün: IMDb < 6,5 elenir; film betiği değişince APK derlenmez |
| #36 | Takvim sayfası, bildirim merkezi (zil), saate göre uygulama sırası, ekran koruyucuda "Sinema tarihinde bugün" |
| #35 | Yazıda L harfi de renkli (banner, README, kapak görseli) |
| #34 | Yeni ad ve amblem: LeXia Launcher (uygulama adı, ikon, TV banner'ı, sihirbaz, README, kapak görseli) |
| #33 | README: arkadaşlar için adım adım kurulum (Downloader, izin, kurulum APK'sı, sihirbaz) ve sorun giderme |
| #32 | Görsel README: banner ve 5 ekran görseli (ana ekran, satırlar, film sayfası, ekran koruyucu, ayarlar) |
| #31 | İzledim/Beğendim, Sana göre tür satırları, havaya göre film, afişten detay sayfasına büyüyerek geçiş, cila turu (`D` ölçü seti) |
| #30 | #29'un gözden geçirilmesi: hafif mod kapanınca yağmur/kar geri gelir, büyük saat ekrandan taşmaz, önizleme değer değişince kararmaz, gece uykusu tam saatinde, yenilikler kartı ana ekran öndeyken |
| #29 | Ayarlar paketi (sağ/sol, okunur seçili satır, canlı önizlemeler, sayfa sıfırlama), ekran koruyucu seçenekleri ve slogan, uyku bitiş saati/uyarı/erteleme, her gece uyku, hafif mod, gezinme sesi, yaş sınırı, dizi takvimi, yenilikler kartı |
