package byedpi

import "strings"

// MatchHost reports whether host should be desynced.
// An empty pattern list matches every host. Patterns are case-insensitive.
// A leading "*." matches the suffix and the bare parent. Any other pattern
// matches that host and its subdomains.
func MatchHost(host string, patterns []string) bool {
	if len(patterns) == 0 {
		return true
	}
	host = normalizeHost(host)
	if host == "" {
		return false
	}
	for _, raw := range patterns {
		p := normalizeHost(raw)
		if p == "" {
			continue
		}
		if strings.HasPrefix(p, "*.") {
			suf := p[1:] // ".example.com"
			parent := p[2:]
			if host == parent || strings.HasSuffix(host, suf) {
				return true
			}
			continue
		}
		if host == p || strings.HasSuffix(host, "."+p) {
			return true
		}
	}
	return false
}

func normalizeHost(host string) string {
	host = strings.TrimSpace(strings.ToLower(host))
	host = strings.TrimSuffix(host, ".")
	if i := strings.LastIndexByte(host, '%'); i >= 0 {
		host = host[:i]
	}
	return host
}
