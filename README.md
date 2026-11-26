# eVasi Video Downloader

<img src="app/src/main/res/drawable-xxxhdpi/brand_logo.webp" width="96" alt="eVasi Video Downloader logosu">

Android 10 ve üzeri için video, fotoğraf, GIF ve albüm indirme uygulaması. Varsayılan dil İngilizcedir. **Settings → Language → Türkçe** seçimi sonraki açılışlarda korunur.

## Kurulum

[2.0.0 evrensel debug APK · 231 MB](https://github.com/omryetimoglu/dowlander/raw/refs/heads/main/apk/eVasiApp.apk?download=1) ARM64, ARMv7, x86 ve x86_64 içerir. GitHub sürümünün paket kimliği `com.evasi.downloader.xtest`, Android Studio’daki varsayılan derlemenin kimliği `com.evasi.downloader` olur. Eski kurulumu yerinde güncellemek için yeni APK aynı paket kimliği ve imzayı taşımalıdır. GitHub’ın kaynak ZIP’inde APK için Git LFS işaretçisi bulunabilir. Telefona kurmak için yukarıdaki APK bağlantısını açın. Klon kullanıyorsanız `git lfs pull` da APK’yı indirir.

GitHub APK SHA-256 özeti `84692702501fdf1866bb29381785f2c2ff59fe8b5f63ebeb7cc8d0a4936fdbbb`.

Android Studio için GitHub’da **Code → Download ZIP** seçin ve ZIP’i yeni bir klasöre çıkarın. **File → Open** ile içinde `settings.gradle.kts` dosyasını gördüğünüz klasörü açın. Gradle eşitlemesi bitince üstte **app**, yanında emülatörünüzü seçip **▶ Run** düğmesine basın. Projede Gradle Wrapper, Android SDK Platform 35 ve Java 17 kullanılır. Windows’ta aşağıdaki komutlarda `./gradlew` yerine `.\gradlew.bat` yazabilirsiniz.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:connectedDebugAndroidTest
```

Normal debug çıktısı `app/build/outputs/apk/debug/eVasiApp.apk` olur. GitHub test paketiyle uyumlu derlemeyi oluşturmak için `./gradlew -PgithubApk :app:assembleDebug` çalıştırın.

## Kullanım

- **Home** ekranına bağlantıyı yazın, yapıştırın veya bir bağlantıyı kopyaladıktan sonra uygulamayı açın. Önizleme hazır olunca **Select quality** ile kaynağın sunabildiği çözünürlükleri görebilirsiniz. **Start download** indirmeyi kuyruğa alır. Otomatik indirme ilk kurulumda kapalıdır.
- Android’in **Paylaş → eVasi Video Downloader** seçeneğiyle gönderilen bağlantı veya medya dosyaları doğrudan kuyruğa alınır. Paylaşım menüsünden gelen yeni gönderi panodaki eski bağlantıdan önceliklidir.
- **Downloads** bekleyen aktarımları sıralamak, duraklatmak, sürdürmek, iptal etmek ve hata veren aktarımı yeniden denemek içindir. Bir aktarım varsayılandır. **Settings** içinden aynı anda çalışacak aktarım sayısını değiştirebilirsiniz. Kesilen dosya yeni bir denemede baştan alınabilir. Tamamlanmış albüm öğeleri kaydedilen kontrol noktalarından tanınır.
- Videolar **Movies**, görseller **Pictures** altında seçilen albüme kaydedilir. **History** her dosyanın adını, boyutunu, tarihini ve bulunabildiğinde çözünürlüğünü gösterir. Galeride açabilir, Android paylaşım menüsüne gönderebilir veya silebilirsiniz. Ayarlardaki geçmişi temizleme işlemi galerideki dosyaları korur.
- Kaydedilen dosyayı ana ekrandan doğrudan paylaşabilirsiniz. **X ile giriş**, sağlayıcının mevcut yanıtında bulunan gönderi metnini düzenleme, kopyalama ve dosyayla birlikte gönderme özelliğini açar. Hedef uygulamanın açıklama metnini kullanıp kullanmadığı kendi paylaşım desteğine bağlıdır.

## Platformlar ve içerikler

| Kaynak | Uygulamanın işleyebildiği herkese açık içerik |
| --- | --- |
| Instagram | Fotoğraf, video, Reels ve tam ek listesi erişilebilen albüm |
| Facebook | Fotoğraf, video, Reels ve tam ek listesi erişilebilen albüm |
| Threads | threads.com veya threads.net üzerindeki fotoğraf, video ve albüm |
| TikTok | Video, fotoğraf slaytları ve kısa paylaşım bağlantıları |
| X | Fotoğraf, MP4 video, platformun sunduğu animasyon ve karma albüm |
| YouTube | Video ve Shorts |
| Mastodon | Açık gönderilerde fotoğraf, video ve GIFV |
| Android paylaşım menüsü | Tek veya çoklu fotoğraf, video ve animasyonlu GIF dosyaları |

Kalite ve dosya boyutu seçenekleri kaynağın verdiği bilgiye bağlıdır. Varsayılan seçim her öğenin erişilebilen en yüksek kalitesini alır. Ayrı sunulan ses ve görüntü gerektiğinde cihazdaki FFmpeg ile birleştirilir. Bir platform yalnızca kapak fotoğrafı veya eksik bir liste döndürürse albüm tamamlanamayabilir. Oturum gerektiren özel içerik, DRM ve aktif canlı yayın desteklenmez. Reddit ve Pinterest için doğrulanmış bir çözümleyici sunulmuyor.

## X hesabı ve iletişim yapılandırması

[config/app.properties](config/app.properties) yalnızca herkese açık istemci kimliği, dönüş adresi ve isteğe bağlı destek e-postası içindir. Boş değerlerle uygulamanın misafir işlevleri kullanılabilir.

1. X Developer Portal’da mobil uygulamayı **Native App / Public Client** olarak kaydedip OAuth 2.0’ı etkinleştirin. Salt okuma izinlerini seçin.
2. Portalda tam bir dönüş adresi kaydedin. Örnek `com.evasi.downloader://oauth/x`. Aynı adresi `EVASI_X_REDIRECT_URI` ve herkese açık **Client ID** değerini `EVASI_X_CLIENT_ID` olarak yazın. Gerekirse Android Studio ve GitHub test derlemeleri için ayrı uygulama kayıtları ve benzersiz dönüş şemaları kullanın. Doğrulanmış HTTPS yönlendirmesi kullanıyorsanız kendi alan adınızda Android App Links ilişkilendirmesini de yayımlayın.
3. İsterseniz `EVASI_SUPPORT_EMAIL` değerine destek gelen kutunuzu yazın. Yapılandırma değişikliklerinden sonra APK’yı yeniden derleyin.
4. Test cihazında **Settings → X** bölümünden sistem tarayıcısını açıp hesabınızla giriş yapın. İzinleri ve uygulamaya dönüşü gözlemleyin. Gönderi metni bulunan bir videoda metni düzenleyip paylaşın, ardından çıkış yaparak metin seçeneğinin tekrar kilitlendiğini doğrulayın.

OAuth akışı Authorization Code + PKCE (S256) kullanır. İstenen kapsamlar `users.read` ve `tweet.read` ile sınırlıdır. Uygulama adına X’te gönderi yayımlanmaz. Client secret mobil uygulamaya veya depoya girilmez. Kaynak yapılandırma değerleri Gradle projesi özelliği veya aynı adlı ortam değişkeniyle de verilebilir.

## Release APK ve Google Play

Yayımlama anahtarı proje sahibine aittir. Şu değerleri makinenizdeki ortam değişkenlerine veya kişisel Gradle ayarlarınıza (`~/.gradle/gradle.properties`) ekleyin. Keystore’u kaynak deposunun dışında saklayın.

| Değer | İçerik |
| --- | --- |
| `EVASI_RELEASE_KEYSTORE` | Keystore dosyasının yolu |
| `EVASI_RELEASE_STORE_PASSWORD` | Keystore parolası |
| `EVASI_RELEASE_KEY_ALIAS` | İmzalama anahtarının takma adı |
| `EVASI_RELEASE_KEY_PASSWORD` | Anahtar parolası |

```sh
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

APK `app/build/outputs/apk/release/`, Android App Bundle ise `app/build/outputs/bundle/release/` altında oluşur. İmzalama yapılandırması boşken release çıktısı imzasız üretilir. Google Play dağıtımında AAB, cihaza uygun yerel kitaplıkların dağıtılmasını sağlar.

## Teknik yapı ve doğrulama

Compose ekranları ViewModel’den gelen durumu gösterir. Room indirme görevlerini, geçmişi ve tamamlanmış albüm öğelerini saklar. WorkManager işi arka planda yürütür. DataStore görünüm ve indirme tercihlerini saklar. MediaStore ile yapılan kayıt için tüm galeriye erişim izni istenmez. Android’in zorla durdurma, güç, ağ ve arka plan çalışma kısıtları görevin ne zaman sürdürülebileceğini etkiler.

[Bu sürümün test sonuçları](docs/2.0-test.md) emülatör, yerel örnek ve dış servis doğrulamalarını ayrı belirtir.
