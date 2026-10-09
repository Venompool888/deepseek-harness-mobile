package auth

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPairingOnlyOnce(t *testing.T) {
	s, e := Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	now := time.Unix(1000, 0)
	code, e := s.NewPairing(now)
	if e != nil {
		t.Fatal(e)
	}
	c, e := s.Pair(code, "Pixel 10", now)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Pair(code, "second", now); e == nil {
		t.Fatal("code reused")
	}
	if _, ok := s.Authenticate(c.DeviceToken); !ok {
		t.Fatal("not authenticated")
	}
	if e = s.Revoke(c.DeviceID); e != nil {
		t.Fatal(e)
	}
	if _, ok := s.Authenticate(c.DeviceToken); ok {
		t.Fatal("revoked works")
	}
}
func TestPairExpiryAndConcurrency(t *testing.T) {
	s, _ := Open(t.TempDir())
	now := time.Now()
	code, _ := s.NewPairing(now)
	if _, e := s.Pair(code, "old", now.Add(10*time.Minute)); e == nil {
		t.Fatal("expiry boundary accepted")
	}
	code, _ = s.NewPairing(now)
	var wins atomic.Int32
	var wg sync.WaitGroup
	for range 12 {
		wg.Go(func() {
			if _, e := s.Pair(code, "phone", now); e == nil {
				wins.Add(1)
			}
		})
	}
	wg.Wait()
	if wins.Load() != 1 {
		t.Fatal("concurrent redemptions", wins.Load())
	}
}
func TestExternalRevocationAndPairingVisible(t *testing.T) {
	dir := t.TempDir()
	server, _ := Open(dir)
	cli, _ := Open(dir)
	now := time.Now()
	code, _ := cli.NewPairing(now)
	c, e := server.Pair(code, "phone", now)
	if e != nil {
		t.Fatal(e)
	}
	if e = cli.Revoke(c.DeviceID); e != nil {
		t.Fatal(e)
	}
	if _, ok := server.Authenticate(c.DeviceToken); ok {
		t.Fatal("server cache accepted revoked token")
	}
	restarted, _ := Open(dir)
	if _, ok := restarted.Authenticate(c.DeviceToken); ok {
		t.Fatal("restarted accepted revoked")
	}
}
func TestPairWriteFailureDoesNotConsumeCode(t *testing.T) {
	s, _ := Open(t.TempDir())
	now := time.Now()
	code, _ := s.NewPairing(now)
	original := s.write
	s.write = func(string, any) error { return errors.New("disk full") }
	if _, e := s.Pair(code, "phone", now); e == nil {
		t.Fatal("success without persistence")
	}
	s.write = original
	if _, e := s.Pair(code, "phone", now); e != nil {
		t.Fatal("consumed code after failure", e)
	}
}
func TestNoPlaintextStoredAndCorruptStateFails(t *testing.T) {
	dir := t.TempDir()
	s, _ := Open(dir)
	code, _ := s.NewPairing(time.Now())
	c, _ := s.Pair(code, "phone", time.Now())
	b, _ := os.ReadFile(filepath.Join(dir, "auth.json"))
	for _, secret := range []string{code, c.DeviceToken} {
		if string(b) == secret {
			t.Fatal("plaintext secret")
		}
		if stringsContains(b, secret) {
			t.Fatal("plaintext in state")
		}
	}
	os.WriteFile(filepath.Join(dir, "auth.json"), []byte("broken"), 0600)
	if _, e := Open(dir); e == nil {
		t.Fatal("corrupt state reset")
	}
	if _, ok := s.Authenticate(c.DeviceToken); ok {
		t.Fatal("failed open on corruption")
	}
}
func stringsContains(b []byte, s string) bool { return bytes.Contains(b, []byte(s)) }
