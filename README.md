# eVasi Dowlander App

Android 10 ve üzeri için Kotlin ve Jetpack Compose ile geliştirilmiş bir galeriye kaydetme uygulaması.

[Android 10 ve üzeri için 1.1 evrensel APK’yı indir (226 MB)](https://github.com/omryetimoglu/dowlander/raw/refs/heads/main/apk/app-debug.apk)

Bu test APK’sı ARM64, ARMv7, x86 ve x86_64 cihazları destekler. Önceki **Evasi X Test** kurulumunu yerinde günceller. **Evasi Video Dowlander 1.0** farklı paket kimliği ve imza kullandığı için yeni sürüm onun yanına ayrı bir uygulama olarak kurulur.

APK’nın SHA-256 özeti: `66a5adef86ad0fcaee9ebc82d239490cb8627c362b6d1e2d74ac925f47924045`. Depoyu klonladığınızda APK’yı indirmek için `git lfs pull` çalıştırabilirsiniz.

## Kullanım

1. Instagram, Facebook, TikTok, YouTube, X veya Mastodon’dan bir gönderi bağlantısı kopyalayıp uygulamayı açın. Bağlantıyı elle okumak için **Bağlantıyı yapıştır** düğmesini kullanın. Kaynak uygulamanın **Paylaş → eVasi Dowlander App** seçeneği indirmeyi doğrudan başlatır.
2. Pano akışında **Piksel seç** ile sunulan boyutlardan birini seçebilir veya **İndirmeyi başlat** düğmesine dokunabilirsiniz. Otomatik başlatma açıksa iki düğme göründükten 1,5 saniye sonra indirme başlar. Kalite seçimine dokunmak bu gönderinin sayacını durdurur.
3. İndirme tamamlandığında **Galeride aç** düğmesine dokunun. Yeni videolar `Movies/eVasi Dowlander App`, fotoğraflar `Pictures/eVasi Dowlander App` albümüne kaydedilir.

**Settings → Language → Türkçe** ile dili seçebilirsiniz. Pano için otomatik başlatma ayarı da aynı ekrandadır.

## Desteklenen bağlantılar

| Kaynak | Medya |
| --- | --- |
| Instagram ve Facebook | Herkese açık video gönderileri, Reels ve paylaşım bağlantıları |
| TikTok | Video gönderileri ve kısa paylaşım bağlantıları |
| YouTube | Video, Shorts ve youtu.be bağlantıları |
| X (Twitter) | Herkese açık gönderilerde X’in sunduğu MP4 videoları |
| Mastodon | Herkese açık gönderilerde fotoğraf, video ve GIFV ekleri |
| Android paylaşım menüsü | Uygulamaya gönderilen fotoğraf, video ve GIF dosyaları |

Instagram, Facebook, TikTok ve YouTube videoları cihazdaki yt-dlp ile çözümlenir. Ayrı sunulan görüntü ve ses akışları gerektiğinde FFmpeg ile birleştirilir. Birden fazla video içeren platform gönderilerinde ilk video seçilir. Oturum açmayı gerektiren içerikler, DRM korumalı videolar ve devam eden canlı yayınlar desteklenmez.

X için otomatik seçim en yüksek bit hızlı MP4’ü kullanır. Mastodon için otomatik seçim her ekin özgün dosyasını alır. Android’in doğrudan paylaştığı medya aynı baytlarla galeriye kopyalanır.

## Derleme ve çalıştırma

Proje Android SDK Platform 35 ve Gradle wrapper kullanır. Java hedefi 17’dir. Android Studio ile proje kökünü açabilir veya PowerShell üzerinden çalıştırabilirsiniz. SDK konumunu `ANDROID_HOME` ile ya da Android Studio’nun oluşturduğu `local.properties` ile belirtin.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:installDebug
adb shell am start -n com.evasi.downloader/.MainActivity
```

Debug APK çıktısı `app/build/outputs/apk/debug/app-debug.apk` olur. Kurulum için USB hata ayıklaması açık bir telefon veya çalışan bir Android emülatörü gerekir.

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

Cihaz testlerindeki `NativePlatformVideoTest`, yerel bir DASH örneğinin ayrı H.264 görüntü ve AAC ses akışlarını indirir, birleştirilmiş dosyayı MediaStore’a kaydeder ve iki izin de okunabildiğini denetler. Bu test için bir platform hesabı veya harici ağ erişimi gerekmez.
