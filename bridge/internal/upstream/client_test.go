package upstream

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCookieExchangeAndRawRPC(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			if r.URL.Query().Get("token") != "private-secret" {
				t.Error("bad exchange")
			}
			sum := sha256.Sum256([]byte(r.Host))
			http.SetCookie(w, &http.Cookie{Name: "dsh-auth-" + base64.RawURLEncoding.EncodeToString(sum[:]), Value: "private-cookie"})
			w.Header().Set("Location", "./")
			w.WriteHeader(303)
			return
		}
		if !strings.Contains(r.Header.Get("Cookie"), "private-cookie") || r.Header.Get("Authorization") != "" {
			t.Error("wrong credentials")
		}
		b, _ := io.ReadAll(r.Body)
		if string(b) != `{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{"_request":{}}}}` {
			t.Error("changed body")
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"type":"server-response","rpcId":"r","result":{"ok":true,"value":null}}`)
	}))
	defer server.Close()
	c, e := New(server.URL, "private-secret")
	if e != nil {
		t.Fatal(e)
	}
	b, e := c.RPC(context.Background(), []byte(`{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{"_request":{}}}}`))
	if e != nil || !strings.Contains(string(b), `"value":null`) {
		t.Fatal("failed", e)
	}
}
func TestRedirectRefused(t *testing.T) {
	var hit bool
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hit = true }))
	defer other.Close()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", other.URL)
		w.WriteHeader(303)
	}))
	defer s.Close()
	c, _ := New(s.URL, "secret")
	if _, e := c.Headers(); e == nil {
		t.Fatal("accepted auth redirect")
	}
	if hit {
		t.Fatal("followed redirect")
	}
}
