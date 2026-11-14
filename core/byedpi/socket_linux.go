//go:build linux || android

package byedpi

import (
	"net"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

// ApplyTCP segments the first flight with the Linux desync sender.
// Later writes on conn are ordinary and continue the same sequence space.
func ApplyTCP(conn net.Conn, payload []byte, s Strategy) error {
	raw, ok := conn.(syscall.Conn)
	if !ok {
		_, modified := Plan(payload, s)
		_, err := conn.Write(modified)
		return err
	}
	sc, err := raw.SyscallConn()
	if err != nil {
		return err
	}
	var out error
	err = sc.Write(func(fd uintptr) bool {
		out = executeFD(int(fd), payload, s)
		return true
	})
	if err != nil {
		return err
	}
	return out
}

// ApplyUDP sends count low-TTL decoy datagrams and then the real payload.
func ApplyUDP(conn *net.UDPConn, addr net.Addr, payload []byte, s Strategy) error {
	if s.UDPFakeCount <= 0 {
		if addr == nil {
			_, err := conn.Write(payload)
			return err
		}
		_, err := conn.WriteTo(payload, addr)
		return err
	}
	raw, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var out error
	err = raw.Write(func(fd uintptr) bool {
		ttl := s.TTL
		if ttl == 0 {
			ttl = 8
		}
		old := readTTL(int(fd))
		_ = setTTL(int(fd), ttl)
		for i := 0; i < s.UDPFakeCount; i++ {
			if addr == nil {
				_, _ = unix.Write(int(fd), FakeUDP)
			} else {
				_ = writeTo(int(fd), FakeUDP, addr)
			}
		}
		if old > 0 {
			_ = setTTL(int(fd), old)
		}
		if addr == nil {
			out = writeAll(int(fd), payload)
		} else {
			out = writeTo(int(fd), payload, addr)
		}
		return true
	})
	if err != nil {
		return err
	}
	return out
}

func executeFD(fd int, payload []byte, s Strategy) error {
	_ = unix.SetsockoptInt(fd, unix.IPPROTO_TCP, unix.TCP_NODELAY, 1)
	if s.DropSack {
		_ = attachDropSack(fd)
		defer detachFilter(fd)
	}
	steps, _ := Plan(payload, s)
	for _, step := range steps {
		if err := emit(fd, step, s.MD5Sig); err != nil {
			return err
		}
	}
	return nil
}

func emit(fd int, step Step, md5 bool) error {
	switch step.Kind {
	case "fake":
		return sendFake(fd, step.Real, step.Fake, step.TTL, md5)
	case "disorder":
		return sendWithTTL(fd, step.Real, step.TTL)
	case "oob":
		return sendOOB(fd, step)
	default:
		return writeAll(fd, step.Real)
	}
}

func sendWithTTL(fd int, p []byte, ttl int) error {
	if ttl == 0 {
		return writeAll(fd, p)
	}
	old := readTTL(fd)
	if err := setTTL(fd, ttl); err != nil {
		return writeAll(fd, p)
	}
	err := writeAll(fd, p)
	if old > 0 {
		_ = setTTL(fd, old)
	}
	return err
}

func sendOOB(fd int, step Step) error {
	pkt := make([]byte, len(step.Real)+1)
	copy(pkt, step.Real)
	pkt[len(step.Real)] = step.OOB
	if step.TTL != 0 {
		old := readTTL(fd)
		_ = setTTL(fd, step.TTL)
		defer func() {
			if old > 0 {
				_ = setTTL(fd, old)
			}
		}()
	}
	for len(pkt) > 0 {
		n, err := unix.SendmsgN(fd, pkt, nil, nil, unix.MSG_OOB)
		if err != nil {
			return err
		}
		if n == 0 {
			break
		}
		pkt = pkt[n:]
	}
	return nil
}

// sendFake is the Linux vmsplice/splice decoy. The page is gifted to the
// kernel, transmitted at a low TTL, then overwritten with the real bytes so
// the retransmission (the packet the server actually accepts) carries the
// original segment. TCP_MD5SIG is best-effort: Android denies it without
// extra capability and that must not fail the connection.
func sendFake(fd int, real, fake []byte, ttl int, md5 bool) error {
	if len(real) == 0 {
		return nil
	}
	page, err := unix.Mmap(-1, 0, len(real), unix.PROT_READ|unix.PROT_WRITE, unix.MAP_PRIVATE|unix.MAP_ANONYMOUS)
	if err != nil || len(page) < len(real) {
		return writeAll(fd, real)
	}
	copy(page, fake)
	old := readTTL(fd)
	if ttl <= 0 {
		ttl = 8
	}
	_ = setTTL(fd, ttl)
	if md5 {
		_ = setMD5(fd, 5)
	}
	err = spliceGift(fd, page)
	if err != nil {
		if old > 0 {
			_ = setTTL(fd, old)
		}
		_ = unix.Munmap(page)
		return writeAll(fd, real)
	}
	waitDrained(fd, 30*time.Millisecond)
	copy(page, real)
	if md5 {
		_ = setMD5(fd, 0)
	}
	if old > 0 {
		_ = setTTL(fd, old)
	}
	_ = unix.Munmap(page)
	return nil
}

func spliceGift(fd int, page []byte) error {
	var p [2]int
	if err := unix.Pipe(p[:]); err != nil {
		return err
	}
	defer unix.Close(p[0])
	defer unix.Close(p[1])
	var iov unix.Iovec
	iov.Base = &page[0]
	iov.SetLen(len(page))
	n, err := unix.Vmsplice(p[1], []unix.Iovec{iov}, unix.SPLICE_F_GIFT)
	if err != nil {
		return err
	}
	_, err = unix.Splice(p[0], nil, fd, nil, n, 0)
	return err
}

func waitDrained(fd int, d time.Duration) {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		info, err := unix.GetsockoptTCPInfo(fd, unix.IPPROTO_TCP, unix.TCP_INFO)
		if err != nil || info.Notsent_bytes == 0 {
			return
		}
		time.Sleep(time.Millisecond)
	}
}

func writeAll(fd int, p []byte) error {
	for len(p) > 0 {
		n, err := unix.Write(fd, p)
		if err != nil {
			if err == unix.EAGAIN || err == unix.EINTR {
				continue
			}
			return err
		}
		if n == 0 {
			break
		}
		p = p[n:]
	}
	return nil
}

func writeTo(fd int, p []byte, addr net.Addr) error {
	sa, err := addrToSockaddr(addr)
	if err != nil {
		return err
	}
	return unix.Sendto(fd, p, 0, sa)
}

func addrToSockaddr(addr net.Addr) (unix.Sockaddr, error) {
	switch a := addr.(type) {
	case *net.UDPAddr:
		if ip4 := a.IP.To4(); ip4 != nil {
			sa := &unix.SockaddrInet4{Port: a.Port}
			copy(sa.Addr[:], ip4)
			return sa, nil
		}
		sa := &unix.SockaddrInet6{Port: a.Port}
		copy(sa.Addr[:], a.IP.To16())
		return sa, nil
	default:
		return nil, unix.EINVAL
	}
}

func readTTL(fd int) int {
	v, err := unix.GetsockoptInt(fd, unix.IPPROTO_IP, unix.IP_TTL)
	if err != nil || v <= 0 {
		return 64
	}
	return v
}

func setTTL(fd, ttl int) error {
	e4 := unix.SetsockoptInt(fd, unix.IPPROTO_IP, unix.IP_TTL, ttl)
	e6 := unix.SetsockoptInt(fd, unix.IPPROTO_IPV6, unix.IPV6_UNICAST_HOPS, ttl)
	if e4 != nil && e6 != nil {
		return e4
	}
	return nil
}

func setMD5(fd int, keyLen int) error {
	// The option is attempted and ignored on failure. Unprivileged Android
	// processes usually cannot set TCP_MD5SIG; the TTL overwrite still runs.
	var md5 unix.TCPMD5Sig
	sa, err := unix.Getsockname(fd)
	_ = sa
	peer, err := unix.Getpeername(fd)
	if err != nil {
		return err
	}
	switch p := peer.(type) {
	case *unix.SockaddrInet4:
		md5.Addr.Family = unix.AF_INET
		copy(md5.Addr.Data[2:6], p.Addr[:])
		md5.Addr.Data[0] = byte(p.Port >> 8)
		md5.Addr.Data[1] = byte(p.Port)
	default:
		return unix.ENOTSUP
	}
	md5.Keylen = uint16(keyLen)
	return unix.SetsockoptTCPMD5Sig(fd, unix.IPPROTO_TCP, unix.TCP_MD5SIG, &md5)
}

func attachDropSack(fd int) error {
	filt := []unix.SockFilter{
		{Code: 0x30, K: 0x0c},
		{Code: 0x74, K: 0x04},
		{Code: 0x35, Jf: 3, K: 0x0b},
		{Code: 0x30, K: 0x22},
		{Code: 0x15, Jf: 1, K: 0x05},
		{Code: 0x06, K: 0x00000000},
		{Code: 0x06, K: 0x00040000},
	}
	prog := unix.SockFprog{Len: uint16(len(filt)), Filter: &filt[0]}
	return unix.SetsockoptSockFprog(fd, unix.SOL_SOCKET, unix.SO_ATTACH_FILTER, &prog)
}

func detachFilter(fd int) {
	_ = unix.SetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_DETACH_FILTER, 0)
}
