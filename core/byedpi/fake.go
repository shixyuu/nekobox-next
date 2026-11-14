package byedpi

import "encoding/hex"

// FakeTLS is ByeDPI's built-in ClientHello (tls_data in packets.c, v0.17.3).
// The C array is 517 bytes; the explicit bytes are shorter and the tail is
// zero-filled, which is what sizeof(tls_data) hands to the fake sender.
// Copyright (c) 2024 hufrea, MIT. See THIRD_PARTY_NOTICES.
var FakeTLS = func() []byte {
	raw, err := hex.DecodeString(fakeTLSHex)
	if err != nil {
		panic(err)
	}
	out := make([]byte, 517)
	copy(out, raw)
	return out
}()

// FakeHTTP is ByeDPI's built-in HTTP decoy.
var FakeHTTP = []byte("GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n")

// FakeUDP is ByeDPI's 64-byte zero datagram used by --udp-fake.
var FakeUDP = make([]byte, 64)

const fakeTLSHex = "1603010200010001fc0303035f6f2ced1322f8dcb2f260482d72666f57dd139d1b37dcfa362ebaf992993a20f9df0c2e8a55898231631aefa8be0858a7a35a18d3965f045cb462af89d70f8b003e130213031301c02cc030009fcca9cca8ccaac02bc02f009ec024c028006bc023c0270067c00ac0140039c009c0130033009d009c003d003c0035002f00ff010001750000001600140000117777772e77696b6970656469612e6f7267000b000403000102000a00160014001d0017001e00190018010001010102010301040010000e000c02683208687474702f312e31001600000017000000310000000d002a0028040305030603080708080809080a080b080408050806040105010601030303010302040205020602002b0009080304030303020301002d00020101003300260024001d0020118cb88ce88a08901eee19d9dde8d406b1d1e2abe01663d6dcda84a4b84bfb0e001500ac000000000000"
