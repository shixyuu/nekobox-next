package byedpi

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"syscall"
	"time"
)

// Server is a loopback SOCKS5 proxy. Outbound sockets go through the desync
// dialer and, on Android, through VpnService.protect. sing-box reaches it
// with a socks outbound, which is also how a ByeDPI node becomes a detour.
type Server struct {
	ln      net.Listener
	udp     *net.UDPConn
	profile Profile
	protect func(int) bool
	user    string
	pass    string
	once    sync.Once
}

// Listen binds addr. protect may be nil off Android. A non-empty user
// requires RFC 1929 username/password authentication.
func Listen(addr, user, pass string, profile Profile, protect func(int) bool) (*Server, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		ln.Close()
		return nil, err
	}
	s := &Server{ln: ln, udp: udp, profile: profile, protect: protect, user: user, pass: pass}
	go s.acceptLoop()
	go s.udpLoop()
	return s, nil
}

func (s *Server) Addr() string { return s.ln.Addr().String() }

func (s *Server) Close() error {
	var err error
	s.once.Do(func() {
		err = s.ln.Close()
		if s.udp != nil {
			_ = s.udp.Close()
		}
	})
	return err
}

func (s *Server) acceptLoop() {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.serve(c)
	}
}

func (s *Server) serve(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	if err := s.handshake(conn); err != nil {
		return
	}
	cmd, host, port, err := readRequest(conn)
	if err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	switch cmd {
	case 0x01:
		s.serveConnect(conn, host, port)
	case 0x03:
		_ = s.reply(conn, 0, s.udp.LocalAddr())
		// The TCP control connection stays open for the life of the UDP flow.
		io.Copy(io.Discard, conn)
	default:
		_, _ = conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
	}
}

func (s *Server) handshake(conn net.Conn) error {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return err
	}
	if hdr[0] != 5 {
		return errors.New("byedpi: not socks5")
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	if s.user != "" {
		if !bytesContains(methods, 0x02) {
			_, _ = conn.Write([]byte{5, 0xff})
			return errors.New("byedpi: client did not offer auth")
		}
		if _, err := conn.Write([]byte{5, 0x02}); err != nil {
			return err
		}
		return s.readAuth(conn)
	}
	if !bytesContains(methods, 0x00) {
		_, _ = conn.Write([]byte{5, 0xff})
		return errors.New("byedpi: no acceptable method")
	}
	_, err := conn.Write([]byte{5, 0x00})
	return err
}

func (s *Server) readAuth(conn net.Conn) error {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return err
	}
	user := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(conn, user); err != nil {
		return err
	}
	var plen [1]byte
	if _, err := io.ReadFull(conn, plen[:]); err != nil {
		return err
	}
	pass := make([]byte, int(plen[0]))
	if _, err := io.ReadFull(conn, pass); err != nil {
		return err
	}
	if string(user) != s.user || string(pass) != s.pass {
		_, _ = conn.Write([]byte{1, 1})
		return errors.New("byedpi: auth rejected")
	}
	_, err := conn.Write([]byte{1, 0})
	return err
}

func readRequest(conn net.Conn) (cmd byte, host string, port int, err error) {
	hdr := make([]byte, 4)
	if _, err = io.ReadFull(conn, hdr); err != nil {
		return
	}
	if hdr[0] != 5 {
		err = errors.New("byedpi: bad request")
		return
	}
	cmd = hdr[1]
	switch hdr[3] {
	case 1:
		addr := make([]byte, 4)
		if _, err = io.ReadFull(conn, addr); err != nil {
			return
		}
		host = net.IP(addr).String()
	case 3:
		var n [1]byte
		if _, err = io.ReadFull(conn, n[:]); err != nil {
			return
		}
		name := make([]byte, int(n[0]))
		if _, err = io.ReadFull(conn, name); err != nil {
			return
		}
		host = string(name)
	case 4:
		addr := make([]byte, 16)
		if _, err = io.ReadFull(conn, addr); err != nil {
			return
		}
		host = net.IP(addr).String()
	default:
		err = errors.New("byedpi: bad atyp")
		return
	}
	var pb [2]byte
	if _, err = io.ReadFull(conn, pb[:]); err != nil {
		return
	}
	port = int(binary.BigEndian.Uint16(pb[:]))
	return
}

func (s *Server) serveConnect(client net.Conn, host string, port int) {
	remote, err := s.dialTCP(host, port)
	if err != nil {
		_, _ = client.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	if err := s.reply(client, 0, remote.LocalAddr()); err != nil {
		return
	}
	s.relay(client, remote, host, port)
}

func (s *Server) dialTCP(host string, port int) (net.Conn, error) {
	d := &net.Dialer{Timeout: 15 * time.Second, Control: protectControl(s.protect)}
	return d.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
}

func (s *Server) relay(client, remote net.Conn, host string, port int) {
	desync := len(s.profile.Strategies) > 0 && MatchHost(host, s.profile.Hosts)
	if !desync {
		go copyClose(remote, client)
		_, _ = io.Copy(client, remote)
		return
	}
	first, _ := readHead(client)
	group := 0
	var prefix []byte
	if len(first) > 0 {
		for {
			if err := ApplyTCP(remote, first, s.profile.Strategies[group]); err != nil {
				return
			}
			next := nextGroup(s.profile.Strategies, group)
			if next < 0 || s.profile.Timeout <= 0 {
				break
			}
			ok, peeked := probe(remote, s.profile.Timeout)
			if ok {
				prefix = peeked
				break
			}
			_ = remote.Close()
			group = next
			var err error
			remote, err = s.dialTCP(host, port)
			if err != nil {
				return
			}
		}
	}
	go copyClose(remote, client)
	if len(prefix) > 0 {
		_, _ = client.Write(prefix)
	}
	_, _ = io.Copy(client, remote)
}

// nextGroup returns the following strategy when it is armed with --auto.
func nextGroup(groups []Strategy, current int) int {
	n := current + 1
	if n < len(groups) && groups[n].Detect != 0 {
		return n
	}
	return -1
}

func probe(conn net.Conn, d time.Duration) (ok bool, peeked []byte) {
	_ = conn.SetReadDeadline(time.Now().Add(d))
	defer conn.SetReadDeadline(time.Time{})
	buf := make([]byte, 8)
	n, err := conn.Read(buf)
	if n > 0 {
		return true, append([]byte(nil), buf[:n]...)
	}
	if err == nil {
		return true, nil
	}
	return false, nil
}

func (s *Server) reply(conn net.Conn, rep byte, addr net.Addr) error {
	ip := net.IPv4zero
	port := 0
	switch a := addr.(type) {
	case *net.TCPAddr:
		if a.IP != nil {
			ip = a.IP
		}
		port = a.Port
	case *net.UDPAddr:
		if a.IP != nil {
			ip = a.IP
		}
		port = a.Port
	}
	if ip4 := ip.To4(); ip4 != nil {
		buf := []byte{5, rep, 0, 1, 0, 0, 0, 0, byte(port >> 8), byte(port)}
		copy(buf[4:8], ip4)
		_, err := conn.Write(buf)
		return err
	}
	buf := make([]byte, 22)
	buf[0], buf[1], buf[3] = 5, rep, 4
	copy(buf[4:20], ip.To16())
	buf[20], buf[21] = byte(port>>8), byte(port)
	_, err := conn.Write(buf)
	return err
}

func (s *Server) udpLoop() {
	buf := make([]byte, 64*1024)
	for {
		n, src, err := s.udp.ReadFromUDP(buf)
		if err != nil {
			return
		}
		pkt := append([]byte(nil), buf[:n]...)
		host, port, data, ok := parseSocksUDP(pkt)
		if !ok || len(data) == 0 {
			continue
		}
		go s.forwardUDP(src, host, port, data)
	}
}

func (s *Server) forwardUDP(client *net.UDPAddr, host string, port int, data []byte) {
	raddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return
	}
	lc := net.ListenConfig{Control: protectControl(s.protect)}
	pc, err := lc.ListenPacket(context.Background(), "udp", "127.0.0.1:0")
	if err != nil {
		return
	}
	conn := pc.(*net.UDPConn)
	defer conn.Close()
	strategy := Strategy{}
	if len(s.profile.Strategies) > 0 && MatchHost(host, s.profile.Hosts) {
		strategy = s.profile.Strategies[0]
	}
	if err := ApplyUDP(conn, raddr, data, strategy); err != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 64*1024)
	n, _, err := conn.ReadFrom(buf)
	if err != nil || n == 0 {
		return
	}
	_, _ = s.udp.WriteToUDP(encodeSocksUDP(raddr.IP.String(), raddr.Port, buf[:n]), client)
}

func readHead(conn net.Conn) ([]byte, error) {
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 16*1024)
	n, err := conn.Read(buf)
	if n == 0 {
		_ = conn.SetReadDeadline(time.Time{})
		return nil, err
	}
	out := append([]byte(nil), buf[:n]...)
	_ = conn.SetReadDeadline(time.Now().Add(20 * time.Millisecond))
	for len(out) < cap(buf) {
		n, err = conn.Read(buf)
		if n > 0 {
			out = append(out, buf[:n]...)
			continue
		}
		break
	}
	_ = conn.SetReadDeadline(time.Time{})
	return out, nil
}

func copyClose(dst, src net.Conn) {
	_, _ = io.Copy(dst, src)
	if c, ok := dst.(interface{ CloseWrite() error }); ok {
		_ = c.CloseWrite()
	}
}

func bytesContains(b []byte, v byte) bool {
	for _, x := range b {
		if x == v {
			return true
		}
	}
	return false
}

func protectControl(protect func(int) bool) func(string, string, syscall.RawConn) error {
	if protect == nil {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		var ok bool
		if err := c.Control(func(fd uintptr) { ok = protect(int(fd)) }); err != nil {
			return err
		}
		if !ok {
			return errors.New("byedpi: protect rejected the socket")
		}
		return nil
	}
}

func parseSocksUDP(pkt []byte) (host string, port int, data []byte, ok bool) {
	if len(pkt) < 6 || pkt[2] != 0 {
		return
	}
	i := 3
	switch pkt[i] {
	case 1:
		i++
		if len(pkt) < i+6 {
			return
		}
		host = net.IP(pkt[i : i+4]).String()
		i += 4
	case 3:
		i++
		if i >= len(pkt) {
			return
		}
		n := int(pkt[i])
		i++
		if len(pkt) < i+n+2 {
			return
		}
		host = string(pkt[i : i+n])
		i += n
	case 4:
		i++
		if len(pkt) < i+18 {
			return
		}
		host = net.IP(pkt[i : i+16]).String()
		i += 16
	default:
		return
	}
	port = int(binary.BigEndian.Uint16(pkt[i : i+2]))
	data = pkt[i+2:]
	ok = true
	return
}

func encodeSocksUDP(host string, port int, data []byte) []byte {
	ip := net.ParseIP(host)
	var head []byte
	if ip4 := ip.To4(); ip4 != nil {
		head = []byte{0, 0, 0, 1, 0, 0, 0, 0, byte(port >> 8), byte(port)}
		copy(head[4:8], ip4)
	} else {
		head = append([]byte{0, 0, 0, 3, byte(len(host))}, []byte(host)...)
		head = append(head, byte(port>>8), byte(port))
	}
	return append(head, data...)
}
