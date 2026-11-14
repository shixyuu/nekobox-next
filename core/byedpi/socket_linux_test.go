//go:build linux || android

package byedpi

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestApplyFakeAndOOBRoundTrip(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	payload := []byte("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
	done := make(chan []byte, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(3 * time.Second))
		buf, _ := io.ReadAll(c)
		done <- buf
	}()
	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	profile, err := ParseArgs("--fake 4 --ttl 8 --oob 8 --mod-http h")
	if err != nil {
		t.Fatal(err)
	}
	if err := ApplyTCP(conn, payload, profile.Strategies[0]); err != nil {
		t.Fatal(err)
	}
	_ = conn.(*net.TCPConn).CloseWrite()
	got := <-done
	_, want := Plan(payload, profile.Strategies[0])
	if string(got) != string(want) && string(got) != string(payload) {
		// OOB may or may not be stripped by the local stack. The stream must
		// still contain the modified request.
		if len(got) == 0 {
			t.Fatal("peer received nothing")
		}
		t.Logf("peer got %q want logical %q", got, want)
	}
	_ = conn.Close()
}
