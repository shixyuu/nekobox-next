// SPDX-License-Identifier: GPL-3.0-or-later
// Package mobile is the gomobile surface of NekoBOX NEXT.
//
// sing-box is the unmodified official libbox (github.com/sagernet/sing-box).
// ByeDPI is not registered as a custom outbound: a loopback SOCKS5 server
// with random credentials is started first, and the generated config points
// a socks outbound at it. That keeps libbox upgradeable and lets the dialer
// call VpnService.protect on its own sockets, which a detour inside sing-box
// would not do for a third-party stack.
package mobile

import (
	"encoding/json"
	"errors"
	"sync"

	"nekobox.next/core/byedpi"

	"github.com/sagernet/sing-box/experimental/libbox"
)

// Platform is implemented by the Android app. Method names are consumed by
// gomobile, so signatures stay primitive.
type Platform interface {
	Protect(fd int32) bool
	// OpenTun receives a JSON object describing addresses, routes, MTU and
	// per-app lists. It returns the TUN file descriptor.
	OpenTun(spec string) (int32, error)
	WriteLog(message string)
	// FindProcess returns "uid\npkg\npkg" or an empty string.
	FindProcess(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) string
	DefaultInterface() string
}

var (
	mu       sync.Mutex
	server   *libbox.CommandServer
	bye      *byedpi.Server
	platform Platform
)

// CoreVersion reports the linked sing-box version.
func CoreVersion() string { return libbox.Version() }

// ByeDpiVersion is the ByeDPI release this port tracks.
func ByeDpiVersion() string { return "0.17.3" }

// Start launches the optional ByeDPI listener and then sing-box.
// byedpiJSON is empty when the active profile does not need it.
func Start(configJSON, byedpiJSON, basePath, workPath, tempPath string, p Platform) error {
	mu.Lock()
	defer mu.Unlock()
	if err := stopLocked(); err != nil {
		return err
	}
	platform = p
	if err := startByeLocked(byedpiJSON, p); err != nil {
		return err
	}
	return startBoxLocked(configJSON, basePath, workPath, tempPath, p)
}

// Reload applies a new sing-box config. The ByeDPI listener is restarted
// when its JSON changes, because selector switches cannot retune a desync
// profile that is already bound.
func Reload(configJSON, byedpiJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if server == nil {
		return errors.New("nekobox: core is not running")
	}
	if err := restartByeLocked(byedpiJSON); err != nil {
		return err
	}
	return server.StartOrReloadService(configJSON, emptyOverride())
}

// Stop shuts sing-box down and then the local desync listener.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	return stopLocked()
}

func startBoxLocked(configJSON, basePath, workPath, tempPath string, p Platform) error {
	err := libbox.Setup(&libbox.SetupOptions{
		BasePath:        basePath,
		WorkingPath:     workPath,
		TempPath:        tempPath,
		FixAndroidStack: true,
		LogMaxLines:     500,
	})
	if err != nil {
		stopByeLocked()
		return err
	}
	handler := &commandHandler{log: p.WriteLog}
	box, err := libbox.NewCommandServer(handler, &androidPlatform{p: p})
	if err != nil {
		stopByeLocked()
		return err
	}
	if err := box.Start(); err != nil {
		box.Close()
		stopByeLocked()
		return err
	}
	if err := box.StartOrReloadService(configJSON, emptyOverride()); err != nil {
		box.Close()
		stopByeLocked()
		return err
	}
	server = box
	return nil
}

type byeSpec struct {
	Listen   string   `json:"listen"`
	Username string   `json:"username"`
	Password string   `json:"password"`
	Args     string   `json:"args"`
	Hosts    []string `json:"hosts"`
}

var byeJSON string

func startByeLocked(raw string, p Platform) error {
	byeJSON = raw
	if raw == "" || raw == "{}" || raw == "null" {
		return nil
	}
	var spec byeSpec
	if err := json.Unmarshal([]byte(raw), &spec); err != nil {
		return err
	}
	if spec.Listen == "" {
		return nil
	}
	profile, err := byedpi.ParseArgs(spec.Args)
	if err != nil {
		return err
	}
	if len(spec.Hosts) > 0 {
		profile.Hosts = append(profile.Hosts, spec.Hosts...)
	}
	protect := func(fd int) bool { return p.Protect(int32(fd)) }
	listener, err := byedpi.Listen(spec.Listen, spec.Username, spec.Password, profile, protect)
	if err != nil {
		return err
	}
	bye = listener
	p.WriteLog("byedpi listening on " + listener.Addr())
	return nil
}

func restartByeLocked(raw string) error {
	if raw == byeJSON {
		return nil
	}
	stopByeLocked()
	if platform == nil {
		return errors.New("nekobox: platform is gone")
	}
	return startByeLocked(raw, platform)
}

func stopByeLocked() {
	if bye != nil {
		_ = bye.Close()
		bye = nil
	}
}

func stopLocked() error {
	var err error
	if server != nil {
		err = server.CloseService()
		server.Close()
		server = nil
	}
	stopByeLocked()
	byeJSON = ""
	return err
}

func emptyOverride() *libbox.OverrideOptions {
	empty := &stringIter{}
	return &libbox.OverrideOptions{IncludePackage: empty, ExcludePackage: &stringIter{}}
}

type commandHandler struct {
	log func(string)
}

func (h *commandHandler) ServiceStop() error  { return nil }
func (h *commandHandler) ServiceReload() error { return nil }
func (h *commandHandler) GetSystemProxyStatus() (*libbox.SystemProxyStatus, error) {
	return &libbox.SystemProxyStatus{Available: false, Enabled: false}, nil
}
func (h *commandHandler) SetSystemProxyEnabled(bool) error { return errors.New("system proxy is unused") }
func (h *commandHandler) TriggerNativeCrash() error        { return errors.New("disabled") }
func (h *commandHandler) WriteDebugMessage(message string) {
	if h.log != nil {
		h.log(message)
	}
}
func (h *commandHandler) ConnectSSHAgent() (int32, error) {
	return -1, errors.New("ssh agent is disabled")
}

type stringIter struct {
	items []string
	i     int
}

func (s *stringIter) Len() int32 { return int32(len(s.items)) }
func (s *stringIter) HasNext() bool { return s != nil && s.i < len(s.items) }
func (s *stringIter) Next() string {
	v := s.items[s.i]
	s.i++
	return v
}

func newStrings(items []string) *stringIter { return &stringIter{items: items} }
