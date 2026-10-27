# Android remote

مشروع أندرويد أصلي بـ Kotlin يتضمن تطبيق الهاتف **Android remote** والتطبيق المرافق **Android TV responsiveness**.

- [افتح الموقع](https://brimiomxf-arch.github.io/android-remote-site1/)
- [شرح الاتصال والتعديل بالعربية PDF](AndroidRemote-Site1-Deploy/AndroidRemote-Guide.pdf)
- [حزمة مصدر Android Studio والواجهة والموقع](AndroidRemote-Site1-Deploy/AndroidRemote-Source.zip)
- [استعراض ملفات المصدر في المستودع](ReceiverControlAndroid/)

التحكم الأساسي يتصل مباشرةً بأجهزة Android TV المتوافقة عبر Android TV Remote ولا يحتاج التطبيق المرافق. إدارة التطبيقات والملفات ولقطات الشاشة تتطلب Android TV responsiveness على الرسيفر أو ADB.

## التحميل

- [تطبيق الهاتف APK](AndroidRemote-Site1-Deploy/ReceiverControl-Controller.apk)
- [تطبيق الرسيفر APK](AndroidRemote-Site1-Deploy/ReceiverControl-Receiver.apk)

رمز Downloader الحالي للرسيفر: `9187062`، والرابط المباشر `http://aftv.news/9187062`.

## البناء

افتح مجلد `ReceiverControlAndroid` من حزمة المصدر في Android Studio مع JDK 17 وAndroid SDK. ابنِ التطبيقين بالأمر:

```powershell
./gradlew.bat :controller:assembleDebug :receiver:assembleDebug
```

راجع [README داخل المشروع](AndroidRemote-Site1-Deploy/README.md) للدليل التفصيلي.
