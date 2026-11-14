package byedpi

import (
	"testing"
	"time"
)

func TestParseArgsPositionsAndGroups(t *testing.T) {
	p, err := ParseArgs(`--disorder 1 --split 1:3:5 --auto=torst,redirect --timeout 3 --tlsrec 3+s --fake -1 --ttl 8`)
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Strategies) != 2 {
		t.Fatalf("groups: got %d", len(p.Strategies))
	}
	if p.Timeout != 3*time.Second {
		t.Fatalf("timeout %s", p.Timeout)
	}
	g0 := p.Strategies[0]
	if len(g0.Parts) != 2 || g0.Parts[0].Mode != ModeDisorder || g0.Parts[0].Pos != 1 {
		t.Fatalf("group0 %#v", g0.Parts)
	}
	rep := g0.Parts[1]
	if rep.Mode != ModeSplit || rep.Pos != 1 || rep.Repeats != 3 || rep.Skip != 5 {
		t.Fatalf("repeats %#v", rep)
	}
	g1 := p.Strategies[1]
	if g1.Detect != DetectTorst|DetectRedirect {
		t.Fatalf("detect %v", g1.Detect)
	}
	if len(g1.TLSRec) != 1 || g1.TLSRec[0].Flags&FlagSNI == 0 || g1.TLSRec[0].Pos != 3 {
		t.Fatalf("tlsrec %#v", g1.TLSRec)
	}
	if len(g1.Parts) != 1 || g1.Parts[0].Mode != ModeFake || g1.Parts[0].Pos != -1 || g1.TTL != 8 {
		t.Fatalf("fake %#v ttl %d", g1.Parts, g1.TTL)
	}
}

func TestParseArgsShortOptionsAndModHTTP(t *testing.T) {
	p, err := ParseArgs(`-s 3+h -o 4+sm -q 1 -M h,d,r -e \n --udp-fake 2 --md5sig --drop-sack --hosts a.com,*.example.com`)
	if err != nil {
		t.Fatal(err)
	}
	s := p.Strategies[0]
	if s.Parts[0].Flags&FlagHost == 0 {
		t.Fatal("expected host flag")
	}
	if s.Parts[1].Mode != ModeOOB || s.Parts[1].Flags&(FlagSNI|FlagMid) != FlagSNI|FlagMid {
		t.Fatalf("oob %#v", s.Parts[1])
	}
	if s.Parts[2].Mode != ModeDisOOB {
		t.Fatal(s.Parts[2].Mode)
	}
	if s.ModHTTP != ModHTTPHostMix|ModHTTPDomainMix|ModHTTPSpace {
		t.Fatalf("mod %v", s.ModHTTP)
	}
	if !s.OOBCharSet || s.OOBChar != '\n' || !s.MD5Sig || !s.DropSack || s.UDPFakeCount != 2 {
		t.Fatalf("flags %#v", s)
	}
	if len(p.Hosts) != 2 || p.Hosts[1] != "*.example.com" {
		t.Fatal(p.Hosts)
	}
}

func TestParseArgsRejectsGarbage(t *testing.T) {
	if _, err := ParseArgs(`--split`); err == nil {
		t.Fatal("expected missing value")
	}
	if _, err := ParseArgs(`--not-a-real-option 1`); err == nil {
		t.Fatal("expected unknown option")
	}
	if _, err := ParseArgs(`--split 1+z`); err == nil {
		t.Fatal("expected bad flag")
	}
	if _, err := ParseArgs(`--auto=nope`); err == nil {
		t.Fatal("expected bad auto")
	}
}

func TestAutoFirstKeepsPlainGroup(t *testing.T) {
	p, err := ParseArgs(`--auto=torst --timeout=3 --tlsrec 3+s`)
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Strategies) != 2 {
		t.Fatalf("groups %d", len(p.Strategies))
	}
	if len(p.Strategies[0].Parts) != 0 || len(p.Strategies[0].TLSRec) != 0 {
		t.Fatal("first group should be plain")
	}
	if p.Strategies[1].Detect&DetectTorst == 0 || len(p.Strategies[1].TLSRec) != 1 {
		t.Fatalf("second %#v", p.Strategies[1])
	}
	if nextGroup(p.Strategies, 0) != 1 {
		t.Fatal("next group")
	}
}
