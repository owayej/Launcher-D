# iOS-style Android Launcher (home screen only)

Real features: installed-app grid, pages (swipe, spring), dock (4), drag to reorder,
hold-over-an-app to group into a folder, folder open/rename/remove-from-folder,
long-press menu (App info, Uninstall, Add/Remove dock), long-press empty space = wallpaper picker,
layout saved across restarts, picks up newly installed / removed apps, set as default Home app.

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo, upload ALL files in this folder (keep the .github folder).
2. Open the repo -> Actions tab -> "Build APK" -> wait ~4 minutes.
3. Open the finished run -> Artifacts -> download "launcher-apk" -> unzip -> app-debug.apk.
4. Copy to the phone, install (allow "install unknown apps"), press Home, choose this launcher, "Always".

## Or with Android Studio
Open this folder -> wait for Gradle sync -> Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
