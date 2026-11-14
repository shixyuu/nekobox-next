package byedpi

import "math/rand"

// Plan turns the first client flight into the segment schedule ByeDPI would
// send. The returned buffer is the post-tamp payload (HTTP tweaks, TLS minor,
// TLS record splits). Steps cover that whole buffer.
//
// Disorder sends the left slice first with TTL 1. The remote observes the
// right slice first, because the TTL-1 segment is dropped and later
// retransmitted. Fake splices a decoy of the same length at a low TTL and
// relies on the Linux page-overwrite retransmission for the real bytes.
func Plan(payload []byte, s Strategy) (steps []Step, modified []byte) {
	buf := append([]byte(nil), payload...)
	if s.ModHTTP != 0 && isHTTP(buf) {
		buf = modHTTP(buf, s.ModHTTP)
	}
	if s.TLSMinorSet && isTLSClientHello(buf) {
		buf[2] = byte(s.TLSMinor)
	}
	buf = applyTLSRec(buf, s.TLSRec)

	fakeTTL := s.TTL
	if fakeTTL == 0 {
		fakeTTL = 8
	}
	oob := byte('a')
	if s.OOBCharSet {
		oob = s.OOBChar
	}

	lp := 0
	i := 0
	r := 0
	var part Part
	for r > 0 || i < len(s.Parts) {
		if r <= 0 {
			part = s.Parts[i]
			r = part.Repeats
			i++
		}
		pos := resolve(part, buf, lp)
		pos += part.Skip * (part.Repeats - r)
		if pos < lp {
			break
		}
		if pos > len(buf) {
			pos = len(buf)
		}
		if pos == lp {
			if r > 0 {
				r--
			}
			continue
		}
		seg := append([]byte(nil), buf[lp:pos]...)
		// (repeats - r) is even on the first iteration, including Repeats==0.
		disorderTTL := (part.Repeats-r)%2 == 0
		switch part.Mode {
		case ModeFake:
			steps = append(steps, Step{
				Kind: "fake",
				Real: seg,
				Fake: fakeBytes(len(seg), buf),
				TTL:  fakeTTL,
			})
		case ModeDisorder:
			ttl := 0
			if disorderTTL {
				ttl = 1
			}
			steps = append(steps, Step{Kind: "disorder", Real: seg, TTL: ttl})
		case ModeDisOOB:
			ttl := 0
			if disorderTTL {
				ttl = 1
			}
			steps = append(steps, Step{Kind: "oob", Real: seg, TTL: ttl, OOB: oob, HasOOB: true})
		case ModeOOB:
			steps = append(steps, Step{Kind: "oob", Real: seg, OOB: oob, HasOOB: true})
		default:
			steps = append(steps, Step{Kind: "send", Real: seg})
		}
		lp = pos
		r--
		if r < 0 {
			r = 0
		}
	}
	if lp < len(buf) {
		steps = append(steps, Step{Kind: "send", Real: append([]byte(nil), buf[lp:]...)})
	}
	return steps, buf
}

func resolve(part Part, buf []byte, lp int) int {
	pos := part.Pos
	flag := part.Flags
	n := len(buf)
	if flag&(FlagSNI|FlagHost) != 0 {
		var hostPos, hostLen int
		var ok bool
		if flag&FlagSNI != 0 {
			hostPos, hostLen, ok = findSNI(buf)
			if !ok {
				return -1
			}
		} else {
			hostPos, hostLen, ok = findHTTPHost(buf)
			if !ok {
				return -1
			}
		}
		pos += hostPos
		switch {
		case flag&FlagEnd != 0:
			pos += hostLen
		case flag&FlagMid != 0:
			pos += hostLen / 2
		case flag&FlagRand != 0 && hostLen > 0:
			pos += rand.Intn(hostLen)
		}
		return pos
	}
	if flag&FlagRand != 0 && n > lp {
		return lp + rand.Intn(n-lp)
	}
	if flag&FlagMid != 0 {
		return n / 2
	}
	if pos < 0 || flag&FlagEnd != 0 {
		return pos + n
	}
	return pos
}

// applyTLSRec ports ByeDPI tamp() record splitting. A plain positive offset
// is a payload length. Flagged or negative offsets are absolute and shifted
// back by the 5-byte record header, matching desync.c.
func applyTLSRec(buf []byte, parts []Part) []byte {
	if len(parts) == 0 || !isTLSClientHello(buf) {
		return buf
	}
	lp := 0
	i := 0
	r := 0
	rc := 0
	var part Part
	for r > 0 || i < len(parts) {
		if r <= 0 {
			part = parts[i]
			r = part.Repeats
			i++
		}
		// gen_offset is evaluated against the current buffer. rc*5 accounts
		// for headers already inserted when the offset is absolute.
		abs := rc * 5
		base := part
		base.Pos = part.Pos
		resolved := resolve(base, buf, lp)
		if part.Flags != 0 || part.Pos < 0 {
			abs += resolved - 5
		} else {
			abs += resolved
		}
		abs += part.Skip * (part.Repeats - r)
		if abs < lp {
			break
		}
		rel := abs - lp
		next, ok := splitTLSRecord(buf[lp:], rel)
		if !ok {
			break
		}
		buf = rebuildTLS(buf, lp, next)
		lp = abs + 5
		rc++
		r--
		if r < 0 {
			r = 0
		}
	}
	return buf
}

func rebuildTLS(original []byte, lp int, next []byte) []byte {
	out := make([]byte, 0, lp+len(next))
	out = append(out, original[:lp]...)
	out = append(out, next...)
	return out
}

func fakeBytes(n int, original []byte) []byte {
	src := FakeTLS
	if isHTTP(original) {
		src = FakeHTTP
	}
	out := make([]byte, n)
	if len(src) == 0 {
		return out
	}
	for i := 0; i < n; i++ {
		out[i] = src[i%len(src)]
	}
	// Prefer a prefix of the template when it is long enough, which is the
	// ByeDPI behaviour (iov length == segment length, template underneath).
	if n <= len(src) {
		copy(out, src[:n])
	}
	return out
}
