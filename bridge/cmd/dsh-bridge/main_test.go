package main

import (
	"app.deepseek.harness/bridge/internal/config"
	"testing"
)

func TestRefusesPlainPublicListener(t *testing.T) {
	c := config.Config{Listen: "0.0.0.0:443", PublicOrigin: "https://example.com"}
	if validateServe(c, "", "", false) == nil {
		t.Fatal("accepted public plaintext")
	}
	if validateServe(c, "", "", true) == nil {
		t.Fatal("proxy mode reachable publicly")
	}
	c.Listen = "127.0.0.1:3090"
	if e := validateServe(c, "", "", true); e != nil {
		t.Fatal(e)
	}
	if validateServe(c, "cert", "", false) == nil {
		t.Fatal("partial TLS accepted")
	}
}
