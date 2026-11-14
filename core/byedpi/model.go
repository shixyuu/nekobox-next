package byedpi

import "time"

// Mode is one ByeDPI desync operation applied to a byte range.
type Mode int

const (
	ModeSplit Mode = iota
	ModeDisorder
	ModeOOB
	ModeDisOOB
	ModeFake
)

func (m Mode) String() string {
	switch m {
	case ModeSplit:
		return "split"
	case ModeDisorder:
		return "disorder"
	case ModeOOB:
		return "oob"
	case ModeDisOOB:
		return "disoob"
	case ModeFake:
		return "fake"
	default:
		return "none"
	}
}

// Offset flags mirror ByeDPI pos_t suffixes (+s, +h, +e, +m, +r, +n).
type OffsetFlag int

const (
	FlagEnd OffsetFlag = 1 << iota
	FlagMid
	FlagRand
	FlagSNI
	FlagHost
	FlagStart
)

// ModHTTP mirrors --mod-http h,d,r (header case, domain case, remove space).
type ModHTTP int

const (
	ModHTTPHostMix ModHTTP = 1 << iota
	ModHTTPDomainMix
	ModHTTPSpace
)

// Detect is the --auto trigger that selects the next strategy group.
type Detect int

const (
	DetectNone     Detect = 0
	DetectTorst    Detect = 1 // timeout or TCP reset
	DetectRedirect Detect = 2 // HTTP redirect
	DetectTLSErr   Detect = 4 // TLS alert
)

// Part is one --split/--disorder/--oob/--fake/--tlsrec position.
// Repeats 0 means a single application. Skip is the extra offset per repeat.
type Part struct {
	Mode    Mode
	Pos     int
	Repeats int
	Skip    int
	Flags   OffsetFlag
}

// Strategy is one desync group. --auto starts another group.
type Strategy struct {
	Parts        []Part
	TLSRec       []Part
	TTL          int // fake TTL; 0 means the ByeDPI default of 8
	MD5Sig       bool
	DropSack     bool
	OOBChar      byte
	OOBCharSet   bool
	ModHTTP      ModHTTP
	TLSMinor     int
	TLSMinorSet  bool
	UDPFakeCount int
	FakeSNI      []string
	Detect       Detect
}

// Profile is the full parsed ByeDPI argument string plus the node host filter.
type Profile struct {
	Strategies []Strategy
	Timeout    time.Duration
	Hosts      []string
	DefTTL     int
}

// Step is one packet the dialer should emit. Logical bytes are what the
// server must eventually reassemble. Fake bytes are the low-TTL decoy.
type Step struct {
	Kind   string // send, disorder, oob, fake
	Real   []byte
	Fake   []byte
	TTL    int // 0 = stack default; 1 = disorder; otherwise fake TTL
	OOB    byte
	HasOOB bool
}

// Logical returns the byte stream a conforming TCP peer reassembles.
// OOB bytes are not part of the stream. Fake steps contribute Real, which
// is what the retransmission carries after the decoy page is overwritten.
func Logical(steps []Step) []byte {
	var out []byte
	for _, s := range steps {
		out = append(out, s.Real...)
	}
	return out
}
