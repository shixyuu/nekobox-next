package byedpi

import (
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// ParseArgs parses a ByeDPI-style argument string into strategy groups.
// Both long options (--split) and the short options ByeDPI documents are
// accepted. --auto=torst,redirect,ssl_err starts a new group; options that
// follow belong to that group. --timeout is global.
func ParseArgs(raw string) (Profile, error) {
	tokens, err := splitArgs(raw)
	if err != nil {
		return Profile{}, err
	}
	profile := Profile{DefTTL: 0}
	cur := Strategy{}
	seen := false
	for i := 0; i < len(tokens); i++ {
		tok := tokens[i]
		key, inline, hasInline := splitInline(tok)
		need := func() (string, error) {
			if hasInline {
				return inline, nil
			}
			if i+1 >= len(tokens) {
				return "", fmt.Errorf("byedpi: %s needs a value", key)
			}
			i++
			return tokens[i], nil
		}
		switch key {
		case "--split", "-s":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			p, err := parsePos(v, ModeSplit)
			if err != nil {
				return Profile{}, err
			}
			cur.Parts = append(cur.Parts, p)
			seen = true
		case "--disorder", "-d":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			p, err := parsePos(v, ModeDisorder)
			if err != nil {
				return Profile{}, err
			}
			cur.Parts = append(cur.Parts, p)
			seen = true
		case "--oob", "-o":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			p, err := parsePos(v, ModeOOB)
			if err != nil {
				return Profile{}, err
			}
			cur.Parts = append(cur.Parts, p)
			seen = true
		case "--disoob", "-q":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			p, err := parsePos(v, ModeDisOOB)
			if err != nil {
				return Profile{}, err
			}
			cur.Parts = append(cur.Parts, p)
			seen = true
		case "--fake", "-f":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			p, err := parsePos(v, ModeFake)
			if err != nil {
				return Profile{}, err
			}
			cur.Parts = append(cur.Parts, p)
			seen = true
		case "--tlsrec", "-r":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			p, err := parsePos(v, ModeSplit)
			if err != nil {
				return Profile{}, err
			}
			cur.TLSRec = append(cur.TLSRec, p)
			seen = true
		case "--ttl", "-t":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			n, err := strconv.Atoi(v)
			if err != nil || n < 1 || n > 255 {
				return Profile{}, fmt.Errorf("byedpi: bad ttl %q", v)
			}
			cur.TTL = n
			seen = true
		case "--def-ttl", "-g":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			n, err := strconv.Atoi(v)
			if err != nil || n < 1 || n > 255 {
				return Profile{}, fmt.Errorf("byedpi: bad def-ttl %q", v)
			}
			profile.DefTTL = n
		case "--udp-fake", "-a":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			n, err := strconv.Atoi(v)
			if err != nil || n < 0 {
				return Profile{}, fmt.Errorf("byedpi: bad udp-fake %q", v)
			}
			cur.UDPFakeCount = n
			seen = true
		case "--mod-http", "-M":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			mode, err := parseModHTTP(v)
			if err != nil {
				return Profile{}, err
			}
			cur.ModHTTP |= mode
			seen = true
		case "--tlsminor", "-m":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			n, err := strconv.Atoi(v)
			if err != nil || n < 0 || n > 255 {
				return Profile{}, fmt.Errorf("byedpi: bad tlsminor %q", v)
			}
			cur.TLSMinor = n
			cur.TLSMinorSet = true
			seen = true
		case "--md5sig", "-S":
			cur.MD5Sig = true
			seen = true
		case "--drop-sack", "-Y":
			cur.DropSack = true
			seen = true
		case "--oob-data", "-e":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			b, err := parseCFormByte(v)
			if err != nil {
				return Profile{}, err
			}
			cur.OOBChar = b
			cur.OOBCharSet = true
			seen = true
		case "--fake-sni", "-n":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			cur.FakeSNI = append(cur.FakeSNI, v)
			seen = true
		case "--hosts", "-H":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			for _, h := range strings.Split(v, ",") {
				h = strings.TrimSpace(h)
				if h != "" {
					profile.Hosts = append(profile.Hosts, h)
				}
			}
		case "--timeout", "-T":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			f, err := strconv.ParseFloat(v, 64)
			if err != nil || f <= 0 {
				return Profile{}, fmt.Errorf("byedpi: bad timeout %q", v)
			}
			profile.Timeout = time.Duration(f * float64(time.Second))
		case "--auto", "-A":
			v, err := need()
			if err != nil {
				return Profile{}, err
			}
			det, err := parseDetect(v)
			if err != nil {
				return Profile{}, err
			}
			// The group parsed so far is tried first, even when it is empty.
			// That is ByeDPI's "plain, then desync on reset" behaviour.
			profile.Strategies = append(profile.Strategies, cur)
			cur = Strategy{Detect: det}
			seen = true
		case "--":
			// explicit end of options
		default:
			return Profile{}, fmt.Errorf("byedpi: unknown option %q", tok)
		}
	}
	if seen {
		profile.Strategies = append(profile.Strategies, cur)
	}
	if len(profile.Strategies) == 0 {
		profile.Strategies = []Strategy{{}}
	}
	return profile, nil
}

func parseDetect(v string) (Detect, error) {
	var d Detect
	for _, p := range strings.Split(v, ",") {
		switch strings.TrimSpace(p) {
		case "", "n", "0":
		case "t", "torst":
			d |= DetectTorst
		case "r", "redirect":
			d |= DetectRedirect
		case "a", "s", "ssl_err", "tls":
			d |= DetectTLSErr
		default:
			return 0, fmt.Errorf("byedpi: bad auto trigger %q", p)
		}
	}
	return d, nil
}

func parseModHTTP(v string) (ModHTTP, error) {
	var m ModHTTP
	for _, p := range strings.Split(v, ",") {
		switch strings.TrimSpace(p) {
		case "h", "hcsmix":
			m |= ModHTTPHostMix
		case "d", "dcsmix":
			m |= ModHTTPDomainMix
		case "r", "rmspace":
			m |= ModHTTPSpace
		default:
			return 0, fmt.Errorf("byedpi: bad mod-http %q", p)
		}
	}
	return m, nil
}

// parsePos reads offset[:repeats:skip][+flags].
func parsePos(s string, mode Mode) (Part, error) {
	part := Part{Mode: mode}
	base := s
	flag := ""
	if i := strings.IndexByte(s, '+'); i >= 0 {
		base = s[:i]
		flag = s[i+1:]
	}
	chunks := strings.Split(base, ":")
	if chunks[0] == "" {
		return Part{}, fmt.Errorf("byedpi: empty position %q", s)
	}
	pos, err := strconv.Atoi(chunks[0])
	if err != nil {
		return Part{}, fmt.Errorf("byedpi: bad position %q", s)
	}
	part.Pos = pos
	if len(chunks) > 1 {
		r, err := strconv.Atoi(chunks[1])
		if err != nil || r <= 0 {
			return Part{}, fmt.Errorf("byedpi: bad repeats in %q", s)
		}
		part.Repeats = r
	}
	if len(chunks) > 2 {
		sk, err := strconv.Atoi(chunks[2])
		if err != nil || sk < 0 {
			return Part{}, fmt.Errorf("byedpi: bad skip in %q", s)
		}
		part.Skip = sk
	}
	if len(chunks) > 3 {
		return Part{}, fmt.Errorf("byedpi: bad position %q", s)
	}
	if flag != "" {
		f, err := parseFlags(flag)
		if err != nil {
			return Part{}, err
		}
		part.Flags = f
	}
	return part, nil
}

func parseFlags(flag string) (OffsetFlag, error) {
	if flag == "" {
		return 0, fmt.Errorf("byedpi: empty flag")
	}
	var out OffsetFlag
	switch flag[0] {
	case 's':
		out = FlagSNI
	case 'h':
		out = FlagHost
	case 'n':
		out = 0
	default:
		return 0, fmt.Errorf("byedpi: bad flag +%s", flag)
	}
	if len(flag) > 1 {
		switch flag[1] {
		case 'e':
			out |= FlagEnd
		case 'm':
			out |= FlagMid
		case 'r':
			out |= FlagRand
		case 's':
			out |= FlagStart
		default:
			return 0, fmt.Errorf("byedpi: bad flag +%s", flag)
		}
	}
	if len(flag) > 2 {
		return 0, fmt.Errorf("byedpi: bad flag +%s", flag)
	}
	return out, nil
}

func parseCFormByte(s string) (byte, error) {
	if s == "" {
		return 0, fmt.Errorf("byedpi: empty oob data")
	}
	if s[0] != '\\' {
		return s[0], nil
	}
	if len(s) < 2 {
		return 0, fmt.Errorf("byedpi: bad oob data")
	}
	switch s[1] {
	case 'n':
		return '\n', nil
	case 'r':
		return '\r', nil
	case 't':
		return '\t', nil
	case '\\':
		return '\\', nil
	case '0':
		return 0, nil
	default:
		return s[1], nil
	}
}

func splitInline(tok string) (key, inline string, ok bool) {
	if !strings.HasPrefix(tok, "--") {
		return tok, "", false
	}
	if i := strings.IndexByte(tok, '='); i > 0 {
		return tok[:i], tok[i+1:], true
	}
	return tok, "", false
}

func splitArgs(raw string) ([]string, error) {
	var out []string
	var b strings.Builder
	inQuote := false
	esc := false
	flush := func() {
		if b.Len() > 0 {
			out = append(out, b.String())
			b.Reset()
		}
	}
	for _, r := range raw {
		switch {
		case esc:
			// Keep unknown backslash sequences so \n reaches parseCFormByte.
			if r != '"' && r != '\\' {
				b.WriteByte('\\')
			}
			b.WriteRune(r)
			esc = false
		case r == '\\':
			esc = true
		case r == '"':
			inQuote = !inQuote
		case unicode.IsSpace(r) && !inQuote:
			flush()
		default:
			b.WriteRune(r)
		}
	}
	if inQuote {
		return nil, fmt.Errorf("byedpi: unmatched quote")
	}
	flush()
	return out, nil
}
