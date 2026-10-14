// Čítanie zvuku priamo z USB zariadenia (izochrónny prenos), bez zvukového systému Androidu.
// Java otvorí zariadenie cez UsbManager, prevezme rozhranie a vyberie nastavenie;
// tu sa cez jeho súborový popisovač posielajú a preberajú požiadavky jadra (usbdevfs).
//
// Požiadavky preberá samostatné vlákno a dáta ukladá do kruhovej zásoby, z ktorej si ich
// Java berie vlastným tempom. Keby ich preberala Java sama, pri každom zdržaní (ukladanie,
// upratovanie pamäte) by zariadenie nemalo kam posielať a zvuk by sa trhal.

#include <jni.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define URBS 16
#define RING (1 << 20)

typedef struct {
    int fd;
    int packetSize, packets, flags, frameBytes;
    struct usbdevfs_urb *urbs[URBS];
    int inflight;

    unsigned char *ring;
    uint64_t head, tail;  // celkový počet zapísaných a prečítaných bajtov
    pthread_mutex_t lock;
    pthread_cond_t ready;
    pthread_t thread;
    int running, started, error;
    int64_t received, dropped, bad, zero, filled, skipped, kept;
    // Niektoré zariadenia (ToneX Plug) posielajú stále rovnako veľké pakety a keď nemajú dosť
    // zvuku, pošlú paket samých núl. Tie sa vyhodia, ak je už zvuku viac, než pri ohlásenom
    // vzorkovaní za ten čas patrí; skutočné ticho tak ostane.
    double perPacket, expected;
} Stream;

static int submit(Stream *s, struct usbdevfs_urb *u) {
    u->status = 0;
    u->actual_length = 0;
    u->error_count = 0;
    u->start_frame = 0;
    u->flags = s->flags;
    u->number_of_packets = s->packets;
    u->buffer_length = s->packets * s->packetSize;
    for (int i = 0; i < s->packets; i++) {
        u->iso_frame_desc[i].length = s->packetSize;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
    return ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0 ? -errno : 0;
}

static void forget(Stream *s, struct usbdevfs_urb *u) {
    for (int i = 0; i < URBS; i++) {
        if (s->urbs[i] == u) s->urbs[i] = NULL;
    }
    free(u->buffer);
    free(u);
}

/** Uloží prijaté pakety do zásoby; volá sa so zamknutým zámkom. */
static void store(Stream *s, struct usbdevfs_urb *u) {
    unsigned char *buf = u->buffer;
    for (int i = 0; i < u->number_of_packets; i++) {
        int len = u->iso_frame_desc[i].actual_length;
        if (u->iso_frame_desc[i].status != 0) {
            s->bad++;
            continue;
        }
        s->expected += s->perPacket;
        if (len <= 0) continue;
        s->received += len;
        s->filled++;
        unsigned char *p = buf + i * s->packetSize;
        int k = 0;
        while (k < len && p[k] == 0) k++;
        if (k == len) {
            s->zero++;
            if (s->perPacket > 0 && (s->kept + len) / s->frameBytes > s->expected + 2 * s->perPacket) {
                s->skipped++;
                continue;
            }
        }
        s->kept += len;
        if (RING - (s->head - s->tail) < (uint64_t) len) {
            s->dropped += len;
            continue;
        }
        unsigned char *src = buf + i * s->packetSize;
        size_t at = s->head % RING, first = RING - at < (size_t) len ? RING - at : (size_t) len;
        memcpy(s->ring + at, src, first);
        if (first < (size_t) len) memcpy(s->ring, src + first, len - first);
        s->head += len;
    }
}

static void *reaper(void *arg) {
    Stream *s = arg;
    for (;;) {
        pthread_mutex_lock(&s->lock);
        int left = s->inflight;
        pthread_mutex_unlock(&s->lock);
        if (left <= 0) break;

        struct usbdevfs_urb *done = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURB, &done) < 0) {
            if (errno == EINTR) continue;
            // Zariadenie zmizlo alebo jadro odmieta: požiadavky sú preč, uprace sa všetko.
            pthread_mutex_lock(&s->lock);
            if (!s->error) s->error = -errno;
            s->running = 0;
            for (int i = 0; i < URBS; i++) {
                if (s->urbs[i]) forget(s, s->urbs[i]);
            }
            s->inflight = 0;
            pthread_cond_broadcast(&s->ready);
            pthread_mutex_unlock(&s->lock);
            break;
        }

        pthread_mutex_lock(&s->lock);
        s->inflight--;
        if (s->running) {
            store(s, done);
            int r = submit(s, done);
            if (r < 0) {
                if (!s->error) s->error = r;
                forget(s, done);
            } else {
                s->inflight++;
            }
        } else {
            forget(s, done);
        }
        pthread_cond_broadcast(&s->ready);
        pthread_mutex_unlock(&s->lock);
    }
    return NULL;
}

/** Pred spustením vlákna: zahodí odoslané požiadavky a počká, kým ich jadro vráti. */
static void abandon(Stream *s) {
    for (int i = 0; i < URBS; i++) {
        if (s->urbs[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    }
    for (int tries = 0; tries < 200 && s->inflight > 0; tries++) {
        struct usbdevfs_urb *done = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
            if (errno != EAGAIN) break;
            struct timespec t = {0, 2000000};
            nanosleep(&t, NULL);
            continue;
        }
        forget(s, done);
        s->inflight--;
    }
}

static void destroy(Stream *s) {
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->ready);
    free(s->ring);
    free(s);
}

JNIEXPORT jlong JNICALL
Java_sk_lubeno_riffy_UsbAudio_nativeOpen(JNIEnv *env, jclass cls, jint fd, jint endpoint, jint packetSize,
                                         jint packets, jint asap, jint frameBytes, jint rate, jint packetsPerSecond) {
    Stream *s = calloc(1, sizeof(Stream));
    s->fd = fd;
    s->packetSize = packetSize;
    s->packets = packets;
    s->flags = asap ? USBDEVFS_URB_ISO_ASAP : 0;
    s->frameBytes = frameBytes > 0 ? frameBytes : 1;
    s->perPacket = rate > 0 && packetsPerSecond > 0 ? (double) rate / packetsPerSecond : 0;
    s->ring = malloc(RING);
    s->running = 1;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->ready, NULL);
    for (int i = 0; i < URBS; i++) {
        struct usbdevfs_urb *u = calloc(1, sizeof(struct usbdevfs_urb)
                + packets * sizeof(struct usbdevfs_iso_packet_desc));
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) endpoint;
        u->buffer = calloc(packets, packetSize);
        u->usercontext = s;
        s->urbs[i] = u;
        int r = submit(s, u);
        if (r < 0) {
            forget(s, u);
            abandon(s);
            destroy(s);
            return r;
        }
        s->inflight++;
    }
    if (pthread_create(&s->thread, NULL, reaper, s) != 0) {
        abandon(s);
        destroy(s);
        return -EAGAIN;
    }
    s->started = 1;
    return (jlong) (intptr_t) s;
}

/**
 * Skopíruje do out čo najviac celých rámcov zo zásoby; ak je prázdna, chvíľu počká.
 * Vráti počet bajtov (aj 0), alebo -errno, ak sa prenos zastavil.
 */
JNIEXPORT jint JNICALL
Java_sk_lubeno_riffy_UsbAudio_nativeRead(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    Stream *s = (Stream *) (intptr_t) handle;
    jsize cap = (*env)->GetArrayLength(env, out);
    pthread_mutex_lock(&s->lock);
    if (s->head - s->tail < (uint64_t) s->frameBytes && s->running && !s->error) {
        struct timespec until;
        clock_gettime(CLOCK_REALTIME, &until);
        until.tv_nsec += 200000000;
        if (until.tv_nsec >= 1000000000) {
            until.tv_sec++;
            until.tv_nsec -= 1000000000;
        }
        pthread_cond_timedwait(&s->ready, &s->lock, &until);
    }
    uint64_t avail = s->head - s->tail;
    size_t n = avail < (uint64_t) cap ? (size_t) avail : (size_t) cap;
    n -= n % s->frameBytes;
    size_t at = s->tail % RING, first = RING - at < n ? RING - at : n;
    if (first > 0) (*env)->SetByteArrayRegion(env, out, 0, (jsize) first, (const jbyte *) (s->ring + at));
    if (first < n) (*env)->SetByteArrayRegion(env, out, (jsize) first, (jsize) (n - first), (const jbyte *) s->ring);
    s->tail += n;
    int error = s->error;
    pthread_mutex_unlock(&s->lock);
    if (n == 0 && error) return error;
    return (jint) n;
}

/**
 * Prijaté bajty, bajty stratené pre plnú zásobu, chybné pakety, pakety so samými nulami,
 * pakety s dátami, vyhodené výplňové pakety, ponechané bajty.
 */
JNIEXPORT jlongArray JNICALL
Java_sk_lubeno_riffy_UsbAudio_nativeStats(JNIEnv *env, jclass cls, jlong handle) {
    Stream *s = (Stream *) (intptr_t) handle;
    pthread_mutex_lock(&s->lock);
    jlong v[7] = {s->received, s->dropped, s->bad, s->zero, s->filled, s->skipped, s->kept};
    pthread_mutex_unlock(&s->lock);
    jlongArray a = (*env)->NewLongArray(env, 7);
    (*env)->SetLongArrayRegion(env, a, 0, 7, v);
    return a;
}

JNIEXPORT void JNICALL
Java_sk_lubeno_riffy_UsbAudio_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    Stream *s = (Stream *) (intptr_t) handle;
    pthread_mutex_lock(&s->lock);
    s->running = 0;
    // Zahodené požiadavky jadro vráti a vlákno ich uprace; potom samo skončí.
    for (int i = 0; i < URBS; i++) {
        if (s->urbs[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    }
    pthread_cond_broadcast(&s->ready);
    pthread_mutex_unlock(&s->lock);
    if (s->started) pthread_join(s->thread, NULL);
    destroy(s);
}
