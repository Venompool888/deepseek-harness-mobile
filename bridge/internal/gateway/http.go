package gateway

import (
	"app.deepseek.harness/bridge/internal/auth"
	"app.deepseek.harness/bridge/internal/config"
	"app.deepseek.harness/bridge/internal/upstream"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type bucket struct {
	since time.Time
	count int
}
type Gateway struct {
	cfg             config.Config
	store           *auth.Store
	up              *upstream.Client
	mu              sync.Mutex
	requests        map[string]int
	pairs           map[string]bucket
	streamHandler   func(http.ResponseWriter, *http.Request, string, string)
	streams         map[string]map[*activeStream]struct{}
	bodyTimeout     time.Duration
	responseTimeout time.Duration
	exportTimeout   time.Duration
	exportLimit     int64
}

func New(cfg config.Config, s *auth.Store, up *upstream.Client) *Gateway {
	g := &Gateway{cfg: cfg, store: s, up: up, requests: map[string]int{}, pairs: map[string]bucket{}, streams: map[string]map[*activeStream]struct{}{}, bodyTimeout: 15 * time.Second, responseTimeout: 75 * time.Second, exportTimeout: 5 * time.Minute, exportLimit: 512 << 20}
	g.streamHandler = g.stream
	return g
}
func empty(w http.ResponseWriter) { w.Header().Set("Content-Length", "0"); w.WriteHeader(404) }
func failure(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"code": code}})
}
func decode(w http.ResponseWriter, r *http.Request, max int64, out any) error {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, max))
	if e := d.Decode(out); e != nil {
		return e
	}
	if d.Decode(new(any)) != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
func (g *Gateway) pairAllowed(peer string) bool {
	host, _, _ := net.SplitHostPort(peer)
	g.mu.Lock()
	defer g.mu.Unlock()
	now := time.Now()
	b := g.pairs[host]
	if now.Sub(b.since) >= time.Minute {
		b = bucket{since: now}
	}
	if len(g.pairs) >= 4096 {
		for k, v := range g.pairs {
			if now.Sub(v.since) >= time.Minute {
				delete(g.pairs, k)
			}
		}
		if _, exists := g.pairs[host]; !exists && len(g.pairs) >= 4096 {
			return false
		}
	}
	b.count++
	g.pairs[host] = b
	return b.count <= 10
}
func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Even rejected requests may be drained by net/http after this handler returns.
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(g.bodyTimeout))
	w.Header().Set("Cache-Control", "no-store")
	if r.URL.RawQuery != "" || r.URL.ForceQuery || r.URL.RawPath != "" || !config.ValidPath(r.URL.Path) {
		empty(w)
		return
	}
	path := r.URL.Path
	if path == g.cfg.Routes.Pair {
		g.deadlines(w, false)
		if r.Method != "POST" {
			empty(w)
			return
		}
		if !g.pairAllowed(r.RemoteAddr) {
			failure(w, 429, "rate-limited")
			return
		}
		var req struct {
			DeviceName  string `json:"deviceName"`
			PairingCode string `json:"pairingCode"`
		}
		if decode(w, r, 8192, &req) != nil {
			empty(w)
			return
		}
		cred, e := g.store.Pair(req.PairingCode, req.DeviceName, time.Now())
		if e != nil {
			empty(w)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"protocolVersion": 1, "deviceId": cred.DeviceID, "deviceToken": cred.DeviceToken, "routes": map[string]string{"rpc": g.cfg.Routes.RPC, "stream": g.cfg.Routes.Stream, "export": g.cfg.Routes.Export}})
		return
	}
	if path != g.cfg.Routes.RPC && path != g.cfg.Routes.Stream && path != g.cfg.Routes.Export {
		empty(w)
		return
	}
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		empty(w)
		return
	}
	token := strings.TrimPrefix(header, "Bearer ")
	id, ok := g.store.Authenticate(token)
	if !ok {
		empty(w)
		return
	}
	if path == g.cfg.Routes.Stream {
		_ = http.NewResponseController(w).SetReadDeadline(time.Time{})
		if r.Method == "GET" && g.streamHandler != nil {
			g.streamHandler(w, r, id, token)
		} else {
			empty(w)
		}
		return
	}
	if r.Method != "POST" {
		empty(w)
		return
	}
	g.mu.Lock()
	if g.requests[id] >= 8 {
		g.mu.Unlock()
		failure(w, 429, "rate-limited")
		return
	}
	g.deadlines(w, path == g.cfg.Routes.Export)
	g.requests[id]++
	g.mu.Unlock()
	defer func() {
		g.mu.Lock()
		g.requests[id]--
		if g.requests[id] == 0 {
			delete(g.requests, id)
		}
		g.mu.Unlock()
	}()
	if path == g.cfg.Routes.RPC {
		raw, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 8<<20))
		if e != nil {
			failure(w, 413, "request-too-large")
			return
		}
		var req struct {
			Type    string `json:"type"`
			RPCID   string `json:"rpcId"`
			Method  string `json:"method"`
			Payload struct {
				Args map[string]json.RawMessage `json:"args"`
			} `json:"payload"`
		}
		if json.Unmarshal(raw, &req) != nil || req.Type != "client-request" || req.RPCID == "" || len(req.RPCID) > 256 || !allowedRPC[req.Method] || req.Payload.Args == nil {
			failure(w, 400, "invalid-request")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
		defer cancel()
		response, e := g.up.RPC(ctx, raw)
		if e != nil {
			failure(w, 502, "upstream-unavailable")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(response)
		return
	}
	var req struct {
		SessionID string `json:"sessionId"`
		Desc      bool   `json:"includeDescendants"`
	}
	if decode(w, r, 8192, &req) != nil || req.SessionID == "" || len(req.SessionID) > 256 {
		failure(w, 400, "invalid-request")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), g.exportTimeout)
	defer cancel()
	resp, e := g.up.Export(ctx, req.SessionID, req.Desc)
	if e != nil {
		failure(w, 502, "upstream-unavailable")
		return
	}
	defer resp.Body.Close()
	if !strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "application/zip") {
		failure(w, 502, "upstream-unavailable")
		return
	}
	if resp.ContentLength > g.exportLimit {
		failure(w, 502, "export-too-large")
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="session.zip"`)
	// A clean public EOF must mean that the whole upstream ZIP arrived.
	if _, e := io.Copy(w, io.LimitReader(resp.Body, g.exportLimit)); e != nil {
		panic(http.ErrAbortHandler)
	}
	var extra [1]byte
	if n, e := resp.Body.Read(extra[:]); n != 0 || e != io.EOF {
		panic(http.ErrAbortHandler)
	}
}

func (g *Gateway) deadlines(w http.ResponseWriter, export bool) {
	c := http.NewResponseController(w)
	_ = c.SetReadDeadline(time.Now().Add(g.bodyTimeout))
	timeout := g.responseTimeout
	if export {
		timeout = g.exportTimeout
	}
	_ = c.SetWriteDeadline(time.Now().Add(timeout))
}
