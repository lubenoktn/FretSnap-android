package sk.lubeno.riffy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
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
    TextView info, status;
    Button rec;
    ProgressBar level;
    ListView list;

    final List<AudioDeviceInfo> devices = new ArrayList<>();
    ArrayAdapter<String> inputNames;
    final List<Riff> riffs = new ArrayList<>();
    ArrayAdapter<Riff> riffList;

    volatile boolean recording;
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
        list = findViewById(R.id.list);

        inputNames = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        inputNames.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inputs.setAdapter(inputNames);
        inputs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { showInfo(); }
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

    // ---- Nahrávanie ----

    void start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            return;
        }
        stopPlaying();
        AudioDeviceInfo dev = selected();
        int[] rates = dev == null ? new int[0] : dev.getSampleRates();
        int rate = rates.length == 0 || has(rates, 48000) ? 48000 : has(rates, 44100) ? 44100 : rates[0];
        int channels = dev != null && has(dev.getChannelCounts(), 2) ? 2 : 1;

        recording = true;
        started = System.currentTimeMillis();
        rec.setText("■ Zastaviť");
        inputs.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tick();

        worker = new Thread(() -> record(dev, rate, channels), "nahravanie");
        worker.start();
    }

    void stop() {
        recording = false;
        rec.setEnabled(false);
    }

    void tick() {
        if (!recording) return;
        rec.setText("■ Zastaviť   " + clock(System.currentTimeMillis() - started));
        ui.postDelayed(this::tick, 250);
    }

    /** Zdroje zvuku od najčistejšieho. Niektoré telefóny pri niektorých z nich USB ignorujú. */
    int[] sources() {
        boolean raw = "true".equals(audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        return raw ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER}
                : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER};
    }

    static String sourceName(int s) {
        switch (s) {
            case MediaRecorder.AudioSource.UNPROCESSED: return "bez úprav";
            case MediaRecorder.AudioSource.MIC: return "mikrofón";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "rozpoznávanie reči";
            case MediaRecorder.AudioSource.CAMCORDER: return "kamera";
            default: return String.valueOf(s);
        }
    }

    @SuppressWarnings("MissingPermission")
    void record(AudioDeviceInfo dev, int rate, int channels) {
        int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int size = Math.max(AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT), rate / 10 * channels * 2) * 2;
        File temp = new File(getCacheDir(), "nahravka.wav");
        AudioRecord ar = null;
        String note = "";
        long bytes = 0;

        try {
            // Skúsi zdroje po rade, kým Android nepripojí naozaj zvolený vstup.
            int[] sources = sources();
            for (int i = 0; i < sources.length; i++) {
                AudioRecord tryAr;
                try {
                    tryAr = new AudioRecord.Builder()
                            .setAudioSource(sources[i])
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(size).build();
                } catch (Exception e) {
                    continue;
                }
                if (dev != null) tryAr.setPreferredDevice(dev);
                tryAr.startRecording();
                AudioDeviceInfo routed = tryAr.getRoutedDevice();
                boolean ok = dev == null || (routed != null && routed.getId() == dev.getId());
                if (ok || i == sources.length - 1) {
                    ar = tryAr;
                    note = "Nahráva z: " + (routed == null ? "neznámy vstup" : deviceName(routed))
                            + ", zdroj „" + sourceName(sources[i]) + "“, " + rate + " Hz, "
                            + (channels == 2 ? "stereo" : "mono")
                            + (ok ? "" : "\nPozor: Android nepustil zvolený vstup.");
                    break;
                }
                tryAr.stop();
                tryAr.release();
            }
            if (ar == null) throw new IOException("Nahrávanie sa nedá spustiť.");
            final String shown = note;
            ui.post(() -> status.setText(shown));

            byte[] buf = new byte[size / 2];
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(new byte[44]);
                long lastLevel = 0;
                while (recording) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    out.write(buf, 0, n);
                    bytes += n;
                    long now = System.currentTimeMillis();
                    if (now - lastLevel > 60) {
                        lastLevel = now;
                        int peak = 0;
                        for (int i = 0; i + 1 < n; i += 2) {
                            int s = Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)));
                            if (s > peak) peak = s;
                        }
                        double db = peak == 0 ? -60 : 20 * Math.log10(peak / 32768.0);
                        int pct = (int) Math.max(0, Math.min(100, (db + 60) / 60 * 100));
                        ui.post(() -> level.setProgress(pct));
                    }
                }
            }
            ar.stop();
            ar.release();
            ar = null;
            writeHeader(temp, rate, channels, bytes);
            String name = "Riff " + DateFormat.format("yyyy-MM-dd HH.mm.ss", new Date(started));
            save(temp, name);
            ui.post(() -> Toast.makeText(this, "Uložené: " + name, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            String msg = e.getMessage();
            ui.post(() -> status.setText("Chyba: " + msg));
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) { }
                ar.release();
            }
            temp.delete();
            recording = false;
            ui.post(() -> {
                rec.setEnabled(true);
                rec.setText("● Nahrávať");
                level.setProgress(0);
                inputs.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                loadRiffs();
            });
        }
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
                r.added = c.getLong(3);
                riffs.add(r);
            }
        } catch (Exception ignored) {
        }
        riffList.notifyDataSetChanged();
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
