# APK banayein bina Android Studio ke

Android Studio + SDK + emulator milakar 8-10 GB leta hai. Niche ka tareeka
laptop par **kuch bhi install kiye bina** APK bana deta hai.

---

## Tareeka 1 — GitHub Actions (recommended)

GitHub ke servers par build hoti hai. Free hai (public repo par unlimited,
private par bhi kaafi minutes free milte hain).

### Steps

1. **github.com** par jaayein → upar right mein **+** → **New repository**
   - Name: `PocketPDF` (ya jo chahein)
   - Private ya Public, dono chalega
   - **Create repository** dabayein

2. Nayi repo ke page par **"uploading an existing file"** link par click karein.

3. Zip ko laptop par extract karein, aur **andar ki saari files aur folders**
   browser window mein drag kar dein.

   > Zaroori: `.github` folder bhi upload hona chahiye — usi mein build
   > instructions hain. Agar woh na dikhe, Windows mein hidden files on karein
   > (Explorer → View → Hidden items).

4. Neeche **Commit changes** dabayein.

5. Upar **Actions** tab kholein. Ek build apne aap chalu ho chuki hogi.
   Pehli baar 5-8 minute lagte hain.

6. Build complete hone par (green tick) us run par click karein → neeche
   **Artifacts** section mein **app-debug-apk** dikhega → download karein.

7. Download ek zip hogi; usme `app-debug.apk` hai. Use phone mein bhejein
   (WhatsApp apne aap ko, ya USB), tap karein, **"Unknown sources"** allow
   karein, install.

### Aage se

Koi bhi file GitHub par edit karne par build dobara apne aap chalegi.
Ya **Actions → Build APK → Run workflow** se manually chala sakte hain.

---

## Tareeka 2 — sirf command line tools (~2-3 GB)

Agar laptop par hi build karni ho lekin Studio nahi chahiye:

1. `developer.android.com/studio` → page ke **sabse neeche**
   "Command line tools only" section → Windows wala zip.
2. Extract karein, maan lijiye `C:\android\cmdline-tools\latest\`
3. JDK 17 install karein (adoptium.net se Temurin 17).
4. Command prompt mein:

```
set ANDROID_HOME=C:\android
%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager --licenses
%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

5. Project folder mein jaakar: `gradle assembleDebug`
   (Gradle alag se chahiye — gradle.org se download karke PATH mein daalein.)

APK: `app\build\outputs\apk\debug\app-debug.apk`

---

## Tareeka 3 — online IDE

**GitHub Codespaces** (github.com par repo kholkar Code → Codespaces) ek poora
Linux environment browser mein deta hai. Free tier mein har mahine kuch ghante
milte hain. Wahan terminal mein Tareeka 2 wale commands chal jayenge.

Laptop par kuch install nahi hota, lekin Tareeka 1 se zyada mehnat hai —
isliye seedha GitHub Actions hi behtar hai.

---

## Jo kaam nahi karega

- **AIDE** jaisi "phone par app banao" apps — ye modern Gradle aur AndroidX
  projects handle nahi kar paatin. Time barbaad hoga.
- **Termux** par build — technically possible hai, lekin setup Studio se bhi
  zyada jhanjhat wala hai.
