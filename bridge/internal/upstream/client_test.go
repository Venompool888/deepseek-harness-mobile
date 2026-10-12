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

func TestExistingDesktopCookieNeverExchangesOrFallsBack(t *testing.T) {
	requests := 0
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Path == "/" || r.URL.RawQuery != "" {
			t.Error("attempted token exchange")
		}
		sum := sha256.Sum256([]byte(r.Host))
		expected := "dsh-auth-" + base64.RawURLEncoding.EncodeToString(sum[:]) + "=existing-desktop-cookie"
		if r.Header.Get("Cookie") != expected {
			t.Error("lost authenticated cookie")
		}
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer s.Close()
	sum := sha256.Sum256([]byte(strings.TrimPrefix(s.URL, "http://")))
	cookie := "dsh-auth-" + base64.RawURLEncoding.EncodeToString(sum[:]) + "=existing-desktop-cookie"
	c, err := NewWithCookie(s.URL, cookie)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err = c.RPC(context.Background(), []byte(`{"method":"session/list"}`)); err == nil {
			t.Fatal("accepted expired cookie")
		}
	}
	if requests != 2 {
		t.Fatalf("unexpected exchange count %d", requests)
	}
	for _, invalid := range []string{"dsh-auth-wrong=value", cookie + "; other=value", cookie + "\r\nX-Test: secret", strings.Split(cookie, "=")[0] + "="} {
		if _, err := NewWithCookie(s.URL, invalid); err == nil {
			t.Fatal("accepted invalid cookie")
		}
	}
}

func TestDesktopCookieAuthority(t *testing.T) {
	for _, origins := range [][2]string{{"http://127.0.0.1", "http://127.0.0.1:80"}, {"https://localhost", "https://LOCALHOST:443"}, {"http://[::1]", "http://[::1]:80"}} {
		if authCookieName(origins[0]) != authCookieName(origins[1]) {
			t.Fatal("default port authority drift")
		}
		if _, err := NewWithCookie(origins[1], authCookieName(origins[0])+"=valid-cookie"); err != nil {
			t.Fatal(err)
		}
	}
	for _, origin := range []string{"http://192.168.10.101:19387", "http://example.com", "http://127.0.0.1:19388"} {
		if _, err := NewWithCookie(origin, authCookieName("http://127.0.0.1:19387")+"=valid-cookie"); err == nil {
			t.Fatal("accepted wrong origin")
		}
	}
}

func TestLocalhostCaseVariantsDialOnlyToIPv4Loopback(t *testing.T) {
	for _, origin := range []string{
		"http://localhost:19387",
		"http://LOCALHOST:19387",
		"http://LoCaLhOsT:19387",
	} {
		if got := upstreamDialAddress(origin); got != "127.0.0.1:19387" {
			t.Fatalf("upstreamDialAddress(%q) = %q, want loopback pin", origin, got)
		}
	}
	if got := upstreamDialAddress("http://127.0.0.2:19387"); got != "127.0.0.2:19387" {
		t.Fatalf("IP loopback address changed unexpectedly: %q", got)
	}
}
