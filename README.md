# Evasi Video Dowlander

[Android 10 ve üzeri için debug APK’yı indir](https://github.com/omryetimoglu/dowlander/raw/refs/heads/main/apk/app-debug.apk)

Android 10 ve üzeri için Kotlin ve Jetpack Compose ile geliştirilmiş bir galeriye kaydetme uygulaması. İlk sürüm, Mastodon sunucularının oturum açmadan okunabilen gönderilerini ve Android üzerinden doğrudan paylaşılan medya dosyalarını destekler.

## Kullanım

- **Paylaş → Evasi Video Dowlander** gönderiyi hemen çözümler. Uygulama her öğenin sunulan özgün dosyasını kaydeder. Doğrudan paylaşılan fotoğraf, video veya GIF dosyaları aynı baytlarla kopyalanır.
- **Bağlantıyı kopyala** işleminden sonra uygulamayı açın. İki alt düğme de kullanılabilir olduğunda 1,5 saniyelik sayaç başlar. **Piksel seç** düğmesine dokunmak o gönderinin sayacını kalıcı olarak durdurur. **İşlemi başlat** seçilen kaliteyle devam eder.
- **Settings → Language → Türkçe** seçimi hemen uygulanır ve sonraki açılışta korunur. İlk dil İngilizcedir. **Kopyalanan bağlantıları otomatik başlat** ayarı pano akışını yönetir.
- Tamamlanan dosyalar, medya türüne göre `Pictures/Evasi Video Dowlander` veya `Movies/Evasi Video Dowlander` albümüne yazılır. **Galeride aç** son kaydedilen öğeyi açar. Çok öğeli bir aktarım kesilirse **Yeniden dene** tamamlanan öğeleri atlar.

Mastodon videosunda ses ve görüntü birlikte korunur. Sunucunun GIFV olarak sunduğu animasyon, sunulan video biçiminde kaydedilir. Piksel seçenekleri sunucunun bildirdiği gerçek boyutlardan gelir. Birden fazla öğede panel, tüm öğelerde bulunan ortak boyutları gösterir. Otomatik indirme her öğenin kendi özgün dosyasını kullanır.

## Windows üzerinde derleme ve çalıştırma

Proje Android SDK Platform 35 ve JDK 17 kullanır. Android Studio ile proje kökünü açabilir veya PowerShell üzerinden çalıştırabilirsiniz. SDK konumunu `ANDROID_HOME` ile ya da Android Studio’nun oluşturduğu `local.properties` ile belirtin.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:installDebug
adb shell am start -n com.evasi.downloader/.MainActivity
```

Debug APK çıktısı `app/build/outputs/apk/debug/app-debug.apk` olur. Kurulum için USB hata ayıklaması açık bir telefon veya çalışan bir Android emülatörü gerekir.

Bu çalışma klasörüne indirilen yerel araçları kullanmak için PowerShell’de `.\.tools\build-local.ps1 :app:testDebugUnitTest :app:assembleDebug` komutunu çalıştırabilirsiniz.

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

İki canlı gönderiyle yapılan son çalıştırmada 18 masaüstü testi geçti. Android 10 Robolectric testinde video, kapalı uygulamaya Android paylaşım niyetiyle gönderildi. Fotoğraf, aynı uygulama açıkken paylaşıldı. Panoda başka bir bağlantı ve pano otomatik başlatma ayarı kapalıyken her özgün ek, dosya tabanlı MediaStore test sağlayıcısında doğru koleksiyona birer kez yazıldı. Kaydedilen dosyaların SHA-256 özetleri özgün eklerle eşleşti.

Compose testinde kalite seçenekleri yüklenirken düğmeye dokunulduğunda, parmak kalkmadan otomatik başlatmayı durduran olayın iletildiği doğrulandı. Sanal saat testi 1.499 ms’deki dokunuşun indirmeyi durdurduğunu gösterdi. Doğrudan paylaşılan GIF örneği bayt bayt korundu. Kesik aktarım ve iptal testlerinde bekleyen kayıt silindi.

Fiziksel Android cihazı bağlı değildi. Android 10 emülatörü bu bilgisayarda yazılımsal CPU modunda açılışta kapandı. Sistem paylaşım menüsündeki görünüm, gerçek galeri uygulamasında açma ve cihazı döndürme davranışı telefonda doğrulanamadı. Cihaz test APK’sı başarıyla derlendi.
