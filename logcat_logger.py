#!/usr/bin/env python3
"""
Android Logcat Logger Script
گرفتن لاگ از برنامه اندروید و ذخیره در فایل
"""

import subprocess
import os
import sys
from datetime import datetime
import argparse

# Package name برنامه
PACKAGE_NAME = "com.example.test"

# فیلترهای لاگ
LOG_FILTERS = [
    f"{PACKAGE_NAME}",
    "AndroidRuntime",
    "FATAL",
    "ERROR",
    "UnifiedService",
    "SmsService",
    "HeartbeatService",
    "NetworkService",
    "UnifiedWatchdogWorker",
    "RestartServiceReceiver",
    "BootReceiver",
    "MyFirebaseMessagingService"
]

def get_logcat_command(filters=None, clear=True):
    """ساخت دستور logcat"""
    cmd = ["adb", "logcat"]
    
    if clear:
        cmd.append("-c")  # Clear log buffer
    
    # فیلترهای پیش‌فرض
    if filters:
        for f in filters:
            cmd.append(f"{f}:*")
    
    # فقط خطاها و بالا
    cmd.extend(["*:S"])  # Silence all
    cmd.extend([f"{PACKAGE_NAME}:V"])  # Verbose for our app
    cmd.extend(["AndroidRuntime:E"])  # Errors from AndroidRuntime
    
    return cmd

def save_log_to_file(log_data, filename=None):
    """ذخیره لاگ در فایل"""
    if filename is None:
        timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        filename = f"log/android_log_{timestamp}.txt"
    
    # ایجاد پوشه log اگر وجود نداشته باشد
    os.makedirs("log", exist_ok=True)
    
    filepath = os.path.join(os.getcwd(), filename)
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(f"=== Android Logcat Log ===\n")
        f.write(f"Package: {PACKAGE_NAME}\n")
        f.write(f"Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 50 + "\n\n")
        f.write(log_data)
    
    print(f"✅ لاگ ذخیره شد: {filepath}")
    return filepath

def capture_logcat(duration=60, save_file=True, filters=None):
    """گرفتن لاگ از logcat"""
    print(f"📱 شروع گرفتن لاگ از {PACKAGE_NAME}...")
    print(f"⏱️  مدت زمان: {duration} ثانیه")
    
    # چک کردن adb
    try:
        subprocess.run(["adb", "version"], check=True, capture_output=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("❌ خطا: adb پیدا نشد! لطفاً Android SDK را نصب کنید.")
        sys.exit(1)
    
    # چک کردن اتصال دستگاه
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    if "device" not in result.stdout:
        print("❌ خطا: هیچ دستگاه اندرویدی متصل نیست!")
        print("💡 لطفاً دستگاه را با USB متصل کنید و USB Debugging را فعال کنید.")
        sys.exit(1)
    
    # ساخت دستور logcat
    cmd = get_logcat_command(filters=filters or LOG_FILTERS, clear=True)
    
    print(f"🔍 فیلترها: {', '.join(filters or LOG_FILTERS)}")
    print("📝 در حال گرفتن لاگ... (Ctrl+C برای توقف)\n")
    
    try:
        # اجرای logcat
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        
        log_lines = []
        start_time = datetime.now()
        
        # خواندن لاگ‌ها
        for line in process.stdout:
            if line.strip():
                log_lines.append(line)
                print(line.strip())  # نمایش در کنسول
            
            # چک کردن مدت زمان
            elapsed = (datetime.now() - start_time).total_seconds()
            if elapsed >= duration:
                print(f"\n⏱️  مدت زمان ({duration} ثانیه) تمام شد.")
                break
        
        # توقف process
        process.terminate()
        process.wait(timeout=5)
        
        log_data = "".join(log_lines)
        
        # ذخیره در فایل
        if save_file and log_data:
            filepath = save_log_to_file(log_data)
            print(f"📊 تعداد خطوط: {len(log_lines)}")
            return filepath
        else:
            print("⚠️  هیچ لاگی دریافت نشد!")
            return None
            
    except KeyboardInterrupt:
        print("\n\n⚠️  متوقف شد توسط کاربر")
        process.terminate()
        if save_file and log_lines:
            filepath = save_log_to_file("".join(log_lines))
            return filepath
        return None
    except Exception as e:
        print(f"❌ خطا در گرفتن لاگ: {e}")
        sys.exit(1)

def get_recent_logs(count=100):
    """گرفتن آخرین لاگ‌ها"""
    print(f"📱 گرفتن آخرین {count} خط لاگ...")
    
    cmd = ["adb", "logcat", "-d", "-t", str(count)]
    cmd.extend([f"{PACKAGE_NAME}:V", "AndroidRuntime:E", "*:S"])
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.stdout:
            timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
            filename = f"log/recent_logs_{timestamp}.txt"
            return save_log_to_file(result.stdout, filename)
        else:
            print("⚠️  هیچ لاگی پیدا نشد!")
            return None
    except Exception as e:
        print(f"❌ خطا: {e}")
        return None

def main():
    parser = argparse.ArgumentParser(
        description="گرفتن لاگ از برنامه اندروید",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
مثال‌ها:
  python logcat_logger.py                    # گرفتن لاگ به مدت 60 ثانیه
  python logcat_logger.py -d 120            # گرفتن لاگ به مدت 120 ثانیه
  python logcat_logger.py --recent 200      # گرفتن آخرین 200 خط لاگ
  python logcat_logger.py -d 0 --no-save    # نمایش لاگ بدون ذخیره
        """
    )
    
    parser.add_argument(
        "-d", "--duration",
        type=int,
        default=60,
        help="مدت زمان گرفتن لاگ به ثانیه (پیش‌فرض: 60)"
    )
    
    parser.add_argument(
        "--recent",
        type=int,
        metavar="COUNT",
        help="گرفتن آخرین N خط لاگ (بدون مدت زمان)"
    )
    
    parser.add_argument(
        "--no-save",
        action="store_true",
        help="ذخیره نکردن لاگ در فایل"
    )
    
    parser.add_argument(
        "--package",
        default=PACKAGE_NAME,
        help=f"Package name برنامه (پیش‌فرض: {PACKAGE_NAME})"
    )
    
    args = parser.parse_args()
    
    # اگر recent mode
    if args.recent:
        get_recent_logs(args.recent)
    else:
        # گرفتن لاگ به مدت زمان مشخص
        capture_logcat(
            duration=args.duration,
            save_file=not args.no_save,
            filters=LOG_FILTERS
        )

if __name__ == "__main__":
    main()

