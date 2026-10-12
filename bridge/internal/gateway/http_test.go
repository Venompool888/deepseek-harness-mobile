package gateway

import (
	"app.deepseek.harness/bridge/internal/auth"
	"app.deepseek.harness/bridge/internal/config"
	"app.deepseek.harness/bridge/internal/upstream"
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func fixture(t *testing.T, h http.Handler) (*Gateway, config.Config, *auth.Store, string) {
	t.Helper()
	s := httptest.NewServer(h)
	t.Cleanup(s.Close)
	cfg, e := config.Init(t.TempDir(), config.Config{ProtocolVersion: 1, Listen: "127.0.0.1:3090", Upstream: s.URL, PublicOrigin: "http://127.0.0.1:3090"})
	if e != nil {
		t.Fatal(e)
	}
	store, e := auth.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	code, _ := store.NewPairing(time.Now())
	credential, e := store.Pair(code, "phone", time.Now())
	if e != nil {
		t.Fatal(e)
	}
	up, e := upstream.New(s.URL, "")
	if e != nil {
		t.Fatal(e)
	}
	g := New(cfg, store, up)
	return g, cfg, store, credential.DeviceToken
}
func TestAnonymousNeverReachesUpstream(t *testing.T) {
	var hits atomic.Int32
	g, cfg, _, _ := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1) }))
	for _, path := range []string{"/", "/api/session/list", "/v1/rpc", cfg.Routes.RPC, cfg.Routes.Stream, cfg.Routes.Export, "/favicon.ico"} {
		rr := httptest.NewRecorder()
		g.ServeHTTP(rr, httptest.NewRequest("POST", path, strings.NewReader("broken")))
		if rr.Code != 404 || rr.Body.Len() != 0 || rr.Header().Get("Location") != "" {
			t.Fatal("fingerprint", path, rr.Code)
		}
	}
	if hits.Load() != 0 {
		t.Fatal("anonymous upstream request")
	}
}
func TestRawRPCAndUnknownMethod(t *testing.T) {
	var hits atomic.Int32
	payload := `{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{"_request":{}}}}`
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		b, _ := io.ReadAll(r.Body)
		if string(b) != payload || r.Header.Get("Authorization") != "" || r.URL.Path != "/api/session/list" {
			t.Error("changed request")
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"type":"server-response","rpcId":"r","result":{"ok":true,"value":[1,null]}}`)
	}))
	r := httptest.NewRequest("POST", cfg.Routes.RPC, strings.NewReader(payload))
	r.Header.Set("Authorization", "Bearer "+token)
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != 200 || !bytes.Contains(rr.Body.Bytes(), []byte(`[1,null]`)) {
		t.Fatal("lost RPC result", rr.Code)
	}
	r = httptest.NewRequest("POST", cfg.Routes.RPC, strings.NewReader(strings.Replace(payload, "session/list", "../../", 1)))
	r.Header.Set("Authorization", "Bearer "+token)
	rr = httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != 400 || hits.Load() != 1 {
		t.Fatal("allowed traversal", rr.Code)
	}
}
func TestPairAndUnsafePaths(t *testing.T) {
	g, cfg, store, _ := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("unexpected upstream") }))
	code, _ := store.NewPairing(time.Now())
	b, _ := json.Marshal(map[string]string{"deviceName": "Pixel 10", "pairingCode": code})
	r := httptest.NewRequest("POST", cfg.Routes.Pair, bytes.NewReader(b))
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	var result struct {
		DeviceToken string            `json:"deviceToken"`
		Routes      map[string]string `json:"routes"`
	}
	json.Unmarshal(rr.Body.Bytes(), &result)
	if rr.Code != 200 || result.DeviceToken == "" || result.Routes["rpc"] != cfg.Routes.RPC {
		t.Fatal("pair result")
	}
	rr = httptest.NewRecorder()
	g.ServeHTTP(rr, httptest.NewRequest("POST", cfg.Routes.Pair, bytes.NewReader(b)))
	if rr.Code != 404 {
		t.Fatal("reused pair")
	}
	for _, p := range []string{cfg.Routes.RPC + "?x=1", "/api/../" + strings.TrimPrefix(cfg.Routes.RPC, "/"), "/%61/b"} {
		rr = httptest.NewRecorder()
		g.ServeHTTP(rr, httptest.NewRequest("GET", p, nil))
		if rr.Code != 404 || rr.Header().Get("Location") != "" {
			t.Fatal("path exposure")
		}
	}
}
func TestExportDropsInternalHeaders(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/session.export" || r.URL.Query().Get("sessionId") != "s" {
			t.Error("bad export query")
		}
		w.Header().Set("Set-Cookie", "dsh-secret=value")
		w.Header().Set("Server", "dsh")
		w.Header().Set("Content-Type", "application/zip")
		io.WriteString(w, "ZIP")
	}))
	r := httptest.NewRequest("POST", cfg.Routes.Export, strings.NewReader(`{"sessionId":"s","includeDescendants":true}`))
	r.Header.Set("Authorization", "Bearer "+token)
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != 200 || rr.Body.String() != "ZIP" || rr.Header().Get("Set-Cookie") != "" || rr.Header().Get("Server") != "" {
		t.Fatal("export leak")
	}
}
