// Pont minimal entre XFiles et rclone (librclone), compilé en bibliothèque Android par gomobile.
//
// Tout passe par l'API RPC de rclone (stable) : configuration des comptes, listes, copies,
// déplacements. Seuls l'emplacement et le chiffrement du fichier de configuration sont réglés
// directement, avant l'initialisation.
//
// Inspiré de rcbridge (RSAF, Andrew Gunnerson, GPL-3.0).
package bridge

import (
	"os"
	"sync"

	"github.com/rclone/rclone/fs/config"
	_ "github.com/rclone/rclone/fs/sync" // méthodes rc « sync/* » (déplacer un dossier)
	"github.com/rclone/rclone/lib/oauthutil"
	"github.com/rclone/rclone/librclone/librclone"

	// Services proposés dans XFiles (les autres alourdiraient l'application).
	_ "github.com/rclone/rclone/backend/alias"
	_ "github.com/rclone/rclone/backend/box"
	_ "github.com/rclone/rclone/backend/drive"
	_ "github.com/rclone/rclone/backend/dropbox"
	_ "github.com/rclone/rclone/backend/local"
	_ "github.com/rclone/rclone/backend/onedrive"
	_ "github.com/rclone/rclone/backend/pcloud"
	_ "github.com/rclone/rclone/backend/yandex"

	_ "golang.org/x/mobile/bind"
)

var once sync.Once

// Init prépare rclone : fichier de configuration chiffré par [password], cache et fichiers
// temporaires dans [cacheDir]. Sans effet après le premier appel.
func Init(configPath string, cacheDir string, password string) {
	once.Do(func() {
		_ = os.Setenv("TMPDIR", cacheDir)
		_ = os.Setenv("HOME", cacheDir)
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
