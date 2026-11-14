# Nuvio medya altyapısını LeXia'ya taşıma planı

Tarih: 14 Kasım 2026

## Hedef

LeXia'nın ana ekranı, film/dizi detay ekranları, View tabanlı tasarımı, kumanda odağı ve launcher davranışı korunur. Sağlayıcı keşfi, eklenti çalıştırma, stream/subtitle sorgulama ve medya kaynağı hazırlama NuvioTV'nin gerçek kaynak kodundan taşınır.

NuvioTV kaynak deposu: https://github.com/NuvioMedia/NuvioTV
Kişisel upstream fork: https://github.com/OoMrToO/NuvioTV
İzlenen Nuvio dalı: `dev`
Lisans: GPL-3.0

## Kaynak kod denetiminde görülen yapı

Nuvio'nun sağlayıcı sistemi tek başına çalışan birkaç dosyadan ibaret değildir:

- `app/src/full/java/com/nuvio/tv/core/plugin/PluginManager.kt`: depo/eklenti yönetimi, scraper keşfi, çalıştırma orkestrasyonu ve sonuç toplama. Yaklaşık 1.228 satır.
- `app/src/full/java/com/nuvio/tv/core/plugin/PluginRuntime.kt`: QuickJS tabanlı JavaScript çalışma zamanı ve eklenti API'leri. Yaklaşık 1.487 satır.
- `app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt`: Stremio eklentileri ve yerel scraper sonuçlarını birleştiren stream arama katmanı. Yaklaşık 860 satır.
- `app/src/main/java/com/nuvio/tv/data/repository/SubtitleRepositoryImpl.kt`: altyazı sorgulama.
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`: Media3 medya kaynağı, HTTP başlıkları, adaptif akışlar ve altyazı yönlendirmesi.
- `app/src/full/java/com/nuvio/tv/di/PluginModule.kt`: Hilt üzerinden plugin runtime/manager bağımlılıkları.

Nuvio'nun `full` kaynak kümesi plugin runtime'ı içerir. Bu kodlar Nuvio uygulamasının kendi `PluginDataStore`, `AuthManager`, `PluginSyncService`, domain modelleri, ağ modülleri ve DI düzenine bağlıdır. `StreamRepositoryImpl` ayrıca TMDB, profil, debrid ve medya sunucusu bileşenleriyle bağlantılıdır. Dolayısıyla bu dosyaları doğrudan LeXia'ya kopyalamak derlenebilir veya güvenilir bir entegrasyon oluşturmaz.

LeXia şu anda daha küçük, View tabanlı bir uygulamadır. Kendi Stremio istemcisi (`MediaAddonRepository`), Nuvio JS deposu/runtime'ı (`NuvioJsRepository`, `NuvioJsRuntime`) ve Media3 oynatıcısı (`PlayerActivity`) bulunur. Amaç mevcut LeXia UI sözleşmelerini koruyup sağlayıcı/stream katmanını Nuvio'nun gerçek uygulamasına geçirmek; ikinci bir Compose arayüzü taşımak değildir.

## Uygulama sırası

1. **Bağımlılık haritası:** Nuvio plugin runtime, scraper modelleri, repository, ağ/fetch katmanı, eklenti ayarları ve player media-source kodunun zorunlu bağımlılıklarını çıkar.
2. **Bağımsız medya çekirdeği:** Nuvio kaynak kodunu ve lisans bildirimlerini koruyarak gerekli sınıfları LeXia içinde ayrı bir paket/modüle taşı. Nuvio'ya özgü uygulama servislerini küçük LeXia adaptörleriyle değiştir; davranışı yeniden yazma.
3. **Sağlayıcı entegrasyonu:** LeXia'nın film/dizi TMDB kimliklerini Nuvio'nun arama sözleşmesine bağla. Nuvio scraper ve Stremio addon sonuçlarını tek kaynak listesinde sun.
4. **Oynatma entegrasyonu:** Nuvio stream/headers/subtitle verisini LeXia'nın mevcut PlayerActivity girişine adapt et; gerekli Nuvio Media3 data-source/extractor davranışlarını uyumlu biçimde taşı.
5. **Geçiş ve temizlik:** Eski `NuvioJsRuntime` / `NuvioJsRepository` ve eski stream sorgu kodunu yalnızca yeni yol testlerden geçtikten sonra kaldır.
6. **Doğrulama:** birim testleri, PR Android build ve mümkün olduğunda gerçek Android TV testleri. Gerçek TV testi yapılmadan çalıştığı iddia edilmez.

## Korunacak LeXia sınırları

- LeXia ana ekranı, detay ekranları, mevcut View tabanlı tasarım ve D-pad davranışı değişmez.
- Paket adı `tv.lexia.launcher`, imza, yayın iş akışları ve kullanıcı verisi korunur.
- Nuvio'nun Media3 sürümünü LeXia'ya körlemesine düşürme. LeXia şu anda Media3 1.11.1 kullanıyor; Nuvio'nun incelenen version catalog'u 1.8.0 tanımlıyor. API uyumluluğu kontrol edilerek gerekli kod uyarlanır.
- Mevcut kapsam kararı geçerlidir: doğrudan HTTPS medya akışları; torrent/debrid, CloudStream ve MPV entegrasyonu yok. Bu sınır değiştirilmeden Nuvio'nun gerekli medya davranışları taşınır.
- Her kaynak kod değişikliğinde bu dosya ve `CLAUDE.md` güncellenir.

## Şu anki durum

- Kişisel Nuvio fork'u doğrulandı: `OoMrToO/NuvioTV`, varsayılan dal `dev`, upstream `NuvioMedia/NuvioTV`.
- LeXia'da bu plan için `chore/nuvio-media-engine-port` dalı açıldı.
- Bu commit yalnızca mimari denetim ve geçiş planıdır; henüz Nuvio medya kodu LeXia'ya taşınmadı.

## İkinci teknik karşılaştırma

14 Kasım 2026 tarihinde Nuvio `PluginRuntime`, LeXia `NuvioJsRuntime`, Nuvio `SubtitleRepositoryImpl` ve `PlayerMediaSourceFactory` yeniden karşılaştırıldı.

### LeXia'da zaten bulunan Nuvio runtime davranışları

- QuickJS tabanlı izole JavaScript çalıştırma.
- `fetch`, URL ayrıştırma, Base64, Cheerio/Jsoup köprüsü ve CryptoJS WebJar yükleme.
- Scraper ayarları, scraper kimliği ve TMDB API anahtarının JS ortamına aktarılması.
- Paralel scraper çalıştırma, zaman aşımı ve iptal sırasında etkin HTTP çağrılarını kapatma.
- HTTPS fetch zorunluluğu, yönlendirme denetimi, yanıt boyutu sınırı ve gzip/deflate yanıt çözme.
- Akış başlıkları, kalite, dil, sunucu, boyut, altyazı ve ses gecikmesi alanlarını okuma.

Bu nedenle Nuvio `PluginRuntime.kt` dosyasını olduğu gibi kopyalamak, mevcut LeXia runtime'ını ikinci kez oluşturur ve daha zayıf ağ/iptal denetimlerini geri getirme riski taşır. Runtime için doğru yaklaşım, eksik sözleşme davranışlarını tek tek taşımaktır.

### Bulunan entegrasyon boşlukları

- Nuvio Stremio altyazı deposu, `subtitles` kaynaklarını ayrıca sorgular. LeXia'nın mevcut `MediaAddonRepository` akışı ise meta/stream kaynaklarına odaklanır; bağımsız Stremio altyazı keşfi henüz aynı kapsamda değildir.
- Nuvio'nun altyazı modeli, altyazı URL'siyle birlikte HTTP başlıklarını da taşır. LeXia'nın `MediaSubtitle` ve oynatıcı `SubtitleTrack` modelleri şu anda bu başlıkları oynatma katmanına aktarmıyor.
- Nuvio `PlayerMediaSourceFactory` gelişmiş cache, özel extractor, adaptif akış ve performans seçenekleri içerir. Bunların tamamı doğrudan HTTPS oynatma için zorunlu değildir; Media3 1.11.1 kullanan LeXia'ya topluca taşınmamalıdır. Önce gerçek bir uyumluluk veya oynatma gereksinimi gösterilmelidir.

### Güncellenen teknik sıra

1. Stremio `subtitles` kaynak keşfini mevcut addon manifest/resource modeline ekle.
2. Altyazı URL'si ve başlıklarını kaybetmeden taşıyan bir model/adaptör oluştur.
3. Media3 1.11.1 üzerinde altyazı başlıklarını ayrı veri kaynağıyla uygulamanın güvenilir yolunu doğrula; global video başlıklarına gelişigüzel ekleme yapma.
4. Nuvio'nun gelişmiş player davranışlarından yalnızca LeXia'da ölçülebilir biçimde eksik kalanları taşı.
5. Her aşamayı küçük değişiklik ve CI derlemesiyle doğrula.

Bu denetim bir kod portu değildir; henüz uygulama kaynak kodu değiştirilmedi.
