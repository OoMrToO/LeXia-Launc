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
