package gateway

import (
	"context"
	"encoding/json"
	"github.com/coder/websocket"
	"net/http"
	"time"
)

type activeStream struct{ cancel context.CancelFunc }

func (g *Gateway) CloseDevice(id string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	for s := range g.streams[id] {
		s.cancel()
	}
}
func (g *Gateway) stream(w http.ResponseWriter, r *http.Request, id, token string) {
	if origin := r.Header.Get("Origin"); origin != "" && origin != g.cfg.PublicOrigin {
		failure(w, 403, "origin-rejected")
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	active := &activeStream{cancel: cancel}
	g.mu.Lock()
	_, valid := g.store.Authenticate(token)
	if !valid {
		g.mu.Unlock()
		empty(w)
		return
	}
	if len(g.streams[id]) >= 4 {
		g.mu.Unlock()
		failure(w, 429, "rate-limited")
		return
	}
	if g.streams[id] == nil {
		g.streams[id] = map[*activeStream]struct{}{}
	}
	g.streams[id][active] = struct{}{}
	g.mu.Unlock()
	defer func() {
		g.mu.Lock()
		delete(g.streams[id], active)
		if len(g.streams[id]) == 0 {
			delete(g.streams, id)
		}
		g.mu.Unlock()
	}()
	headers, e := g.up.Headers()
	if e != nil {
		failure(w, 502, "upstream-unavailable")
		return
	}
	headers.Set("Origin", g.cfg.Upstream)
	dialCtx, stopDial := context.WithTimeout(ctx, 15*time.Second)
	upstream, _, e := websocket.Dial(dialCtx, g.up.WSURL(), &websocket.DialOptions{HTTPHeader: headers, HTTPClient: g.up.HTTPClient(), CompressionMode: websocket.CompressionDisabled})
	stopDial()
	if e != nil {
		failure(w, 502, "upstream-unavailable")
		return
	}
	defer upstream.CloseNow()
	if _, valid := g.store.Authenticate(token); !valid {
		empty(w)
		return
	}
	// Origin is already checked against configured public origin, including TLS proxy deployments.
	client, e := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true, CompressionMode: websocket.CompressionDisabled})
	if e != nil {
		return
	}
	defer client.CloseNow()
	client.SetReadLimit(16 << 20)
	upstream.SetReadLimit(16 << 20)
	done := make(chan struct{}, 2)
	relay := func(source, target *websocket.Conn, validate bool) {
		defer func() { done <- struct{}{} }()
		for {
			kind, b, e := source.Read(ctx)
			if e != nil {
				return
			}
			if _, ok := g.store.Authenticate(token); !ok {
				return
			}
			if kind != websocket.MessageText {
				return
			}
			if validate && !validFrame(b) {
				return
			}
			writeCtx, stop := context.WithTimeout(ctx, 15*time.Second)
			e = target.Write(writeCtx, kind, b)
			stop()
			if e != nil {
				return
			}
		}
	}
	go relay(client, upstream, true)
	go relay(upstream, client, false)
	go func() {
		ticker := time.NewTicker(250 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if _, ok := g.store.Authenticate(token); !ok {
					cancel()
					return
				}
			}
		}
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
	cancel()
	client.CloseNow()
	upstream.CloseNow()
}
func validFrame(b []byte) bool {
	var f struct {
		Type     string `json:"type"`
		ID       string `json:"streamId"`
		Endpoint string `json:"endpoint"`
		Payload  struct {
			Args map[string]json.RawMessage `json:"args"`
		} `json:"payload"`
	}
	if json.Unmarshal(b, &f) != nil || f.ID == "" || len(f.ID) > 256 {
		return false
	}
	switch f.Type {
	case "open":
		return allowedStream[f.Endpoint] && f.Payload.Args != nil
	case "cancel":
		return true
	}
	return false
}
