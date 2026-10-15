package sk.lubeno.riffy;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Nahrávanie riffov z USB zvukovej karty (ToneX Plug).
 *
 * Vstup sa číta stále ({@link Engine}): ukazuje úroveň, pamätá si posledných 30 sekúnd a na
 * požiadanie zapisuje nahrávku. ToneX číta aplikácia priamo cez USB ({@link UsbAudio}),
 * lebo zvukový systém Androidu ho na niektorých telefónoch nepustí.
 *
 * Obrazovky sú dve v jednom okne: hlavná (nahrávanie, nástroje, riffy, prehrávač)
 * a nastavenia (vstup, voľby, diagnostika, aktualizácie).
 */
public class MainActivity extends Activity {
    static final String FOLDER = Environment.DIRECTORY_MUSIC + "/Riffy/";
    static final String SHARED = FOLDER + "Zdieľané/";
    static final int ASK_PERMISSIONS = 1, ASK_DELETE = 2, ASK_RENAME = 3;
    static final Drums DRUMS = new Drums();
    static final LoopStation LOOPER = new LoopStation(DRUMS);
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final Object COUNT_IN = new Object();
    static final int GREEN = 0xFF2E7D32, ORANGE = 0xFFEF6C00, GRAY = 0xFF757575, RED = 0xFFC62828, DARK = 0xFF323232;
    static final Pattern AUTO_NAME = Pattern.compile("^(Riff|Slučka) \\d{4}-\\d{2}-\\d{2} (\\d{2})\\.(\\d{2})\\.\\d{2}( spätne| obnovené)?$");

    final Handler ui = new Handler(Looper.getMainLooper());
    final Engine engine = Engine.get();
    AudioManager audio;
    UsbManager usb;
    SharedPreferences prefs;
    Meta meta;

    View mainPage, settingsPage, listArea, tools, spacer, tunerPanel, playerPanel, playerMore, notice;
    TextView chip, banner, levelText, gainText, recHint, metroChip, drumChip, countChip, filterAll, filterFav, filterSongs, empty;
    TextView recClock, tunerText, playerTitle, playerInfo, playerTime, loopText, playPause, playerExpand;
    TextView noticeText, noticeAction, status, info, diaryText, selectCount;
    View filterRow, selectBar;
    Button rec, retro, tunerButton, test, usbInfo;
    LevelView level;
    LiveWaveView live;
    LoopView loopView;
    View looperPanel;
    TextView looperChip, looperText;
    Button looperMain, looperUndo;
    boolean looperOn;
    final Runnable looperTick = this::looperTick;
    TunerView tunerView;
    WaveView playerWave;
    ObjectAnimator pulse;
    ValueAnimator glow;
    GradientDrawable border;
    BroadcastReceiver onPlug;
    AudioDeviceCallback onDevices;
    SeekBar gainBar;
    ProgressBar playerProgress;
    Spinner inputs, sourceSpin, formatSpin, channelSpin, speed;
    CheckBox trim, screenOn, metroPhone, loop, normalize, playDrums, autoRec;
    /** Automatické nahrávanie: zapnuté, nahrávka spustená ním, ako dlho je hlasno / ticho (ms). */
    volatile boolean autoOn, autoStarted;
    long loudMs, quietMs;
    EditText search;
    ListView list;

    final List<AudioDeviceInfo> devices = new ArrayList<>();
    /** USB zariadenia so zvukovým vstupom, ktoré aplikácia číta sama; v zozname sú prvé. */
    final List<UsbDevice> usbDevices = new ArrayList<>();
    final Set<String> askedUsb = new HashSet<>();
    ArrayAdapter<String> inputNames;
    final List<Riff> riffs = new ArrayList<>();
    /** Riadky zoznamu: String = nadpis dňa, Riff = nahrávka. */
    final List<Object> rows = new ArrayList<>();
    RiffAdapter adapter;

    volatile boolean testing;
    boolean resumed, counting, tunerOn;
    /** Filter zoznamu: všetky, obľúbené, piesne. */
    static final int ALL = 0, FAVORITES = 1, SONGS = 2;
    int filter;
    /** Pieseň, ktorá sa prehráva po častiach (zvyšné časti). */
    final List<Riff> queue = new ArrayList<>();
    /** Značky počas nahrávania (ms od začiatku nahrávky). */
    final List<Integer> recordingMarks = new ArrayList<>();
    /** Po zapojení zariadenia sa chvíľu počká, kým sa zapne (ToneX inak prepočuje spustenie prenosu). */
    long waitUntil;
    final Runnable delayedMonitor = this::startMonitor;
    float currentGain = 1f;
    int bpm, recordingBpm, recordingBeats = 4;
    /** Bicie počas nahrávania: štýl a doba, na ktorej nahrávka začala (NaN = nehrali). */
    String recordingDrums = "";
    double recordingBeat = Double.NaN;
    /** Poloha prehrávača pre bicie, ktoré idú za nahrávkou. */
    volatile int clockPos;
    volatile long clockAt;
    volatile boolean clockPlaying;
    volatile float clockSpeed = 1;
    final Drums.Clock playerClock = new Drums.Clock() {
        public boolean playing() {
            return clockPlaying;
        }

        public double positionMs() {
            return clockPlaying ? clockPos + (SystemClock.uptimeMillis() - clockAt) * clockSpeed : clockPos;
        }

        public float speed() {
            return clockSpeed;
        }
    };
    final List<Long> taps = new ArrayList<>();

    MediaPlayer player;
    long playing = -1;
    /** Slučka v ms; -1 = od začiatku / do konca. */
    int loopStart = -1, loopEnd = -1;
    static final float[] SPEEDS = {0.5f, 0.6f, 0.75f, 0.9f, 1f, 1.25f};
    static final String[] SPEED_NAMES = {"50 %", "60 %", "75 %", "90 %", "100 %", "125 %"};
    final Runnable progress = this::showProgress;

    /** Riffy čakajúce na zmazanie; kým beží „Vrátiť“, sú len skryté. */
    final List<Riff> pendingDelete = new ArrayList<>();
    /** Hromadný výber (id riffov); prázdny = bežný režim. */
    final Set<Long> selection = new LinkedHashSet<>();
    /** Riffy, pri ktorých sa už hľadali údaje v súbore. */
    final Set<Long> scanned = new HashSet<>();
    Diary diary;
    long lastLevelAt, diarySavedAt, diaryShownAt;
    final Runnable commitDelete = this::commitDelete;
    final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    static class Riff {
        long id;
        String name;
        long duration, added;
        Meta.Info info;

        Uri uri() {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    final Engine.Listener engineListener = new Engine.Listener() {
        public void onStatus(String text) {
            ui.post(() -> status.setText(text));
        }

        public void onLevel(int[] peak) {
            showLevel(peak);
        }

        public void onFailed(String text) {
            ui.post(() -> {
                setChip(RED, "Vstup prerušený");
                status.setText(text);
                if (engine.recording()) finishRecording();
            });
        }

        public void onOpened(Source c) {
            String name = c instanceof UsbAudio.Direct ? UsbAudio.name(((UsbAudio.Direct) c).dev)
                    : c instanceof Capture && ((Capture) c).routed != null ? deviceName(((Capture) c).routed) : "Vstup";
            ui.post(() -> setChip(GREEN, name));
        }

        public void onRetry() {
            ui.post(() -> setChip(ORANGE, "Zapína sa…"));
        }

        public void onOpenFailed(String text) {
            ui.post(() -> {
                setChip(RED, "Vstup nefunguje");
                notice("Vstup sa nedá otvoriť.", "Podrobnosti", () -> showSettings(true));
            });
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        prefs = getSharedPreferences("nastavenia", MODE_PRIVATE);
        Look.apply(this, prefs);
        super.onCreate(saved);
        setContentView(R.layout.main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        audio = getSystemService(AudioManager.class);
        usb = getSystemService(UsbManager.class);
        meta = new Meta(this);
        UsbAudio.load();
        findViews();

        // Horná lišta a nastavenia
        chip.setOnClickListener(v -> showSettings(true));
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettings(true));
        findViewById(R.id.settingsBack).setOnClickListener(v -> showSettings(false));
        findViewById(R.id.helpButton).setOnClickListener(v -> showManual(true));
        findViewById(R.id.manualButton).setOnClickListener(v -> showManual(true));
        findViewById(R.id.manualBack).setOnClickListener(v -> showManual(false));
        setChip(GRAY, "Vstup vypnutý");

        // Úroveň vstupu
        gainBar.setMax(48);
        gainBar.setProgress(prefs.getInt("gainDb", 0) + 24);
        currentGain = gain();
        showGain();
        gainText.setOnClickListener(v -> gainBar.setVisibility(gainBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int value, boolean user) {
                prefs.edit().putInt("gainDb", value - 24).apply();
                showGain();
                currentGain = gain();
                engine.setGain(currentGain);
            }

            public void onStartTrackingTouch(SeekBar b) {
            }

            public void onStopTrackingTouch(SeekBar b) {
            }
        });

        // Nahrávanie, spätné uloženie, ladička
        rec.setOnClickListener(v -> onRecordPressed());
        retro.setText("⟲\n" + Engine.HISTORY_SECONDS + " s");
        retro.setOnClickListener(v -> saveRetro());
        tunerButton.setOnClickListener(v -> setTuner(!tunerOn));
        findViewById(R.id.markButton).setOnClickListener(v -> {
            if (!engine.recording()) return;
            recordingMarks.add((int) (System.currentTimeMillis() - engine.recordingSince()));
            notice("📍 Značka " + clock(System.currentTimeMillis() - engine.recordingSince()), null, null);
        });
        findViewById(R.id.markAdd).setOnClickListener(v -> addMark());
        findViewById(R.id.backupNew).setOnClickListener(v -> backup(true));
        findViewById(R.id.backupAll).setOnClickListener(v -> backup(false));
        setupLooper();
        setupScale();
        setupBacking();
        engine.tap = this::onInput;

        // Metronóm a odpočet
        bpm = prefs.getInt("bpm", 100);
        metroChip.setOnClickListener(v -> {
            if (DRUMS.playing(Drums.CLICK)) rhythmOff();
            else rhythm(Drums.CLICK);
            showTools();
        });
        DRUMS.style = prefs.getInt("drumStyle", 0);
        DRUMS.fillBars = Drums.FILL_BARS[Math.max(0, Math.min(prefs.getInt("drumFill", 1), Drums.FILL_BARS.length - 1))];
        DRUMS.volume = prefs.getInt("drumVolume", 80) / 100f;
        if (!DRUMS.following()) DRUMS.bpm = bpm;
        drumChip.setOnClickListener(v -> {
            if (DRUMS.playing(Drums.DRUMS)) rhythmOff();
            else rhythm(Drums.DRUMS);
            showTools();
        });
        drumChip.setOnLongClickListener(v -> {
            drumDialog();
            return true;
        });
        metroChip.setOnLongClickListener(v -> {
            tempoDialog();
            return true;
        });
        countChip.setOnClickListener(v -> {
            int bars = leadBars();
            prefs.edit().putInt("leadBars", bars == 0 ? 1 : bars == 1 ? LEAD_IN : 0).apply();
            showTools();
        });

        // Nastavenia
        sourceSpin = choice(R.id.source, SOURCE_NAMES, "source");
        formatSpin = choice(R.id.format, FORMAT_NAMES, "format");
        channelSpin = choice(R.id.channels, CHANNEL_NAMES, "channels");
        normalize.setChecked(prefs.getBoolean("normalize", true));
        normalize.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("normalize", on).apply());
        trim.setChecked(prefs.getBoolean("trim", true));
        trim.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("trim", on).apply());
        metroPhone.setChecked(prefs.getBoolean("metroPhone", false));
        metroPhone.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("metroPhone", on).apply();
            // Iný výstup: prehrávač začne znova (slučka sa posunie na jeho začiatok).
            if (DRUMS.running() && !DRUMS.following()) DRUMS.start(this, output(), DRUMS.mode, false);
        });
        autoRec = findViewById(R.id.autoRec);
        autoRec.setChecked(prefs.getBoolean("autoRec", false));
        autoOn = autoRec.isChecked();
        autoRec.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("autoRec", on).apply();
            autoOn = on;
            showAutoHint();
        });
        screenOn.setChecked(prefs.getBoolean("screenOn", true));
        screenOn.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("screenOn", on).apply();
            keepScreen(engine.recording());
        });
        test.setOnClickListener(v -> findSetup());
        usbInfo.setOnClickListener(v -> usbReport());
        findViewById(R.id.toneTest).setOnClickListener(v -> toneTest());
        lookChoice(R.id.theme, Look.THEMES, "theme");
        lookChoice(R.id.accent, Look.ACCENTS, "accent");
        ((TextView) findViewById(R.id.version)).setText("Verzia " + Updates.current(this));
        findViewById(R.id.updates).setOnClickListener(v -> Updates.check(this, prefs, false));
        Updates.check(this, prefs, true);
        showTools();

        // Vstupy
        IntentFilter plugs = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        plugs.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        onPlug = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) deviceAttached();
                refreshInputs();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onPlug, plugs, Context.RECEIVER_EXPORTED);
        else registerReceiver(onPlug, plugs);
        inputNames = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        inputNames.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inputs.setAdapter(inputNames);
        inputs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                showInfo();
                startMonitor();
            }

            public void onNothingSelected(AdapterView<?> p) {
                showInfo();
            }
        });
        onDevices = new AudioDeviceCallback() {
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                refreshInputs();
            }

            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                refreshInputs();
            }
        };
        audio.registerAudioDeviceCallback(onDevices, ui);

        // Zoznam riffov
        adapter = new RiffAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object r = rows.get(pos);
            if (r instanceof Song) {
                playSong(((Song) r).name);
                return;
            }
            if (!(r instanceof Riff)) return;
            queue.clear();
            if (selection.isEmpty()) play((Riff) r);
            else toggleSelected((Riff) r);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object r = rows.get(pos);
            if (r instanceof Riff) toggleSelected((Riff) r);
            return true;
        });
        findViewById(R.id.selectClose).setOnClickListener(v -> clearSelection());
        findViewById(R.id.selectFav).setOnClickListener(v -> favoriteSelected());
        findViewById(R.id.selectShare).setOnClickListener(v -> shareSelected());
        findViewById(R.id.selectZip).setOnClickListener(v -> zipSelected());
        findViewById(R.id.selectTest).setOnClickListener(v -> {
            List<Riff> chosen = selectedRiffs();
            clearSelection();
            sendForTest(chosen);
        });
        findViewById(R.id.selectDelete).setOnClickListener(v -> {
            List<Riff> chosen = selectedRiffs();
            clearSelection();
            delete(chosen);
        });
        diary = Diary.parse(prefs.getString("diary", null));
        diaryText.setOnClickListener(v -> showDiary());
        showDiaryLine();
        filterAll.setOnClickListener(v -> setFilter(ALL));
        filterFav.setOnClickListener(v -> setFilter(FAVORITES));
        filterSongs.setOnClickListener(v -> setFilter(SONGS));
        setFilter(prefs.getInt("filter", prefs.getBoolean("favorites", false) ? FAVORITES : ALL));
        findViewById(R.id.searchButton).setOnClickListener(v -> toggleSearch());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                render();
            }
        });

        setupPlayer();
        notice.setBackgroundTintList(ColorStateList.valueOf(DARK));

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) deviceAttached();
        refreshInputs();
        askPermissions();
        loadRiffs();
        if (!engine.recording()) IO.execute(this::recover);
        if (saved != null && saved.getBoolean("settings")) showSettings(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("settings", settingsPage.getVisibility() == View.VISIBLE);
    }

    void findViews() {
        mainPage = findViewById(R.id.mainPage);
        settingsPage = findViewById(R.id.settingsPage);
        chip = findViewById(R.id.chip);
        banner = findViewById(R.id.banner);
        level = findViewById(R.id.level);
        live = findViewById(R.id.live);
        recClock = findViewById(R.id.recClock);
        levelText = findViewById(R.id.levelText);
        gainText = findViewById(R.id.gainText);
        gainBar = findViewById(R.id.gain);
        rec = findViewById(R.id.rec);
        retro = findViewById(R.id.retro);
        tunerButton = findViewById(R.id.tunerButton);
        recHint = findViewById(R.id.recHint);
        tools = findViewById(R.id.tools);
        metroChip = findViewById(R.id.metroChip);
        drumChip = findViewById(R.id.drumChip);
        playDrums = findViewById(R.id.playDrums);
        countChip = findViewById(R.id.countChip);
        tunerPanel = findViewById(R.id.tunerPanel);
        looperPanel = findViewById(R.id.looperPanel);
        looperChip = findViewById(R.id.looperChip);
        looperText = findViewById(R.id.looperText);
        loopView = findViewById(R.id.loopView);
        looperMain = findViewById(R.id.looperMain);
        looperUndo = findViewById(R.id.looperUndo);
        tunerView = findViewById(R.id.tunerView);
        tunerText = findViewById(R.id.tunerText);
        listArea = findViewById(R.id.listArea);
        spacer = findViewById(R.id.spacer);
        filterAll = findViewById(R.id.filterAll);
        filterFav = findViewById(R.id.filterFav);
        filterSongs = findViewById(R.id.filterSongs);
        search = findViewById(R.id.search);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        notice = findViewById(R.id.notice);
        noticeText = findViewById(R.id.noticeText);
        noticeAction = findViewById(R.id.noticeAction);
        playerPanel = findViewById(R.id.player);
        playerMore = findViewById(R.id.playerMore);
        playPause = findViewById(R.id.playPause);
        playerTitle = findViewById(R.id.playerTitle);
        playerProgress = findViewById(R.id.playerProgress);
        playerInfo = findViewById(R.id.playerInfo);
        playerExpand = findViewById(R.id.playerExpand);
        playerWave = findViewById(R.id.playerWave);
        playerTime = findViewById(R.id.playerTime);
        speed = findViewById(R.id.speed);
        loop = findViewById(R.id.loop);
        loopText = findViewById(R.id.loopText);
        inputs = findViewById(R.id.inputs);
        status = findViewById(R.id.status);
        info = findViewById(R.id.info);
        test = findViewById(R.id.test);
        usbInfo = findViewById(R.id.usbInfo);
        trim = findViewById(R.id.trim);
        screenOn = findViewById(R.id.screenOn);
        metroPhone = findViewById(R.id.metroPhone);
        normalize = findViewById(R.id.normalize);
        diaryText = findViewById(R.id.diaryText);
        filterRow = findViewById(R.id.filterRow);
        selectBar = findViewById(R.id.selectBar);
        selectCount = findViewById(R.id.selectCount);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pripojený ToneX (Android otvoril aplikáciu sám).
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) deviceAttached();
        refreshInputs();
    }

    void deviceAttached() {
        waitUntil = SystemClock.uptimeMillis() + 2000;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        engine.listener = engineListener;
        engine.setGain(currentGain);
        if (engine.recording()) {
            setRecordingUi(true);
            tick();
        }
        showTools();
        startMonitor();
    }

    @Override
    protected void onPause() {
        resumed = false;
        // Počas nahrávania beží vstup ďalej aj pri zhasnutej obrazovke (RecordingService).
        saveDiary();
        lastLevelAt = 0;
        if (!engine.recording() && !testing) {
            engine.stop();
            if (!counting) {
                if (!DRUMS.following()) DRUMS.stop();
                DRUMS.backing = null;
                LOOPER.cancel();
            }
            level.clear();
            live.clear();
            levelText.setText("");
            setChip(GRAY, "Vstup vypnutý");
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        commitDelete();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopPlaying();
        stopPulse();
        // Pri zmene vzhľadu sa okno vytvára znova; staré by inak ďalej počúvalo zapojenie USB.
        unregisterReceiver(onPlug);
        audio.unregisterAudioDeviceCallback(onDevices);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        View manual = findViewById(R.id.manualPage);
        android.webkit.WebView web = findViewById(R.id.manual);
        if (manual.getVisibility() == View.VISIBLE) {
            if (web.canGoBack()) web.goBack();
            else showManual(false);
        } else if (settingsPage.getVisibility() == View.VISIBLE) showSettings(false);
        else if (!selection.isEmpty()) clearSelection();
        else if (playerMore.getVisibility() == View.VISIBLE) expandPlayer(false);
        else if (search.getVisibility() == View.VISIBLE) toggleSearch();
        else super.onBackPressed();
    }

    void askPermissions() {
        List<String> missing = new ArrayList<>();
        missing.add(Manifest.permission.RECORD_AUDIO);
        missing.add(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        missing.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), ASK_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshInputs();
        startMonitor();
        loadRiffs();
    }

    // ---- Spoločné prvky obrazovky ----

    void setChip(int color, String text) {
        chip.setText(text);
        chip.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    void showSettings(boolean show) {
        if (show) showBackupInfo();
        settingsPage.setVisibility(show ? View.VISIBLE : View.GONE);
        mainPage.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    /**
     * Návod (assets/navod.html) vo farbách aplikácie: tmavý alebo svetlý podľa vzhľadu, so zvolenou
     * farbou zvýraznenia. Funguje bez internetu.
     */
    void showManual(boolean show) {
        View page = findViewById(R.id.manualPage);
        if (show) {
            android.webkit.WebView web = findViewById(R.id.manual);
            try (InputStream in = getAssets().open("navod.html")) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                int bg = Look.color(this, android.R.attr.colorBackground);
                boolean dark = (0.299 * ((bg >> 16) & 255) + 0.587 * ((bg >> 8) & 255) + 0.114 * (bg & 255)) < 128;
                String accent = String.format("#%06X", Look.accent(this) & 0xFFFFFF);
                String html = new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("<html lang=\"sk\">", "<html lang=\"sk\"" + (dark ? " class=\"dark\"" : "")
                                + " style=\"--accent:" + accent + "\">");
                web.setBackgroundColor(bg);
                web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null);
            } catch (IOException e) {
                notice("Návod sa nedá otvoriť.", null, null);
                return;
            }
        }
        page.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Krátke hlásenie dole, prípadne s tlačidlom (Vrátiť, Podrobnosti). */
    void notice(String text, String action, Runnable onAction) {
        noticeText.setText(text);
        noticeAction.setText(action == null ? "" : action);
        noticeAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        noticeAction.setOnClickListener(v -> {
            notice.setVisibility(View.GONE);
            if (onAction != null) onAction.run();
        });
        notice.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNotice);
        ui.postDelayed(hideNotice, action == null ? 3000 : 5000);
    }

    /** Počas nahrávania ostane len to podstatné: čas, úroveň a tlačidlo na zastavenie. */
    void setRecordingUi(boolean recording) {
        keepScreen(recording);
        banner.setVisibility(recording ? View.VISIBLE : View.GONE);
        banner.setBackgroundTintList(ColorStateList.valueOf(RED));
        if (recording) {
            recHint.setText(autoStarted ? "Ťuknutím zastavíte a uložíte · samo sa zastaví po 4 s ticha"
                    : "Ťuknutím zastavíte a uložíte");
            recHint.setVisibility(View.VISIBLE);
        } else {
            showAutoHint();
        }
        recClock.setVisibility(recording ? View.VISIBLE : View.GONE);
        findViewById(R.id.markButton).setVisibility(recording ? View.VISIBLE : View.GONE);
        retro.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        tunerButton.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        tools.setVisibility(recording ? View.GONE : View.VISIBLE);
        diaryText.setVisibility(recording ? View.GONE : View.VISIBLE);
        updatePanels();
        spacer.setVisibility(recording ? View.VISIBLE : View.GONE);
        if (recording) stopPlaying();
        rec.setText(recording ? "■" : "●");
        rec.setContentDescription(recording ? "Zastaviť a uložiť" : "Nahrávať");
        if (recording) startPulse();
        else stopPulse();
        inputs.setEnabled(!recording);
        usbInfo.setEnabled(!recording);
        test.setEnabled(!recording && selectedUsb() == null);
    }

    /** Počas nahrávania tlačidlo pulzuje a okraj obrazovky svieti na červeno, aby to bolo vidno aj z diaľky. */
    void startPulse() {
        if (pulse != null) return;
        pulse = ObjectAnimator.ofPropertyValuesHolder(rec, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.92f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.92f));
        pulse.setDuration(700);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
        if (border == null) {
            border = new GradientDrawable();
            border.setStroke(Math.round(5 * getResources().getDisplayMetrics().density), RED);
            border.setCornerRadius(18 * getResources().getDisplayMetrics().density);
        }
        findViewById(R.id.root).setForeground(border);
        glow = ValueAnimator.ofInt(90, 255);
        glow.setDuration(700);
        glow.setRepeatCount(ValueAnimator.INFINITE);
        glow.setRepeatMode(ValueAnimator.REVERSE);
        glow.addUpdateListener(a -> border.setAlpha((int) a.getAnimatedValue()));
        glow.start();
    }

    void stopPulse() {
        if (pulse != null) pulse.cancel();
        if (glow != null) glow.cancel();
        pulse = null;
        glow = null;
        rec.setScaleX(1);
        rec.setScaleY(1);
        findViewById(R.id.root).setForeground(null);
    }

    /** Počas nahrávania obrazovka nezhasne (ak to používateľ nevypol); nahrávanie by bežalo aj pri zhasnutej. */
    void keepScreen(boolean recording) {
        if (recording && screenOn.isChecked()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void showTools() {
        boolean on = DRUMS.playing(Drums.CLICK);
        metroChip.setText("♩ " + bpm + (on ? "  ●" : ""));
        metroChip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        metroChip.setContentDescription("Metronóm " + bpm + " BPM, " + (on ? "zapnutý" : "vypnutý")
                + ". Podržaním nastavíte tempo.");
        int lead = leadBars();
        countChip.setBackgroundResource(lead > 0 ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        countChip.setText(lead == 0 ? "Odpočet" : lead == 1 ? "Odpočet ✓" : "Rozbeh " + lead + " ✓");
        tunerButton.setBackgroundResource(tunerOn ? R.drawable.bg_circle_on : R.drawable.bg_circle);
        boolean drums = DRUMS.playing(Drums.DRUMS);
        drumChip.setText("🥁 " + Drums.STYLES[prefs.getInt("drumStyle", 0)] + (drums ? "  ●" : ""));
        drumChip.setBackgroundResource(drums ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        boolean loop = LOOPER.hasLoop() || LOOPER.state() != LoopStation.EMPTY;
        looperChip.setText("🔁 Looper" + (loop ? "  ●" : ""));
        looperChip.setBackgroundResource(looperOn ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        findViewById(R.id.scaleChip).setBackgroundResource(scaleOn ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        ((TextView) findViewById(R.id.backingChip)).setText("🎹 Sprievod" + (DRUMS.backing != null ? "  ●" : ""));
        findViewById(R.id.backingChip).setBackgroundResource(backingOn ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        drumChip.setContentDescription("Bicie " + Drums.STYLES[prefs.getInt("drumStyle", 0)] + ", "
                + (drums ? "hrajú" : "vypnuté") + ". Podržaním vyberiete štýl.");
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
        String before = inputKey();
        usbDevices.clear();
        for (UsbDevice d : usb.getDeviceList().values()) if (UsbAudio.hasAudioInput(d)) usbDevices.add(d);
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
        for (UsbDevice d : usbDevices) inputNames.add(UsbAudio.name(d) + " – priamo cez USB");
        for (AudioDeviceInfo d : devices) inputNames.add(deviceName(d) + (isUsb(d) ? " – cez Android" : ""));
        inputNames.notifyDataSetChanged();

        int pick = 0;
        for (int i = 0; i < inputNames.getCount(); i++) {
            if (keyAt(i).equals(before)) pick = i;
        }
        if (inputNames.getCount() > 0) inputs.setSelection(pick);
        showInfo();
        startMonitor();
    }

    String keyAt(int i) {
        if (i < 0) return "";
        if (i < usbDevices.size()) return "usb:" + usbDevices.get(i).getDeviceName();
        i -= usbDevices.size();
        return i < devices.size() ? "android:" + devices.get(i).getId() : "";
    }

    String inputKey() {
        return inputs == null ? "" : keyAt(inputs.getSelectedItemPosition());
    }

    AudioDeviceInfo selected() {
        int i = inputs == null ? -1 : inputs.getSelectedItemPosition() - usbDevices.size();
        return i >= 0 && i < devices.size() ? devices.get(i) : null;
    }

    UsbDevice selectedUsb() {
        int i = inputs == null ? -1 : inputs.getSelectedItemPosition();
        return i >= 0 && i < usbDevices.size() ? usbDevices.get(i) : null;
    }

    void showInfo() {
        AudioDeviceInfo d = selected();
        boolean anyUsb = !usbDevices.isEmpty() || devices.stream().anyMatch(MainActivity::isUsb);
        boolean direct = selectedUsb() != null;
        boolean busy = engine.recording() || testing;
        usbInfo.setVisibility(usbDevices.isEmpty() ? View.GONE : View.VISIBLE);
        sourceSpin.setEnabled(!direct && !busy);
        formatSpin.setEnabled(!direct && !busy);
        channelSpin.setEnabled(!direct && !busy);
        test.setEnabled(!direct && !busy);
        if (direct) {
            info.setText("Aplikácia číta zariadenie sama, zvukový systém Androidu obchádza. "
                    + "Zdroj, formát a kanály sa tu nepoužijú.");
        } else if (d == null) {
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

    // ---- Nastavenia vstupu cez Android ----

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

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        return s;
    }

    /** Voľba vzhľadu: po zmene sa okno nakreslí znova v nových farbách. */
    void lookChoice(int id, String[] names, String key) {
        Spinner s = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(prefs.getInt(key, 0), names.length - 1)));
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == prefs.getInt(key, 0)) return;
                prefs.edit().putInt(key, pos).apply();
                recreate();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
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

    /** Vstup cez zvukový systém Androidu s konkrétnym nastavením. */
    static class Capture extends Source {
        AudioRecord ar;
        AudioDeviceInfo routed;
        int source, encoding;
        short[] shorts;
        float[] floats;

        @Override
        int read(int[] out) {
            float g = gain;
            if (encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                if (shorts == null || shorts.length < out.length) shorts = new short[out.length];
                int n = ar.read(shorts, 0, out.length);
                for (int i = 0; i < n; i++) out[i] = g == 1f ? shorts[i] << 16 : Source.clamp(shorts[i] * 65536.0 * g);
                return n;
            }
            if (floats == null || floats.length < out.length) floats = new float[out.length];
            int n = ar.read(floats, 0, out.length, AudioRecord.READ_BLOCKING);
            for (int i = 0; i < n; i++) out[i] = Source.clamp(floats[i] * 2147483647.0 * g);
            return n;
        }

        @Override
        void close() {
            try {
                ar.stop();
            } catch (Exception ignored) {
            }
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

    static String describe(Capture c, AudioDeviceInfo dev) {
        return (c.routed == null ? "neznámy vstup" : deviceName(c.routed))
                + ", zdroj „" + sourceName(c.source) + "“, " + formatName(c.encoding) + ", " + c.rate + " Hz, "
                + (c.channels == 2 ? "stereo" : "mono")
                + (routedTo(c, dev) ? "" : "\nPozor: Android nepustil zvolený vstup.");
    }

    /** Vstup podľa toho, čo je vybrané; null, ak treba počkať na povolenie pre USB. */
    Source.Opener opener() {
        UsbDevice u = selectedUsb();
        if (u != null) {
            if (!usb.hasPermission(u)) {
                status.setText("Povoľte aplikácii prístup k USB zariadeniu.");
                setChip(ORANGE, "Povoľte prístup k USB");
                if (askedUsb.add(u.getDeviceName())) {
                    Intent answer = new Intent("sk.lubeno.riffy.USB").setPackage(getPackageName());
                    usb.requestPermission(u, PendingIntent.getBroadcast(this, 0, answer, PendingIntent.FLAG_IMMUTABLE));
                }
                return null;
            }
            return attempt -> UsbAudio.open(usb, u, UsbAudio.wantFor(attempt));
        }
        AudioDeviceInfo dev = selected();
        int source = SOURCE_IDS[sourceSpin.getSelectedItemPosition()];
        int encoding = FORMAT_IDS[formatSpin.getSelectedItemPosition()];
        int channels = channelsFor(dev, CHANNEL_IDS[channelSpin.getSelectedItemPosition()]);
        int rate = rateFor(dev);
        return attempt -> {
            Capture c = openChosen(dev, source, encoding, channels, rate);
            if (c == null) throw new IOException("S týmto nastavením sa vstup nedá otvoriť.");
            c.where = describe(c, dev);
            return c;
        };
    }

    String settingsKey() {
        return inputKey() + "/" + sourceSpin.getSelectedItemPosition() + "/" + formatSpin.getSelectedItemPosition()
                + "/" + channelSpin.getSelectedItemPosition();
    }

    /** Spustí stále čítanie vstupu (ak už beží s rovnakým nastavením, nič nemení). */
    void startMonitor() {
        if (!resumed || testing || engine.recording() || sourceSpin == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setChip(GRAY, "Bez povolenia mikrofónu");
            return;
        }
        long wait = waitUntil - SystemClock.uptimeMillis();
        if (wait > 0) {
            status.setText("Čakám, kým sa zariadenie zapne…");
            setChip(ORANGE, "Zapína sa…");
            ui.removeCallbacks(delayedMonitor);
            ui.postDelayed(delayedMonitor, wait);
            return;
        }
        Source.Opener opener = opener();
        if (opener == null) return;
        if (!engine.running()) setChip(ORANGE, "Pripája sa…");
        engine.start(settingsKey(), opener);
    }

    float gain() {
        return (float) Math.pow(10, (gainBar.getProgress() - 24) / 20.0);
    }

    void showGain() {
        int db = gainBar.getProgress() - 24;
        gainText.setText("Zosilnenie " + (db > 0 ? "+" : "") + db + " dB ▾");
    }

    void showLevel(int[] peak) {
        int max = 0;
        for (int p : peak) max = Math.max(max, p);
        int top = max;
        String text = Source.levels(peak) + (max >= 32700 ? "   ⚠ prebudené, uberte" : "");
        ui.post(() -> {
            level.set(top);
            live.push(top, engine.recording());
            levelText.setText(text);
            heard(top);
        });
    }

    // ---- Metronóm ----

    /**
     * Zapne metronóm alebo bicie. Ak už prehrávač rytmu hrá (aj len slučku loopera), len sa
     * prepne, čo hrá, aby sa nestratilo poradie dôb a slučka sedela.
     */
    void rhythm(int mode) {
        stopFollowing();
        if (DRUMS.running() && !DRUMS.following()) {
            DRUMS.mode = mode;
        } else {
            DRUMS.bpm = bpm;
            DRUMS.start(this, output(), mode, false);
        }
    }

    /** Vypne metronóm aj bicie; slučka loopera hrá ďalej. */
    void rhythmOff() {
        if (LOOPER.hasLoop() || LOOPER.state() != LoopStation.EMPTY || DRUMS.backing != null) DRUMS.mode = Drums.OFF;
        else DRUMS.stop();
    }

    void setBpm(int value) {
        if (LOOPER.hasLoop() && value != bpm) {
            notice("Tempo sa nedá zmeniť, kým je v looperi slučka.", "Vymazať", () -> {
                LOOPER.clear();
                setBpm(value);
            });
            return;
        }
        bpm = Math.max(30, Math.min(300, value));
        prefs.edit().putInt("bpm", bpm).apply();
        if (!DRUMS.following()) DRUMS.bpm = bpm;
        showTools();
    }

    /** Tempo: výber číslom alebo ťukaním do rytmu. */
    void tempoDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(30);
        picker.setMaxValue(300);
        picker.setValue(bpm);
        picker.setWrapSelectorWheel(false);
        Button tap = new Button(this);
        tap.setText("Ťukajte do rytmu");
        tap.setAllCaps(false);
        taps.clear();
        tap.setOnClickListener(v -> {
            long now = SystemClock.uptimeMillis();
            if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2000) taps.clear();
            taps.add(now);
            while (taps.size() > 6) taps.remove(0);
            if (taps.size() >= 3) {
                double avg = (taps.get(taps.size() - 1) - taps.get(0)) / (double) (taps.size() - 1);
                picker.setValue((int) Math.max(30, Math.min(300, Math.round(60000 / avg))));
            }
        });
        box.addView(picker);
        box.addView(tap);
        new AlertDialog.Builder(this)
                .setTitle("Tempo (BPM)")
                .setView(box)
                .setPositiveButton("Hotovo", (d, w) -> setBpm(picker.getValue()))
                .setNegativeButton("Zrušiť", null)
                .show();
    }


    /** Štýl, prechody, hlasitosť a tempo bicích; zmeny počuť hneď. */
    void drumDialog() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(20 * d), Math.round(8 * d), Math.round(20 * d), 0);
        android.widget.RadioGroup styles = new android.widget.RadioGroup(this);
        for (int i = 0; i < Drums.STYLES.length; i++) {
            android.widget.RadioButton b = new android.widget.RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(Drums.STYLES[i]);
            b.setMinHeight(Math.round(44 * d));
            int style = i;
            b.setOnCheckedChangeListener((x, on) -> {
                if (!on) return;
                prefs.edit().putInt("drumStyle", style).apply();
                if (!DRUMS.following()) DRUMS.style = style;
                showTools();
            });
            styles.addView(b);
            if (i == prefs.getInt("drumStyle", 0)) b.setChecked(true);
        }
        box.addView(styles);
        TextView fillLabel = new TextView(this);
        fillLabel.setText("Prechod");
        fillLabel.setPadding(0, Math.round(12 * d), 0, 0);
        box.addView(fillLabel);
        Spinner fills = new Spinner(this);
        ArrayAdapter<String> fa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Drums.FILLS);
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fills.setAdapter(fa);
        fills.setSelection(Math.max(0, Math.min(prefs.getInt("drumFill", 1), Drums.FILLS.length - 1)));
        fills.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("drumFill", pos).apply();
                DRUMS.fillBars = Drums.FILL_BARS[pos];
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        box.addView(fills);
        TextView volLabel = new TextView(this);
        volLabel.setText("Hlasitosť");
        volLabel.setPadding(0, Math.round(12 * d), 0, 0);
        box.addView(volLabel);
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(prefs.getInt("drumVolume", 80));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int value, boolean user) {
                prefs.edit().putInt("drumVolume", value).apply();
                DRUMS.volume = value / 100f;
            }

            public void onStartTrackingTouch(SeekBar b) {
            }

            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        box.addView(vol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * d)));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Bicie")
                .setView(scroll)
                .setPositiveButton("Hotovo", null)
                .setNeutralButton("Tempo " + bpm, (x, w) -> tempoDialog())
                .setNegativeButton(DRUMS.playing(Drums.DRUMS) ? "Zastaviť" : "Hrať", (x, w) -> {
                    if (DRUMS.playing(Drums.DRUMS)) rhythmOff();
                    else rhythm(Drums.DRUMS);
                    showTools();
                })
                .show();
    }

    /**
     * Kam idú metronóm a bicie: do slúchadiel na USB zariadení (priamo, lebo Android ho pri
     * vlastnom ovládači nevidí), inak cez Android do telefónu.
     */
    Output output() {
        if (!metroPhone.isChecked() && Output.Usb.available()) return new Output.Usb();
        return new Output.Track(metronomeDevice());
    }

    /** Krátke pípnutia priamo do USB zariadenia; potom ukáže, ako výstup beží. */
    void toneTest() {
        UsbAudio.Direct d = UsbAudio.Direct.current;
        if (d == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Skúšobný tón")
                    .setMessage("ToneX práve nie je pripojený cez vlastný ovládač (USB). Zapojte ho a skúste znova.")
                    .setPositiveButton("Zavrieť", null)
                    .show();
            return;
        }
        findViewById(R.id.toneTest).setEnabled(false);
        new Thread(() -> {
            Output.Usb out = new Output.Usb();
            short[] chunk = new short[Output.RATE / 100];
            long k = 0;
            for (int c = 0; c < 150; c++) {
                for (int i = 0; i < chunk.length; i++, k++) {
                    double t = k / (double) Output.RATE;
                    boolean on = (t % 0.5) < 0.25;
                    chunk[i] = (short) (on ? Math.sin(2 * Math.PI * 440 * t) * 0.5 * 32767 * Math.min(1, (0.25 - t % 0.5) * 40) : 0);
                }
                out.write(chunk, chunk.length);
            }
            String state = d.outputState();
            ui.post(() -> {
                findViewById(R.id.toneTest).setEnabled(true);
                status.setText("Výstup do USB: " + state);
                new AlertDialog.Builder(this)
                        .setTitle("Skúšobný tón")
                        .setMessage((d.hasOutput() ? "Zazneli v slúchadlách ToneX tri pípnutia?\n\n"
                                : "Do ToneX sa zvuk poslať nepodarilo.\n\n") + "Výstup: " + state
                                + "\n\nAk nič nepočuť, pošlite Popis USB.")
                        .setPositiveButton("Zavrieť", null)
                        .setNeutralButton("Popis USB", (x, w) -> usbReport())
                        .show();
            });
        }, "ton").start();
    }

    /** Metronóm ide do ToneX (do slúchadiel na gitare), ak nie je zvolený telefón. */
    AudioDeviceInfo metronomeDevice() {
        if (!metroPhone.isChecked()) {
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (isUsb(d)) return d;
            }
        }
        return playbackDevice();
    }

    // ---- Nahrávanie ----

    void onRecordPressed() {
        if (testing) {
            testing = false;
            return;
        }
        if (counting) {
            cancelCountIn();
            return;
        }
        if (engine.recording()) {
            finishRecording();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            return;
        }
        stopPlaying();
        if (!engine.running()) {
            startMonitor();
            notice("Vstup sa ešte spúšťa, skúste o chvíľu.", null, null);
            return;
        }
        if (leadBars() > 0) startCountIn();
        else beginRecording(false);
    }

    static final int LEAD_IN = 4;

    /** Takty pred nahrávaním: 0 = hneď, 1 = odpočet, LEAD_IN = rozbeh (bicie hrajú, posledný takt odpočet). */
    int leadBars() {
        if (!prefs.contains("leadBars")) return prefs.getBoolean("countIn", false) ? 1 : 0;
        return prefs.getInt("leadBars", 0);
    }

    /** Rozbeh a odpočet posledného taktu (metronómom alebo paličkami), potom sa začne nahrávať. */
    void startCountIn() {
        counting = true;
        boolean free = DRUMS.running() && !DRUMS.following();
        boolean wasOn = free && DRUMS.mode != Drums.OFF;
        // Hrajúce bicie odpočítajú paličkami a pokračujú; inak odpočíta metronóm.
        stopFollowing();
        DRUMS.bpm = bpm;
        int lead = leadBars();
        DRUMS.start(this, output(), wasOn ? DRUMS.mode : Drums.CLICK, lead);
        // Metronóm, ktorý hrá len na odpočet, na prvej dobe už necvakne (mikrofón by ho nahral).
        if (!wasOn) DRUMS.silentFromStart();
        int bar = DRUMS.beatsPerBar(), beats = bar * lead;
        banner.setVisibility(View.VISIBLE);
        banner.setBackgroundTintList(ColorStateList.valueOf(ORANGE));
        // Poistka: keby výstup nehlásil, čo už zaznelo, začne sa nahrávať chvíľu po odpočte.
        long deadline = SystemClock.uptimeMillis() + Math.round(beats * 60000.0 / bpm) + 1000;
        countTick(lead, bar, beats, wasOn, Integer.MIN_VALUE, deadline);
    }

    /**
     * Ukazuje odpočet podľa toho, čo je práve počuť (výstup má oneskorenie), a nahrávať začne,
     * až keď zaznie prvá doba – inak by mikrofón nahral aj posledný úder odpočtu.
     */
    void countTick(int lead, int bar, int beats, boolean wasOn, int shown, long deadline) {
        double heard = DRUMS.audibleBeat();
        int k = Double.isNaN(heard) ? shown == Integer.MIN_VALUE ? -beats : shown : (int) Math.floor(heard + 1e-6);
        if (k >= 0 || SystemClock.uptimeMillis() > deadline) {
            counting = false;
            if (!wasOn) rhythmOff();
            showTools();
            beginRecording(true);
            return;
        }
        if (k != shown) {
            int left = -k, b = (k + beats) / bar + 1;
            boolean last = left <= bar;
            rec.setText(last ? String.valueOf(left) : "♪");
            banner.setText((last ? "Odpočet " + left : "Rozbeh – takt " + b + " z " + (lead - 1))
                    + "   (ťuknutím zrušíte)");
        }
        ui.postAtTime(() -> countTick(lead, bar, beats, wasOn, k, deadline), COUNT_IN, SystemClock.uptimeMillis() + 10);
    }

    void cancelCountIn() {
        ui.removeCallbacksAndMessages(COUNT_IN);
        counting = false;
        rhythmOff();
        showTools();
        banner.setVisibility(View.GONE);
        rec.setText("●");
    }

    /** counted: pred nahrávaním bol odpočet, tempo sa k riffu uloží, aj keď metronóm potom stíchol. */
    void beginRecording(boolean counted) {
        autoStarted = false;
        beginRecording(counted, 0);
    }

    void beginRecording(boolean counted, double preSeconds) {
        recordingMarks.clear();
        File temp = new File(getCacheDir(), "nahravka-" + System.currentTimeMillis() + ".wav");
        try {
            engine.startRecording(temp, preSeconds);
        } catch (IOException e) {
            status.setText(e.getMessage());
            banner.setVisibility(View.GONE);
            rec.setText("●");
            notice("Nahrávanie sa nedá spustiť.", "Podrobnosti", () -> showSettings(true));
            return;
        }
        boolean drums = DRUMS.playing(Drums.DRUMS);
        recordingBpm = DRUMS.playing(Drums.CLICK) || drums || counted ? bpm : 0;
        recordingDrums = drums ? Drums.STYLES[DRUMS.style] : "";
        recordingBeat = DRUMS.audibleBeat() - preSeconds * bpm / 60.0;
        recordingBeats = DRUMS.beatsPerBar();
        RecordingService.start(this);
        setRecordingUi(true);
        tick();
    }

    void tick() {
        if (!engine.recording()) return;
        banner.setText("●  Nahrávam");
        recClock.setText(clock(System.currentTimeMillis() - engine.recordingSince()));
        ui.postDelayed(this::tick, 250);
    }

    void finishRecording() {
        rec.setEnabled(false);
        RecordingService.stop(this);
        int tempo = recordingBpm;
        List<Integer> marks = new ArrayList<>(recordingMarks);
        Groove groove = tempo <= 0 || Double.isNaN(recordingBeat) ? null
                : new Groove(recordingDrums, recordingBeat, false, recordingBeats);
        long since = engine.recordingSince();
        IO.execute(() -> {
            Engine.Recorded r = null;
            try {
                r = engine.stopRecording();
                if (r != null) {
                    String name = "Riff " + DateFormat.format("yyyy-MM-dd HH.mm.ss", new Date(since));
                    String saved = save(r.file, name, tempo, groove, marks);
                    ui.post(() -> savedNotice(saved));
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                ui.post(() -> {
                    status.setText("Chyba pri ukladaní: " + msg);
                    notice("Nahrávku sa nepodarilo uložiť.", "Podrobnosti", () -> showSettings(true));
                });
            } finally {
                if (r != null) r.file.delete();
                ui.post(() -> {
                    rec.setEnabled(true);
                    setRecordingUi(false);
                    loadRiffs();
                    if (!resumed) engine.stop();
                    else startMonitor();
                });
            }
        });
    }

    /** Spätné nahrávanie: uloží, čo vstup práve počul. */
    void saveRetro() {
        if (!engine.running()) {
            notice("Vstup nebeží.", null, null);
            return;
        }
        retro.setEnabled(false);
        boolean drums = DRUMS.playing(Drums.DRUMS);
        int tempo = DRUMS.playing(Drums.CLICK) || drums ? bpm : 0;
        double beatNow = tempo > 0 ? DRUMS.audibleBeat() : Double.NaN;
        Groove groove = Double.isNaN(beatNow) ? null
                : new Groove(drums ? Drums.STYLES[DRUMS.style] : "", beatNow, true, DRUMS.beatsPerBar());
        long now = System.currentTimeMillis();
        IO.execute(() -> {
            File temp = new File(getCacheDir(), "spatne-" + now + ".wav");
            try {
                Engine.Recorded r = engine.saveHistory(temp, Engine.HISTORY_SECONDS);
                if (r == null) {
                    ui.post(() -> notice("Ešte nie je čo uložiť.", null, null));
                } else {
                    String saved = save(temp, "Riff " + DateFormat.format("yyyy-MM-dd HH.mm.ss", new Date(now)) + " spätne", tempo, groove);
                    ui.post(() -> savedNotice(saved));
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                ui.post(() -> {
                    status.setText("Chyba pri ukladaní: " + msg);
                    notice("Nahrávku sa nepodarilo uložiť.", "Podrobnosti", () -> showSettings(true));
                });
            } finally {
                temp.delete();
                ui.post(() -> {
                    retro.setEnabled(true);
                    loadRiffs();
                });
            }
        });
    }

    /**
     * Uloží nahrávku do Hudba/Riffy, kde ju vidia aj iné aplikácie; podľa nastavenia oreže ticho.
     * Vráti meno, pod ktorým sa uložila.
     */
    /**
     * Rytmus, ktorý hral pri nahrávaní: štýl bicích (prázdny = metronóm), doba na začiatku
     * (alebo na konci) nahrávky a doby v takte.
     */
    static final class Groove {
        final String style;
        final double beat;
        final boolean atEnd;
        final int beats;

        Groove(String style, double beat, boolean atEnd, int beats) {
            this.style = style;
            this.beat = beat;
            this.atEnd = atEnd;
            this.beats = beats;
        }
    }

    /** Kde (ms) je v nahrávke prvá doba taktu, ak jej začiatok padol na dobu startBeat. */
    static int firstBeatMs(double startBeat, int bpm) {
        return firstBeatMs(startBeat, bpm, 4);
    }

    static int firstBeatMs(double startBeat, int bpm, int beats) {
        double toBar = Math.floorMod((long) Math.floor(-startBeat * 1000), beats * 1000L) / 1000.0;
        return (int) Math.round(toBar * 60000.0 / bpm);
    }

    String save(File temp, String name, int tempo, Groove groove) throws IOException {
        return save(temp, name, tempo, groove, null);
    }

    /** marks = značky (ms od začiatku nahrávky pred orezaním). */
    String save(File temp, String name, int tempo, Groove groove, List<Integer> marks) throws IOException {
        long[] range;
        Wav.Info format;
        try (InputStream in = new FileInputStream(temp)) {
            format = Wav.read(in);
            range = new long[]{0, format.frames()};
        }
        if (prefs.getBoolean("trim", true)) range = Wav.trimRange(temp, -50, 0.3);
        double gain = 1;
        if (prefs.getBoolean("normalize", true)) {
            double[] a = Wav.analyze(temp, range[0], range[1]);
            gain = Wav.loudnessGain(a[0], a[1]);
        }
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".wav");
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Nahrávku sa nepodarilo uložiť.");
        try (OutputStream out = cr.openOutputStream(uri)) {
            // Jemný nábeh 10 ms a dozvuk 50 ms, aby na začiatku a konci nelúplo.
            Wav.copyProcessed(temp, range[0], range[1], gain, format.rate / 100, format.rate / 20, out);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        String saved = name;
        try (Cursor c = cr.query(uri, new String[]{MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) saved = c.getString(0).replaceFirst("\\.wav$", "");
        }
        boolean hasMarks = marks != null && !marks.isEmpty();
        String tone = prefs.getString("lastTone", "");
        if (tempo > 0 || hasMarks || !tone.isEmpty()) {
            Meta.Info i = meta.get(saved);
            if (i.tone.isEmpty()) i.tone = tone;
            if (hasMarks) {
                long cut = range[0] * 1000 / format.rate, len = (range[1] - range[0]) * 1000 / format.rate;
                for (int m : marks) if (m - cut >= 0 && m - cut <= len) i.markers.add((int) (m - cut));
            }
            if (tempo > 0) i.bpm = tempo;
            if (groove != null && tempo > 0) {
                double seconds = groove.atEnd ? format.frames() / (double) format.rate : 0;
                double start = groove.beat - seconds * tempo / 60.0 + range[0] / (double) format.rate * tempo / 60.0;
                if (!groove.style.isEmpty()) i.drums = groove.style;
                i.beat0 = firstBeatMs(start, tempo, groove.beats);
            }
            meta.put(saved, i);
            embed(uri, i);
        }
        return saved;
    }

    // ---- Technický popis USB zariadenia ----

    void usbReport() {
        UsbDevice u = selectedUsb() != null ? selectedUsb() : usbDevices.isEmpty() ? null : usbDevices.get(0);
        if (u == null) return;
        if (!usb.hasPermission(u)) {
            askedUsb.remove(u.getDeviceName());
            Intent answer = new Intent("sk.lubeno.riffy.USB").setPackage(getPackageName());
            usb.requestPermission(u, PendingIntent.getBroadcast(this, 0, answer, PendingIntent.FLAG_IMMUTABLE));
            notice("Povoľte prístup a ťuknite znova.", null, null);
            return;
        }
        testing = true;
        engine.stop();
        usbInfo.setEnabled(false);
        status.setText("Hrajte na gitare, asi 5 sekúnd skúšam čítať z USB…");
        new Thread(() -> {
            String text = "Telefón: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                    + "\n" + UsbAudio.selfTest(usb, u) + UsbAudio.report(usb, u);
            ui.post(() -> {
                testing = false;
                usbInfo.setEnabled(true);
                status.setText("");
                startMonitor();
                showReport(text);
            });
        }, "popis").start();
    }

    void showReport(String text) {
        new AlertDialog.Builder(this)
                .setTitle("Popis USB zariadenia")
                .setMessage(text)
                .setPositiveButton("Poslať", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Riffy – popis USB zariadenia");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, "Poslať popis"));
                })
                .setNegativeButton("Zavrieť", null)
                .show();
    }

    // ---- Skúška vstupu cez Android ----

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
            return Source.levels(peak) + " — " + how + (routed ? "" : " (nahrávalo z: " + device + ")");
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
        engine.stop();
        AudioDeviceInfo dev = selected();
        int rate = rateFor(dev);
        testing = true;
        test.setText("Zastaviť skúšku");
        test.setOnClickListener(v -> testing = false);
        new Thread(() -> {
            List<Result> results = new ArrayList<>();
            int total = (SOURCE_IDS.length - 1) * FORMAT_IDS.length * 2, done = 0;
            for (int s = 1; s < SOURCE_IDS.length && testing; s++) {
                for (int e = 0; e < FORMAT_IDS.length && testing; e++) {
                    for (int ch = 1; ch <= 2 && testing; ch++) {
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
                            int[] buf = new int[rate / 20 * ch];
                            long begin = System.currentTimeMillis();
                            while (testing && System.currentTimeMillis() - begin < 1500) {
                                int n = c.read(buf);
                                if (n <= 0) break;
                                // Prvé chvíle po spustení bývajú tiché, nepočítajú sa.
                                if (System.currentTimeMillis() - begin > 300) Source.peaks(buf, n, ch, r.peak);
                            }
                            c.close();
                        }
                        results.add(r);
                    }
                }
            }
            ui.post(() -> {
                testing = false;
                test.setText("Nájsť nastavenie");
                test.setOnClickListener(v -> findSetup());
                status.setText("");
                startMonitor();
                showResults(results, dev);
            });
        }, "skuska").start();
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

    // ---- Zoznam nahrávok ----

    void setFilter(int f) {
        filter = f;
        prefs.edit().putInt("filter", f).apply();
        filterAll.setBackgroundResource(f == ALL ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        filterFav.setBackgroundResource(f == FAVORITES ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        filterSongs.setBackgroundResource(f == SONGS ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        render();
    }

    /** Počas hľadania sa skryje nahrávanie a nástroje, aby nad klávesnicou ostal zoznam. */
    void toggleSearch() {
        boolean show = search.getVisibility() != View.VISIBLE;
        search.setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.controls).setVisibility(show ? View.GONE : View.VISIBLE);
        tools.setVisibility(show ? View.GONE : View.VISIBLE);
        tunerPanel.setVisibility(!show && tunerOn ? View.VISIBLE : View.GONE);
        looperPanel.setVisibility(!show && looperOn ? View.VISIBLE : View.GONE);
        findViewById(R.id.scalePanel).setVisibility(!show && scaleOn ? View.VISIBLE : View.GONE);
        findViewById(R.id.backingPanel).setVisibility(!show && backingOn ? View.VISIBLE : View.GONE);
        updatePanels();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (show) {
            search.requestFocus();
            im.showSoftInput(search, 0);
        } else {
            search.setText("");
            im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

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
                r.info = meta.get(r.name);
                r.duration = c.getLong(2);
                if (r.duration <= 0) r.duration = wavLength(r.uri());
                r.added = c.getLong(3);
                riffs.add(r);
            }
        } catch (Exception ignored) {
        }
        render();
        importMeta();
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Nadpis piesne v zozname (ťuknutím sa pieseň prehrá po častiach). */
    static final class Song {
        final String name;

        Song(String name) {
            this.name = name;
        }
    }

    /** Zoznam podľa filtra a hľadania: s nadpismi dní, alebo po piesňach v ich poradí. */
    void render() {
        if (adapter == null) return;
        rows.clear();
        String q = fold(search.getText().toString().trim());
        List<Riff> shown = new ArrayList<>();
        for (Riff r : riffs) {
            if (isPending(r)) continue;
            if (filter == FAVORITES && !r.info.favorite) continue;
            if (filter == SONGS && r.info.song.isEmpty()) continue;
            if (!q.isEmpty() && !fold(label(r.name) + " " + r.name + " " + r.info.note + " " + r.info.key + " "
                    + r.info.song + " " + r.info.part + " " + r.info.tone).contains(q)) continue;
            shown.add(r);
        }
        if (filter == SONGS) {
            shown.sort((a, b) -> {
                int c = a.info.song.compareToIgnoreCase(b.info.song);
                return c != 0 ? c : a.info.order != b.info.order ? Integer.compare(a.info.order, b.info.order)
                        : Long.compare(a.added, b.added);
            });
            String last = null;
            for (Riff r : shown) {
                if (!r.info.song.equals(last)) {
                    rows.add(new Song(r.info.song));
                    last = r.info.song;
                }
                rows.add(r);
            }
        } else {
            String lastDay = null;
            for (Riff r : shown) {
                String day = day(r.added * 1000);
                if (!day.equals(lastDay)) {
                    rows.add(day);
                    lastDay = day;
                }
                rows.add(r);
            }
        }
        adapter.notifyDataSetChanged();
        boolean none = rows.isEmpty();
        empty.setText(riffs.isEmpty() ? "Zatiaľ tu nie sú žiadne riffy.\nNahrajte prvý veľkým červeným tlačidlom."
                : filter == FAVORITES && q.isEmpty() ? "Žiadne obľúbené riffy.\nHviezdičkou pri riffe ho pridáte."
                : filter == SONGS && q.isEmpty() ? "Žiadne piesne.\nRiff zaradíte do piesne cez ⋮ → Pieseň."
                : "Nič sa nenašlo.");
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    static String day(long ms) {
        Calendar c = Calendar.getInstance(), today = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int d = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
        int t = today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR);
        today.add(Calendar.DAY_OF_YEAR, -1);
        int y = today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR);
        if (d == t) return "Dnes";
        if (d == y) return "Včera";
        return DateFormat.format("d. M. yyyy", new Date(ms)).toString();
    }

    /** Automatické meno „Riff 2026-10-15 09.12.30“ sa ukáže ako „Riff 9:12“; dátum je v nadpise dňa. */
    static String label(String name) {
        Matcher m = AUTO_NAME.matcher(name);
        if (!m.matches()) return name;
        return m.group(1) + " " + Integer.parseInt(m.group(2)) + ":" + m.group(3) + (m.group(4) != null ? " ·" + m.group(4) : "");
    }

    class RiffAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int i) {
            return rows.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i) instanceof Riff ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int i) {
            return !(rows.get(i) instanceof String);
        }

        public View getView(int i, View v, ViewGroup parent) {
            Object row = rows.get(i);
            if (!(row instanceof Riff)) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(MainActivity.this);
                int pad = Math.round(4 * getResources().getDisplayMetrics().density);
                t.setPadding(pad * 2, pad * 4, pad, pad);
                boolean song = row instanceof Song;
                t.setTextSize(song ? 16 : 13);
                t.setText(song ? "🎵 " + ((Song) row).name + "   ▶\uFE0E" : (String) row);
                t.setTypeface(null, song ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                return t;
            }
            Riff r = (Riff) row;
            if (v == null) v = getLayoutInflater().inflate(R.layout.riff_row, parent, false);
            ((TextView) v.findViewById(R.id.rowPlay)).setText(r.id == playing && player != null && player.isPlaying() ? "⏸\uFE0E" : "▶\uFE0E");
            ((TextView) v.findViewById(R.id.rowTitle)).setText(filter == SONGS && !r.info.part.isEmpty()
                    ? r.info.part + "  ·  " + label(r.name) : label(r.name));
            StringBuilder line = new StringBuilder(clock(r.duration));
            if (r.info.bpm > 0) line.append(" · ").append(r.info.bpm).append(" BPM");
            if (!r.info.drums.isEmpty()) line.append(" · 🥁 ").append(r.info.drums);
            if (!r.info.tone.isEmpty()) line.append(" · 🎛 ").append(r.info.tone);
            if (!r.info.song.isEmpty() && filter != SONGS) line.append(" · 🎵 ").append(r.info.song)
                    .append(r.info.part.isEmpty() ? "" : " – " + r.info.part);
            if (!r.info.markers.isEmpty()) line.append(" · 📍").append(r.info.markers.size());
            if (!r.info.key.isEmpty()) line.append(" · ").append(r.info.key);
            if (!r.info.note.isEmpty()) line.append("\n").append(r.info.note);
            ((TextView) v.findViewById(R.id.rowMeta)).setText(line);
            TextView star = v.findViewById(R.id.rowStar);
            star.setText(r.info.favorite ? "★" : "☆");
            star.setTextColor(r.info.favorite ? 0xFFF9A825 : 0x99808080);
            star.setOnClickListener(x -> {
                r.info.favorite = !r.info.favorite;
                saveMeta(r);
                render();
            });
            View menu = v.findViewById(R.id.rowMenu);
            menu.setOnClickListener(x -> options(r));
            boolean selecting = !selection.isEmpty(), chosen = selection.contains(r.id);
            menu.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
            if (selecting) ((TextView) v.findViewById(R.id.rowPlay)).setText(chosen ? "☑" : "☐");
            v.setBackgroundColor(chosen ? Look.alpha(Look.accent(MainActivity.this), 0x33) : 0);
            ((WaveView) v.findViewById(R.id.rowWave)).setPeaks(wave(r));
            return v;
        }
    }

    /** Náhľad priebehu riffu; kým sa počíta, null (zoznam sa potom prekreslí). */
    byte[] wave(Riff r) {
        return Waves.get(getCacheDir(), r.id + "_" + r.duration, () -> {
            android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(r.uri(), "r");
            if (fd == null) throw new IOException("Súbor sa nedá otvoriť.");
            return new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
        }, () -> ui.post(() -> {
            if (adapter != null) adapter.notifyDataSetChanged();
            if (playing == r.id) playerWave.setPeaks(wave(r));
        }));
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

    // ---- Prehrávač: rýchlosť bez zmeny ladenia, slučka od–do ----

    void setupPlayer() {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SPEED_NAMES);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        speed.setAdapter(a);
        speed.setSelection(Math.max(0, Math.min(prefs.getInt("speed", 4), SPEEDS.length - 1)));
        speed.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("speed", pos).apply();
                applySpeed();
                showPlayerInfo();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        playDrums.setChecked(prefs.getBoolean("playDrums", false));
        playDrums.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("playDrums", on).apply();
            followPlayer();
        });
        loop.setChecked(prefs.getBoolean("loop", false));
        loop.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("loop", on).apply();
            showPlayerInfo();
        });
        playPause.setOnClickListener(v -> togglePause());
        findViewById(R.id.playerClose).setOnClickListener(v -> stopPlaying());
        playerExpand.setOnClickListener(v -> expandPlayer(playerMore.getVisibility() != View.VISIBLE));
        findViewById(R.id.playerMain).setOnClickListener(v -> expandPlayer(playerMore.getVisibility() != View.VISIBLE));
        playerWave.setListener(new WaveView.Listener() {
            public void onSeek(float at) {
                if (player == null) return;
                int pos = Math.round(at * player.getDuration());
                // Ťuknutie mimo slučky ju zruší, inak by prehrávanie hneď skočilo späť.
                if ((loopStart >= 0 && pos < loopStart) || (loopEnd >= 0 && pos > loopEnd)) {
                    loopStart = loopEnd = -1;
                    showLoop();
                }
                player.seekTo(pos);
                showProgress();
            }

            public void onSelect(float from, float to) {
                if (player == null) return;
                int dur = player.getDuration();
                loopStart = Math.round(from * dur);
                loopEnd = to >= 0.995f ? -1 : Math.round(to * dur);
                loop.setChecked(true);
                player.seekTo(loopStart);
                if (!player.isPlaying()) resume();
                showLoop();
            }
        });
        findViewById(R.id.loopA).setOnClickListener(v -> {
            if (player == null) return;
            loopStart = player.getCurrentPosition();
            if (loopEnd >= 0 && loopEnd <= loopStart) loopEnd = -1;
            showLoop();
        });
        findViewById(R.id.loopB).setOnClickListener(v -> {
            if (player == null) return;
            int pos = player.getCurrentPosition();
            if (pos <= Math.max(0, loopStart) + 100) return;
            loopEnd = pos;
            loop.setChecked(true);
            player.seekTo(Math.max(0, loopStart));
            showLoop();
        });
        findViewById(R.id.loopClear).setOnClickListener(v -> {
            loopStart = loopEnd = -1;
            showLoop();
        });
    }

    void expandPlayer(boolean expand) {
        playerMore.setVisibility(expand ? View.VISIBLE : View.GONE);
        playerExpand.setText(expand ? "⌄" : "⌃");
        playerExpand.setContentDescription(expand ? "Zbaliť prehrávač" : "Rozbaliť prehrávač");
    }

    void showPlayerInfo() {
        float s = speed();
        playerInfo.setText((s == 1f ? "" : SPEED_NAMES[speed.getSelectedItemPosition()]) + (loop.isChecked() ? " ⟳" : ""));
    }

    float speed() {
        return SPEEDS[Math.max(0, speed.getSelectedItemPosition())];
    }

    /** Rýchlosť sa mení bez zmeny výšky tónu; nastavuje sa len počas hrania (inak by sa prehrávanie spustilo). */
    void applySpeed() {
        if (player == null || !player.isPlaying()) return;
        try {
            PlaybackParams p = player.getPlaybackParams();
            player.setPlaybackParams(p.setSpeed(speed()).setPitch(1f));
        } catch (Exception ignored) {
        }
    }

    void play(Riff r) {
        if (r.id == playing) {
            togglePause();
            return;
        }
        stopPlaying();
        if (engine.recording()) return;
        // Voľne hrajúce bicie by išli mimo riffu; k riffu sa zapínajú v prehrávači.
        if (DRUMS.running()) {
            DRUMS.stop();
            DRUMS.backing = null;
            LOOPER.cancel();
            showTools();
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(this, r.uri());
            player.setOnCompletionListener(p -> ended());
            AudioDeviceInfo out = playbackDevice();
            if (out != null) player.setPreferredDevice(out);
            player.prepare();
            playing = r.id;
            loopStart = loopEnd = -1;
            playerTitle.setText(label(r.name));
            playerProgress.setMax(player.getDuration());
            playerWave.setPeaks(wave(r));
            playerPanel.setVisibility(View.VISIBLE);
            playingRiff = r;
            playDrums.setText(r.info.bpm > 0 ? "🥁 Bicie k riffu (" + drumStyleOf(r) + ", " + r.info.bpm + " BPM)"
                    : "🥁 Bicie k riffu (tempo zistím z hrania)");
            showMarks();
            showLoop();
            showPlayerInfo();
            resume();
        } catch (Exception e) {
            stopPlaying();
            notice("Nahrávka sa nedá prehrať.", null, null);
        }
        adapter.notifyDataSetChanged();
    }

    void resume() {
        if (player == null) return;
        player.start();
        applySpeed();
        tickClock();
        followPlayer();
        playPause.setText("⏸\uFE0E");
        ui.removeCallbacks(progress);
        ui.post(progress);
        adapter.notifyDataSetChanged();
    }

    void togglePause() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            tickClock();
            playPause.setText("▶\uFE0E");
            adapter.notifyDataSetChanged();
        } else {
            resume();
        }
    }

    /** Koniec nahrávky alebo slučky: dookola znova od začiatku slučky, inak zastaviť. */
    void ended() {
        if (player == null) return;
        if (!queue.isEmpty() && !loop.isChecked()) {
            // Ďalšia časť piesne
            Riff next = queue.remove(0);
            ui.post(() -> {
                List<Riff> rest = new ArrayList<>(queue);
                play(next);
                queue.clear();
                queue.addAll(rest);
            });
            return;
        }
        if (loop.isChecked()) {
            player.seekTo(Math.max(0, loopStart));
            resume();
        } else {
            player.pause();
            player.seekTo(Math.max(0, loopStart));
            playPause.setText("▶\uFE0E");
            adapter.notifyDataSetChanged();
        }
    }

    void showProgress() {
        if (player == null) return;
        int pos = player.getCurrentPosition(), dur = player.getDuration();
        if (loopEnd > 0 && pos >= loopEnd && player.isPlaying()) {
            ended();
            pos = player.getCurrentPosition();
        }
        tickClock();
        playerProgress.setProgress(pos);
        playerWave.setPosition(dur > 0 ? pos / (float) dur : -1);
        playerTime.setText(time(pos, 0) + " / " + time(dur, 0));
        if (player.isPlaying()) ui.postDelayed(progress, 50);
    }

    void showLoop() {
        int dur = player == null ? 0 : player.getDuration();
        playerWave.setLoop(dur <= 0 || loopStart < 0 ? -1 : loopStart / (float) dur,
                dur <= 0 || loopEnd < 0 ? -1 : loopEnd / (float) dur);
        loopText.setText(loopStart < 0 && loopEnd < 0 ? "Slučka: celá nahrávka · potiahnutím po vlnke vyznačíte časť"
                : "Slučka: " + time(Math.max(0, loopStart), 1) + " – "
                + (loopEnd < 0 ? "koniec" : time(loopEnd, 1)));
    }

    /** Čas m:ss, s desatinami sekundy pre slučku. */
    static String time(long ms, int decimals) {
        long s = ms / 1000;
        if (decimals == 0) return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d,%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    /** Prehráva sa do slúchadiel, Bluetooth alebo reproduktora telefónu, nie do USB zariadenia, z ktorého sa nahráva. */
    AudioDeviceInfo playbackDevice() {
        AudioDeviceInfo speaker = null, other = null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    if (other == null) other = d;
                    break;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                    speaker = d;
                    break;
                default:
            }
        }
        return other != null ? other : speaker;
    }

    void stopPlaying() {
        ui.removeCallbacks(progress);
        playingRiff = null;
        stopFollowing();
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        playing = -1;
        if (playerPanel != null) {
            playerPanel.setVisibility(View.GONE);
            expandPlayer(false);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    Riff playingRiff;

    String drumStyleOf(Riff r) {
        for (String s : Drums.STYLES) if (s.equals(r.info.drums)) return s;
        return Drums.STYLES[prefs.getInt("drumStyle", 0)];
    }

    /** Poloha prehrávača pre bicie (MediaPlayer sa číta len tu, na hlavnom vlákne). */
    void tickClock() {
        if (player == null) {
            clockPlaying = false;
            return;
        }
        clockPos = player.getCurrentPosition();
        clockAt = SystemClock.uptimeMillis();
        clockSpeed = speed();
        clockPlaying = player.isPlaying();
    }

    /** Bicie k prehrávanému riffu: v jeho tempe a štýle, od jeho prvej doby. */
    void followPlayer() {
        Riff r = playingRiff;
        if (r != null && r.info.bpm <= 0 && playDrums.isChecked() && player != null) {
            playDrums.setChecked(false);
            detectTempo(r, () -> {
                if (playingRiff == r) {
                    playDrums.setText("🥁 Bicie k riffu (" + drumStyleOf(r) + ", " + r.info.bpm + " BPM)");
                    playDrums.setChecked(true);
                }
            });
            return;
        }
        boolean want = r != null && r.info.bpm > 0 && playDrums.isChecked() && player != null;
        if (!want) {
            stopFollowing();
            return;
        }
        if (DRUMS.following()) return;
        DRUMS.bpm = r.info.bpm;
        DRUMS.style = Math.max(0, Drums.styleIndex(drumStyleOf(r)));
        tickClock();
        DRUMS.follow(this, new Output.Track(playbackDevice()), playerClock, Math.max(0, r.info.beat0));
        showTools();
        if (!r.info.aligned) align(r);
    }

    /**
     * Doladí, kde v riffe sú doby, podľa úderov v nahrávke (raz; potom je to uložené pri riffe).
     * Odhad pri nahrávaní nevie o všetkých oneskoreniach v ToneX a v telefóne.
     */
    void align(Riff r) {
        Uri uri = r.uri();
        int bpm0 = r.info.bpm, base = Math.max(0, r.info.beat0);
        IO.execute(() -> {
            try {
                int rate;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    rate = Beat.rateOf(in);
                }
                float[] x;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    x = Beat.mono(in, 0, rate * 60L);
                }
                int shift = Beat.shiftMs(Beat.onsets(x, rate), base, bpm0);
                int bar = Math.round(60000f * Drums.PATTERNS[Math.max(0, Drums.styleIndex(drumStyleOf(r)))].beats / bpm0);
                int fixed = Math.floorMod(base + shift, bar);
                ui.post(() -> {
                    r.info.beat0 = fixed;
                    r.info.aligned = true;
                    saveMeta(r);
                    if (playingRiff == r && DRUMS.following()) DRUMS.setFirstBeat(fixed);
                });
            } catch (Exception ignored) {
            }
        });
    }

    void stopFollowing() {
        if (!DRUMS.following()) return;
        DRUMS.stop();
        DRUMS.bpm = bpm;
        DRUMS.style = prefs.getInt("drumStyle", 0);
        if (drumChip != null) showTools();
    }

    // ---- Looper ----

    /** Vstup (vlákno vstupu): looper si z neho berie vrstvy, automatické nahrávanie počúva hranie. */
    void onInput(int[] buf, int n, int channels, int rate) {
        LOOPER.onAudio(buf, n, channels, rate);
        autoListen(buf, n, channels, rate);
    }

    /**
     * Automatické nahrávanie (vlákno vstupu): keď je aspoň 0,15 s hlasnejšie než -30 dB, nahrávanie
     * sa začne aj so sekundou pred tým; keď je 4 s tichšie než -42 dB, skončí (len to, čo začalo samo).
     */
    void autoListen(int[] buf, int n, int channels, int rate) {
        if (!autoOn || testing || n == 0) return;
        int peak = 0;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i] >> 16));
        double db = 20 * Math.log10(Math.max(1, peak) / 32768.0);
        long ms = n / Math.max(1, channels) * 1000L / Math.max(1, rate);
        boolean rec = engine.recording();
        if (!rec) {
            loudMs = db > -30 ? loudMs + ms : 0;
            // Pri looperi sa hrá do slučky, nie do nahrávky.
            if (loudMs >= 150 && !counting && LOOPER.state() == LoopStation.EMPTY && !LOOPER.hasLoop()) {
                loudMs = 0;
                ui.post(() -> {
                    if (!autoOn || engine.recording() || counting || !resumed) return;
                    autoStarted = true;
                    beginRecording(false, 1.0);
                });
            }
        } else if (autoStarted) {
            quietMs = db < -42 ? quietMs + ms : 0;
            if (quietMs >= 4000) {
                quietMs = 0;
                autoStarted = false;
                ui.post(() -> {
                    if (engine.recording()) finishRecording();
                });
            }
        }
    }

    void showAutoHint() {
        if (engine.recording() || counting) return;
        recHint.setText(autoOn ? "Automatické nahrávanie: začne sa, keď začnete hrať." : "Ťuknutím zastavíte a uložíte");
        recHint.setVisibility(autoOn ? View.VISIBLE : View.GONE);
    }

    void setupLooper() {
        looperChip.setOnClickListener(v -> setLooper(!looperOn));
        LinearLayout row = findViewById(R.id.looperBars);
        float d = getResources().getDisplayMetrics().density;
        LOOPER.bars = prefs.getInt("loopBars", 2);
        for (int b : new int[]{1, 2, 4, 8}) {
            TextView chipB = new TextView(this);
            chipB.setText(String.valueOf(b));
            chipB.setGravity(Gravity.CENTER);
            chipB.setTag(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(48 * d), Math.round(36 * d));
            lp.rightMargin = Math.round(8 * d);
            chipB.setOnClickListener(v -> {
                if (LOOPER.hasLoop() || LOOPER.state() != LoopStation.EMPTY) {
                    notice("Dĺžku zmeníte, keď slučku vymažete.", null, null);
                    return;
                }
                LOOPER.bars = b;
                prefs.edit().putInt("loopBars", b).apply();
                showLooper();
            });
            row.addView(chipB, lp);
        }
        looperMain.setOnClickListener(v -> looperAction());
        looperUndo.setOnClickListener(v -> {
            LOOPER.undo();
            showLooper();
        });
        findViewById(R.id.looperMore).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Slučka")
                .setItems(new String[]{"Uložiť ako riff", "Vymazať slučku"}, (x, which) -> {
                    if (which == 0) saveLoop();
                    else {
                        LOOPER.clear();
                        if (DRUMS.mode == Drums.OFF && !DRUMS.following()) DRUMS.stop();
                        showLooper();
                        showTools();
                    }
                })
                .show());
        LOOPER.changed = () -> ui.post(this::showLooper);
    }

    void setLooper(boolean on) {
        looperOn = on;
        looperPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        showLooper();
        showTools();
        updatePanels();
    }

    /** Hlavné tlačidlo: nahrať slučku, pridať vrstvu, zrušiť čakanie, alebo slučku znova pustiť. */
    void looperAction() {
        int st = LOOPER.state();
        if (st == LoopStation.COUNTING || st == LoopStation.ARMED || st == LoopStation.RECORDING || st == LoopStation.OVERDUB) {
            LOOPER.cancel();
            if (!LOOPER.hasLoop() && DRUMS.mode == Drums.OFF) DRUMS.stop();
            showLooper();
            return;
        }
        if (!engine.running()) {
            notice("Vstup nebeží.", null, null);
            return;
        }
        if (engine.recording()) {
            notice("Najprv dokončite nahrávanie.", null, null);
            return;
        }
        stopPlaying();
        boolean free = DRUMS.running() && !DRUMS.following();
        if (!free) {
            // Odpočet paličkami; potom hrá, čo bolo zapnuté (alebo len slučka).
            stopFollowing();
            DRUMS.bpm = bpm;
            DRUMS.start(this, output(), Drums.OFF, true);
        }
        if (LOOPER.hasLoop() && !free) {
            showLooper();
            showTools();
            return;
        }
        // Prehrávač potrebuje chvíľu, kým zaznie prvý zvuk.
        ui.postDelayed(() -> {
            boolean ok = LOOPER.hasLoop() ? LOOPER.recordLayer() : LOOPER.recordFirst();
            if (!ok) notice("Looper sa nedá spustiť, skúste znova.", null, null);
            showLooper();
            showTools();
        }, free ? 0 : 150);
    }

    void showLooper() {
        int st = LOOPER.state();
        boolean live = DRUMS.running() && !DRUMS.following();
        boolean has = LOOPER.hasLoop();
        String bars = LOOPER.bars + (LOOPER.bars == 1 ? " takt" : LOOPER.bars < 5 ? " takty" : " taktov");
        switch (st) {
            case LoopStation.COUNTING:
                looperMain.setText("Zrušiť");
                looperText.setText("Pripravte sa: po odpočte sa nahrá presne " + bars + ". Nemusíte nič stláčať.");
                break;
            case LoopStation.RECORDING:
                looperMain.setText("Zrušiť");
                looperText.setText("Hrajte. Nahrávanie skončí samo na konci " + (LOOPER.bars == 1 ? "taktu" : "posledného taktu")
                        + " a slučka začne hrať dookola.");
                break;
            case LoopStation.ARMED:
                looperMain.setText("Zrušiť");
                looperText.setText("Ďalšia vrstva sa začne nahrávať od začiatku slučky, presne na jedno kolo.");
                break;
            case LoopStation.OVERDUB:
                looperMain.setText("Zrušiť");
                looperText.setText("Nahrávam vrstvu, skončí sa sama na konci kola.");
                break;
            default:
                if (!has) {
                    looperMain.setText("● Nahrať slučku");
                    looperText.setText("Zvoľte dĺžku slučky. Po ťuknutí zaznie odpočet a nahrá sa presne " + bars
                            + " v tempe " + bpm + ". Metronóm alebo bicie si môžete zapnúť aj počas hrania.");
                } else if (!live) {
                    looperMain.setText("▶ Pustiť slučku");
                    looperText.setText("Slučka je pripravená. Po odpočte bude hrať dookola.");
                } else {
                    looperMain.setText("● Pridať vrstvu");
                    looperText.setText("Slučka hrá dookola. Ďalšia vrstva sa nahrá presne na jedno kolo od jej začiatku. "
                            + "Späť odoberie poslednú vrstvu; ⋮ ju uloží ako riff.");
                }
        }
        looperUndo.setEnabled(has && st == LoopStation.PLAYING && LOOPER.layers() > 0);
        LinearLayout row = findViewById(R.id.looperBars);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getTag() instanceof Integer) {
                c.setBackgroundResource((Integer) c.getTag() == LOOPER.bars ? R.drawable.bg_chip_on : R.drawable.bg_chip);
                c.setAlpha(has ? 0.5f : 1f);
            }
        }
        looperTick();
    }

    void looperTick() {
        ui.removeCallbacks(looperTick);
        if (!looperOn) return;
        int st = LOOPER.state();
        boolean waiting = st == LoopStation.COUNTING || st == LoopStation.ARMED;
        boolean recording = st == LoopStation.RECORDING || st == LoopStation.OVERDUB;
        loopView.set(LOOPER.bars, DRUMS.beatsPerBar(), LOOPER.layers(), LOOPER.position(), waiting,
                LOOPER.beatsToStart(), recording, LOOPER.recorded());
        ui.postDelayed(looperTick, 40);
    }

    /** Slučka ako nový riff (48 kHz, s tempom a štýlom bicích). */
    void saveLoop() {
        float[] m = LOOPER.mixdown();
        if (m == null) {
            notice("Slučka je prázdna.", null, null);
            return;
        }
        int tempo = bpm, beats = DRUMS.beatsPerBar();
        String style = DRUMS.mode == Drums.DRUMS ? Drums.STYLES[DRUMS.style] : "";
        long now = System.currentTimeMillis();
        IO.execute(() -> {
            File temp = new File(getCacheDir(), "slucka-" + now + ".wav");
            try {
                Wav.Writer w = new Wav.Writer(temp, 2, LoopStation.RATE);
                int[] buf = new int[4096 * 2];
                for (int at = 0; at < m.length; at += 4096) {
                    int n = Math.min(4096, m.length - at);
                    for (int i = 0; i < n; i++) {
                        int v = Source.clamp(m[at + i] * 2147483647.0);
                        buf[2 * i] = v;
                        buf[2 * i + 1] = v;
                    }
                    w.write(buf, 0, n * 2);
                }
                w.finish(LoopStation.RATE);
                String saved = save(temp, "Slučka " + DateFormat.format("yyyy-MM-dd HH.mm.ss", new Date(now)), tempo,
                        new Groove(style, 0, false, beats));
                ui.post(() -> savedNotice(saved));
            } catch (Exception e) {
                ui.post(() -> notice("Slučku sa nepodarilo uložiť.", null, null));
            } finally {
                temp.delete();
                ui.post(this::loadRiffs);
            }
        });
    }

    // ---- Stupnica ----

    boolean scaleOn;
    /** Poloha na hmatníku: -1 = celý, inak poradie polohy. */
    int scalePos = -1;

    /** Štítok (ako filtre a takty): text, zapnutý, čo urobí ťuknutie. */
    TextView chip(String text, boolean on, Runnable click) {
        float d = getResources().getDisplayMetrics().density;
        TextView c = new TextView(this);
        c.setText(text);
        c.setGravity(Gravity.CENTER);
        c.setMinWidth(Math.round(40 * d));
        c.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);
        c.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        c.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * d));
        lp.rightMargin = Math.round(6 * d);
        c.setLayoutParams(lp);
        return c;
    }

    void setupScale() {
        findViewById(R.id.scaleChip).setOnClickListener(v -> setScalePanel(!scaleOn));
    }

    void setScalePanel(boolean on) {
        scaleOn = on;
        findViewById(R.id.scalePanel).setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) showScale();
        showTools();
        backingTick();
        updatePanels();
    }

    /** Tónina a stupnica si človek volí sám (alebo ich nastaví riff či sprievod). */
    void setScale(int root, int type) {
        prefs.edit().putInt("scaleRoot", Math.floorMod(root, 12)).putInt("scaleType", type).apply();
        scalePos = -1;
        showScale();
    }

    void showScale() {
        int root = prefs.getInt("scaleRoot", 4), type = prefs.getInt("scaleType", 0);
        int[] strings = Tunings.strings(prefs.getInt("tuning", 0));
        LinearLayout roots = findViewById(R.id.scaleRoots);
        roots.removeAllViews();
        for (int i = 0; i < 12; i++) {
            int pc = i;
            roots.addView(chip(Scales.ROOTS[i], i == root, () -> setScale(pc, prefs.getInt("scaleType", 0))));
        }
        LinearLayout types = findViewById(R.id.scaleTypes);
        types.removeAllViews();
        for (int i = 0; i < Scales.NAMES.length; i++) {
            int t = i;
            types.addView(chip(Scales.NAMES[i], i == type, () -> setScale(prefs.getInt("scaleRoot", 4), t)));
        }
        List<Integer> pos = Scales.positions(strings, root, type);
        if (scalePos >= pos.size()) scalePos = -1;
        LinearLayout row = findViewById(R.id.scalePositions);
        row.removeAllViews();
        row.addView(chip("Celý hmatník", scalePos < 0, () -> {
            scalePos = -1;
            showScale();
        }));
        for (int k = 0; k < pos.size(); k++) {
            int index = k;
            row.addView(chip("Poloha " + (k + 1), k == scalePos, () -> {
                scalePos = index;
                showScale();
            }));
        }
        ((ScaleView) findViewById(R.id.scaleView)).set(strings, root, Scales.notes(root, type),
                scalePos < 0 ? -1 : pos.get(scalePos));
        ((TextView) findViewById(R.id.scaleText)).setText(Scales.ROOTS[root] + " " + Scales.NAMES[type].toLowerCase(Locale.ROOT)
                + ": " + Scales.list(root, type) + "   ·   ladenie " + Tunings.NAMES[prefs.getInt("tuning", 0)]);
    }

    /** Stupnica k riffu: tónina z údajov riffu, inak sa zistí z nahrávky; potom si ju dá človek zmeniť. */
    void scaleFor(Riff r) {
        int[] k = Scales.parseKey(r.info.key);
        if (k != null) {
            setScale(k[0], k[1] == 1 ? Scales.MINOR_PENTA : Scales.MAJOR_PENTA);
            setScalePanel(true);
            return;
        }
        notice("Zisťujem tóninu…", null, null);
        Uri uri = r.uri();
        IO.execute(() -> {
            int[] key = null;
            try {
                int rate;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    rate = Beat.rateOf(in);
                }
                float[] x;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    x = Beat.mono(in, 0, rate * 60L);
                }
                key = Scales.detectKey(Scales.chroma(x, rate));
            } catch (Exception ignored) {
            }
            int[] found = key;
            ui.post(() -> {
                if (found == null) {
                    notice("Tóninu sa nepodarilo zistiť – vyberte ju v paneli stupnice.", null, null);
                    setScalePanel(true);
                    return;
                }
                r.info.key = Scales.key(found[0], found[1] == 1);
                saveMeta(r);
                render();
                setScale(found[0], found[1] == 1 ? Scales.MINOR_PENTA : Scales.MAJOR_PENTA);
                setScalePanel(true);
                notice("Tónina riffu: " + Scales.ROOTS[found[0]] + (found[1] == 1 ? " mol" : " dur")
                        + " (zmeniť ju môžete v paneli)", null, null);
            });
        });
    }

    /**
     * Keď je otvorený niektorý nástroj (ladička, looper, stupnica, sprievod), dostane miesto zoznamu
     * riffov a dá sa posúvať; inak je vidno zoznam.
     */
    void updatePanels() {
        boolean any = tunerOn || looperOn || scaleOn || backingOn;
        boolean searching = search.getVisibility() == View.VISIBLE, rec = engine.recording();
        findViewById(R.id.panels).setVisibility(any && !searching && !rec ? View.VISIBLE : View.GONE);
        listArea.setVisibility(rec || (any && !searching) ? View.GONE : View.VISIBLE);
    }

    // ---- Sprievod ----

    boolean backingOn;
    final Runnable backingTick = this::backingTick;

    void setupBacking() {
        findViewById(R.id.backingChip).setOnClickListener(v -> {
            backingOn = !backingOn;
            findViewById(R.id.backingPanel).setVisibility(backingOn ? View.VISIBLE : View.GONE);
            if (backingOn) showBacking();
            showTools();
            updatePanels();
        });
        findViewById(R.id.backPlay).setOnClickListener(v -> backingPlay());
        findViewById(R.id.backScale).setOnClickListener(v -> {
            setScale(prefs.getInt("backKey", 4), prefs.getBoolean("backMinor", true) ? Scales.MINOR_PENTA : Scales.MAJOR_PENTA);
            setScalePanel(true);
            View panel = findViewById(R.id.scalePanel);
            panel.post(() -> ((android.widget.ScrollView) findViewById(R.id.panels)).smoothScrollTo(0, panel.getTop()));
        });
    }

    int[][] progression() {
        return Backing.decode(prefs.getString("backProg", Backing.encode(Backing.PRESET_CHORDS[4])));
    }

    Backing buildBacking() {
        return new Backing(prefs.getInt("backKey", 4), progression(), prefs.getInt("backBars", 1),
                prefs.getBoolean("backBass", true), prefs.getBoolean("backKeys", true));
    }

    /** Zmena počas hrania platí hneď. */
    void backingChanged() {
        if (DRUMS.backing != null) {
            Backing b = buildBacking();
            IO.execute(() -> prewarm(b));
            DRUMS.backing = b;
        }
        showBacking();
    }

    /** Zvuky basy a kláves sa pripravia vopred, aby sa pri prvom akorde nezasekol zvuk. */
    static void prewarm(Backing b) {
        for (int i = 0; i < b.roots.length; i++) {
            Backing.bass(Backing.bassMidi(b.roots[i]));
            for (int m : Backing.voicing(b.roots[i], b.kinds[i])) Backing.key(m);
        }
    }

    void backingPlay() {
        if (DRUMS.backing != null) {
            DRUMS.backing = null;
            if (DRUMS.mode == Drums.OFF && !LOOPER.hasLoop() && LOOPER.state() == LoopStation.EMPTY) DRUMS.stop();
            showBacking();
            showTools();
            return;
        }
        Backing b = buildBacking();
        boolean drums = prefs.getBoolean("backDrums", true);
        IO.execute(() -> {
            prewarm(b);
            ui.post(() -> {
                boolean free = DRUMS.running() && !DRUMS.following();
                if (!free) {
                    stopPlaying();
                    stopFollowing();
                    DRUMS.bpm = bpm;
                    DRUMS.start(this, output(), drums ? Drums.DRUMS : Drums.OFF, true);
                } else if (drums && DRUMS.mode == Drums.OFF) {
                    DRUMS.mode = Drums.DRUMS;
                }
                DRUMS.backing = b;
                showBacking();
                showTools();
            });
        });
    }

    void showBacking() {
        int key = prefs.getInt("backKey", 4);
        boolean minor = prefs.getBoolean("backMinor", true);
        int preset = prefs.getInt("backPreset", 4);
        LinearLayout presets = findViewById(R.id.backPresets);
        presets.removeAllViews();
        presets.addView(chip("Tónina: " + Scales.ROOTS[key] + (minor ? " mol" : " dur") + " ▾", false, this::backingKeyDialog));
        for (int i = 0; i < Backing.PRESETS.length; i++) {
            int index = i;
            presets.addView(chip(Backing.PRESETS[i], i == preset, () -> {
                prefs.edit().putInt("backPreset", index).putString("backProg", Backing.encode(Backing.PRESET_CHORDS[index]))
                        .putBoolean("backMinor", Backing.PRESET_MINOR[index])
                        .putInt("backBars", index == 3 ? 1 : prefs.getInt("backBars", 1)).apply();
                backingChanged();
            }));
        }
        int[][] prog = progression();
        int now = DRUMS.backing != null ? DRUMS.chordNow() : -1;
        LinearLayout chords = findViewById(R.id.backChords);
        chords.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < prog.length; i++) {
            int index = i;
            TextView c = chip(Backing.name(Math.floorMod(key + prog[i][0], 12), prog[i][1]), i == now, () -> editChord(index));
            c.setTextSize(18);
            c.setMinWidth(Math.round(56 * d));
            c.setTag("chord" + i);
            chords.addView(c);
        }
        chords.addView(chip("+", false, () -> editChord(-1)));
        LinearLayout opts = findViewById(R.id.backOptions);
        opts.removeAllViews();
        int bars = prefs.getInt("backBars", 1);
        opts.addView(chip(bars == 1 ? "Akord na 1 takt" : "Akord na 2 takty", false, () -> {
            prefs.edit().putInt("backBars", bars == 1 ? 2 : 1).apply();
            backingChanged();
        }));
        opts.addView(chip("Basa", prefs.getBoolean("backBass", true), () -> {
            prefs.edit().putBoolean("backBass", !prefs.getBoolean("backBass", true)).apply();
            backingChanged();
        }));
        opts.addView(chip("Klávesy", prefs.getBoolean("backKeys", true), () -> {
            prefs.edit().putBoolean("backKeys", !prefs.getBoolean("backKeys", true)).apply();
            backingChanged();
        }));
        boolean drums = prefs.getBoolean("backDrums", true);
        opts.addView(chip("Bicie: " + Drums.STYLES[prefs.getInt("drumStyle", 0)], drums, () -> {
            boolean on = !prefs.getBoolean("backDrums", true);
            prefs.edit().putBoolean("backDrums", on).apply();
            if (DRUMS.backing != null && !DRUMS.following()) DRUMS.mode = on ? Drums.DRUMS : Drums.OFF;
            showBacking();
            showTools();
        }));
        boolean playing = DRUMS.backing != null;
        ((Button) findViewById(R.id.backPlay)).setText(playing ? "■ Zastaviť" : "▶ Hrať");
        ((TextView) findViewById(R.id.backText)).setText(playing
                ? "Hrá v tempe " + bpm + ". Ťuknutím na akord ho zmeníte. Stupnica k tomu ukáže, čo sa hodí do sóla – zelené sú tóny práve hraného akordu."
                : "Vyberte postup a tóninu, potom Hrať. Štýl bicích a tempo nastavíte podržaním 🥁 a ♩. Sprievod sedí aj s looperom.");
        backingTick();
    }

    /** Zvýrazní práve hraný akord (aj na hmatníku stupnice). */
    void backingTick() {
        ui.removeCallbacks(backingTick);
        Backing b = DRUMS.backing;
        int now = b != null ? DRUMS.chordNow() : -1;
        LinearLayout chords = findViewById(R.id.backChords);
        for (int i = 0; i < chords.getChildCount(); i++) {
            View c = chords.getChildAt(i);
            if (c.getTag() != null) c.setBackgroundResource(("chord" + now).equals(c.getTag()) ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        }
        ScaleView sv = findViewById(R.id.scaleView);
        sv.setChord(now >= 0 ? Backing.tones(b.roots[now], b.kinds[now]) : new boolean[12]);
        if (b != null && (backingOn || scaleOn)) ui.postDelayed(backingTick, 80);
    }

    void backingKeyDialog() {
        String[] keys = new String[24];
        for (int i = 0; i < 12; i++) {
            keys[2 * i] = Scales.ROOTS[i] + " dur";
            keys[2 * i + 1] = Scales.ROOTS[i] + " mol";
        }
        new AlertDialog.Builder(this)
                .setTitle("Tónina sprievodu")
                .setItems(keys, (x, which) -> {
                    prefs.edit().putInt("backKey", which / 2).putBoolean("backMinor", which % 2 == 1).apply();
                    backingChanged();
                })
                .show();
    }

    /** Zmena akordu v postupe (index -1 = pridať na koniec). */
    void editChord(int index) {
        int key = prefs.getInt("backKey", 4);
        int[][] options = Backing.diatonic(prefs.getBoolean("backMinor", true));
        List<String> names = new ArrayList<>();
        for (int[] o : options) names.add(Backing.name(Math.floorMod(key + o[0], 12), o[1]));
        if (index >= 0) names.add("Odstrániť");
        new AlertDialog.Builder(this)
                .setTitle(index < 0 ? "Pridať akord" : "Zmeniť akord")
                .setItems(names.toArray(new String[0]), (x, which) -> {
                    List<int[]> prog = new ArrayList<>(java.util.Arrays.asList(progression()));
                    if (which >= options.length) {
                        if (prog.size() > 1) prog.remove(index);
                    } else if (index < 0) {
                        prog.add(options[which]);
                    } else {
                        prog.set(index, options[which]);
                    }
                    prefs.edit().putString("backProg", Backing.encode(prog.toArray(new int[0][]))).putInt("backPreset", -1).apply();
                    backingChanged();
                })
                .show();
    }

    // ---- Ladička ----

    /** Naladené struny (index 0 = 6. struna) a od kedy sa drží tón v ladení (ms), aby fajka nebola náhodná. */
    final boolean[] tuned = new boolean[6];
    long inTuneSince;
    int inTuneString = -1;

    void setTuner(boolean on) {
        tunerOn = on;
        showTools();
        tunerPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        updatePanels();
        tunerView.clear();
        if (!on) {
            engine.tuner = null;
            return;
        }
        java.util.Arrays.fill(tuned, false);
        showTunings();
        tunerText.setText("Zahrajte jednu strunu.");
        Tuner t = new Tuner();
        t.a4 = prefs.getInt("a4", 440);
        long[] lastSeen = {0};
        t.listener = (p, db) -> ui.post(() -> {
            if (!tunerOn) return;
            ((TextView) findViewById(R.id.tunerLevel)).setText(db < -55 ? "Signál: ticho – ladička nič nepočuje"
                    : String.format(Locale.ROOT, "Signál: %.0f dB", db));
            long now = SystemClock.uptimeMillis();
            if (p == null) {
                // Krátke výpadky (doznievanie) nemažú poslednú notu hneď.
                if (now - lastSeen[0] > 1200) {
                    tunerView.clear();
                    tunerText.setText(db < -55 ? "Zahrajte jednu strunu." : "Počujem zvuk, ale nie jasný tón – zahrajte jednu strunu.");
                    inTuneString = -1;
                }
                return;
            }
            lastSeen[0] = now;
            int[] strings = Tunings.strings(prefs.getInt("tuning", 0));
            int s = Tunings.nearest(strings, p.exact);
            double cents = s >= 0 ? (p.exact - strings[s]) * 100 : p.cents;
            String name = s >= 0 ? Tunings.note(strings[s]) : p.note();
            boolean ok = Math.abs(cents) < TunerView.OK;
            tunerView.set(name, cents);
            tunerText.setText(String.format(Locale.ROOT, "%s%+.0f centov   %.1f Hz   %s",
                    s >= 0 ? (6 - s) + ". struna · " : "", cents, p.hz,
                    ok ? "naladené" : cents < 0 ? "pritiahnuť" : "povoliť"));
            // Struna je naladená, keď sa drží v tolerancii aspoň 0,4 s.
            if (ok && s >= 0) {
                if (inTuneString != s) {
                    inTuneString = s;
                    inTuneSince = now;
                } else if (now - inTuneSince > 400 && !tuned[s]) {
                    tuned[s] = true;
                    showStrings();
                }
            } else {
                inTuneString = -1;
            }
            highlightString(s);
        });
        engine.tuner = t;
    }

    /** Výber ladenia a komorného A. */
    void showTunings() {
        LinearLayout row = findViewById(R.id.tunings);
        row.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int current = prefs.getInt("tuning", 0);
        for (int i = 0; i < Tunings.NAMES.length; i++) {
            TextView c = new TextView(this);
            c.setText(Tunings.NAMES[i]);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);
            c.setBackgroundResource(i == current ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * d));
            lp.rightMargin = Math.round(6 * d);
            int tuning = i;
            c.setOnClickListener(v -> {
                prefs.edit().putInt("tuning", tuning).apply();
                java.util.Arrays.fill(tuned, false);
                showTunings();
                if (scaleOn) showScale();
            });
            row.addView(c, lp);
        }
        TextView a4 = findViewById(R.id.a4);
        a4.setText("A = " + prefs.getInt("a4", 440) + " Hz");
        findViewById(R.id.a4Minus).setOnClickListener(v -> setA4(prefs.getInt("a4", 440) - 1));
        findViewById(R.id.a4Plus).setOnClickListener(v -> setA4(prefs.getInt("a4", 440) + 1));
        showStrings();
    }

    void setA4(int hz) {
        hz = Math.max(430, Math.min(450, hz));
        prefs.edit().putInt("a4", hz).apply();
        Tuner t = engine.tuner;
        if (t != null) t.a4 = hz;
        ((TextView) findViewById(R.id.a4)).setText("A = " + hz + " Hz");
    }

    /** Struny ladenia; naladené sú zelené. */
    void showStrings() {
        LinearLayout row = findViewById(R.id.tunerStrings);
        row.removeAllViews();
        int[] strings = Tunings.strings(prefs.getInt("tuning", 0));
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < strings.length; i++) {
            TextView c = new TextView(this);
            c.setText(Tunings.note(strings[i]) + (tuned[i] ? " ✓" : ""));
            c.setGravity(Gravity.CENTER);
            c.setTextSize(13);
            c.setBackgroundResource(R.drawable.bg_chip);
            if (tuned[i]) {
                c.setBackgroundTintList(ColorStateList.valueOf(0xFF43A047));
                c.setTextColor(0xFFFFFFFF);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(34 * d), 1);
            lp.rightMargin = Math.round(4 * d);
            row.addView(c, lp);
        }
    }

    /** Zvýrazní strunu, ktorá sa práve ladí. */
    void highlightString(int s) {
        LinearLayout row = findViewById(R.id.tunerStrings);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            c.setAlpha(s < 0 || i == s ? 1f : 0.45f);
        }
    }

    // ---- Možnosti riffu ----

    void options(Riff r) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(r.info.favorite ? "☆ Odobrať z obľúbených" : "★ Pridať k obľúbeným");
        actions.add(() -> {
            r.info.favorite = !r.info.favorite;
            saveMeta(r);
            render();
        });
        items.add("Poznámka, tempo, tónina");
        actions.add(() -> editInfo(r));
        items.add(r.info.tone.isEmpty() ? "🎛 Zvuk (ToneX)" : "🎛 Zvuk: " + r.info.tone);
        actions.add(() -> toneDialog(r));
        items.add(r.info.song.isEmpty() ? "🎵 Zaradiť do piesne" : "🎵 Pieseň: " + r.info.song);
        actions.add(() -> songDialog(r));
        if (filter == SONGS && !r.info.song.isEmpty()) {
            items.add("↑ Posunúť v piesni vyššie");
            actions.add(() -> moveInSong(r, -1));
            items.add("↓ Posunúť v piesni nižšie");
            actions.add(() -> moveInSong(r, 1));
        }
        items.add("Premenovať");
        actions.add(() -> rename(r));
        items.add("🎼 Stupnica a tónina" + (r.info.key.isEmpty() ? "" : " (" + r.info.key + ")"));
        actions.add(() -> scaleFor(r));
        items.add(r.info.bpm > 0 ? "Zistiť tempo znova z hrania" : "Zistiť tempo z hrania");
        actions.add(() -> detectTempo(r, null));
        items.add("Poslať ako WAV (plná kvalita)");
        actions.add(() -> share(r.uri(), "audio/wav", label(r.name)));
        items.add("Poslať ako M4A (menší súbor)");
        actions.add(() -> shareM4a(r));
        items.add("🥁 Poslať s bicími (M4A)");
        actions.add(() -> shareWithDrums(r));
        items.add("Poslať na test (Disk)");
        actions.add(() -> sendForTest(java.util.Collections.singletonList(r)));
        items.add("Zmazať");
        actions.add(() -> delete(r));
        new AlertDialog.Builder(this)
                .setTitle(label(r.name))
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    // ---- Značky ----

    /** Značka na mieste, kde sa práve hrá (v rozbalenom prehrávači). */
    void addMark() {
        Riff r = playingRiff;
        if (r == null || player == null) return;
        int pos = player.getCurrentPosition();
        for (int m : r.info.markers) if (Math.abs(m - pos) < 300) return;
        r.info.markers.add(pos);
        java.util.Collections.sort(r.info.markers);
        saveMeta(r);
        showMarks();
        render();
    }

    /** Značky prehrávaného riffu: na vlnke a ako tlačidlá (ťuknutie = skok, podržanie = zmazať). */
    void showMarks() {
        LinearLayout row = findViewById(R.id.markList);
        row.removeAllViews();
        Riff r = playingRiff;
        if (r == null || player == null) return;
        int dur = Math.max(1, player.getDuration());
        float[] f = new float[r.info.markers.size()];
        float d = getResources().getDisplayMetrics().density;
        for (int k = 0; k < f.length; k++) {
            int ms = r.info.markers.get(k);
            f[k] = Math.min(1, ms / (float) dur);
            TextView t = new TextView(this);
            t.setText(time(ms, 1));
            t.setGravity(Gravity.CENTER);
            t.setBackgroundResource(R.drawable.bg_chip);
            t.setPadding(Math.round(10 * d), 0, Math.round(10 * d), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * d));
            lp.leftMargin = Math.round(6 * d);
            t.setOnClickListener(v -> {
                if (player == null) return;
                player.seekTo(ms);
                showProgress();
            });
            t.setOnLongClickListener(v -> {
                r.info.markers.remove((Integer) ms);
                saveMeta(r);
                showMarks();
                render();
                return true;
            });
            row.addView(t, lp);
        }
        playerWave.setMarks(f);
        findViewById(R.id.markScroll).setVisibility(f.length > 0 ? View.VISIBLE : View.GONE);
    }

    // ---- Záloha ----

    /** Pošle riffy (s údajmi v súboroch) do aplikácie Disk; len nové od poslednej zálohy, alebo všetky. */
    void backup(boolean onlyNew) {
        long last = prefs.getLong("lastBackup", 0);
        ArrayList<Uri> uris = new ArrayList<>();
        for (Riff r : riffs) if (!onlyNew || r.added > last) uris.add(r.uri());
        if (uris.isEmpty()) {
            notice("Nič nové na zálohovanie.", null, null);
            showBackupInfo();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType("audio/*");
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(new Intent(send).setPackage(DRIVE));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(Intent.createChooser(send, "Zálohovať"));
        }
        prefs.edit().putLong("lastBackup", System.currentTimeMillis() / 1000).apply();
        showBackupInfo();
    }

    void showBackupInfo() {
        long last = prefs.getLong("lastBackup", 0);
        int fresh = 0;
        for (Riff r : riffs) if (r.added > last) fresh++;
        ((TextView) findViewById(R.id.backupInfo)).setText((last == 0 ? "Zatiaľ nezálohované."
                : "Posledná záloha: " + DateFormat.format("d. M. yyyy H:mm", new Date(last * 1000)) + ".")
                + " Nových riffov: " + fresh + ". Riffy sa pošlú do aplikácie Disk; uložte ich do priečinka "
                + "„Riffy záloha“. Poznámky, tempo, piesne a značky sú uložené priamo v súboroch.");
        ((Button) findViewById(R.id.backupNew)).setText("Zálohovať nové (" + fresh + ")");
    }

    // ---- Zvuk pri riffe ----

    /** Hlásenie po uložení; zvuk sa dá hneď zmeniť (nový riff dostane naposledy použitý). */
    void savedNotice(String saved) {
        String tone = prefs.getString("lastTone", "");
        notice("Uložené: " + label(saved) + (tone.isEmpty() ? "" : " · 🎛 " + tone), "Zvuk", () -> {
            for (Riff r : riffs) {
                if (r.name.equals(saved)) {
                    toneDialog(r);
                    return;
                }
            }
        });
    }

    /** Naposledy použité zvuky (najnovší prvý). */
    List<String> recentTones() {
        List<String> out = new ArrayList<>();
        for (String t : prefs.getString("tones", "").split("\n")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out;
    }

    void rememberTone(String tone) {
        List<String> list = recentTones();
        list.removeIf(t -> t.equalsIgnoreCase(tone));
        if (!tone.isEmpty()) list.add(0, tone);
        while (list.size() > 8) list.remove(list.size() - 1);
        prefs.edit().putString("tones", String.join("\n", list)).putString("lastTone", tone).apply();
    }

    /** Aký zvuk alebo preset z ToneX bol pri riffe; ten istý sa ponúkne pri ďalších nahrávkach. */
    void toneDialog(Riff r) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText field = new EditText(this);
        field.setHint("napr. Marshall crunch, preset 3");
        field.setText(r.info.tone);
        field.setSingleLine(true);
        box.addView(field);
        List<String> recent = recentTones();
        if (!recent.isEmpty()) {
            TextView label = new TextView(this);
            label.setText("Naposledy použité");
            label.setPadding(0, Math.round(10 * d), 0, Math.round(4 * d));
            box.addView(label);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            LinearLayout row = new LinearLayout(this);
            for (String t : recent) {
                TextView c = new TextView(this);
                c.setText(t);
                c.setGravity(Gravity.CENTER);
                c.setBackgroundResource(R.drawable.bg_chip);
                c.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * d));
                lp.rightMargin = Math.round(6 * d);
                c.setOnClickListener(v -> field.setText(t));
                row.addView(c, lp);
            }
            scroll.addView(row);
            box.addView(scroll);
        }
        new AlertDialog.Builder(this)
                .setTitle("Zvuk (ToneX)")
                .setView(box)
                .setPositiveButton("Uložiť", (x, w) -> {
                    r.info.tone = field.getText().toString().trim();
                    rememberTone(r.info.tone);
                    saveMeta(r);
                    render();
                })
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    // ---- Piesne ----

    List<String> songs() {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Riff r : riffs) if (!r.info.song.isEmpty()) names.add(r.info.song);
        return new ArrayList<>(names);
    }

    /** Pieseň a časť riffu; nová časť ide na koniec piesne. */
    void songDialog(Riff r) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.AutoCompleteTextView song = new android.widget.AutoCompleteTextView(this);
        song.setHint("Pieseň (napr. Nová pieseň)");
        song.setText(r.info.song);
        song.setThreshold(0);
        song.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, songs()));
        song.setOnFocusChangeListener((v, f) -> {
            if (f) song.showDropDown();
        });
        android.widget.AutoCompleteTextView part = new android.widget.AutoCompleteTextView(this);
        part.setHint("Časť (napr. Sloha, Refrén, Sólo)");
        part.setText(r.info.part);
        part.setThreshold(0);
        part.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                new String[]{"Intro", "Sloha", "Refrén", "Bridge", "Sólo", "Outro"}));
        box.addView(song);
        box.addView(part);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Pieseň")
                .setView(box)
                .setPositiveButton("Uložiť", (x, w) -> {
                    String name = song.getText().toString().trim();
                    if (!name.equalsIgnoreCase(r.info.song)) {
                        int max = 0;
                        for (Riff o : riffs) if (o != r && o.info.song.equalsIgnoreCase(name)) max = Math.max(max, o.info.order);
                        for (String n : songs()) if (n.equalsIgnoreCase(name)) name = n;
                        r.info.order = max + 1;
                    }
                    r.info.song = name;
                    r.info.part = name.isEmpty() ? "" : part.getText().toString().trim();
                    saveMeta(r);
                    render();
                })
                .setNegativeButton("Zrušiť", null);
        if (!r.info.song.isEmpty()) b.setNeutralButton("Vyradiť", (x, w) -> {
            r.info.song = "";
            r.info.part = "";
            saveMeta(r);
            render();
        });
        b.show();
    }

    /** Vymení riff so susedom v piesni. */
    void moveInSong(Riff r, int dir) {
        List<Riff> parts = new ArrayList<>();
        for (Riff o : riffs) if (o.info.song.equals(r.info.song)) parts.add(o);
        parts.sort((a, b) -> a.info.order != b.info.order ? Integer.compare(a.info.order, b.info.order) : Long.compare(a.added, b.added));
        for (int k = 0; k < parts.size(); k++) parts.get(k).info.order = k + 1;
        int at = parts.indexOf(r), to = at + dir;
        if (at < 0 || to < 0 || to >= parts.size()) return;
        Riff other = parts.get(to);
        int o = other.info.order;
        other.info.order = r.info.order;
        r.info.order = o;
        for (Riff p : parts) saveMeta(p);
        render();
    }

    /** Prehrá časti piesne za sebou. */
    void playSong(String name) {
        List<Riff> parts = new ArrayList<>();
        for (Object o : rows) if (o instanceof Riff && ((Riff) o).info.song.equals(name)) parts.add((Riff) o);
        if (parts.isEmpty()) return;
        stopPlaying();
        queue.clear();
        queue.addAll(parts.subList(1, parts.size()));
        play(parts.get(0));
    }

    // ---- Tempo z hrania ----

    /** Zistí tempo a prvú dobu z úderov v riffe; potom zavolá then (ak sa podarilo). */
    void detectTempo(Riff r, Runnable then) {
        notice("Zisťujem tempo…", null, null);
        Uri uri = r.uri();
        int beats = Drums.PATTERNS[Math.max(0, Drums.styleIndex(drumStyleOf(r)))].beats;
        IO.execute(() -> {
            int[] t = null;
            try {
                int rate;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    rate = Beat.rateOf(in);
                }
                float[] x;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    x = Beat.mono(in, 0, rate * 60L);
                }
                t = Beat.tempo(Beat.onsets(x, rate), beats);
            } catch (Exception ignored) {
            }
            int[] found = t;
            ui.post(() -> {
                if (found == null) {
                    notice("Tempo sa nedalo zistiť (hrá sa voľne?). Zadajte ho v ⋮ → Poznámka, tempo.", "Zadať", () -> editInfo(r));
                    return;
                }
                r.info.bpm = found[0];
                r.info.beat0 = found[1];
                r.info.aligned = true;
                saveMeta(r);
                render();
                notice("Tempo " + found[0] + " BPM", null, null);
                if (then != null) then.run();
            });
        });
    }

    // ---- Riff s bicími ----

    /** Riff zmiešaný s bicími (v jeho tempe a štýle) ako M4A na poslanie; originál ostane čistý. */
    void shareWithDrums(Riff r) {
        if (r.info.bpm <= 0) {
            detectTempo(r, () -> shareWithDrums(r));
            return;
        }
        notice("Miešam riff s bicími…", null, null);
        int style = Math.max(0, Drums.styleIndex(drumStyleOf(r)));
        int fills = DRUMS.fillBars, beat0 = Math.max(0, r.info.beat0);
        float bpmR = r.info.bpm, vol = DRUMS.volume;
        short[][] kit = Drums.load(this);
        IO.execute(() -> {
            File wav = new File(getCacheDir(), "s-bicimi.wav"), m4a = new File(getCacheDir(), "s-bicimi.m4a");
            try {
                try (InputStream in = getContentResolver().openInputStream(r.uri())) {
                    Mixdown.withDrums(in, wav, kit, style, fills, bpmR, beat0, vol);
                }
                try (InputStream in = new FileInputStream(wav)) {
                    M4a.convert(in, m4a);
                }
                Uri uri = publishShared(m4a, r.name + " (bicie)");
                ui.post(() -> share(uri, "audio/mp4", label(r.name) + " s bicími"));
            } catch (Exception e) {
                String msg = e.getMessage();
                ui.post(() -> notice("Zmiešanie sa nepodarilo: " + msg, null, null));
            } finally {
                wav.delete();
                m4a.delete();
            }
        });
    }

    /** Uloží M4A do Hudba/Riffy/Zdieľané (aby sa dal poslať) a vráti jeho adresu. */
    Uri publishShared(File m4a, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".m4a");
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, SHARED);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("M4A sa nepodarilo uložiť.");
        try (InputStream in = new FileInputStream(m4a); OutputStream out = cr.openOutputStream(uri)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    void editInfo(Riff r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText note = new EditText(this);
        note.setHint("Poznámka");
        note.setText(r.info.note);
        EditText tempo = new EditText(this);
        tempo.setHint("Tempo (BPM)");
        tempo.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (r.info.bpm > 0) tempo.setText(String.valueOf(r.info.bpm));
        EditText key = new EditText(this);
        key.setHint("Tónina (napr. Am)");
        key.setText(r.info.key);
        box.addView(note);
        box.addView(tempo);
        box.addView(key);
        new AlertDialog.Builder(this)
                .setTitle(label(r.name))
                .setView(box)
                .setPositiveButton("Uložiť", (d, w) -> {
                    r.info.note = note.getText().toString().trim();
                    r.info.key = key.getText().toString().trim();
                    try {
                        r.info.bpm = Integer.parseInt(tempo.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        r.info.bpm = 0;
                    }
                    saveMeta(r);
                    render();
                })
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    void rename(Riff r) {
        EditText field = new EditText(this);
        field.setText(label(r.name).replace(" · ", " "));
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Premenovať")
                .setView(field)
                .setPositiveButton("Uložiť", (d, w) -> {
                    String name = field.getText().toString().trim().replaceAll("[/\\\\:]", "-");
                    if (name.isEmpty() || name.equals(r.name)) return;
                    for (Riff o : riffs) {
                        if (o != r && o.name.equalsIgnoreCase(name)) {
                            notice("Riff s menom „" + name + "“ už existuje.", null, null);
                            return;
                        }
                    }
                    renameTo(r, name, true);
                })
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    /** Riff a meno, ktoré čakajú na súhlas Androidu so zmenou súboru. */
    Riff renaming;
    String renamingTo;

    /**
     * Nahrávky z predošlej inštalácie aplikácie Android považuje za cudzie; zmeniť ich
     * smie aplikácia až po súhlase, o ktorý si raz povie (ask).
     */
    void renameTo(Riff r, String name, boolean ask) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".wav");
        try {
            getContentResolver().update(r.uri(), v, null, null);
            meta.rename(r.name, name);
            r.name = name;
            saveMeta(r);
        } catch (SecurityException e) {
            if (ask && Build.VERSION.SDK_INT >= 30) {
                try {
                    renaming = r;
                    renamingTo = name;
                    startIntentSenderForResult(MediaStore.createWriteRequest(getContentResolver(),
                            java.util.Collections.singletonList(r.uri())).getIntentSender(), ASK_RENAME, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) {
                }
            }
            notice("Android nedovolil nahrávku premenovať.", null, null);
        } catch (Exception e) {
            notice("Túto nahrávku sa nedá premenovať: " + e.getMessage(), null, null);
        }
        loadRiffs();
    }

    void share(Uri uri, String type, String title) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, title));
    }

    /** Prevedie riff do M4A, uloží ho do Hudba/Riffy/Zdieľané a ponúkne na poslanie. */
    void shareM4a(Riff r) {
        notice("Pripravujem M4A…", null, null);
        IO.execute(() -> {
            File temp = new File(getCacheDir(), "zdielanie.m4a");
            try {
                try (InputStream in = getContentResolver().openInputStream(r.uri())) {
                    M4a.convert(in, temp);
                }
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, r.name + ".m4a");
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, SHARED);
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("M4A sa nepodarilo uložiť.");
                try (InputStream in = new FileInputStream(temp); OutputStream out = cr.openOutputStream(uri)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                v.clear();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                ui.post(() -> share(uri, "audio/mp4", label(r.name)));
            } catch (Exception e) {
                String msg = e.getMessage();
                ui.post(() -> notice("Prevod sa nepodaril: " + msg, null, null));
            } finally {
                temp.delete();
            }
        });
    }

    boolean isPending(Riff r) {
        for (Riff p : pendingDelete) if (p.id == r.id) return true;
        return false;
    }

    void delete(Riff r) {
        List<Riff> one = new ArrayList<>();
        one.add(r);
        delete(one);
    }

    /** Zmazanie s možnosťou vrátiť: riffy sa najprv len skryjú, zmažú sa po pár sekundách. */
    void delete(List<Riff> items) {
        if (items.isEmpty()) return;
        commitDelete();
        for (Riff r : items) if (r.id == playing) stopPlaying();
        pendingDelete.addAll(items);
        render();
        String what = items.size() == 1 ? label(items.get(0).name) : items.size() + " riffov";
        notice("Zmazané: " + what, "Vrátiť", () -> {
            pendingDelete.clear();
            ui.removeCallbacks(commitDelete);
            render();
        });
        ui.removeCallbacks(commitDelete);
        ui.postDelayed(commitDelete, 5000);
    }

    void commitDelete() {
        if (pendingDelete.isEmpty()) return;
        List<Riff> items = new ArrayList<>(pendingDelete);
        pendingDelete.clear();
        ui.removeCallbacks(commitDelete);
        List<Uri> foreign = new ArrayList<>();
        for (Riff r : items) {
            try {
                getContentResolver().delete(r.uri(), null, null);
                meta.remove(r.name);
            } catch (SecurityException e) {
                // Nahrávka z predošlej inštalácie: Android si vypýta súhlas sám.
                foreign.add(r.uri());
            }
        }
        if (!foreign.isEmpty() && Build.VERSION.SDK_INT >= 30) {
            try {
                startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), foreign)
                        .getIntentSender(), ASK_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                notice("Niektoré nahrávky sa nedajú zmazať.", null, null);
            }
        }
        loadRiffs();
    }

    // ---- Hromadný výber ----

    void toggleSelected(Riff r) {
        if (!selection.remove(r.id)) selection.add(r.id);
        showSelection();
    }

    void clearSelection() {
        selection.clear();
        showSelection();
    }

    List<Riff> selectedRiffs() {
        List<Riff> out = new ArrayList<>();
        for (Riff r : riffs) if (selection.contains(r.id)) out.add(r);
        return out;
    }

    void showSelection() {
        boolean on = !selection.isEmpty();
        selectBar.setVisibility(on ? View.VISIBLE : View.GONE);
        filterRow.setVisibility(on ? View.GONE : View.VISIBLE);
        int n = selection.size();
        selectCount.setText(n == 1 ? "1 vybraný" : n >= 2 && n <= 4 ? n + " vybrané" : n + " vybraných");
        adapter.notifyDataSetChanged();
    }

    /** Ak sú všetky vybrané obľúbené, hviezdička sa im zruší; inak sa pridá všetkým. */
    void favoriteSelected() {
        List<Riff> chosen = selectedRiffs();
        boolean all = true;
        for (Riff r : chosen) all &= r.info.favorite;
        for (Riff r : chosen) {
            r.info.favorite = !all;
            saveMeta(r);
        }
        clearSelection();
        render();
    }

    void shareSelected() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Riff r : selectedRiffs()) uris.add(r.uri());
        if (uris.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType("audio/wav");
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, uris.size() + " riffov"));
        clearSelection();
    }

    /** Meno súboru v ZIP: bez diakritiky a znakov, ktoré niektoré systémy nedovolia. */
    static String fileName(String label) {
        String plain = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replace(":", ".").replace(" · ", " ").replaceAll("[/\\\\?*\"<>|]", "-");
    }

    /** Vybrané riffy do jedného ZIP (v Stiahnutých/Riffy), aj s poznámkami, a ponúknuť na poslanie. */
    void zipSelected() {
        List<Riff> chosen = selectedRiffs();
        clearSelection();
        if (chosen.isEmpty()) return;
        notice("Pripravujem ZIP…", null, null);
        IO.execute(() -> {
            try {
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "Riffy " + DateFormat.format("yyyy-MM-dd HH.mm", new Date()) + ".zip");
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Riffy/");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("ZIP sa nepodarilo vytvoriť.");
                StringBuilder notes = new StringBuilder();
                Set<String> used = new HashSet<>();
                try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(cr.openOutputStream(uri), 1 << 16))) {
                    z.setLevel(1);
                    byte[] buf = new byte[65536];
                    for (Riff r : chosen) {
                        String base = fileName(r.name.equals(label(r.name)) ? r.name
                                : DateFormat.format("yyyy-MM-dd", new Date(r.added * 1000)) + " " + label(r.name));
                        String file = base + ".wav";
                        for (int k = 2; !used.add(file); k++) file = base + " (" + k + ").wav";
                        z.putNextEntry(new ZipEntry(file));
                        try (InputStream in = cr.openInputStream(r.uri())) {
                            int n;
                            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                        }
                        z.closeEntry();
                        if (!r.info.empty()) {
                            notes.append(file).append(":");
                            if (r.info.favorite) notes.append(" ★");
                            if (r.info.bpm > 0) notes.append(" ").append(r.info.bpm).append(" BPM");
                            if (!r.info.key.isEmpty()) notes.append(" ").append(r.info.key);
                            if (!r.info.note.isEmpty()) notes.append(" – ").append(r.info.note);
                            notes.append("\n");
                        }
                    }
                    if (notes.length() > 0) {
                        z.putNextEntry(new ZipEntry("poznamky.txt"));
                        z.write(notes.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        z.closeEntry();
                    }
                }
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                ui.post(() -> share(uri, "application/zip", chosen.size() + " riffov"));
            } catch (Exception e) {
                String msg = e.getMessage();
                ui.post(() -> notice("ZIP sa nepodaril: " + msg, null, null));
            }
        });
    }

    static final String DRIVE = "com.google.android.apps.docs";

    /**
     * Riffy na skúšku (napr. tabulatúry) rovno do aplikácie Disk; spolu s nimi nepovinný text,
     * čo sa naozaj hralo, aby sa dal výsledok porovnať.
     */
    void sendForTest(List<Riff> chosen) {
        if (chosen.isEmpty()) return;
        EditText played = new EditText(this);
        played.setHint("Napr. pražce alebo tabulatúra (nepovinné)");
        played.setMinLines(3);
        played.setGravity(Gravity.TOP);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(played, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Čo ste hrali?")
                .setMessage("Riffy sa pošlú do aplikácie Disk; uložte ich do priečinka Riffy.")
                .setView(box)
                .setPositiveButton("Poslať", (d, w) -> IO.execute(() -> {
                    ArrayList<Uri> uris = new ArrayList<>();
                    for (Riff r : chosen) uris.add(r.uri());
                    String text = played.getText().toString().trim();
                    if (!text.isEmpty()) {
                        try {
                            uris.add(testNote(chosen, text));
                        } catch (Exception ignored) {
                        }
                    }
                    ui.post(() -> {
                        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        send.setType("*/*");
                        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(new Intent(send).setPackage(DRIVE));
                        } catch (android.content.ActivityNotFoundException e) {
                            // Bez aplikácie Disk: bežná ponuka zdieľania.
                            startActivity(Intent.createChooser(send, "Poslať na test"));
                        }
                    });
                }))
                .setNegativeButton("Zrušiť", null)
                .show();
    }

    /** Textový súbor (v Stiahnutých/Riffy) s tým, čo sa hralo, pomenovaný podľa riffu. */
    Uri testNote(List<Riff> chosen, String text) throws IOException {
        Riff first = chosen.get(0);
        String base = fileName(DateFormat.format("yyyy-MM-dd", new Date(first.added * 1000)) + " " + label(first.name));
        StringBuilder body = new StringBuilder();
        for (Riff r : chosen) body.append("Riff: ").append(r.name).append(".wav\n");
        body.append("Čo som hral:\n").append(text).append("\n");
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, base + " - co som hral.txt");
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Riffy/");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Text sa nepodarilo uložiť.");
        try (OutputStream out = cr.openOutputStream(uri)) {
            out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return uri;
    }

    // ---- Údaje v súboroch a záchrana nahrávok ----

    /** Uloží údaje riffu do aplikácie aj do jeho súboru WAV. */
    void saveMeta(Riff r) {
        meta.put(r.name, r.info);
        Uri uri = r.uri();
        Meta.Info copy = r.info;
        IO.execute(() -> embed(uri, copy));
    }

    /** Zapíše údaje do súboru; pri nahrávke z predošlej inštalácie to Android nedovolí, vtedy ostanú len v aplikácii. */
    void embed(Uri uri, Meta.Info info) {
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "rw");
             FileInputStream in = new FileInputStream(fd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(fd.getFileDescriptor())) {
            Wav.writeExtra(in.getChannel(), out.getChannel(), Meta.toBytes(info));
        } catch (Exception ignored) {
        }
    }

    /** Riffom, ktoré aplikácia nepozná (napr. po preinštalovaní), načíta údaje z ich súborov. */
    void importMeta() {
        List<Riff> unknown = new ArrayList<>();
        for (Riff r : riffs) if (!meta.has(r.name) && scanned.add(r.id)) unknown.add(r);
        if (unknown.isEmpty()) return;
        IO.execute(() -> {
            boolean found = false;
            for (Riff r : unknown) {
                try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(r.uri(), "r");
                     FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                    Meta.Info i = Meta.fromBytes(Wav.readExtra(in.getChannel()));
                    if (i != null && !i.empty()) {
                        meta.put(r.name, i);
                        found = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (found) ui.post(this::loadRiffs);
        });
    }

    /** Nahrávky prerušené pádom aplikácie (zostali v dočasnom priečinku) dokončí a uloží. */
    void recover() {
        File[] files = getCacheDir().listFiles();
        if (files == null) return;
        int saved = 0;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("spatne-") || n.startsWith("zdielanie")) {
                f.delete();
                continue;
            }
            if (!n.startsWith("nahravka-") || !n.endsWith(".wav")) continue;
            try {
                long frames = Wav.repair(f);
                long started = Long.parseLong(n.substring(9, n.length() - 4));
                if (frames > 22050) {
                    save(f, "Riff " + DateFormat.format("yyyy-MM-dd HH.mm.ss", new Date(started)) + " obnovené", 0, null);
                    saved++;
                }
            } catch (Exception ignored) {
            }
            f.delete();
        }
        if (saved > 0) {
            int count = saved;
            ui.post(() -> {
                notice(count == 1 ? "Obnovená prerušená nahrávka." : "Obnovené prerušené nahrávky: " + count, null, null);
                loadRiffs();
            });
        }
    }

    // ---- Denník ----

    /** Volá sa s každou úrovňou zo vstupu; čas, keď ide signál (nad -45 dB), sa zapíše do denníka. */
    void heard(int peak) {
        long now = SystemClock.uptimeMillis();
        long dt = lastLevelAt == 0 ? 0 : Math.min(now - lastLevelAt, 300);
        lastLevelAt = now;
        if (peak > 184) diary.add(LocalDate.now(), dt);
        if (now - diarySavedAt > 15000) saveDiary();
        if (now - diaryShownAt > 1000) showDiaryLine();
    }

    void saveDiary() {
        diarySavedAt = SystemClock.uptimeMillis();
        prefs.edit().putString("diary", diary.format()).apply();
    }

    void showDiaryLine() {
        diaryShownAt = SystemClock.uptimeMillis();
        LocalDate today = LocalDate.now();
        int streak = diary.streak(today);
        diaryText.setText("Dnes hrané " + Diary.minutes(diary.on(today))
                + (streak >= 2 ? "  ·  " + streak + " dni po sebe" : "") + "  ›");
    }

    void showDiary() {
        LocalDate today = LocalDate.now();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE d. M.", new Locale("sk"));
        long max = 1;
        for (int i = 0; i < 14; i++) max = Math.max(max, diary.on(today.minusDays(i)));
        StringBuilder s = new StringBuilder();
        s.append("Tento týždeň: ").append(Diary.minutes(diary.week(today))).append("\n");
        s.append("Spolu: ").append(Diary.minutes(diary.total())).append("\n");
        int streak = diary.streak(today);
        s.append("Dní po sebe: ").append(streak).append("\n\n");
        for (int i = 0; i < 14; i++) {
            LocalDate d = today.minusDays(i);
            long ms = diary.on(d);
            int bar = (int) Math.round(12.0 * ms / max);
            s.append(i == 0 ? "dnes" : f.format(d)).append("   ");
            for (int k = 0; k < bar; k++) s.append('▇');
            s.append(ms > 0 ? "  " + Diary.minutes(ms) : "  –").append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Denník hrania")
                .setMessage(s.toString())
                .setPositiveButton("Zavrieť", null)
                .show();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == ASK_DELETE) loadRiffs();
        if (code == ASK_RENAME && renaming != null) {
            Riff r = renaming;
            renaming = null;
            if (result == RESULT_OK) renameTo(r, renamingTo, false);
        }
    }
}
