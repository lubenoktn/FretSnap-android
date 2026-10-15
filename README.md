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
    ./gradlew assembleRelease --project-cache-dir ~/.cache/riffy-gradle
    cp ~/.cache/riffy-build/app/outputs/apk/release/app-release.apk Riffy.apk

Výstupy zostavenia idú do `~/.cache/riffy-build`, mimo plochy synchronizovanej cez iCloud
(ten v nich vytváral kópie súborov „(1)“, ktoré pokazili zostavenie).

Zvuky bicích (`app/src/main/res/raw/d_*.pcm`) sú zo súpravy
[Virtuosity Drums](https://github.com/sfzinstruments/virtuosity_drums) od Versilian Studios
(licencia CC0): pre každý nástroj zmiešané mikrofóny kopáka, rytmičáka a nad súpravou,
mono 48 kHz 16 bit.

Návod pre používateľa je v aplikácii (? na hlavnej obrazovke, alebo Nastavenia → Návod);
jeho text je v `app/src/main/assets/navod.html`. Pri zmene funkcií ho treba upraviť.

Sprievod: basa `res/raw/b*.pcm` je zo [Growlybass](https://github.com/sfzinstruments/karoryfer.growlybass)
(Karoryfer Samples, CC0), klavír `res/raw/p*.pcm` z [VCSL](https://github.com/sgossner/VCSL) – Kawai grand
(Versilian Studios, CC0). Číslo v názve je MIDI tón; ostatné tóny sa dopočítajú posunutím výšky.
