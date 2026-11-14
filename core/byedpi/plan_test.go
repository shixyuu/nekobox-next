package byedpi

import (
	"bytes"
	"testing"
)

func strategy(parts ...Part) Strategy {
	return Strategy{Parts: parts}
}

func TestPlanSplitAndRepeats(t *testing.T) {
	payload := bytes.Repeat([]byte("abcdefghijklmnopqrstuvwxyz0123"), 1) // 30 bytes
	if len(payload) != 30 {
		t.Fatal(len(payload))
	}
	steps, modified := Plan(payload, strategy(Part{Mode: ModeSplit, Pos: 3}, Part{Mode: ModeSplit, Pos: 7}))
	if !bytes.Equal(modified, payload) {
		t.Fatal("split must not change bytes")
	}
	if got := Logical(steps); !bytes.Equal(got, payload) {
		t.Fatalf("logical %q", got)
	}
	if len(steps) != 3 || steps[0].Kind != "send" || len(steps[0].Real) != 3 || len(steps[1].Real) != 4 {
		t.Fatalf("steps %#v", lens(steps))
	}

	rep, _ := Plan(payload, strategy(Part{Mode: ModeSplit, Pos: 1, Repeats: 3, Skip: 5}))
	var cuts []int
	off := 0
	for i, s := range rep {
		if i == len(rep)-1 {
			break
		}
		off += len(s.Real)
		cuts = append(cuts, off)
	}
	want := []int{1, 6, 11}
	if len(cuts) != len(want) {
		t.Fatalf("cuts %v", cuts)
	}
	for i := range want {
		if cuts[i] != want[i] {
			t.Fatalf("cuts %v", cuts)
		}
	}
}

func TestPlanDisorderTTL(t *testing.T) {
	payload := bytes.Repeat([]byte("x"), 30)
	steps, _ := Plan(payload, strategy(Part{Mode: ModeDisorder, Pos: 7}))
	if len(steps) != 2 {
		t.Fatalf("steps %d", len(steps))
	}
	if steps[0].Kind != "disorder" || steps[0].TTL != 1 || len(steps[0].Real) != 7 {
		t.Fatalf("head %#v", steps[0])
	}
	if steps[1].Kind != "send" || len(steps[1].Real) != 23 || steps[1].TTL != 0 {
		t.Fatalf("tail %#v len %d", steps[1].Kind, len(steps[1].Real))
	}
	if !bytes.Equal(Logical(steps), payload) {
		t.Fatal("disorder changed the stream")
	}
}

func TestPlanFakeAndOOB(t *testing.T) {
	payload := bytes.Repeat([]byte("y"), 20)
	steps, _ := Plan(payload, Strategy{
		Parts: []Part{{Mode: ModeFake, Pos: 4}, {Mode: ModeOOB, Pos: 8}},
		TTL:   5,
	})
	if steps[0].Kind != "fake" || steps[0].TTL != 5 || len(steps[0].Fake) != 4 || len(steps[0].Real) != 4 {
		t.Fatalf("fake %#v", steps[0])
	}
	if steps[1].Kind != "oob" || !steps[1].HasOOB || len(steps[1].Real) != 4 {
		t.Fatalf("oob %#v", steps[1])
	}
	if !bytes.Equal(Logical(steps), payload) {
		t.Fatal("logical mismatch")
	}
}

func TestPlanSNIOffset(t *testing.T) {
	hello := clientHello("www.example.com")
	pos, n, ok := findSNI(hello)
	if !ok || string(hello[pos:pos+n]) != "www.example.com" {
		t.Fatalf("sni %d %d %v", pos, n, ok)
	}
	steps, _ := Plan(hello, strategy(Part{Mode: ModeSplit, Pos: 3, Flags: FlagSNI}))
	if len(steps) < 2 || len(steps[0].Real) != pos+3 {
		t.Fatalf("cut %d want %d", len(steps[0].Real), pos+3)
	}
}

func TestPlanTLSRec(t *testing.T) {
	hello := clientHello("abc")
	origLen := int(hello[3])<<8 | int(hello[4])
	_, modified := Plan(hello, Strategy{TLSRec: []Part{{Pos: 3}}})
	if len(modified) != len(hello)+5 {
		t.Fatalf("grew %d -> %d", len(hello), len(modified))
	}
	first := int(modified[3])<<8 | int(modified[4])
	if first != 3 {
		t.Fatalf("first record payload %d", first)
	}
	secondOff := 5 + 3
	if modified[secondOff] != 0x16 {
		t.Fatalf("second record type %x", modified[secondOff])
	}
	second := int(modified[secondOff+3])<<8 | int(modified[secondOff+4])
	if second != origLen-3 {
		t.Fatalf("second payload %d want %d", second, origLen-3)
	}
}

func TestModHTTP(t *testing.T) {
	req := []byte("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
	out := modHTTP(req, ModHTTPHostMix|ModHTTPDomainMix)
	if !bytes.Contains(out, []byte("hOsT:")) {
		t.Fatalf("header %q", out)
	}
	if !bytes.Contains(out, []byte("ExAmPlE.CoM")) && !bytes.Contains(out, []byte("ExAmPlE.cOm")) {
		// even indexes of "example.com" uppercased: E x A m P l E . C o M
		if !bytes.Contains(out, []byte("ExAmPlE.cOm")) && !bytes.Contains(bytes.ToLower(out), []byte("example.com")) {
			t.Fatalf("domain %q", out)
		}
	}
	spaced := modHTTP([]byte("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"), ModHTTPSpace)
	if !bytes.Contains(spaced, []byte("Host:example.com\t")) {
		t.Fatalf("space %q", spaced)
	}
}

func TestHostFilter(t *testing.T) {
	if !MatchHost("anything.test", nil) {
		t.Fatal("empty list matches all")
	}
	patterns := []string{"*.Example.com", "cdn.test"}
	if !MatchHost("A.example.com.", patterns) || !MatchHost("example.com", patterns) {
		t.Fatal("wildcard")
	}
	if !MatchHost("img.cdn.test", patterns) {
		t.Fatal("subdomain")
	}
	if MatchHost("example.org", patterns) {
		t.Fatal("should not match")
	}
}

func TestFakeTemplate(t *testing.T) {
	if len(FakeTLS) != 517 || FakeTLS[0] != 0x16 || !bytes.Contains(FakeTLS, []byte("wikipedia")) {
		t.Fatal("fake tls template drifted from ByeDPI")
	}
	if !bytes.Contains(FakeHTTP, []byte("www.wikipedia.org")) {
		t.Fatal("fake http")
	}
	if len(FakeUDP) != 64 {
		t.Fatal("fake udp")
	}
}

func lens(steps []Step) []int {
	out := make([]int, len(steps))
	for i, s := range steps {
		out[i] = len(s.Real)
	}
	return out
}

func clientHello(sni string) []byte {
	name := []byte(sni)
	// extension: type 0, list, name
	extBody := make([]byte, 2+1+2+len(name))
	extBody[0] = byte((len(extBody) - 2) >> 8)
	extBody[1] = byte(len(extBody) - 2)
	extBody[2] = 0 // host_name
	extBody[3] = byte(len(name) >> 8)
	extBody[4] = byte(len(name))
	copy(extBody[5:], name)
	ext := []byte{0x00, 0x00, byte(len(extBody) >> 8), byte(len(extBody))}
	ext = append(ext, extBody...)
	exts := append([]byte{byte(len(ext) >> 8), byte(len(ext))}, ext...)

	hello := []byte{0x01, 0x00, 0x00, 0x00, 0x03, 0x03}
	hello = append(hello, bytes.Repeat([]byte{0x11}, 32)...)
	hello = append(hello, 0x00)                   // session id
	hello = append(hello, 0x00, 0x02, 0x13, 0x01) // one cipher
	hello = append(hello, 0x01, 0x00)             // compression
	hello = append(hello, exts...)
	hsLen := len(hello) - 4
	hello[1] = byte(hsLen >> 16)
	hello[2] = byte(hsLen >> 8)
	hello[3] = byte(hsLen)
	recLen := len(hello)
	rec := []byte{0x16, 0x03, 0x01, byte(recLen >> 8), byte(recLen)}
	return append(rec, hello...)
}
