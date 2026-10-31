# Kullanım ve kurulum

## Android uygulaması

1. APK'yi kurun. İlk kullanımda **İzni aç** üzerinden Erişilebilirlik → Yüklü uygulamalar → Boran PDF iznini etkinleştirin.
2. Sayfa sayısını girin. Başlangıç sayfası da toplama dahildir.
3. **İçeriği aç** düğmesine dokunun; hedef uygulamada başlangıç sayfanızı açıp ekranda beliren **Başlat** düğmesine dokunun.
4. Bitince **Samsung Notes'ta aç → Nota ekle**. Dosya ayrıca **İndirilenler → Boran PDF** klasöründedir.

İşler'de tam ekran, tek sayfa görünümü kullanın; uzun çizim çubuğunu **Basit** düğmesiyle küçültün. Diğer uygulamalarda ileri düğmesini bir kez gösterebilir veya kaydırma yönünü seçebilirsiniz. Uyumluluk uygulamaya bağlıdır. Uzun içerikte kaydırılan ekranlar örtüşebilir; görüntü birleştirme yapılmaz.

Çekim sırasında uygulama veya ekran yönü değiştirmeyin. **Durdur** kaydedilmiş sayfaları `EKSIK.pdf` olarak korur. Süreç sistem tarafından tamamen kapatılırsa bitmemiş geçici dosya tamamlanmış PDF olarak yayımlanmaz. Otomatik devam yoktur. Görünen işaretlemeler görüntünün parçasıdır, düzenlenebilir kalem katmanı değildir. Çok benzer veya boş sayfalar durmaya neden olabilir; ilk/son sayfa ve toplamı kontrol edin.

## Kaynaktan derleme

Gerekenler: Android SDK Platform 36, Build Tools 36.0.0, Java 17+.

```sh
./android/build.sh
```

SDK için `ANDROID_SDK_ROOT`, JDK için `BORAN_JDK` kullanılabilir. APK `android/build/Boran-PDF.apk` altında üretilir. İlk derleme yerel bir imza anahtarı oluşturur. Aynı kurulumun güncellenebilmesi için `android/.signing/` anahtarını koruyun; paylaşmayın.

Varsayılan kaynak ikon nötrdür. İsteğe bağlı kişisel ikonlar, aynı kaynak dizilimini koruyarak `android/local-res/` altında tutulabilir; mevcutsa derlemede birleştirilir. Bu klasör Git'e dahil edilmez. Kişisel kaynaklar olmadan derlemek için:

```sh
BORAN_PERSONAL_RESOURCES=0 ./android/build.sh
```

## Mac CLI

Python 3 ve Android platform-tools gerekir. `./setup` Pillow ve ReportLab kurar. Mac ve tablet aynı ağda olmalı; tablette kablosuz hata ayıklama açık olmalı.

```sh
./setup
./capture --pages 3
```

Gerekirse tabletin güncel adresleriyle eşleştirin ve bağlanın. İki port farklıdır; eşleştirme kodu terminalde sorulur:

```sh
./capture --pair IP:ESLESTIRME_PORTU
./capture --connect IP:BAGLANTI_PORTU
```

İşler profili `profiles/isler.json` içindedir. Birden çok cihaz varsa `--device` ile seçin. Cihaz kimliği gibi yerel ayarlar için `profiles/isler.local.json` oluşturabilirsiniz; varsa varsayılan olarak tercih edilir ve Git'e dahil edilmez.

Tamamlanan PDF `output/pdf/`, kaynak görüntüler `output/captures/` altında tutulur. PDF tablete aktarılır ve Samsung Notes açılır; yalnızca Mac'e kaydetmek için `--no-transfer` ekleyin. Ctrl+C durdurur. Eksik işlem sıfır olmayan kodla çıkar; eldeki sayfalar `.partial.pdf` olarak korunur, otomatik aktarılmaz.

Başka uygulama için `--profile` ile genel bir profil kullanın. Koordinatlar yalnızca örnektir; kendi ekranınıza göre ayarlayın:

```json
{
  "mode": "generic",
  "package": "example.reader",
  "screen_size": [1440, 2304],
  "crop": [0, 120, 1440, 2040],
  "next": ["tap", 867, 2162]
}
```

Kaydırma örneği: `"next": ["swipe", 1150, 1150, 300, 1150, 350]`. Genel profilde sayfa numarası kontrolü yoktur. Önce kısa bir seriyle uygulamanın davranışını kontrol edin. OCR ve DRM aşma yoktur.

## Klasörü silme ve GitHub'dan geri kurma

Repo kaynak kodun tamamını içerir; gerekli SDK ve JDK kurulduktan sonra temiz bir kopyadan APK üretilebilir. Temiz kaynak kopyasının derlenmesi doğrulanmıştır. Hazır APK repo içinde saklanmaz.

```sh
git clone https://github.com/berkaybakac/android-page-to-pdf.git
cd android-page-to-pdf
./android/build.sh
```

**Yerel klasörü silmeden önce `android/.signing/` klasörünü güvenli bir yere ayrıca yedekleyin.** İçindeki anahtar ve parola olmadan yeni bir APK oluşturulabilir, fakat mevcut kurulumun üzerine güncelleme yapılamaz; farklı imzalı APK için eski uygulamayı kaldırmak gerekir. Anahtarı GitHub'a yüklemeyin. Geri kurarken aynı klasöre yerleştirin.

Kişisel fotoğraflı ikonu korumak için `android/local-res/` ve `android/assets/`, yerel cihaz seçimi için `profiles/*.local.json` dosyalarını da ayrıca yedekleyebilirsiniz. İkon dosyaları olmadan uygulama nötr ikonla derlenir. PDF ve ekran görüntüleri de GitHub yedeğine dahil değildir; saklamak istiyorsanız `output/` içeriğini ayrıca koruyun.

GitHub'dan indirmek, tablete kurulmuş uygulamayı etkilemez. Tabletteki uygulamanın günlük çalışması bu Mac klasörüne bağlı değildir.
