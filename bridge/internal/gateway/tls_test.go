package gateway

import (
	"context"
	"github.com/coder/websocket"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestAuthenticatedHTTPSAndWSS(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var unavailable atomic.Bool
	g, cfg, store, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/remote.mux" {
			c, e := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
			if e == nil {
				defer c.CloseNow()
				_, _, _ = c.Read(ctx)
			}
			return
		}
		if unavailable.Load() {
			w.WriteHeader(503)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"type":"server-response","rpcId":"r","result":{"ok":true,"value":null}}`))
	}))
	s := httptest.NewTLSServer(g)
	defer s.Close()
	r, _ := http.NewRequest("POST", s.URL+cfg.Routes.RPC, strings.NewReader(`{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{}}}`))
	r.Header.Set("Authorization", "Bearer "+token)
	resp, e := s.Client().Do(r)
	if e != nil {
		t.Fatal(e)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatal(resp.StatusCode)
	}
	c, _, e := websocket.Dial(ctx, strings.Replace(s.URL, "https:", "wss:", 1)+cfg.Routes.Stream, &websocket.DialOptions{HTTPClient: s.Client(), HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if e != nil {
		t.Fatal(e)
	}
	c.CloseNow()
	check := func(path string, bearer string, want int) {
		r, _ := http.NewRequest("POST", s.URL+path, strings.NewReader(`{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{}}}`))
		r.Header.Set("Authorization", "Bearer "+bearer)
		x, e := s.Client().Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer x.Body.Close()
		b, _ := io.ReadAll(x.Body)
		if x.StatusCode != want {
			t.Fatalf("want %d got %d", want, x.StatusCode)
		}
		if want == 404 && len(b) != 0 {
			t.Fatal("unauthenticated content leak")
		}
	}
	check("/wrong/route", token, 404)
	check(cfg.Routes.RPC, "invalid", 404)
	unavailable.Store(true)
	check(cfg.Routes.RPC, token, 502)
	unavailable.Store(false)
	id, _ := store.Authenticate(token)
	if e := store.Revoke(id); e != nil {
		t.Fatal(e)
	}
	check(cfg.Routes.RPC, token, 404)
}
