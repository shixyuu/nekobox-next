//go:build !linux && !android

package byedpi

import "net"

// ApplyTCP on non-Linux hosts still applies tamp (HTTP tweaks, TLS record
// splits) but cannot set TTL, MSG_OOB, or vmsplice. The bytes are written
// as one segment so the peer receives a valid stream.
func ApplyTCP(conn net.Conn, payload []byte, s Strategy) error {
	_, modified := Plan(payload, s)
	_, err := conn.Write(modified)
	return err
}

// ApplyUDP writes the datagram. Decoy datagrams need IP_TTL and are skipped.
func ApplyUDP(conn *net.UDPConn, addr net.Addr, payload []byte, s Strategy) error {
	if addr == nil {
		_, err := conn.Write(payload)
		return err
	}
	_, err := conn.WriteTo(payload, addr)
	return err
}
