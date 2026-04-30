# doLoveTime (Android Local-Only MVP)

## Features
- Local-only storage (Room)
- PIN lock on app start
- Partner management
- Event records: partner/single mode, location, method, duration, note
- Event stats: day/week/month frequency, total duration, top locations
- Privacy: auto-lock timer + secure window (hide recents/disable screenshots)

## No Android Studio: build and install

### Double-click build (easiest)
- Run [`build-and-open-apk.bat`](D:\work\学习项目\doLoveTime\build-and-open-apk.bat)
- It builds debug APK and opens the output folder automatically.

### 1) Generate Gradle wrapper (first time only)
```powershell
.\scripts\bootstrap-wrapper.ps1
```

### 2) Build APK
```powershell
.\scripts\build-apk.ps1
```

### 3) Install on phone (USB debugging enabled)
```powershell
.\scripts\install-apk.ps1
```

## If you do not want local SDK setup
- Push this repo to GitHub.
- Run GitHub Action: `build-android-apk`.
- Download artifact `doLoveTime-debug-apk`.
- Copy to phone and install.

## Notes
- This MVP stores data on device only and does not connect to cloud services.
- PIN is stored as SHA-256 hash in DataStore.
