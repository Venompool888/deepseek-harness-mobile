package gateway

import (
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestTruncatedExportFailsClient(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/zip")
		w.Header().Set("Content-Length", "1000")
		io.WriteString(w, "ZIP")
	}))
	s := httptest.NewServer(g)
	defer s.Close()
	r, _ := http.NewRequest("POST", s.URL+cfg.Routes.Export, strings.NewReader(`{"sessionId":"s"}`))
	r.Header.Set("Authorization", "Bearer "+token)
	x, e := s.Client().Do(r)
	if e != nil {
		return
	}
	defer x.Body.Close()
	b, e := io.ReadAll(x.Body)
	if x.StatusCode == 200 && e == nil {
		t.Fatalf("truncated ZIP looks successful: %d bytes", len(b))
	}
}
func TestStalledBodyReleasesHandler(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("unexpected upstream") }))
	g.bodyTimeout = 100 * time.Millisecond
	// Test production's bounded deadline through a real socket, without waiting 15 s.
	s := httptest.NewServer(g)
	defer s.Close()
	c, e := net.Dial("tcp", strings.TrimPrefix(s.URL, "http://"))
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	io.WriteString(c, "POST "+cfg.Routes.RPC+" HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer "+token+"\r\nContent-Length: 100\r\n\r\n{")
	c.SetReadDeadline(time.Now().Add(350 * time.Millisecond))
	b := make([]byte, 1024)
	_, e = c.Read(b)
	if e != nil {
		t.Fatalf("body stalled without server deadline: %v", e)
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if len(g.requests) != 0 {
		t.Fatal("body timeout retained request slot")
	}
}

func TestExportOverflowFailsClient(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/zip")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		io.WriteString(w, strings.Repeat("Z", 65))
	}))
	g.exportLimit = 64
	s := httptest.NewServer(g)
	defer s.Close()
	r, _ := http.NewRequest("POST", s.URL+cfg.Routes.Export, strings.NewReader(`{"sessionId":"s"}`))
	r.Header.Set("Authorization", "Bearer "+token)
	x, e := s.Client().Do(r)
	if e != nil {
		return
	}
	defer x.Body.Close()
	_, e = io.ReadAll(x.Body)
	if x.StatusCode == 200 && e == nil {
		t.Fatal("oversize ZIP looks successful")
	}
}
func TestStalledExportReleasesRequestSlot(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/zip")
		io.CopyN(w, strings.NewReader(strings.Repeat("z", 16<<20)), 16<<20)
	}))
	g.exportTimeout = 100 * time.Millisecond
	done := make(chan struct{})
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(done)
		defer func() { _ = recover() }()
		g.ServeHTTP(w, r)
	}))
	defer s.Close()
	c, e := net.Dial("tcp", strings.TrimPrefix(s.URL, "http://"))
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	c.(*net.TCPConn).SetReadBuffer(1024)
	io.WriteString(c, "POST "+cfg.Routes.Export+" HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer "+token+"\r\nContent-Length: 17\r\n\r\n{\"sessionId\":\"s\"}")
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("blocked downstream retains request slot")
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if len(g.requests) != 0 {
		t.Fatal("request slot leaked")
	}
}
