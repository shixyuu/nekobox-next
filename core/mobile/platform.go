package mobile

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"

	"github.com/sagernet/sing-box/experimental/libbox"
)

// androidPlatform adapts the small gomobile interface to libbox 1.14.
type androidPlatform struct {
	p       Platform
	tunName string
}

func (a *androidPlatform) LocalDNSTransport() libbox.LocalDNSTransport { return noopDNS{} }
func (a *androidPlatform) UsePlatformAutoDetectInterfaceControl() bool  { return true }
func (a *androidPlatform) AutoDetectInterfaceControl(fd int32) error {
	if a.p.Protect(fd) {
		return nil
	}
	return errors.New("VpnService.protect rejected fd")
}

func (a *androidPlatform) OpenTun(options libbox.TunOptions) (int32, error) {
	spec := tunSpec{
		MTU:            options.GetMTU(),
		AutoRoute:      options.GetAutoRoute(),
		StrictRoute:    options.GetStrictRoute(),
		Inet4Address:   prefixes(options.GetInet4Address()),
		Inet6Address:   prefixes(options.GetInet6Address()),
		Inet4Route:     prefixes(options.GetInet4RouteAddress()),
		Inet6Route:     prefixes(options.GetInet6RouteAddress()),
		Inet4Exclude:   prefixes(options.GetInet4RouteExcludeAddress()),
		Inet6Exclude:   prefixes(options.GetInet6RouteExcludeAddress()),
		IncludePackage: collect(options.GetIncludePackage()),
		ExcludePackage: collect(options.GetExcludePackage()),
	}
	if mode := options.GetDNSMode(); mode != nil {
		spec.DNSMode = mode.Value
	}
	if servers, err := options.GetDNSServerAddress(); err == nil {
		spec.DNSServers = collect(servers)
	}
	raw, err := json.Marshal(spec)
	if err != nil {
		return 0, err
	}
	return a.p.OpenTun(string(raw))
}

func (a *androidPlatform) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*libbox.ConnectionOwner, error) {
	raw := a.p.FindProcess(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
	if raw == "" {
		return nil, errors.New("connection owner not found")
	}
	lines := strings.Split(raw, "\n")
	uid, err := strconv.Atoi(strings.TrimSpace(lines[0]))
	if err != nil {
		return nil, err
	}
	owner := &libbox.ConnectionOwner{UserId: int32(uid)}
	var pkgs []string
	for _, line := range lines[1:] {
		if line != "" {
			pkgs = append(pkgs, line)
		}
	}
	if len(pkgs) > 0 {
		owner.SetAndroidPackageNames(newStrings(pkgs))
	}
	return owner, nil
}

func (a *androidPlatform) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	if listener == nil {
		return errors.New("nil interface listener")
	}
	name := a.p.DefaultInterface()
	if name == "" {
		name = "wlan0"
	}
	listener.UpdateDefaultInterface(name, 1, false, false)
	return nil
}

func (a *androidPlatform) CloseDefaultInterfaceMonitor(libbox.InterfaceUpdateListener) error {
	return nil
}

func (a *androidPlatform) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	name := a.p.DefaultInterface()
	if name == "" {
		name = "wlan0"
	}
	item := &libbox.NetworkInterface{
		Index:     1,
		MTU:       1500,
		Name:      name,
		Addresses: newStrings(nil),
		Type:      libbox.InterfaceTypeWIFI,
		DNSServer: newStrings(nil),
		Gateway:   newStrings(nil),
	}
	return &niIter{items: []*libbox.NetworkInterface{item}}, nil
}

func (a *androidPlatform) UnderNetworkExtension() bool { return false }
func (a *androidPlatform) IncludeAllNetworks() bool    { return false }
func (a *androidPlatform) ReadWIFIState() *libbox.WIFIState {
	return libbox.NewWIFIState("", "")
}
func (a *androidPlatform) ClearDNSCache() {}
func (a *androidPlatform) SendNotification(notification *libbox.Notification) error {
	if notification != nil && a.p != nil {
		a.p.WriteLog(notification.Title + ": " + notification.Body)
	}
	return nil
}
func (a *androidPlatform) CancelNotification(string, int32) error { return nil }
func (a *androidPlatform) StartNeighborMonitor(libbox.NeighborUpdateListener) error {
	return errors.New("neighbor monitor is not used")
}
func (a *androidPlatform) CloseNeighborMonitor(libbox.NeighborUpdateListener) error { return nil }
func (a *androidPlatform) RegisterMyInterface(name string)                         { a.tunName = name }
func (a *androidPlatform) UsePlatformShell() bool                                  { return false }
func (a *androidPlatform) CheckPlatformShell() error {
	return errors.New("platform shell is disabled")
}
func (a *androidPlatform) OpenShellSession(*libbox.PlatformUser, string, libbox.StringIterator, string, int32, int32) (libbox.ShellSession, error) {
	return nil, errors.New("platform shell is disabled")
}
func (a *androidPlatform) LookupUser(string) (*libbox.PlatformUser, error) {
	return nil, errors.New("user lookup is disabled")
}
func (a *androidPlatform) LookupSFTPServer() (string, error) {
	return "", errors.New("sftp is disabled")
}
func (a *androidPlatform) ReadSystemSSHHostKey() (string, error) {
	return "", errors.New("ssh host key is disabled")
}
func (a *androidPlatform) TailscaleHostname() string { return "" }
func (a *androidPlatform) UsePlatformBridge() bool   { return false }
func (a *androidPlatform) CreateBridge(*libbox.BridgeOptions) (libbox.BridgeSession, error) {
	return nil, errors.New("bridge is disabled")
}

type tunSpec struct {
	MTU            int32    `json:"mtu"`
	AutoRoute      bool     `json:"autoRoute"`
	StrictRoute    bool     `json:"strictRoute"`
	DNSMode        string   `json:"dnsMode"`
	Inet4Address   []string `json:"inet4Address"`
	Inet6Address   []string `json:"inet6Address"`
	Inet4Route     []string `json:"inet4Route"`
	Inet6Route     []string `json:"inet6Route"`
	Inet4Exclude   []string `json:"inet4Exclude"`
	Inet6Exclude   []string `json:"inet6Exclude"`
	IncludePackage []string `json:"includePackage"`
	ExcludePackage []string `json:"excludePackage"`
	DNSServers     []string `json:"dnsServers"`
}

func prefixes(it libbox.RoutePrefixIterator) []string {
	if it == nil {
		return nil
	}
	var out []string
	for it.HasNext() {
		p := it.Next()
		if p == nil {
			break
		}
		out = append(out, p.String())
	}
	return out
}

func collect(it libbox.StringIterator) []string {
	if it == nil {
		return nil
	}
	var out []string
	for it.HasNext() {
		out = append(out, it.Next())
	}
	return out
}

type niIter struct {
	items []*libbox.NetworkInterface
	i     int
}

func (n *niIter) HasNext() bool { return n.i < len(n.items) }
func (n *niIter) Next() *libbox.NetworkInterface {
	if n.i >= len(n.items) {
		return nil
	}
	v := n.items[n.i]
	n.i++
	return v
}

type noopDNS struct{}

func (noopDNS) Raw() bool { return false }
func (noopDNS) Lookup(*libbox.ExchangeContext, string, string) error {
	return errors.New("platform local dns is disabled")
}
func (noopDNS) Exchange(*libbox.ExchangeContext, []byte) error {
	return errors.New("platform local dns is disabled")
}
