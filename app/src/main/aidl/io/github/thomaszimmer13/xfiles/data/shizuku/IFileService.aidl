// Fichiers vus avec les droits de Shizuku (processus séparé, utilisateur « shell »).
package io.github.thomaszimmer13.xfiles.data.shizuku;

interface IFileService {
    /** Réservé par Shizuku : arrêt du service. */
    void destroy() = 16777114;

    /** Contenu d'un dossier, en JSON : [{"n":nom,"d":dossier,"s":taille,"m":date}]. */
    String list(String path) = 1;

    ParcelFileDescriptor openRead(String path) = 2;

    /** Fichier créé ou remplacé. */
    ParcelFileDescriptor openWrite(String path) = 3;

    void mkdir(String path) = 4;

    void rename(String from, String to) = 5;

    /** Fichier, ou dossier et tout son contenu. */
    void delete(String path) = 6;

    boolean exists(String path) = 7;
}
