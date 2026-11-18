/*
 * Pont JNI entre XFiles (LibArchive.kt) et libarchive : lecture séquentielle d'une archive
 * (RAR 4, RAR 5, ISO 9660 ; tar seulement pour les tests). Chemins et noms échangés en octets
 * UTF-8 (le « UTF-8 modifié » de JNI déformerait les émojis). Erreurs : IOException.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "archive.h"
#include "archive_entry.h"

#define FORMAT_RAR 1
#define FORMAT_ISO 2
#define FORMAT_TAR 4

#define TYPE_FILE 0
#define TYPE_DIR 1
#define TYPE_OTHER 2 /* lien symbolique, périphérique… : jamais extrait */
#define TYPE_HARDLINK 3 /* même contenu qu'un élément précédent (ISO Rock Ridge, RAR) */

typedef struct {
    struct archive *a;
    struct archive_entry *entry;
} Handle;

static void throw_io(JNIEnv *env, const char *message) {
    jclass c = (*env)->FindClass(env, "java/io/IOException");
    if (c != NULL) (*env)->ThrowNew(env, c, message != NULL ? message : "libarchive");
}

/* Copie d'un tableau d'octets Java en chaîne C terminée par un zéro ; NULL si absent. */
static char *to_cstring(JNIEnv *env, jbyteArray bytes) {
    if (bytes == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *s = malloc((size_t) n + 1);
    if (s == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *) s);
    s[n] = '\0';
    return s;
}

#define NAME(f) Java_io_github_thomaszimmer13_xfiles_data_archive_LibArchive_##f

JNIEXPORT jlong JNICALL NAME(nativeOpen)(JNIEnv *env, jclass cls, jbyteArray path, jbyteArray password, jint formats) {
    (void) cls;
    Handle *h = calloc(1, sizeof(Handle));
    if (h == NULL) {
        throw_io(env, "mémoire insuffisante");
        return 0;
    }
    h->a = archive_read_new();
    if (formats & FORMAT_RAR) {
        archive_read_support_format_rar(h->a);
        archive_read_support_format_rar5(h->a);
    }
    if (formats & FORMAT_ISO) archive_read_support_format_iso9660(h->a);
    if (formats & FORMAT_TAR) archive_read_support_format_tar(h->a);
    char *pass = to_cstring(env, password);
    if (pass != NULL) {
        archive_read_add_passphrase(h->a, pass);
        memset(pass, 0, strlen(pass));
        free(pass);
    }
    char *p = to_cstring(env, path);
    int r = p != NULL ? archive_read_open_filename(h->a, p, 64 * 1024) : ARCHIVE_FATAL;
    free(p);
    if (r != ARCHIVE_OK) {
        throw_io(env, archive_error_string(h->a));
        archive_read_free(h->a);
        free(h);
        return 0;
    }
    return (jlong) (intptr_t) h;
}

/* Élément suivant : son nom en UTF-8, ou null à la fin de l'archive. */
JNIEXPORT jbyteArray JNICALL NAME(nativeNext)(JNIEnv *env, jclass cls, jlong handle) {
    (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    int r;
    do {
        r = archive_read_next_header(h->a, &h->entry);
    } while (r == ARCHIVE_RETRY);
    if (r == ARCHIVE_EOF) return NULL;
    if (r < ARCHIVE_WARN) {
        throw_io(env, archive_error_string(h->a));
        return NULL;
    }
    const char *name = archive_entry_pathname_utf8(h->entry);
    if (name == NULL) name = archive_entry_pathname(h->entry);
    if (name == NULL) name = "";
    jsize n = (jsize) strlen(name);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) name);
    return out;
}

JNIEXPORT jint JNICALL NAME(nativeType)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env, (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    if (archive_entry_hardlink(h->entry) != NULL) return TYPE_HARDLINK;
    switch (archive_entry_filetype(h->entry)) {
        case AE_IFREG: return TYPE_FILE;
        case AE_IFDIR: return TYPE_DIR;
        default: return TYPE_OTHER;
    }
}

/* Taille décompressée annoncée, -1 si inconnue. */
JNIEXPORT jlong JNICALL NAME(nativeSize)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env, (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    return archive_entry_size_is_set(h->entry) ? (jlong) archive_entry_size(h->entry) : -1;
}

/* Date de modification en millisecondes, 0 si inconnue. */
JNIEXPORT jlong JNICALL NAME(nativeTime)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env, (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    if (!archive_entry_mtime_is_set(h->entry)) return 0;
    return (jlong) archive_entry_mtime(h->entry) * 1000 + archive_entry_mtime_nsec(h->entry) / 1000000;
}

/* Cible d'un lien physique (nom d'un élément précédent), en UTF-8 ; null sinon. */
JNIEXPORT jbyteArray JNICALL NAME(nativeLink)(JNIEnv *env, jclass cls, jlong handle) {
    (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    const char *link = archive_entry_hardlink_utf8(h->entry);
    if (link == NULL) link = archive_entry_hardlink(h->entry);
    if (link == NULL) return NULL;
    jsize n = (jsize) strlen(link);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) link);
    return out;
}

JNIEXPORT jboolean JNICALL NAME(nativeEncrypted)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env, (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    return archive_entry_is_encrypted(h->entry) ? JNI_TRUE : JNI_FALSE;
}

/* Contenu de l'élément courant : nombre d'octets lus, 0 à la fin, IOException en cas d'erreur. */
JNIEXPORT jint JNICALL NAME(nativeRead)(JNIEnv *env, jclass cls, jlong handle, jbyteArray buffer, jint offset, jint length) {
    (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    /* Tampon C : la décompression peut être longue, pas de section critique JNI pendant ce temps. */
    char *b = malloc((size_t) length);
    if (b == NULL) {
        throw_io(env, "mémoire insuffisante");
        return -1;
    }
    la_ssize_t n = archive_read_data(h->a, b, (size_t) length);
    if (n > 0) (*env)->SetByteArrayRegion(env, buffer, offset, (jsize) n, (const jbyte *) b);
    free(b);
    if (n < 0) {
        throw_io(env, archive_error_string(h->a));
        return -1;
    }
    return (jint) n;
}

JNIEXPORT void JNICALL NAME(nativeClose)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env, (void) cls;
    Handle *h = (Handle *) (intptr_t) handle;
    if (h == NULL) return;
    archive_read_free(h->a);
    free(h);
}
