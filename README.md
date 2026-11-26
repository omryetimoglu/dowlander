# eVasi Downloader App

<img src="app/src/main/res/drawable-xxxhdpi/brand_logo.webp" width="96" alt="eVasi Downloader App logosu">

Android 10 ve üzeri için Kotlin ve Jetpack Compose ile geliştirilmiş bir galeriye kaydetme uygulaması.

[Android 10 ve üzeri için 1.2.1 evrensel APK’yı indir (226 MB)](apk/eVasiApp.apk?raw=true)

Bu test APK’sı ARM64, ARMv7, x86 ve x86_64 cihazları destekler. **Evasi X Test** ve önceki GitHub test sürümlerinin üzerine kurulabilir. İlk sürüm (1.0) farklı paket kimliği ve imza kullandığı için yeni sürüm onun yanına ayrı bir uygulama olarak kurulur.

APK’nın SHA-256 özeti: `3cdf6179c2b46ef383fe1a986067ffa71188876ff99641857d826e9a12ab207f`. Depoyu klonladığınızda APK’yı indirmek için `git lfs pull` çalıştırabilirsiniz.

## Kullanım

1. Instagram, Facebook, Threads, TikTok, YouTube, X veya Mastodon’dan bir gönderi bağlantısı kopyalayıp uygulamayı açın. Bağlantıyı elle okumak için **Bağlantıyı yapıştır** düğmesini kullanın. Kaynak uygulamanın **Paylaş → eVasi Downloader App** seçeneği indirmeyi doğrudan başlatır.
2. Pano akışında **Piksel seç** ile sunulan boyutlardan birini seçebilir veya **İndirmeyi başlat** düğmesine dokunabilirsiniz. Otomatik başlatma açıksa iki düğme görünürken varsayılan bekleme süresi 1,5 saniyedir. Kalite seçimine dokunmak bu gönderinin sayacını durdurur. Bu kaynak revizyonundan derlenen uygulama, Android erişilebilirlik ayarında seçtiğiniz daha uzun süreyi bekler. Hassas olarak işaretlenen pano bağlantılarını **Bağlantıyı yapıştır** düğmesiyle açabilirsiniz.
3. İndirme tamamlandığında **Galeride aç** düğmesine dokunun. Yeni videolar `Movies/eVasi Downloader App`, fotoğraflar `Pictures/eVasi Downloader App` albümüne kaydedilir.

**Settings → Language → Türkçe** ile dili seçebilirsiniz. Pano için otomatik başlatma ayarı da aynı ekrandadır.

## Desteklenen bağlantılar

| Kaynak | Medya |
| --- | --- |
| Instagram ve Facebook | Herkese açık fotoğraf, video, Reels ve görsel ekleri erişilebilen albüm gönderileri |
| Threads | threads.com ve threads.net üzerindeki herkese açık fotoğraf, video ve albüm gönderileri |
| TikTok | Fotoğraf slaytları, video gönderileri ve kısa paylaşım bağlantıları |
| YouTube | Video, Shorts ve youtu.be bağlantıları |
| X (Twitter) | Herkese açık gönderilerde özgün boyutlu fotoğraflar, MP4 videoları ve karma albümler |
| Mastodon | Herkese açık gönderilerde fotoğraf, video ve GIFV ekleri |
| Android paylaşım menüsü | Uygulamaya gönderilen fotoğraf, video ve GIF dosyaları |

Instagram, Facebook, TikTok ve YouTube videoları cihazdaki yt-dlp ile çözümlenir. Ayrı sunulan görüntü ve ses akışları gerektiğinde FFmpeg ile birleştirilir. Albüm bağlantıları gönderinin tamamına uygulanır. Örneğin Instagram’daki `img_index=1` yalnızca tarayıcıda seçili görseli belirtir. Uygulama fotoğraf ve videoları gönderi sırasıyla kaydeder. Bir aktarım kesilirse **Yeniden dene** aynı oturumda kaydedilen öğeleri atlar. Oturum açmayı gerektiren içerikler, DRM korumalı videolar ve devam eden canlı yayınlar desteklenmez.

Albüm için kaynağın herkese açık olarak sunduğu ek listesi ve dosyalar gerekir. Yalnızca kapak veya eksik bir fotoğraf listesi sunulan albümlerde indirme tamamlanamayabilir. Galeri uygulamasında seçilen birden fazla dosyayı Android paylaşım menüsünden de gönderebilirsiniz.

X için otomatik seçim en yüksek bit hızlı MP4’ü kullanır. Mastodon için otomatik seçim her ekin özgün dosyasını alır. Android’in doğrudan paylaştığı medya aynı baytlarla galeriye kopyalanır.

## Derleme ve çalıştırma

GitHub’dan **Code → Download ZIP** ile indirdiyseniz ZIP’i yeni bir klasöre çıkarın. Android Studio’da **File → Open** ile içinde `settings.gradle.kts` dosyasını gördüğünüz klasörü seçin. Gradle eşitlemesi bitince üst çubukta **app** ve emülatörünüzü seçip **▶ Run** düğmesine basın.

Proje Android SDK Platform 35 ve Gradle wrapper kullanır. Java hedefi 17’dir. PowerShell ile çalıştırmak için proje kökünü açın. SDK konumunu `ANDROID_HOME` ile ya da Android Studio’nun oluşturduğu `local.properties` ile belirtebilirsiniz.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:installDebug
adb shell am start -n com.evasi.downloader/.MainActivity
```

GitHub’daki test sürümüyle aynı paket kimliğini kullanmak için derleme komutuna `-PgithubApk` ekleyin. Mevcut kurulumu güncellemek için aynı imzalama anahtarını kullanın.

Debug APK çıktısı `app/build/outputs/apk/debug/eVasiApp.apk` olur. Kurulum için USB hata ayıklaması açık bir telefon veya çalışan bir Android emülatörü gerekir.

Linux ve macOS üzerinde proje kökünde `./gradlew :app:testDebugUnitTest :app:assembleDebug` komutunu kullanın. APK, video çözümleme ve ses birleştirme araçlarını içerir.

## Doğrulama

Birim testleri pano zamanlamasını sanal saatle, kaliteye erken dokunmayı, paylaşımın panoya önceliğini, eski bağlantının atlanmasını ve kesilen indirmeyi yeniden başlatmayı kapsar.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Cihaz testleri İngilizce ilk açılışı, Türkçe seçiminin korunmasını, kalite düğmesine basmayı ve art arda paylaşılan iki animasyonlu GIF’in ekran döndüğünde bile galeriye birer kez yazılmasını denetler.

Gerçek bir gönderiyi sınamak için aşağıdaki komutta `GONDERI_URL` yerine tek ekli, herkese açık bir Mastodon gönderisinin tam adresini yazın. Bu test Android paylaşım niyetini gönderir, galerideki kaydın yayımlandığını denetler ve dosyanın SHA-256 özetini sunucunun özgün ekiyle karşılaştırır. Test sonunda oluşturduğu kaydı temizler.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.evasi.downloader.PublicPostTest" "-Pandroid.testInstrumentationRunnerArguments.postUrl=GONDERI_URL"
```

### Gerçek gönderi kontrolleri

Masaüstü JVM testinde aşağıdaki iki gönderinin gerçek API yanıtları uygulamanın Mastodon çözümleyicisinden geçirildi. Test, özgün ekin tamamını okudu ve dosya türünü imzasından belirledi.

| Gönderi | Özgün dosya | Okunan boyut |
| --- | --- | ---: |
| [Thomas Kole’nin örümcek ağı simülasyonu](https://mastodon.gamedev.place/@thomas_kole/116385659543124875) | MP4 · 1080 × 1080 | 8.656.339 bayt |
| [ephtracy’nin MagicaCSG görseli](https://mastodon.gamedev.place/@ephtracy/113995266669577850) | PNG · 1280 × 832 | 364.187 bayt |

Kaynak ve kayıt testlerini iki gönderiyle birlikte çalıştırabilirsiniz.

```powershell
$env:EVASI_PUBLIC_POSTS = 'https://mastodon.gamedev.place/@thomas_kole/116385659543124875 https://mastodon.gamedev.place/@ephtracy/113995266669577850'
.\gradlew.bat :app:testDebugUnitTest
```

`AlbumDownloadTest`, platformların gönderi ve medya yanıtlarını taklit ederek gerçek Android MediaStore üzerinde Instagram, Facebook, Threads, TikTok ve X albümlerini sınar. Öğe sayısını, sırasını, galeri koleksiyonunu ve kaydedilen baytları denetler.

Cihaz testlerindeki `NativePlatformVideoTest`, yerel bir DASH örneğinin ayrı H.264 görüntü ve AAC ses akışlarını indirir, birleştirilmiş dosyayı MediaStore’a kaydeder ve iki izin de okunabildiğini denetler. Bu test için bir platform hesabı veya harici ağ erişimi gerekmez.
