# 📱 Android Logcat Logger

اسکریپت پایتون برای گرفتن لاگ از برنامه اندروید

## 📋 نیازمندی‌ها

- Python 3.6+
- Android SDK (adb)
- دستگاه اندروید متصل با USB Debugging فعال

## 🚀 استفاده

### گرفتن لاگ به مدت زمان مشخص:
```bash
python logcat_logger.py
```

### گرفتن لاگ به مدت 120 ثانیه:
```bash
python logcat_logger.py -d 120
```

### گرفتن آخرین 200 خط لاگ:
```bash
python logcat_logger.py --recent 200
```

### نمایش لاگ بدون ذخیره:
```bash
python logcat_logger.py -d 60 --no-save
```

## 📁 خروجی

لاگ‌ها در پوشه `log/` با فرمت زیر ذخیره می‌شوند:
- `android_log_YYYY-MM-DD_HH-MM-SS.txt`
- `recent_logs_YYYY-MM-DD_HH-MM-SS.txt`

## 🔍 فیلترهای پیش‌فرض

اسکریپت به صورت خودکار این فیلترها را اعمال می‌کند:
- `com.example.test` (Package name برنامه)
- `AndroidRuntime`
- `FATAL`, `ERROR`
- `UnifiedService`, `SmsService`, `HeartbeatService`
- `NetworkService`, `UnifiedWatchdogWorker`
- `RestartServiceReceiver`, `BootReceiver`
- `MyFirebaseMessagingService`

## ⚙️ تنظیمات

می‌توانید Package name را تغییر دهید:
```bash
python logcat_logger.py --package com.your.package.name
```

