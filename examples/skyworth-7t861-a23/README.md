# Skyworth 7T861_A23 (Coocaa OS, Android 10)

Tested on Skyworth 7T861_A23 (MediaTek MT9638 / mt5872, Android 10, build `eng.nobody.20241225.120706`).

## Safety Warning

**Do not disable `com.skyworth.officehomepage`.**
Disabling the stock home app on this firmware causes the TV to hang indefinitely on the SKYWORTH boot logo upon restart. The system will fail to start the network stack, disabling port 5555. Recovery requires entering Recovery Mode and performing a factory reset ("Wipe data / factory reset").

## Installing CleanYourTV Launcher safely

On this Coocaa OS build, Home is managed by the system property `persist.service.homepage.pkg` and `persist.service.model.pkg`, rather than Android's standard Home intent chooser. In addition, Coocaa OS terminates background apps whose package name does not begin with manufacturer prefixes (`coocaa`, `skyworth`, `tianci`, `android`).

1. **Build Launcher with Coocaa package ID**:
   ```bash
   cd launcher
   ./gradlew assembleRelease -PlauncherApplicationId=com.coocaa.tvhome
   ```
2. **Install the launcher and coocaa-home-shim**:
   ```bash
   adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/tvhome.apk
   adb shell pm install -r /data/local/tmp/tvhome.apk

   # Install coocaa-home-shim (com.ccos.tv.homepage)
   adb push coocaa-home-shim.apk /data/local/tmp/shim.apk
   adb shell pm install -r /data/local/tmp/shim.apk
   ```
3. **Point Home properties to the shim**:
   ```bash
   adb shell setprop persist.service.homepage.pkg com.ccos.tv.homepage
   adb shell setprop persist.service.homepage.cls com.ccos.tv.homepage.LaunchActivity
   adb shell setprop persist.service.model.pkg com.ccos.tv.homepage
   adb shell setprop persist.service.model.cls com.ccos.tv.homepage.LaunchActivity
   ```
4. **Apply debloat list**:
   ```bash
   TV_HOST=<tv-ip> tools/disable.sh examples/skyworth-7t861-a23/debloat.txt
   ```
