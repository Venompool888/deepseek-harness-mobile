package upstream

import (
	"app.deepseek.harness/bridge/internal/config"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

type Client struct {
	origin, launchToken, cookie string
	http                        *http.Client
	mu                          sync.Mutex
}

func New(origin, launchToken string) (*Client, error) {
	o, e := config.Origin(origin, true)
	if e != nil {
		return nil, e
	}
	u, _ := url.Parse(o)
	host := u.Hostname()
	if host == "localhost" {
		host = "127.0.0.1"
	}
	port := u.Port()
	if port == "" {
		port = "80"
		if u.Scheme == "https" {
			port = "443"
		}
	}
	dialer := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	transport := &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		return dialer.DialContext(ctx, network, net.JoinHostPort(host, port))
	}, TLSHandshakeTimeout: 15 * time.Second, ResponseHeaderTimeout: 60 * time.Second}
	return &Client{origin: o, launchToken: launchToken, http: &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}
func (c *Client) WSURL() string {
	return strings.Replace(strings.Replace(c.origin, "https://", "wss://", 1), "http://", "ws://", 1) + "/api/remote.mux"
}
func (c *Client) HTTPClient() *http.Client { return c.http }
func (c *Client) Headers() (http.Header, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	h := http.Header{}
	if c.launchToken == "" {
		return h, nil
	}
	if c.cookie == "" {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		r, e := http.NewRequestWithContext(ctx, "GET", c.origin+"/?token="+url.QueryEscape(c.launchToken), nil)
		if e != nil {
			return nil, errors.New("internal authentication unavailable")
		}
		resp, e := c.http.Do(r)
		if e != nil {
			return nil, errors.New("internal authentication unavailable")
		}
		defer resp.Body.Close()
		base, _ := url.Parse(c.origin + "/")
		loc, e := resp.Location()
		if resp.StatusCode != 303 || e != nil || loc.Scheme != base.Scheme || loc.Host != base.Host || loc.Path != "/" || loc.RawQuery != "" || loc.Fragment != "" {
			return nil, errors.New("internal authentication unavailable")
		}
		u, _ := url.Parse(c.origin)
		authority := strings.ToLower(u.Host)
		if u.Port() == "80" {
			authority = u.Hostname()
			if strings.Contains(authority, ":") {
				authority = "[" + authority + "]"
			}
		}
		sum := sha256.Sum256([]byte(authority))
		name := "dsh-auth-" + base64.RawURLEncoding.EncodeToString(sum[:])
		for _, cookie := range resp.Cookies() {
			if cookie.Name == name {
				c.cookie = cookie.Name + "=" + cookie.Value
				break
			}
		}
		if c.cookie == "" {
			return nil, errors.New("internal authentication unavailable")
		}
	}
	h.Set("Cookie", c.cookie)
	return h, nil
}
func (c *Client) request(ctx context.Context, method, path string, body io.Reader) (*http.Response, error) {
	h, e := c.Headers()
	if e != nil {
		return nil, e
	}
	r, e := http.NewRequestWithContext(ctx, method, c.origin+path, body)
	if e != nil {
		return nil, errors.New("upstream unavailable")
	}
	r.Header = h
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	resp, e := c.http.Do(r)
	if e != nil {
		return nil, errors.New("upstream unavailable")
	}
	if resp.StatusCode != 200 {
		resp.Body.Close()
		if resp.StatusCode == 401 || resp.StatusCode == 403 {
			c.mu.Lock()
			c.cookie = ""
			c.mu.Unlock()
		}
		return nil, errors.New("upstream unavailable")
	}
	return resp, nil
}
func (c *Client) RPC(ctx context.Context, raw []byte) ([]byte, error) {
	var envelope struct {
		Method string `json:"method"`
	}
	if json.Unmarshal(raw, &envelope) != nil {
		return nil, errors.New("invalid RPC")
	}
	resp, e := c.request(ctx, "POST", "/api/"+envelope.Method, bytes.NewReader(raw))
	if e != nil {
		return nil, e
	}
	defer resp.Body.Close()
	if !strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "application/json") {
		return nil, errors.New("invalid upstream response")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, (16<<20)+1))
	if e != nil || len(b) > 16<<20 || !json.Valid(b) {
		return nil, errors.New("invalid upstream response")
	}
	return b, nil
}
func (c *Client) Export(ctx context.Context, id string, desc bool) (*http.Response, error) {
	q := url.Values{"sessionId": {id}, "includeDescendants": {"false"}}
	if desc {
		q.Set("includeDescendants", "true")
	}
	return c.request(ctx, "GET", "/api/session.export?"+q.Encode(), nil)
}
