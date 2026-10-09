package config

import (
	"os"
	"path/filepath"
	"regexp"
	"testing"
)

func base() Config {
	return Config{ProtocolVersion: 1, Listen: "127.0.0.1:3090", Upstream: "http://127.0.0.1:3080", PublicOrigin: "http://127.0.0.1:3090"}
}
func TestRoutesPersist(t *testing.T) {
	dir := t.TempDir()
	cfg, err := Init(dir, base())
	if err != nil {
		t.Fatal(err)
	}
	loaded, err := Load(dir)
	if err != nil || loaded.Routes != cfg.Routes {
		t.Fatal("lost routes", err)
	}
	if _, err := Init(dir, base()); err == nil {
		t.Fatal("reinitialized deployment")
	}
	if err := os.WriteFile(filepath.Join(dir, "routes.json"), []byte("broken"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(dir); err == nil {
		t.Fatal("silently recovered broken routes")
	}
}
func TestRoutesRandomAndValid(t *testing.T) {
	a, err := Init(t.TempDir(), base())
	if err != nil {
		t.Fatal(err)
	}
	b, err := Init(t.TempDir(), base())
	if err != nil {
		t.Fatal(err)
	}
	if a.Routes == b.Routes {
		t.Fatal("same deployment routes")
	}
	seen := map[string]bool{}
	re := regexp.MustCompile(`^/[A-Za-z0-9_-]{11}/[A-Za-z0-9_-]{11}$`)
	for _, p := range []string{a.Routes.Pair, a.Routes.RPC, a.Routes.Stream, a.Routes.Export} {
		if !re.MatchString(p) || seen[p] {
			t.Fatal("bad route", p)
		}
		seen[p] = true
	}
}
func TestInvalidConfigRefused(t *testing.T) {
	for _, origin := range []string{"http://example.com", "https://x.test/path", "https://user:pass@x.test", "https://x.test/?token=a"} {
		c := base()
		c.PublicOrigin = origin
		if _, e := Init(t.TempDir(), c); e == nil {
			t.Fatal("accepted origin", origin)
		}
	}
	for _, up := range []string{"http://192.168.1.2:3080", "http://127.0.0.1:3080/a", "http://example.com", "http://localhost.evil", "http://[::]:3080"} {
		c := base()
		c.Upstream = up
		if _, e := Init(t.TempDir(), c); e == nil {
			t.Fatal("accepted upstream", up)
		}
	}
	c := base()
	c.Upstream = "http://[::1]:3080"
	if _, e := Init(t.TempDir(), c); e != nil {
		t.Fatal(e)
	}
}
func TestDuplicateAndMissingRoutesRefused(t *testing.T) {
	dir := t.TempDir()
	if _, e := Init(dir, base()); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(dir, "routes.json"), []byte(`{"pair":"/a/b","rpc":"/a/b","stream":"/c/d","export":"/e/f"}`), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e := Load(dir); e == nil {
		t.Fatal("duplicate accepted")
	}
	os.Remove(filepath.Join(dir, "routes.json"))
	if _, e := Load(dir); e == nil {
		t.Fatal("missing accepted")
	}
}
