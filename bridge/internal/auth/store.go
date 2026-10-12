package auth

import (
	"app.deepseek.harness/bridge/internal/config"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

type DeviceCredential struct {
	DeviceID    string `json:"deviceId"`
	DeviceToken string `json:"deviceToken"`
}
type DeviceInfo struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	CreatedAt int64  `json:"createdAt"`
	Revoked   bool   `json:"revoked"`
	TokenHash string `json:"-"`
}
type deviceRecord struct {
	DeviceInfo
	Hash string `json:"hash"`
}
type pairing struct {
	Hash    string `json:"hash"`
	Expires int64  `json:"expires"`
}
type state struct {
	Version  int            `json:"version"`
	Pairings []pairing      `json:"pairings"`
	Devices  []deviceRecord `json:"devices"`
}
type Store struct {
	dir   string
	mu    sync.Mutex
	write func(string, any) error
}

func Open(dir string) (*Store, error) {
	if e := os.MkdirAll(dir, 0700); e != nil {
		return nil, e
	}
	s := &Store{dir: dir, write: config.WriteJSON}
	e := s.transaction(func(st *state) (bool, error) { return false, nil })
	return s, e
}
func random() (string, error) {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		return "", e
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
func digest(v string) string { b := sha256.Sum256([]byte(v)); return hex.EncodeToString(b[:]) }

// Each transaction reloads state so a separate local admin process can pair/revoke.
// Exclusive lock files serialize processes. A stale lock is fail-closed and needs
// removal by the owner only after confirming all bridge processes have stopped.
func (s *Store) transaction(fn func(*state) (bool, error)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	lock := filepath.Join(s.dir, ".auth.lock")
	deadline := time.Now().Add(3 * time.Second)
	for {
		f, e := os.OpenFile(lock, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e == nil {
			f.Close()
			break
		}
		if !errors.Is(e, os.ErrExist) || time.Now().After(deadline) {
			return errors.New("credential state unavailable")
		}
		time.Sleep(5 * time.Millisecond)
	}
	defer os.Remove(lock)
	path := filepath.Join(s.dir, "auth.json")
	st := state{Version: 1}
	b, e := os.ReadFile(path)
	if e == nil {
		if len(b) > 4<<20 || json.Unmarshal(b, &st) != nil || st.Version != 1 {
			return errors.New("invalid credential state")
		}
	} else if !errors.Is(e, os.ErrNotExist) {
		return errors.New("credential state unavailable")
	}
	changed, e := fn(&st)
	if e != nil {
		return e
	}
	if changed {
		return s.write(path, st)
	}
	return nil
}
func (s *Store) NewPairing(now time.Time) (string, error) {
	code, e := random()
	if e != nil {
		return "", e
	}
	e = s.transaction(func(st *state) (bool, error) {
		kept := st.Pairings[:0]
		for _, p := range st.Pairings {
			if p.Expires > now.Unix() {
				kept = append(kept, p)
			}
		}
		if len(kept) >= 128 {
			return false, errors.New("too many active pairing codes")
		}
		st.Pairings = append(kept, pairing{digest(code), now.Add(10 * time.Minute).Unix()})
		return true, nil
	})
	if e != nil {
		return "", e
	}
	return code, nil
}
func (s *Store) Pair(code, name string, now time.Time) (DeviceCredential, error) {
	var out DeviceCredential
	name = strings.TrimSpace(name)
	if utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 80 || strings.IndexFunc(name, unicode.IsControl) >= 0 || len(code) != 43 {
		return out, errors.New("pairing rejected")
	}
	token, e := random()
	if e != nil {
		return out, e
	}
	id, e := random()
	if e != nil {
		return out, e
	}
	e = s.transaction(func(st *state) (bool, error) {
		i := -1
		for n, p := range st.Pairings {
			if p.Hash == digest(code) && p.Expires > now.Unix() {
				i = n
				break
			}
		}
		if i < 0 || len(st.Devices) >= 1000 {
			return false, errors.New("pairing rejected")
		}
		st.Pairings = append(st.Pairings[:i], st.Pairings[i+1:]...)
		st.Devices = append(st.Devices, deviceRecord{DeviceInfo: DeviceInfo{ID: id, Name: name, CreatedAt: now.Unix()}, Hash: digest(token)})
		return true, nil
	})
	if e != nil {
		return out, e
	}
	return DeviceCredential{id, token}, nil
}
func (s *Store) Authenticate(token string) (string, bool) {
	if len(token) != 43 {
		return "", false
	}
	id := ""
	e := s.transaction(func(st *state) (bool, error) {
		h := digest(token)
		for _, d := range st.Devices {
			if !d.Revoked && d.Hash == h {
				id = d.ID
				break
			}
		}
		return false, nil
	})
	return id, e == nil && id != ""
}
func (s *Store) Revoke(id string) error {
	return s.transaction(func(st *state) (bool, error) {
		for i := range st.Devices {
			if st.Devices[i].ID == id {
				st.Devices[i].Revoked = true
				return true, nil
			}
		}
		return false, errors.New("device not found")
	})
}
func (s *Store) Devices() []DeviceInfo {
	var out []DeviceInfo
	s.transaction(func(st *state) (bool, error) {
		for _, d := range st.Devices {
			out = append(out, d.DeviceInfo)
		}
		return false, nil
	})
	return out
}
