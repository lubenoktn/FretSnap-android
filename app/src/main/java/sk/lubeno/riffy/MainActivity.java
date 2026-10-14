package sk.lubeno.riffy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Nahrávanie riffov z USB zvukovej karty (ToneX Plug).
 *
 * Prehliadač na Androide si vstup vybrať nevie, Android mu dá ten, ktorý uzná za vhodný
 * (na Xiaomi často vstavaný mikrofón). Tu si aplikácia USB vstup vyžiada sama
 * (setPreferredDevice) a po spustení overí, kam ju Android naozaj pripojil.
 */
public class MainActivity extends Activity {
    static final String FOLDER = Environment.DIRECTORY_MUSIC + "/Riffy/";
    static final int ASK_PERMISSIONS = 1, ASK_DELETE = 2;

    final Handler ui = new Handler(Looper.getMainLooper());
    AudioManager audio;
    Spinner inputs;
    TextView info, status, levelText;
    Button rec, test;
    Spinner sourceSpin, formatSpin, channelSpin;
    SharedPreferences prefs;
    ProgressBar level;
    ListView list;

    final List<AudioDeviceInfo> devices = new ArrayList<>();
    ArrayAdapter<String> inputNames;
    final List<Riff> riffs = new ArrayList<>();
    ArrayAdapter<Riff> riffList;

    volatile boolean recording, testing, monitoring;
    Thread monitor;
    boolean resumed;
    Thread worker;
    long started;
    MediaPlayer player;
    long playing = -1;

    static class Riff {
        long id;
        String name;
        long duration, added;

        Uri uri() {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int pad = Math.round(16 * getResources().getDisplayMetrics().density);
            v.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        audio = getSystemService(AudioManager.class);
        inputs = findViewById(R.id.inputs);
        info = findViewById(R.id.info);
        status = findViewById(R.id.status);
        rec = findViewById(R.id.rec);
        level = findViewById(R.id.level);
        levelText = findViewById(R.id.levelText);
        test = findViewById(R.id.test);
        prefs = getSharedPreferences("nastavenia", MODE_PRIVATE);
        sourceSpin = choice(R.id.source, SOURCE_NAMES, "source");
        formatSpin = choice(R.id.format, FORMAT_NAMES, "format");
        channelSpin = choice(R.id.channels, CHANNEL_NAMES, "channels");
        test.setOnClickListener(v -> findSetup());
        list = findViewById(R.id.list);

        inputNames = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        inputNames.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inputs.setAdapter(inputNames);
        inputs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { showInfo(); startMonitor(); }
            public void onNothingSelected(AdapterView<?> p) { showInfo(); }
        });
        audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshInputs(); }
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { refreshInputs(); }
        }, ui);

        riffList = new ArrayAdapter<Riff>(this, android.R.layout.simple_list_item_2, android.R.id.text1, riffs) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                View v = super.getView(pos, convert, parent);
                Riff r = getItem(pos);
                ((TextView) v.findViewById(android.R.id.text1)).setText((r.id == playing ? "▶ " : "") + r.name);
                String when = DateFormat.format("d. M. yyyy  HH:mm", new Date(r.added * 1000)).toString();
                ((TextView) v.findViewById(android.R.id.text2)).setText(when + "   " + clock(r.duration));
                return v;
            }
        };
        list.setAdapter(riffList);
        list.setOnItemClickListener((p, v, pos, id) -> play(riffs.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { options(riffs.get(pos)); return true; });

        rec.setOnClickListener(v -> { if (recording) stop(); else start(); });

        refreshInputs();
        askPermissions();
        loadRiffs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startMonitor();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopMonitor();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        recording = false;
        stopPlaying();
        super.onDestroy();
    }

    void askPermissions() {
        List<String> missing = new ArrayList<>();
        missing.add(Manifest.permission.RECORD_AUDIO);
        missing.add(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
        missing.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), ASK_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshInputs();
        startMonitor();
        loadRiffs();
    }

    // ---- Vstupy ----

    static boolean isUsb(AudioDeviceInfo d) {
        return d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                || d.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    static String typeName(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "vstavaný mikrofón";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "slúchadlá s mikrofónom";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth";
            default: return "typ " + d.getType();
        }
    }

    static String deviceName(AudioDeviceInfo d) {
        String product = d.getProductName() == null ? "" : d.getProductName().toString().trim();
        String type = typeName(d);
        return product.isEmpty() || d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? capitalize(type) : product + " (" + type + ")";
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    void refreshInputs() {
        AudioDeviceInfo before = selected();
        devices.clear();
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_TELEPHONY || t == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    || t == AudioDeviceInfo.TYPE_FM_TUNER) continue;
            // Vstavaných mikrofónov býva viac (predný, zadný); stačí jeden.
            if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC && devices.stream().anyMatch(x -> x.getType() == t)) continue;
            devices.add(d);
        }
        devices.sort((a, b) -> Boolean.compare(isUsb(b), isUsb(a)));
        inputNames.clear();
        for (AudioDeviceInfo d : devices) inputNames.add(deviceName(d));
        inputNames.notifyDataSetChanged();

        int pick = 0;
        for (int i = 0; i < devices.size(); i++) {
            if (before != null && devices.get(i).getId() == before.getId()) pick = i;
        }
        if (!devices.isEmpty()) inputs.setSelection(pick);
        showInfo();
    }

    AudioDeviceInfo selected() {
        int i = inputs == null ? -1 : inputs.getSelectedItemPosition();
        return i >= 0 && i < devices.size() ? devices.get(i) : null;
    }

    void showInfo() {
        AudioDeviceInfo d = selected();
        boolean anyUsb = devices.stream().anyMatch(MainActivity::isUsb);
        if (d == null) {
            info.setText("Telefón nehlási žiadny vstup.");
        } else {
            info.setText((anyUsb ? "" : "USB zariadenie Android nevidí. Skontrolujte kábel a OTG.\n")
                    + "Kanály: " + join(d.getChannelCounts()) + "   Vzorkovanie: " + join(d.getSampleRates()) + " Hz");
        }
    }

    static String join(int[] values) {
        if (values.length == 0) return "ľubovoľné";
        StringBuilder s = new StringBuilder();
        for (int v : values) s.append(s.length() > 0 ? ", " : "").append(v);
        return s.toString();
    }

    static boolean has(int[] values, int v) {
        for (int x : values) if (x == v) return true;
        return false;
    }

    // ---- Nastavenia nahrávania ----

    static final int AUTO = -1;
    static final int[] SOURCE_IDS = {AUTO, MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER};
    static final String[] SOURCE_NAMES = {"automaticky", "bez úprav", "mikrofón", "predvolený", "hudobný výkon",
            "rozpoznávanie reči", "kamera"};
    static final int[] FORMAT_IDS = {AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT};
    static final String[] FORMAT_NAMES = {"16 bit", "float"};
    static final int[] CHANNEL_IDS = {AUTO, 1, 2};
    static final String[] CHANNEL_NAMES = {"auto", "mono", "stereo"};

    Spinner choice(int id, String[] names, String key) {
        Spinner s = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(prefs.getInt(key, 0), names.length - 1)));
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(key, pos).apply();
                startMonitor();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        return s;
    }

    static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    static String sourceName(int s) {
        return SOURCE_NAMES[indexOf(SOURCE_IDS, s)];
    }

    static String formatName(int e) {
        return FORMAT_NAMES[indexOf(FORMAT_IDS, e)];
    }

    static int rateFor(AudioDeviceInfo dev) {
        int[] rates = dev == null ? new int[0] : dev.getSampleRates();
        return rates.length == 0 || has(rates, 48000) ? 48000 : has(rates, 44100) ? 44100 : rates[0];
    }

    static int channelsFor(AudioDeviceInfo dev, int choice) {
        if (choice != AUTO) return choice;
        return dev != null && has(dev.getChannelCounts(), 2) ? 2 : 1;
    }

    void setBusy(boolean busy) {
        inputs.setEnabled(!busy);
        sourceSpin.setEnabled(!busy);
        formatSpin.setEnabled(!busy);
        channelSpin.setEnabled(!busy);
        test.setEnabled(!busy);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Spustené nahrávanie s konkrétnym nastavením. */
    static class Capture {
        AudioRecord ar;
        AudioDeviceInfo routed;
        int source, encoding, channels, rate;
        float[] floats;

        /** Prečíta blok ako 16-bitové vzorky; vráti ich počet. */
        int read(short[] out) {
            if (encoding != AudioFormat.ENCODING_PCM_FLOAT) return ar.read(out, 0, out.length);
            if (floats == null || floats.length < out.length) floats = new float[out.length];
            int n = ar.read(floats, 0, out.length, AudioRecord.READ_BLOCKING);
            for (int i = 0; i < n; i++) out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(floats[i] * 32767)));
            return n;
        }

        void close() {
            try { ar.stop(); } catch (Exception ignored) { }
            ar.release();
        }
    }

    @SuppressWarnings("MissingPermission")
    static Capture open(AudioDeviceInfo dev, int source, int encoding, int channels, int rate) {
        int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int min = AudioRecord.getMinBufferSize(rate, mask, encoding);
        if (min <= 0) return null;
        int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
        AudioRecord ar = null;
        try {
            ar = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(encoding).build())
                    .setBufferSizeInBytes(Math.max(min, rate / 10 * channels * bytes) * 2).build();
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
            if (dev != null) ar.setPreferredDevice(dev);
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
            Capture c = new Capture();
            c.ar = ar;
            c.routed = ar.getRoutedDevice();
            c.source = source;
            c.encoding = encoding;
            c.channels = channels;
            c.rate = rate;
            return c;
        } catch (Exception e) {
            if (ar != null) ar.release();
            return null;
        }
    }

    static boolean routedTo(Capture c, AudioDeviceInfo dev) {
        return dev == null || (c.routed != null && c.routed.getId() == dev.getId());
    }

    /** Pri automatickom zdroji skúša zdroje po rade, kým Android nepripojí naozaj zvolený vstup. */
    Capture openChosen(AudioDeviceInfo dev, int source, int encoding, int channels, int rate) {
        boolean raw = "true".equals(audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = source != AUTO ? new int[]{source}
                : raw ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER}
                : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER};
        Capture fallback = null;
        for (int s : sources) {
            Capture c = open(dev, s, encoding, channels, rate);
            if (c == null) continue;
            if (routedTo(c, dev)) {
                if (fallback != null) fallback.close();
                return c;
            }
            if (fallback == null) fallback = c;
            else c.close();
        }
        return fallback;
    }

    static int[] peaks(short[] buf, int n, int channels, int[] into) {
        for (int i = 0; i < n; i++) {
            int v = Math.abs((int) buf[i]);
            if (v > into[i % channels]) into[i % channels] = v;
        }
        return into;
    }

    static String db(int peak) {
        return peak == 0 ? "ticho" : String.format(Locale.ROOT, "%.0f dB", 20 * Math.log10(peak / 32768.0));
    }

    static String levels(int[] p) {
        return p.length == 2 ? "ľavý " + db(p[0]) + ", pravý " + db(p[1]) : db(p[0]);
    }

    void showLevel(int[] peak) {
        int max = 0;
        for (int p : peak) max = Math.max(max, p);
        double d = max == 0 ? -60 : 20 * Math.log10(max / 32768.0);
        int pct = (int) Math.max(0, Math.min(100, (d + 60) / 60 * 100));
        String text = "Úroveň: " + levels(peak);
        ui.post(() -> { level.setProgress(pct); levelText.setText(text); });
    }

    static String describe(Capture c, AudioDeviceInfo dev) {
        return (c.routed == null ? "neznámy vstup" : deviceName(c.routed))
                + ", zdroj „" + sourceName(c.source) + "“, " + formatName(c.encoding) + ", " + c.rate + " Hz, "
                + (c.channels == 2 ? "stereo" : "mono")
                + (routedTo(c, dev) ? "" : "\nPozor: Android nepustil zvolený vstup.");
    }

    // ---- Počúvanie: ukazuje, odkiaľ ide signál, aj keď sa nenahráva ----

    void startMonitor() {
        if (!resumed || recording || sourceSpin == null
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        stopMonitor();
        AudioDeviceInfo dev = selected();
        int source = SOURCE_IDS[sourceSpin.getSelectedItemPosition()];
        int encoding = FORMAT_IDS[formatSpin.getSelectedItemPosition()];
        int channels = channelsFor(dev, CHANNEL_IDS[channelSpin.getSelectedItemPosition()]);
        int rate = rateFor(dev);
        monitoring = true;
        monitor = new Thread(() -> {
            Capture c = openChosen(dev, source, encoding, channels, rate);
            if (c == null) {
                ui.post(() -> status.setText("S týmto nastavením sa vstup nedá otvoriť."));
                return;
            }
            String where = "Signál ide z: " + describe(c, dev);
            ui.post(() -> status.setText(where));
            short[] buf = new short[rate / 20 * channels];
            int[] peak = new int[channels];
            long last = 0;
            while (monitoring) {
                int n = c.read(buf);
                if (n <= 0) break;
                peaks(buf, n, channels, peak);
                long now = System.currentTimeMillis();
                if (now - last > 80) {
                    last = now;
                    if (monitoring) showLevel(peak);
                    peak = new int[channels];
                }
            }
            c.close();
        }, "pocuvanie");
        monitor.start();
    }

    void stopMonitor() {
        monitoring = false;
        Thread t = monitor;
        monitor = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        level.setProgress(0);
        levelText.setText("");
    }

    // ---- Nahrávanie ----

    void start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            return;
        }
        stopPlaying();
        stopMonitor();
        AudioDeviceInfo dev = selected();
        int source = SOURCE_IDS[sourceSpin.getSelectedItemPosition()];
        int encoding = FORMAT_IDS[formatSpin.getSelectedItemPosition()];
        int channels = channelsFor(dev, CHANNEL_IDS[channelSpin.getSelectedItemPosition()]);
        int rate = rateFor(dev);

        recording = true;
        started = System.currentTimeMillis();
        rec.setText("■ Zastaviť");
        setBusy(true);
        tick();

        worker = new Thread(() -> record(dev, source, encoding, channels, rate), "nahravanie");
        worker.start();
    }

    void stop() {
        recording = false;
        rec.setEnabled(false);
    }

    void tick() {
        if (!recording || testing) return;
        rec.setText("■ Zastaviť   " + clock(System.currentTimeMillis() - started));
        ui.postDelayed(this::tick, 250);
    }

    void record(AudioDeviceInfo dev, int source, int encoding, int channels, int rate) {
        File temp = new File(getCacheDir(), "nahravka.wav");
        Capture c = null;
        long bytes = 0;

        try {
            c = openChosen(dev, source, encoding, channels, rate);
            if (c == null) throw new IOException("S týmto nastavením sa nahrávanie nedá spustiť.");
            final String shown = "Nahráva z: " + describe(c, dev);
            ui.post(() -> status.setText(shown));

            short[] buf = new short[rate / 20 * channels];
            byte[] out16 = new byte[buf.length * 2];
            int[] peak = new int[channels];
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(new byte[44]);
                long lastLevel = 0;
                while (recording) {
                    int n = c.read(buf);
                    if (n <= 0) continue;
                    for (int i = 0; i < n; i++) {
                        out16[2 * i] = (byte) buf[i];
                        out16[2 * i + 1] = (byte) (buf[i] >> 8);
                    }
                    out.write(out16, 0, n * 2);
                    bytes += n * 2L;
                    peaks(buf, n, channels, peak);
                    long now = System.currentTimeMillis();
                    if (now - lastLevel > 80) {
                        lastLevel = now;
                        showLevel(peak);
                        peak = new int[channels];
                    }
                }
            }
            c.close();
            c = null;
            writeHeader(temp, rate, channels, bytes);
            String name = "Riff " + DateFormat.format("yyyy-MM-dd HH.mm.ss", new Date(started));
            save(temp, name);
            ui.post(() -> Toast.makeText(this, "Uložené: " + name, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            String msg = e.getMessage();
            ui.post(() -> status.setText("Chyba: " + msg));
        } finally {
            if (c != null) c.close();
            temp.delete();
            recording = false;
            ui.post(() -> {
                rec.setEnabled(true);
                rec.setText("● Nahrávať");
                level.setProgress(0);
                levelText.setText("");
                setBusy(false);
                loadRiffs();
                startMonitor();
            });
        }
    }

    // ---- Skúška vstupu ----

    static class Result {
        int source, encoding, channels;
        boolean opened, routed;
        String device = "";
        int[] peak = new int[0];

        int max() {
            int m = -1;
            for (int p : peak) m = Math.max(m, p);
            return m;
        }

        String line() {
            String how = sourceName(source) + ", " + formatName(encoding) + ", " + (channels == 2 ? "stereo" : "mono");
            if (!opened) return "nespustilo sa — " + how;
            return levels(peak) + " — " + how + (routed ? "" : " (nahrávalo z: " + device + ")");
        }
    }

    void findSetup() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Skúška vstupu")
                .setMessage("Aplikácia vyskúša všetky nastavenia, každé asi na sekundu a pol. "
                        + "Celý čas, asi 45 sekúnd, hrajte na gitare (napríklad držte akord a opakovane brnkajte).")
                .setPositiveButton("Začať", (d, w) -> runTest())
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    void runTest() {
        stopPlaying();
        stopMonitor();
        AudioDeviceInfo dev = selected();
        int rate = rateFor(dev);
        recording = true;
        testing = true;
        rec.setText("■ Zastaviť skúšku");
        setBusy(true);
        worker = new Thread(() -> {
            List<Result> results = new ArrayList<>();
            int total = (SOURCE_IDS.length - 1) * FORMAT_IDS.length * 2, done = 0;
            for (int s = 1; s < SOURCE_IDS.length && recording; s++) {
                for (int e = 0; e < FORMAT_IDS.length && recording; e++) {
                    for (int ch = 1; ch <= 2 && recording; ch++) {
                        Result r = new Result();
                        r.source = SOURCE_IDS[s];
                        r.encoding = FORMAT_IDS[e];
                        r.channels = ch;
                        String step = "Skúšam " + (++done) + " z " + total + ": " + sourceName(r.source) + ", "
                                + formatName(r.encoding) + ", " + (ch == 2 ? "stereo" : "mono");
                        ui.post(() -> status.setText(step));
                        Capture c = open(dev, r.source, r.encoding, ch, rate);
                        if (c != null) {
                            r.opened = true;
                            r.routed = routedTo(c, dev);
                            r.device = c.routed == null ? "neznámy vstup" : deviceName(c.routed);
                            r.peak = new int[ch];
                            short[] buf = new short[rate / 20 * ch];
                            long begin = System.currentTimeMillis();
                            while (recording && System.currentTimeMillis() - begin < 1500) {
                                int n = c.read(buf);
                                if (n <= 0) break;
                                // Prvé chvíle po spustení bývajú tiché, nepočítajú sa.
                                if (System.currentTimeMillis() - begin > 300) peaks(buf, n, ch, r.peak);
                            }
                            c.close();
                            String lvl = "Úroveň: " + levels(r.peak);
                            ui.post(() -> levelText.setText(lvl));
                        }
                        results.add(r);
                    }
                }
            }
            ui.post(() -> {
                recording = false;
                testing = false;
                rec.setEnabled(true);
                rec.setText("● Nahrávať");
                levelText.setText("");
                status.setText("");
                setBusy(false);
                startMonitor();
                showResults(results, dev);
            });
        }, "skuska");
        worker.start();
    }

    void showResults(List<Result> results, AudioDeviceInfo dev) {
        results.sort((a, b) -> Integer.compare(
                b.routed ? b.max() : -2, a.routed ? a.max() : -2));
        StringBuilder text = new StringBuilder();
        text.append("Vstup: ").append(dev == null ? "neznámy" : deviceName(dev)).append("\n\n");
        for (Result r : results) text.append(r.line()).append("\n");
        Result best = results.isEmpty() ? null : results.get(0);
        // Pod -60 dB je to len šum, nie gitara.
        boolean works = best != null && best.routed && best.max() > 32;
        String head = works ? "Najlepšie funguje: " + sourceName(best.source) + ", " + formatName(best.encoding) + ", "
                + (best.channels == 2 ? "stereo" : "mono") + ".\n\n"
                : "Zo žiadneho nastavenia neprišiel zo zvoleného vstupu použiteľný zvuk (hlasnejší než -60 dB).\n\n";

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Výsledok skúšky")
                .setMessage(head + text)
                .setNeutralButton("Poslať výsledok", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Riffy – výsledok skúšky vstupu");
                    send.putExtra(Intent.EXTRA_TEXT, "Telefón: " + Build.MANUFACTURER + " " + Build.MODEL
                            + ", Android " + Build.VERSION.RELEASE + "\n" + head + text);
                    startActivity(Intent.createChooser(send, "Poslať výsledok"));
                })
                .setNegativeButton("Zavrieť", null);
        if (works) {
            dialog.setPositiveButton("Použiť", (d, w) -> {
                sourceSpin.setSelection(indexOf(SOURCE_IDS, best.source));
                formatSpin.setSelection(indexOf(FORMAT_IDS, best.encoding));
                channelSpin.setSelection(indexOf(CHANNEL_IDS, best.channels));
                startMonitor();
            });
        }
        dialog.show();
    }

    static void writeHeader(File f, int rate, int channels, long data) throws IOException {
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            byte[] h = new byte[44];
            put(h, 0, "RIFF");
            int32(h, 4, 36 + data);
            put(h, 8, "WAVEfmt ");
            int32(h, 16, 16);
            h[20] = 1;
            h[22] = (byte) channels;
            int32(h, 24, rate);
            int32(h, 28, (long) rate * channels * 2);
            h[32] = (byte) (channels * 2);
            h[34] = 16;
            put(h, 36, "data");
            int32(h, 40, data);
            w.seek(0);
            w.write(h);
        }
    }

    static void put(byte[] h, int at, String s) {
        for (int i = 0; i < s.length(); i++) h[at + i] = (byte) s.charAt(i);
    }

    static void int32(byte[] h, int at, long v) {
        for (int i = 0; i < 4; i++) h[at + i] = (byte) (v >> (8 * i));
    }

    /** Uloží nahrávku do Hudba/Riffy, kde ju vidia aj iné aplikácie. */
    void save(File temp, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".wav");
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Nahrávku sa nepodarilo uložiť.");
        try (InputStream in = new FileInputStream(temp); OutputStream out = cr.openOutputStream(uri)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
    }

    // ---- Zoznam nahrávok ----

    void loadRiffs() {
        riffs.clear();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED};
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Audio.Media.RELATIVE_PATH + "=?", new String[]{FOLDER},
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                Riff r = new Riff();
                r.id = c.getLong(0);
                r.name = c.getString(1).replaceFirst("\\.wav$", "");
                r.duration = c.getLong(2);
                if (r.duration <= 0) r.duration = wavLength(r.uri());
                r.added = c.getLong(3);
                riffs.add(r);
            }
        } catch (Exception ignored) {
        }
        riffList.notifyDataSetChanged();
    }

    /** Dĺžka v ms podľa hlavičky WAV a veľkosti súboru. */
    long wavLength(Uri uri) {
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
             InputStream in = new FileInputStream(fd.getFileDescriptor())) {
            byte[] h = new byte[44];
            if (in.read(h) < 44) return 0;
            long byteRate = (h[28] & 0xff) | (h[29] & 0xff) << 8 | (h[30] & 0xff) << 16 | (long) (h[31] & 0xff) << 24;
            return byteRate == 0 ? 0 : (fd.getStatSize() - 44) * 1000 / byteRate;
        } catch (Exception e) {
            return 0;
        }
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    void play(Riff r) {
        boolean same = r.id == playing;
        stopPlaying();
        if (same || recording) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(this, r.uri());
            player.setOnCompletionListener(p -> stopPlaying());
            player.prepare();
            player.start();
            playing = r.id;
        } catch (Exception e) {
            stopPlaying();
            Toast.makeText(this, "Nahrávka sa nedá prehrať.", Toast.LENGTH_SHORT).show();
        }
        riffList.notifyDataSetChanged();
    }

    void stopPlaying() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        playing = -1;
        if (riffList != null) riffList.notifyDataSetChanged();
    }

    void options(Riff r) {
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setItems(new String[]{"Premenovať", "Poslať / zdieľať", "Zmazať"}, (d, which) -> {
                    if (which == 0) rename(r);
                    else if (which == 1) share(r);
                    else delete(r);
                })
                .show();
    }

    void rename(Riff r) {
        EditText field = new EditText(this);
        field.setText(r.name);
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Premenovať")
                .setView(field)
                .setPositiveButton("Uložiť", (d, w) -> {
                    String name = field.getText().toString().trim().replaceAll("[/\\\\]", "-");
                    if (name.isEmpty()) return;
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".wav");
                    try {
                        getContentResolver().update(r.uri(), v, null, null);
                    } catch (Exception e) {
                        Toast.makeText(this, "Túto nahrávku sa nedá premenovať.", Toast.LENGTH_SHORT).show();
                    }
                    loadRiffs();
                })
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    void share(Riff r) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/wav");
        send.putExtra(Intent.EXTRA_STREAM, r.uri());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, r.name));
    }

    void delete(Riff r) {
        new AlertDialog.Builder(this)
                .setTitle("Zmazať „" + r.name + "“?")
                .setPositiveButton("Zmazať", (d, w) -> {
                    if (r.id == playing) stopPlaying();
                    try {
                        getContentResolver().delete(r.uri(), null, null);
                        loadRiffs();
                    } catch (SecurityException e) {
                        // Nahrávka z predošlej inštalácie: Android si vypýta súhlas sám.
                        try {
                            if (Build.VERSION.SDK_INT >= 30) {
                                startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(),
                                        List.of(r.uri())).getIntentSender(), ASK_DELETE, null, 0, 0, 0);
                            } else if (e instanceof RecoverableSecurityException) {
                                startIntentSenderForResult(((RecoverableSecurityException) e).getUserAction()
                                        .getActionIntent().getIntentSender(), ASK_DELETE, null, 0, 0, 0);
                            }
                        } catch (Exception ignored) {
                            Toast.makeText(this, "Nahrávka sa nedá zmazať.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == ASK_DELETE) loadRiffs();
    }
}
