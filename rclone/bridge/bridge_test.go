package bridge

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func call(t *testing.T, method string, in map[string]any) map[string]any {
	t.Helper()
	b, _ := json.Marshal(in)
	r := Rpc(method, string(b))
	var out map[string]any
	_ = json.Unmarshal([]byte(r.Output), &out)
	if r.Status != 200 {
		t.Fatalf("%s: %d %s", method, r.Status, r.Output)
	}
	return out
}

// Mêmes appels que RcloneRemote (XFiles), sur un compte « local », avec configuration chiffrée.
func TestOperationsLikeXFiles(t *testing.T) {
	dir := t.TempDir()
	Init(filepath.Join(dir, "rclone.conf"), filepath.Join(dir, "cache"), "secret")
	data := filepath.Join(dir, "data")
	os.MkdirAll(data, 0o755)
	call(t, "config/create", map[string]any{"name": "xftest", "type": "alias", "parameters": map[string]any{"remote": data}, "opt": map[string]any{"nonInteractive": true, "obscure": true}})
	conf, _ := os.ReadFile(filepath.Join(dir, "rclone.conf"))
	if !strings.Contains(string(conf), "RCLONE_ENCRYPT_V0") {
		t.Fatalf("configuration non chiffrée : %s", conf)
	}
	fs := "xftest:"
	call(t, "operations/mkdir", map[string]any{"fs": fs, "remote": "Dossier été"})
	src := filepath.Join(dir, "up.txt")
	os.WriteFile(src, []byte("bonjour"), 0o644)
	job := call(t, "operations/copyfile", map[string]any{"srcFs": dir, "srcRemote": "up.txt", "dstFs": fs, "dstRemote": "Dossier été/a.txt", "_async": true, "_group": "g1"})
	id := job["jobid"]
	for i := 0; ; i++ {
		st := call(t, "job/status", map[string]any{"jobid": id})
		if st["finished"] == true {
			if st["success"] != true {
				t.Fatalf("copie : %v", st["error"])
			}
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	stats := call(t, "core/stats", map[string]any{"group": "g1"})
	if stats["bytes"].(float64) != 7 {
		t.Fatalf("stats : %v", stats)
	}
	call(t, "core/stats-delete", map[string]any{"group": "g1"})
	list := call(t, "operations/list", map[string]any{"fs": fs, "remote": "Dossier été", "opt": map[string]any{}})["list"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["Name"] != "a.txt" || list[0].(map[string]any)["Size"].(float64) != 7 {
		t.Fatalf("liste : %v", list)
	}
	if call(t, "operations/stat", map[string]any{"fs": fs, "remote": "absent", "opt": map[string]any{}})["item"] != nil {
		t.Fatal("stat d'un absent")
	}
	if call(t, "operations/stat", map[string]any{"fs": fs, "remote": "Dossier été", "opt": map[string]any{}})["item"].(map[string]any)["IsDir"] != true {
		t.Fatal("stat dossier")
	}
	call(t, "operations/movefile", map[string]any{"srcFs": fs, "srcRemote": "Dossier été/a.txt", "dstFs": fs, "dstRemote": "Dossier été/b.txt"})
	call(t, "sync/move", map[string]any{"srcFs": fs + "Dossier été", "dstFs": fs + "Renommé", "deleteEmptySrcDirs": true})
	Rpc("operations/rmdir", `{"fs":"xftest:","remote":"Dossier été"}`)
	root := call(t, "operations/list", map[string]any{"fs": fs, "remote": "", "opt": map[string]any{}})["list"].([]any)
	if len(root) != 1 || root[0].(map[string]any)["Name"] != "Renommé" {
		t.Fatalf("après déplacement : %v", root)
	}
	call(t, "operations/copyfile", map[string]any{"srcFs": fs, "srcRemote": "Renommé/b.txt", "dstFs": dir, "dstRemote": "down.txt"})
	if b, _ := os.ReadFile(filepath.Join(dir, "down.txt")); string(b) != "bonjour" {
		t.Fatalf("téléchargé : %q", b)
	}
	call(t, "operations/deletefile", map[string]any{"fs": fs, "remote": "Renommé/b.txt"})
	call(t, "operations/purge", map[string]any{"fs": fs, "remote": "Renommé"})
	r := Rpc("operations/list", `{"fs":"xftest:","remote":"Renommé","opt":{}}`)
	if r.Status == 200 || !strings.Contains(strings.ToLower(r.Output), "not found") {
		t.Fatalf("dossier supprimé : %d %s", r.Status, r.Output)
	}

	// Connexion Google : rclone ouvre son serveur local, l'adresse se lit puis mène à Google.
	done := make(chan *Result, 1)
	go func() {
		// Même boucle que XFiles : chaque question reçoit sa réponse par défaut, « navigateur » = oui.
		params := map[string]any{"client_id": "123-test.apps.googleusercontent.com", "client_secret": "abc", "config_template": "<p>{{ if .OK }}ok{{ end }}</p>"}
		b, _ := json.Marshal(map[string]any{"name": "xfdrive", "type": "drive", "parameters": params, "opt": map[string]any{"nonInteractive": true, "obscure": true}})
		r := Rpc("config/create", string(b))
		for i := 0; i < 20 && r.Status == 200; i++ {
			var out map[string]any
			json.Unmarshal([]byte(r.Output), &out)
			state, _ := out["State"].(string)
			if state == "" {
				break
			}
			opt, _ := out["Option"].(map[string]any)
			answer, _ := opt["ValueStr"].(string)
			if opt["Name"] == "config_is_local" {
				answer = "true"
			}
			b, _ := json.Marshal(map[string]any{"name": "xfdrive", "parameters": params, "opt": map[string]any{"nonInteractive": true, "obscure": true, "continue": true, "state": state, "result": answer}})
			r = Rpc("config/update", string(b))
		}
		done <- r
	}()
	var url string
	for i := 0; i < 100 && url == ""; i++ {
		time.Sleep(100 * time.Millisecond)
		select {
		case r := <-done:
			t.Fatalf("config/create terminé sans attendre : %d %s", r.Status, r.Output)
		default:
		}
		st := call(t, "config/oauthstatus", map[string]any{})
		url, _ = st["authUrl"].(string)
	}
	if !strings.HasPrefix(url, "http://127.0.0.1:53682/auth?state=") {
		t.Fatalf("adresse OAuth : %q", url)
	}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	loc := resp.Header.Get("Location")
	if !strings.HasPrefix(loc, "https://accounts.google.com/") || !strings.Contains(loc, "client_id=123-test.apps.googleusercontent.com") || !strings.Contains(loc, "access_type=offline") {
		t.Fatalf("redirection : %d %q", resp.StatusCode, loc)
	}
	call(t, "config/oauthstop", map[string]any{})
	select {
	case r := <-done:
		if r.Status == 200 && !strings.Contains(r.Output, "cancel") {
			t.Logf("création après arrêt : %s", r.Output)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("config/create ne s'arrête pas")
	}
}

// Coffre comme dans XFiles : remote « crypt » à la volée (rien dans la configuration), mot de
// passe obscurci par rclone, fichier témoin pour vérifier le mot de passe.
func TestVaultLikeXFiles(t *testing.T) {
	os.Setenv("TMPDIR", "/tmp") // Init (test précédent) l'a fait pointer vers un dossier supprimé
	dir := t.TempDir()
	Init(filepath.Join(dir, "rclone.conf"), filepath.Join(dir, "cache"), "secret")
	target := filepath.Join(dir, "Coffre d'été, 1")
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	vault := func(password string) string {
		obscured := call(t, "core/obscure", map[string]any{"clear": password})["obscured"].(string)
		return ":crypt,remote=" + quote(target) + ",password=" + quote(obscured) + ":"
	}
	fs := vault("très secret")
	src := filepath.Join(dir, "witness")
	os.WriteFile(src, []byte("xfiles-vault-1"), 0o644)
	call(t, "operations/copyfile", map[string]any{"srcFs": dir, "srcRemote": "witness", "dstFs": fs, "dstRemote": ".xfiles-coffre"})
	call(t, "operations/mkdir", map[string]any{"fs": fs, "remote": "Factures"})
	list := call(t, "operations/list", map[string]any{"fs": fs, "remote": "", "opt": map[string]any{}})["list"].([]any)
	if len(list) != 2 {
		t.Fatalf("liste déchiffrée : %v", list)
	}
	raw, _ := os.ReadDir(target)
	for _, e := range raw {
		if strings.Contains(e.Name(), "xfiles") || strings.Contains(e.Name(), "Factures") {
			t.Fatalf("nom en clair dans le coffre : %s", e.Name())
		}
	}
	// Mauvais mot de passe : le témoin est introuvable.
	b, _ := json.Marshal(map[string]any{"fs": vault("faux"), "remote": ".xfiles-coffre", "opt": map[string]any{}})
	r := Rpc("operations/stat", string(b))
	var st map[string]any
	_ = json.Unmarshal([]byte(r.Output), &st)
	if r.Status == 200 && st["item"] != nil {
		t.Fatalf("mauvais mot de passe accepté : %s", r.Output)
	}
}
