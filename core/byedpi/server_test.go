package byedpi

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func TestSOCKSSplitRoundTrip(t *testing.T) {
	echo := echoServer(t)
	defer echo.Close()

	profile, err := ParseArgs("--split 3 --disorder 1")
	if err != nil {
		t.Fatal(err)
	}
	var protected int
	srv, err := Listen("127.0.0.1:0", "user", "secret", profile, func(fd int) bool {
		if fd > 0 {
			protected++
		}
		return true
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	payload := []byte("HELLO-WORLD-1234567890-abcdef")
	got := socksExchange(t, srv.Addr(), "user", "secret", echo.Addr().String(), payload)
	if string(got) != string(payload) {
		t.Fatalf("echo %q", got)
	}
	if protected == 0 {
		t.Fatal("protect was not called")
	}
}

func TestSOCKSRejectsBadPassword(t *testing.T) {
	profile, _ := ParseArgs("--split 1")
	srv, err := Listen("127.0.0.1:0", "user", "secret", profile, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	conn, err := net.Dial("tcp", srv.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_, _ = conn.Write([]byte{5, 1, 2})
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil || buf[1] != 2 {
		t.Fatal(buf, err)
	}
	// version, ulen, user, plen, pass
	_, _ = conn.Write([]byte{1, 4, 'u', 's', 'e', 'r', 3, 'n', 'o', 'p'})
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatal(err)
	}
	if buf[1] == 0 {
		t.Fatal("bad password was accepted")
	}
}

func echoServer(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(c)
		}
	}()
	return ln
}

func socksExchange(t *testing.T, proxy, user, pass, dest string, payload []byte) []byte {
	t.Helper()
	conn, err := net.Dial("tcp", proxy)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write([]byte{5, 1, 2}); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil || buf[0] != 5 || buf[1] != 2 {
		t.Fatalf("method %v %v", buf, err)
	}
	auth := []byte{1, byte(len(user))}
	auth = append(auth, user...)
	auth = append(auth, byte(len(pass)))
	auth = append(auth, pass...)
	if _, err := conn.Write(auth); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(conn, buf); err != nil || buf[1] != 0 {
		t.Fatalf("auth %v %v", buf, err)
	}
	host, portStr, err := net.SplitHostPort(dest)
	if err != nil {
		t.Fatal(err)
	}
	var port int
	for _, c := range portStr {
		port = port*10 + int(c-'0')
	}
	req := []byte{5, 1, 0, 1}
	ip := net.ParseIP(host).To4()
	if ip == nil {
		t.Fatalf("want ipv4 echo, got %s", host)
	}
	req = append(req, ip...)
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		t.Fatal(err)
	}
	rep := make([]byte, 10)
	if _, err := io.ReadFull(conn, rep); err != nil {
		t.Fatal(err)
	}
	if rep[1] != 0 {
		t.Fatalf("connect rep %v", rep)
	}
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	_ = conn.(*net.TCPConn).CloseWrite()
	out, err := io.ReadAll(conn)
	if err != nil {
		t.Fatal(err)
	}
	_ = binary.BigEndian
	return out
}
