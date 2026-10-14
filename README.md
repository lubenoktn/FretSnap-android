# Riffy

Android aplikácia na nahrávanie riffov z USB zvukovej karty (ToneX Plug). Vstup si vyžiada
priamo (Android ho inak môže presmerovať na vstavaný mikrofón) a po spustení ukáže, z čoho
naozaj nahráva. Nahrávky ukladá ako WAV do Hudba/Riffy.

Zostavenie (Mac, Homebrew: openjdk@17, android-commandlinetools):

    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    ./gradlew assembleDebug --project-cache-dir .gradle.nosync
    cp app/build.nosync/outputs/apk/debug/app-debug.apk Riffy.apk

Výstupy zostavenia idú do priečinkov `*.nosync`, aby ich iCloud nesynchronizoval.
