package main

import (
	"app.deepseek.harness/bridge/internal/auth"
	"app.deepseek.harness/bridge/internal/config"
	"app.deepseek.harness/bridge/internal/gateway"
	"app.deepseek.harness/bridge/internal/upstream"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"
)

func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("commands: init, routes, pair, devices, revoke, serve")
	}
	fs := flag.NewFlagSet(args[0], flag.ContinueOnError)
	state := fs.String("state", "", "private state directory")
	listen := fs.String("listen", "127.0.0.1:3090", "listen address")
	upstreamOrigin := fs.String("upstream", "http://127.0.0.1:3080", "loopback dsh origin")
	device := fs.String("device", "", "device ID")
	cert := fs.String("tls-cert", "", "TLS certificate file")
	key := fs.String("tls-key", "", "TLS key file")
	proxy := fs.Bool("behind-tls-proxy", false, "TLS proxy on same host only")
	tokenFile := fs.String("dsh-token-file", "", "private file with dsh launch token")
	origin := fs.String("public-origin", "http://127.0.0.1:3090", "client origin")
	if e := fs.Parse(args[1:]); e != nil {
		return e
	}
	if *state == "" {
		return fmt.Errorf("--state is required")
	}
	switch args[0] {
	case "init":
		_, e := config.Init(*state, config.Config{ProtocolVersion: 1, Listen: *listen, Upstream: *upstreamOrigin, PublicOrigin: *origin})
		return e
	case "pair":
		c, e := config.Load(*state)
		if e != nil {
			return e
		}
		store, e := auth.Open(*state)
		if e != nil {
			return e
		}
		code, e := store.NewPairing(time.Now())
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"connection": map[string]any{"protocolVersion": 1, "origin": c.PublicOrigin, "pairPath": c.Routes.Pair}, "pairingCode": code})
	case "devices":
		store, e := auth.Open(*state)
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(store.Devices())
	case "revoke":
		store, e := auth.Open(*state)
		if e != nil {
			return e
		}
		return store.Revoke(*device)
	case "serve":
		c, e := config.Load(*state)
		if e != nil {
			return e
		}
		if e = validateServe(c, *cert, *key, *proxy); e != nil {
			return e
		}
		store, e := auth.Open(*state)
		if e != nil {
			return e
		}
		token := ""
		if *tokenFile != "" {
			b, e := os.ReadFile(*tokenFile)
			if e != nil {
				return fmt.Errorf("unable to read dsh token file")
			}
			token = strings.TrimSpace(string(b))
			if token == "" || len(token) > 4096 {
				return fmt.Errorf("invalid dsh token file")
			}
		}
		up, e := upstream.New(c.Upstream, token)
		if e != nil {
			return e
		}
		g := gateway.New(c, store, up)
		server := &http.Server{Addr: c.Listen, Handler: g, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
		if *cert != "" {
			return server.ListenAndServeTLS(*cert, *key)
		}
		return server.ListenAndServe()
	case "routes":
		c, e := config.Load(*state)
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(c.Routes)
	default:
		return fmt.Errorf("unknown command")
	}
}
func main() {
	if e := run(os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}

func validateServe(c config.Config, cert, key string, proxy bool) error {
	if (cert == "") != (key == "") {
		return fmt.Errorf("both TLS files required")
	}
	if cert != "" {
		return nil
	}
	if !config.PlainListenerAllowed(c.Listen) {
		return fmt.Errorf("plaintext listener must bind loopback")
	}
	if strings.HasPrefix(c.PublicOrigin, "https://") && !proxy {
		return fmt.Errorf("HTTPS origin requires TLS or explicit loopback proxy mode")
	}
	return nil
}
