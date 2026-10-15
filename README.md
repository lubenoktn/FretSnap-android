# Riffy

Android aplikácia na nahrávanie riffov z USB zvukovej karty (ToneX Plug). ToneX číta priamo
cez USB vlastným ovládačom (zvukový systém Androidu ho na niektorých telefónoch nepustí).
Nahrávky ukladá ako 24-bitové WAV do Hudba/Riffy.

Stiahnuť poslednú verziu: https://github.com/lubenoktn/Riffy-android/releases/latest/download/Riffy.apk
Aplikácia si nové vydania (GitHub Releases) kontroluje sama raz denne.

Nové vydanie: zvýšiť `versionCode` a `versionName` v `app/build.gradle`, zostaviť a

    gh release create v<verzia> Riffy.apk --title "Riffy <verzia>" --notes "…"

APK musí byť podpísané tým istým kľúčom (ladiaci kľúč tohto Macu, `~/.android/debug.keystore`),
inak sa nenainštaluje cez starú verziu.

Zostavenie (Mac, Homebrew: openjdk@17, android-commandlinetools):

    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    ./gradlew assembleDebug --project-cache-dir .gradle.nosync
    cp app/build.nosync/outputs/apk/debug/app-debug.apk Riffy.apk

Výstupy zostavenia idú do priečinkov `*.nosync`, aby ich iCloud nesynchronizoval.
