package gateway

import (
	"app.deepseek.harness/bridge/internal/auth"
	"app.deepseek.harness/bridge/internal/config"
	"app.deepseek.harness/bridge/internal/upstream"
	"context"
	"encoding/json"
	"github.com/coder/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestStreamRelayAndRevoke(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	frames := make(chan []byte, 1)
	closed := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			t.Error("public token leaked")
		}
		c, e := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if e != nil {
			return
		}
		defer close(closed)
		defer c.CloseNow()
		_, b, e := c.Read(ctx)
		if e != nil {
			return
		}
		frames <- b
		c.Write(ctx, websocket.MessageText, []byte(`{"type":"item","streamId":"s1","value":{"type":"baseline"}}`))
		c.Read(ctx)
	}))
	defer server.Close()
	cfg, e := config.Init(t.TempDir(), config.Config{ProtocolVersion: 1, Listen: "127.0.0.1:3090", Upstream: server.URL, PublicOrigin: "http://127.0.0.1:3090"})
	if e != nil {
		t.Fatal(e)
	}
	store, _ := auth.Open(t.TempDir())
	code, _ := store.NewPairing(time.Now())
	credential, _ := store.Pair(code, "phone", time.Now())
	up, _ := upstream.New(server.URL, "")
	g := New(cfg, store, up)
	public := httptest.NewServer(g)
	defer public.Close()
	headers := http.Header{"Authorization": {"Bearer " + credential.DeviceToken}}
	c, _, e := websocket.Dial(ctx, strings.Replace(public.URL, "http:", "ws:", 1)+cfg.Routes.Stream, &websocket.DialOptions{HTTPHeader: headers})
	if e != nil {
		t.Fatal(e)
	}
	defer c.CloseNow()
	frame := `{"type":"open","streamId":"s1","endpoint":"session/control","payload":{"args":{}}}`
	if e = c.Write(ctx, websocket.MessageText, []byte(frame)); e != nil {
		t.Fatal(e)
	}
	select {
	case b := <-frames:
		if string(b) != frame {
			t.Fatal("changed frame")
		}
	case <-ctx.Done():
		t.Fatal("no upstream frame")
	}
	_, b, e := c.Read(ctx)
	if e != nil || !json.Valid(b) {
		t.Fatal("no downstream frame", e)
	}
	store.Revoke(credential.DeviceID)
	if _, _, e = c.Read(ctx); e == nil {
		t.Fatal("revoked stream works")
	}
	select {
	case <-closed:
	case <-ctx.Done():
		t.Fatal("leaked upstream")
	}
}
func TestStreamInvalidEndpoint(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, e := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if e == nil {
			defer c.CloseNow()
			if _, _, e = c.Read(ctx); e == nil {
				t.Error("forwarded bad frame")
			}
		}
	}))
	s := httptest.NewServer(g)
	defer s.Close()
	c, _, e := websocket.Dial(ctx, strings.Replace(s.URL, "http:", "ws:", 1)+cfg.Routes.Stream, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if e != nil {
		t.Fatal(e)
	}
	defer c.CloseNow()
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"open","streamId":"x","endpoint":"../../","payload":{"args":{}}}`))
	if _, _, e = c.Read(ctx); e == nil {
		t.Fatal("bad frame accepted")
	}
}

func TestRevokeDuringUpstreamWSHandshake(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	entered := make(chan struct{})
	release := make(chan struct{})
	g, cfg, store, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-release
		c, e := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if e == nil {
			defer c.CloseNow()
			c.Read(ctx)
		}
	}))
	s := httptest.NewServer(g)
	defer s.Close()
	done := make(chan error, 1)
	go func() {
		c, _, e := websocket.Dial(ctx, strings.Replace(s.URL, "http:", "ws:", 1)+cfg.Routes.Stream, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
		if c != nil {
			c.CloseNow()
		}
		done <- e
	}()
	<-entered
	id, _ := store.Authenticate(token)
	if e := store.Revoke(id); e != nil {
		t.Fatal(e)
	}
	close(release)
	if e := <-done; e == nil {
		t.Fatal("revoked device admitted after blocked handshake")
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if len(g.streams) != 0 {
		t.Fatal("stream reservation leaked")
	}
}
