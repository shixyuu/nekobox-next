package byedpi

import "bytes"

// findSNI returns the absolute offset and length of the first SNI hostname
// inside a TLS ClientHello record. ok is false when the buffer is not a
// ClientHello or has no server_name extension.
func findSNI(buf []byte) (pos, length int, ok bool) {
	if !isTLSClientHello(buf) {
		return 0, 0, false
	}
	ext := findExtBlock(buf)
	if ext < 0 {
		return 0, 0, false
	}
	off := findExt(buf, ext, 0x0000)
	if off < 0 || off+12 > len(buf) {
		return 0, 0, false
	}
	nameLen := int(buf[off+7])<<8 | int(buf[off+8])
	if off+9+nameLen > len(buf) {
		return 0, 0, false
	}
	return off + 9, nameLen, true
}

func isTLSClientHello(buf []byte) bool {
	return len(buf) > 5 && buf[0] == 0x16 && buf[1] == 0x03 && buf[5] == 0x01
}

// findExtBlock returns the offset of the extensions-length field, matching
// ByeDPI find_ext_block (handshake bytes, record header included).
func findExtBlock(buf []byte) int {
	if len(buf) < 44 {
		return -1
	}
	sidLen := int(buf[43])
	if len(buf) < 44+sidLen+2 {
		return -1
	}
	cipLen := int(buf[44+sidLen])<<8 | int(buf[45+sidLen])
	skip := 44 + sidLen + 2 + cipLen + 2
	if skip > len(buf) {
		return -1
	}
	return skip
}

func findExt(buf []byte, skip, typ int) int {
	if len(buf) <= skip+2 {
		return -1
	}
	extLen := int(buf[skip])<<8 | int(buf[skip+1])
	skip += 2
	end := len(buf)
	if extLen < end-skip {
		end = skip + extLen
	}
	for skip+4 < end {
		e := int(buf[skip])<<8 | int(buf[skip+1])
		ln := int(buf[skip+2])<<8 | int(buf[skip+3])
		if e == typ {
			return skip
		}
		skip += ln + 4
	}
	return -1
}

func findHTTPHost(buf []byte) (pos, length int, ok bool) {
	if !isHTTP(buf) {
		return 0, 0, false
	}
	lower := bytes.ToLower(buf)
	key := []byte("\nhost:")
	idx := bytes.Index(lower, key)
	if idx < 0 {
		// Host on the first line is not valid, but a request can start with it
		// only after the request line. Also accept a leading "host:".
		if bytes.HasPrefix(lower, []byte("host:")) {
			idx = -1
		} else {
			return 0, 0, false
		}
	}
	start := idx + len(key)
	if idx < 0 {
		start = len("host:")
	}
	for start < len(buf) && (buf[start] == ' ' || buf[start] == '\t') {
		start++
	}
	end := start
	for end < len(buf) && buf[end] != '\r' && buf[end] != '\n' && buf[end] != ' ' && buf[end] != '\t' && buf[end] != ':' {
		end++
	}
	if end == start {
		return 0, 0, false
	}
	return start, end - start, true
}

func isHTTP(buf []byte) bool {
	if len(buf) < 16 {
		return false
	}
	methods := []string{"HEAD", "GET", "POST", "PUT", "DELETE", "OPTIONS", "CONNECT", "TRACE", "PATCH"}
	for _, m := range methods {
		if bytes.HasPrefix(buf, []byte(m)) {
			return true
		}
	}
	return false
}

// modHTTP applies --mod-http. The total length is preserved.
func modHTTP(buf []byte, mode ModHTTP) []byte {
	if mode == 0 {
		return buf
	}
	hostPos, hostLen, ok := findHTTPHost(buf)
	if !ok {
		return buf
	}
	colon := hostPos - 1
	for colon > 0 && buf[colon] != ':' {
		colon--
	}
	if colon < 4 {
		return buf
	}
	name := colon - 4
	out := append([]byte(nil), buf...)
	if mode&ModHTTPHostMix != 0 {
		out[name] = lower(out[name])
		out[name+1] = upper(out[name+1])
		out[name+3] = upper(out[name+3])
	}
	if mode&ModHTTPDomainMix != 0 {
		for i := 0; i < hostLen; i += 2 {
			out[hostPos+i] = upper(out[hostPos+i])
		}
	}
	if mode&ModHTTPSpace != 0 {
		hlen := hostLen
		for hostPos+hlen < len(out) && !isSpace(out[hostPos+hlen]) {
			hlen++
		}
		sc := hostPos - (name + 5)
		if sc > 0 && name+5+hlen+sc <= len(out) {
			chunk := append([]byte(nil), out[hostPos:hostPos+hlen]...)
			copy(out[name+5:], chunk)
			for i := 0; i < sc; i++ {
				out[name+5+hlen+i] = '\t'
			}
		}
	}
	return out
}

func lower(b byte) byte {
	if b >= 'A' && b <= 'Z' {
		return b + ('a' - 'A')
	}
	return b
}

func upper(b byte) byte {
	if b >= 'a' && b <= 'z' {
		return b - ('a' - 'A')
	}
	return b
}

func isSpace(b byte) bool {
	return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == '\v' || b == '\f'
}

// splitTLSRecord inserts one extra TLS record header at a payload offset,
// growing the buffer by 5 bytes. pos is the first record's payload length
// (ByeDPI part_tls).
func splitTLSRecord(buf []byte, pos int) ([]byte, bool) {
	n := len(buf)
	if n < 3 || pos < 0 || pos+5 > n {
		return buf, false
	}
	recLen := int(buf[3])<<8 | int(buf[4])
	if recLen < pos {
		return buf, false
	}
	out := make([]byte, n+5)
	copy(out, buf[:5+pos])
	copy(out[5+pos:5+pos+3], buf[:3])
	copy(out[5+pos+5:], buf[5+pos:])
	out[3] = byte(pos >> 8)
	out[4] = byte(pos)
	second := recLen - pos
	off := 5 + pos + 3
	out[off] = byte(second >> 8)
	out[off+1] = byte(second)
	return out, true
}
