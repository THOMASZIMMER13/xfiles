// Pont minimal entre XFiles et rclone (librclone), compilé en bibliothèque Android par gomobile.
//
// Tout passe par l'API RPC de rclone (stable) : configuration des comptes, listes, copies,
// déplacements. Seuls l'emplacement et le chiffrement du fichier de configuration sont réglés
// directement, avant l'initialisation.
//
// Inspiré de rcbridge (RSAF, Andrew Gunnerson, GPL-3.0).
package bridge

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"os"
	"strings"
	"sync"

	"golang.org/x/crypto/nacl/secretbox"
	"golang.org/x/text/unicode/norm"

	"github.com/rclone/rclone/fs"

	"github.com/rclone/rclone/fs/config"
	_ "github.com/rclone/rclone/fs/sync" // méthodes rc « sync/* » (déplacer un dossier)
	"github.com/rclone/rclone/lib/oauthutil"
	"github.com/rclone/rclone/librclone/librclone"

	// Services proposés dans XFiles (les autres alourdiraient l'application).
	_ "github.com/rclone/rclone/backend/alias"
	_ "github.com/rclone/rclone/backend/box"
	_ "github.com/rclone/rclone/backend/crypt" // coffres chiffrés
	_ "github.com/rclone/rclone/backend/drive"
	_ "github.com/rclone/rclone/backend/dropbox"
	_ "github.com/rclone/rclone/backend/local"
	_ "github.com/rclone/rclone/backend/onedrive"
	_ "github.com/rclone/rclone/backend/pcloud"
	_ "github.com/rclone/rclone/backend/yandex"

	_ "golang.org/x/mobile/bind"
)

var once sync.Once

// État de la configuration au démarrage, renvoyé par Init.
const (
	ConfigOK         = ""
	ConfigUnreadable = "illisible" // fichier abîmé ou mot de passe perdu : mis de côté, comptes à reconnecter
)

// Init prépare rclone : fichier de configuration chiffré par [password], cache et fichiers
// temporaires dans [cacheDir]. Renvoie ConfigUnreadable si la configuration a dû être mise de
// côté (renommée en « .illisible ») : rclone aurait sinon arrêté l'application (os.Exit) à chaque
// lancement. Sans effet après le premier appel (renvoie alors ConfigOK).
func Init(configPath string, cacheDir string, password string) string {
	status := ConfigOK
	once.Do(func() {
		_ = os.Setenv("TMPDIR", cacheDir)
		_ = os.Setenv("HOME", cacheDir)
		// Jamais de question sur l'entrée standard (elle bloquerait l'application).
		fs.GetConfig(context.Background()).AskPassword = false
		if !configReadable(configPath, password) {
			_ = os.Rename(configPath, configPath+".illisible")
			status = ConfigUnreadable
		}
		_ = config.SetConfigPath(configPath)
		_ = config.SetCacheDir(cacheDir)
		if password != "" {
			_ = config.SetConfigPassword(password)
		}
		// Pas de navigateur à ouvrir depuis rclone : XFiles lit l'adresse (config/oauthstatus)
		// et l'ouvre lui-même.
		oauthutil.OpenURL = func(string) error { return nil }
		librclone.Initialize()
	})
	return status
}

// configReadable vérifie, sans passer par rclone (qui demanderait un mot de passe ou quitterait),
// que le fichier est absent, en clair, ou déchiffrable avec [password] : même format et même
// dérivation de clé que fs/config (RCLONE_ENCRYPT_V0, SHA-256 de « [mot de passe][rclone-config] »,
// secretbox).
func configReadable(path string, password string) bool {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return true
	}
	if err != nil {
		return false
	}
	r := bufio.NewReader(bytes.NewReader(data))
	for {
		line, err := r.ReadString('\n')
		l := strings.TrimSpace(line)
		if l != "" && !strings.HasPrefix(l, ";") && !strings.HasPrefix(l, "#") {
			if l != "RCLONE_ENCRYPT_V0:" {
				// Configuration en clair : lisible si ce n'est pas un autre format chiffré.
				return !strings.HasPrefix(l, "RCLONE_ENCRYPT_V")
			}
			break
		}
		if err == io.EOF {
			return true // vide
		}
		if err != nil {
			return false
		}
	}
	if password == "" {
		return false
	}
	box, err := io.ReadAll(base64.NewDecoder(base64.StdEncoding, r))
	if err != nil || len(box) < 24+secretbox.Overhead {
		return false
	}
	sum := sha256.Sum256([]byte("[" + norm.NFKC.String(password) + "][rclone-config]"))
	var nonce [24]byte
	var key [32]byte
	copy(nonce[:], box[:24])
	copy(key[:], sum[:])
	_, ok := secretbox.Open(nil, box[24:], &nonce, &key)
	return ok
}

// Result d'un appel RPC : JSON de sortie et code d'état HTTP (200 = réussite).
type Result struct {
	Output string
	Status int
}

// Rpc appelle une méthode de l'API rc de rclone (« operations/list », « config/create »…).
func Rpc(method string, input string) *Result {
	out, status := librclone.RPC(method, input)
	return &Result{Output: out, Status: status}
}
