package config

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type Routes struct {
	Pair   string `json:"pair"`
	RPC    string `json:"rpc"`
	Stream string `json:"stream"`
	Export string `json:"export"`
}
type Config struct {
	ProtocolVersion int    `json:"protocolVersion"`
	Listen          string `json:"listen"`
	Upstream        string `json:"upstream"`
	PublicOrigin    string `json:"publicOrigin"`
	Routes          Routes `json:"-"`
}

var routePattern = regexp.MustCompile(`^/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$`)

func ValidPath(p string) bool { return routePattern.MatchString(p) && len(p) <= 256 }
func Origin(raw string, loopbackOnly bool) (string, error) {
	u, e := url.Parse(raw)
	if e != nil || u == nil {
		return "", errors.New("invalid origin")
	}
	if (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return "", errors.New("origin must contain only scheme and authority")
	}
	host := strings.ToLower(u.Hostname())
	ip := net.ParseIP(host)
	local := host == "localhost" || (ip != nil && ip.IsLoopback())
	if loopbackOnly && !local {
		return "", errors.New("upstream must be loopback")
	}
	if !loopbackOnly && u.Scheme == "http" && !local {
		return "", errors.New("public origin requires HTTPS")
	}
	if u.Port() != "" {
		if _, e = net.LookupPort("tcp", u.Port()); e != nil {
			return "", errors.New("invalid port")
		}
	}
	u.Path = ""
	u.RawPath = ""
	return u.String(), nil
}
func validate(c Config, routes bool) error {
	if c.ProtocolVersion != 1 {
		return errors.New("unsupported protocol version")
	}
	if _, _, e := net.SplitHostPort(c.Listen); e != nil {
		return errors.New("invalid listen address")
	}
	if _, e := Origin(c.Upstream, true); e != nil {
		return e
	}
	if _, e := Origin(c.PublicOrigin, false); e != nil {
		return e
	}
	if routes {
		seen := map[string]bool{}
		for _, p := range []string{c.Routes.Pair, c.Routes.RPC, c.Routes.Stream, c.Routes.Export} {
			if !ValidPath(p) || seen[p] {
				return errors.New("invalid or duplicate route")
			}
			seen[p] = true
		}
	}
	return nil
}
func randomPath() (string, error) {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		return "", e
	}
	return "/" + base64.RawURLEncoding.EncodeToString(b[:8]) + "/" + base64.RawURLEncoding.EncodeToString(b[8:]), nil
}
func Init(dir string, c Config) (Config, error) {
	if e := validate(c, false); e != nil {
		return Config{}, e
	}
	if e := os.MkdirAll(dir, 0700); e != nil {
		return Config{}, e
	}
	f, e := os.OpenFile(filepath.Join(dir, "config.json"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return Config{}, fmt.Errorf("initialization refused: %w", e)
	}
	defer f.Close()
	paths := make([]string, 4)
	seen := map[string]bool{}
	for i := range paths {
		for {
			p, e := randomPath()
			if e != nil {
				return Config{}, e
			}
			if !seen[p] {
				paths[i] = p
				seen[p] = true
				break
			}
		}
	}
	c.Routes = Routes{paths[0], paths[1], paths[2], paths[3]}
	if e = WriteJSON(filepath.Join(dir, "routes.json"), c.Routes); e != nil {
		return Config{}, e
	}
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	if e = enc.Encode(c); e != nil {
		return Config{}, e
	}
	if e = f.Sync(); e != nil {
		return Config{}, e
	}
	return c, nil
}
func readJSON(path string, dst any) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	d := json.NewDecoder(io.LimitReader(f, 1<<20))
	d.DisallowUnknownFields()
	if e = d.Decode(dst); e != nil {
		return e
	}
	if d.Decode(new(any)) != io.EOF {
		return errors.New("trailing configuration data")
	}
	return nil
}
func Load(dir string) (Config, error) {
	var c Config
	if e := readJSON(filepath.Join(dir, "config.json"), &c); e != nil {
		return c, e
	}
	if e := readJSON(filepath.Join(dir, "routes.json"), &c.Routes); e != nil {
		return c, e
	}
	return c, validate(c, true)
}

// WriteJSON stages a complete private file before replacing its predecessor.
func WriteJSON(path string, value any) error {
	b, e := json.MarshalIndent(value, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".state-*")
	if e != nil {
		return e
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if e = f.Chmod(0600); e == nil {
		_, e = f.Write(append(b, '\n'))
	}
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e != nil {
		return e
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(tmp, path)
}
func PlainListenerAllowed(listen string) bool {
	host, _, e := net.SplitHostPort(listen)
	if e != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
