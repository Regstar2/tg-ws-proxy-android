package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
)

const (
	defaultAwgWarpMTU       = 1280
	maxAwgWarpConfigBytes   = 64 * 1024
	wireGuardKeySize        = 32
	awgWarpInterfaceSection = "interface"
	awgWarpPeerSection      = "peer"
)

type awgWarpConfig struct {
	privateKeyHex string
	addresses     []netip.Addr
	mtu           int
	deviceOptions []awgWarpOption
	peer          awgWarpPeerConfig
}

type awgWarpPeerConfig struct {
	publicKeyHex        string
	endpoint            string
	allowedIPs          []netip.Prefix
	persistentKeepalive string
}

type awgWarpOption struct {
	key   string
	value string
}

func loadAwgWarpConfigFile(path string) (awgWarpConfig, error) {
	info, err := os.Stat(path)
	if err != nil {
		return awgWarpConfig{}, fmt.Errorf("stat AWG/WARP config: %w", err)
	}
	if !info.Mode().IsRegular() {
		return awgWarpConfig{}, errors.New("AWG/WARP config must be a regular file")
	}
	if info.Size() > maxAwgWarpConfigBytes {
		return awgWarpConfig{}, fmt.Errorf("AWG/WARP config exceeds %d bytes", maxAwgWarpConfigBytes)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return awgWarpConfig{}, fmt.Errorf("read AWG/WARP config: %w", err)
	}
	return parseAwgWarpConfig(string(data))
}

func parseAwgWarpConfig(text string) (awgWarpConfig, error) {
	cfg := awgWarpConfig{mtu: defaultAwgWarpMTU}
	seenOptions := make(map[string]struct{})
	section := ""
	peerCount := 0

	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 1024), maxAwgWarpConfigBytes)
	for lineNumber := 1; scanner.Scan(); lineNumber++ {
		line := strings.TrimSpace(stripAwgWarpComment(scanner.Text()))
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			switch section {
			case awgWarpInterfaceSection:
			case awgWarpPeerSection:
				peerCount++
				if peerCount > 1 {
					return awgWarpConfig{}, errors.New("AWG/WARP PoC supports exactly one [Peer] section")
				}
			default:
				return awgWarpConfig{}, fmt.Errorf("unsupported AWG/WARP section on line %d", lineNumber)
			}
			continue
		}

		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return awgWarpConfig{}, fmt.Errorf("invalid AWG/WARP config line %d", lineNumber)
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if key == "" || value == "" {
			return awgWarpConfig{}, fmt.Errorf("empty AWG/WARP key or value on line %d", lineNumber)
		}

		var err error
		switch section {
		case awgWarpInterfaceSection:
			err = cfg.parseInterfaceField(key, value, seenOptions)
		case awgWarpPeerSection:
			err = cfg.parsePeerField(key, value)
		default:
			err = errors.New("AWG/WARP fields must be inside [Interface] or [Peer]")
		}
		if err != nil {
			return awgWarpConfig{}, fmt.Errorf("AWG/WARP config line %d: %w", lineNumber, err)
		}
	}
	if err := scanner.Err(); err != nil {
		return awgWarpConfig{}, fmt.Errorf("scan AWG/WARP config: %w", err)
	}
	if peerCount != 1 {
		return awgWarpConfig{}, errors.New("AWG/WARP PoC requires exactly one [Peer] section")
	}
	if err := cfg.validate(); err != nil {
		return awgWarpConfig{}, err
	}
	return cfg, nil
}

func (cfg *awgWarpConfig) parseInterfaceField(key, value string, seenOptions map[string]struct{}) error {
	normalized := strings.ToLower(strings.TrimSpace(key))
	switch normalized {
	case "privatekey":
		if cfg.privateKeyHex != "" {
			return errors.New("duplicate PrivateKey")
		}
		keyHex, err := decodeWireGuardKey(value)
		if err != nil {
			return fmt.Errorf("invalid PrivateKey: %w", err)
		}
		cfg.privateKeyHex = keyHex
		return nil
	case "address":
		addresses, err := parseAwgWarpAddresses(value)
		if err != nil {
			return err
		}
		cfg.addresses = append(cfg.addresses, addresses...)
		return nil
	case "mtu":
		mtu, err := strconv.Atoi(value)
		if err != nil || mtu < 576 || mtu > 65535 {
			return errors.New("MTU must be an integer in range 576..65535")
		}
		cfg.mtu = mtu
		return nil
	case "dns", "table":
		// The PoC does not route system traffic or rely on in-tunnel DNS.
		return nil
	default:
		uapiKey, ok := awgWarpDeviceOptionKey(normalized)
		if !ok {
			return fmt.Errorf("unsupported [Interface] field %q", key)
		}
		if _, exists := seenOptions[uapiKey]; exists {
			return fmt.Errorf("duplicate %s", key)
		}

		normalizedValue := value
		switch uapiKey {
		case "header_protection_key":
			var err error
			normalizedValue, err = decodeAwgWarpHeaderProtectionKey(value)
			if err != nil {
				return err
			}
		case "random_trailers", "disable_cookies":
			switch strings.ToLower(value) {
			case "on", "true", "1":
				normalizedValue = "true"
			case "off", "false", "0":
				normalizedValue = "false"
			default:
				return fmt.Errorf("%s must be on/off, true/false or 1/0", key)
			}
		}
		if err := validateAwgWarpDeviceOption(uapiKey, normalizedValue); err != nil {
			return err
		}
		seenOptions[uapiKey] = struct{}{}
		cfg.deviceOptions = append(cfg.deviceOptions, awgWarpOption{key: uapiKey, value: normalizedValue})
		return nil
	}
}

func (cfg *awgWarpConfig) parsePeerField(key, value string) error {
	normalized := strings.ToLower(strings.TrimSpace(key))
	switch normalized {
	case "publickey":
		if cfg.peer.publicKeyHex != "" {
			return errors.New("duplicate PublicKey")
		}
		keyHex, err := decodeWireGuardKey(value)
		if err != nil {
			return fmt.Errorf("invalid PublicKey: %w", err)
		}
		cfg.peer.publicKeyHex = keyHex
		return nil
	case "endpoint":
		if cfg.peer.endpoint != "" {
			return errors.New("duplicate Endpoint")
		}
		if err := validateAwgWarpEndpoint(value); err != nil {
			return err
		}
		cfg.peer.endpoint = value
		return nil
	case "allowedips":
		prefixes, err := parseAwgWarpAllowedIPs(value)
		if err != nil {
			return err
		}
		cfg.peer.allowedIPs = append(cfg.peer.allowedIPs, prefixes...)
		return nil
	case "persistentkeepalive":
		if cfg.peer.persistentKeepalive != "" {
			return errors.New("duplicate PersistentKeepalive")
		}
		seconds, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return errors.New("PersistentKeepalive must be an integer in range 0..65535")
		}
		cfg.peer.persistentKeepalive = strconv.FormatUint(seconds, 10)
		return nil
	case "presharedkey":
		return errors.New("PresharedKey is outside the initial AWG/WARP PoC scope")
	default:
		return fmt.Errorf("unsupported [Peer] field %q", key)
	}
}

func (cfg awgWarpConfig) validate() error {
	if cfg.privateKeyHex == "" {
		return errors.New("AWG/WARP config is missing Interface.PrivateKey")
	}
	if len(cfg.addresses) == 0 {
		return errors.New("AWG/WARP config is missing Interface.Address")
	}
	if cfg.peer.publicKeyHex == "" {
		return errors.New("AWG/WARP config is missing Peer.PublicKey")
	}
	if cfg.peer.endpoint == "" {
		return errors.New("AWG/WARP config is missing Peer.Endpoint")
	}
	if len(cfg.peer.allowedIPs) == 0 {
		return errors.New("AWG/WARP config is missing Peer.AllowedIPs")
	}

	var jmin, jmax uint64
	var hasJmin, hasJmax bool
	var hasHeaderProtectionKey bool
	paddingValues := make(map[string]uint64, 4)
	for _, option := range cfg.deviceOptions {
		switch option.key {
		case "jmin":
			jmin, _ = strconv.ParseUint(option.value, 10, 32)
			hasJmin = true
		case "jmax":
			jmax, _ = strconv.ParseUint(option.value, 10, 32)
			hasJmax = true
		case "header_protection_key":
			hasHeaderProtectionKey = true
		case "s1", "s2", "s3", "s4":
			padding, _ := strconv.ParseUint(option.value, 10, 16)
			paddingValues[option.key] = padding
		}
	}
	if hasJmin && hasJmax && jmin > jmax {
		return errors.New("AWG/WARP Jmin must not exceed Jmax")
	}
	if hasHeaderProtectionKey {
		for _, key := range []string{"s1", "s2", "s3", "s4"} {
			padding, ok := paddingValues[key]
			if !ok || padding < 12 {
				return errors.New("AWG/WARP HeaderProtectionKey requires S1, S2, S3 and S4 to be at least 12")
			}
		}
	}
	return nil
}

func (cfg awgWarpConfig) uapiConfig() string {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", cfg.privateKeyHex)
	b.WriteString("replace_peers=true\n")
	for _, option := range cfg.deviceOptions {
		fmt.Fprintf(&b, "%s=%s\n", option.key, option.value)
	}
	fmt.Fprintf(&b, "public_key=%s\n", cfg.peer.publicKeyHex)
	fmt.Fprintf(&b, "endpoint=%s\n", cfg.peer.endpoint)
	b.WriteString("replace_allowed_ips=true\n")
	for _, prefix := range cfg.peer.allowedIPs {
		fmt.Fprintf(&b, "allowed_ip=%s\n", prefix.String())
	}
	if cfg.peer.persistentKeepalive != "" {
		fmt.Fprintf(&b, "persistent_keepalive_interval=%s\n", cfg.peer.persistentKeepalive)
	}
	b.WriteByte('\n')
	return b.String()
}

func (cfg awgWarpConfig) allowsTarget(addr netip.Addr) bool {
	for _, prefix := range cfg.peer.allowedIPs {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}

func decodeAwgWarpHeaderProtectionKey(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	decoded, err := hex.DecodeString(trimmed)
	if err != nil || len(decoded) != wireGuardKeySize {
		decoded, err = base64.StdEncoding.DecodeString(trimmed)
		if err != nil {
			decoded, err = base64.RawStdEncoding.DecodeString(trimmed)
		}
	}
	if err != nil || len(decoded) != wireGuardKeySize {
		return "", errors.New("HEADER_PROTECTION_KEY must be a 32-byte Base64 key or a 64-character hex key")
	}
	allZero := true
	for _, value := range decoded {
		if value != 0 {
			allZero = false
			break
		}
	}
	if allZero {
		return "", errors.New("HEADER_PROTECTION_KEY must not be all zero")
	}
	return hex.EncodeToString(decoded), nil
}

func decodeWireGuardKey(value string) (string, error) {
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil {
		decoded, err = base64.RawStdEncoding.DecodeString(strings.TrimSpace(value))
	}
	if err != nil {
		return "", errors.New("expected base64 WireGuard key")
	}
	if len(decoded) != wireGuardKeySize {
		return "", fmt.Errorf("expected %d decoded bytes, got %d", wireGuardKeySize, len(decoded))
	}
	return hex.EncodeToString(decoded), nil
}

func parseAwgWarpAddresses(value string) ([]netip.Addr, error) {
	var addresses []netip.Addr
	for _, item := range strings.Split(value, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			return nil, errors.New("Address entry must not be empty")
		}

		if addr, err := netip.ParseAddr(item); err == nil {
			addresses = append(addresses, addr.Unmap())
			continue
		}

		prefix, err := netip.ParsePrefix(item)
		if err != nil {
			return nil, fmt.Errorf("invalid Address %q", item)
		}
		addresses = append(addresses, prefix.Addr().Unmap())
	}
	return addresses, nil
}

func parseAwgWarpAllowedIPs(value string) ([]netip.Prefix, error) {
	var prefixes []netip.Prefix
	for _, item := range strings.Split(value, ",") {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(item))
		if err != nil {
			return nil, fmt.Errorf("invalid AllowedIPs entry %q", strings.TrimSpace(item))
		}
		prefixes = append(prefixes, prefix.Masked())
	}
	return prefixes, nil
}

func validateAwgWarpEndpoint(value string) error {
	host, portText, err := net.SplitHostPort(strings.TrimSpace(value))
	if err != nil || strings.TrimSpace(host) == "" {
		return errors.New("Endpoint must be host:port or [ipv6]:port")
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil || port == 0 {
		return errors.New("Endpoint port must be in range 1..65535")
	}
	return nil
}

func awgWarpDeviceOptionKey(normalized string) (string, bool) {
	switch normalized {
	case "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5":
		return normalized, true
	case "headerprotectionkey":
		return "header_protection_key", true
	case "contentpaddingaddition":
		return "content_padding_addition", true
	case "rekeyaftertime":
		return "rekey_after_time", true
	case "rekeytimeout":
		return "rekey_timeout", true
	case "rejectaftertime":
		return "reject_after_time", true
	case "keepalivetimeout":
		return "keepalive_timeout", true
	case "maxhandshakeattempts":
		return "max_handshake_attempts", true
	case "randomtrailers":
		return "random_trailers", true
	case "disablecookies":
		return "disable_cookies", true
	default:
		return "", false
	}
}

func parseAwgUint32Range(value string) (uint64, uint64, error) {
	parts := strings.Split(value, "-")
	if len(parts) < 1 || len(parts) > 2 {
		return 0, 0, errors.New("expected an unsigned 32-bit integer or range")
	}
	low, err := strconv.ParseUint(parts[0], 10, 32)
	if err != nil {
		return 0, 0, errors.New("invalid lower bound in unsigned 32-bit range")
	}
	high := low
	if len(parts) == 2 {
		high, err = strconv.ParseUint(parts[1], 10, 32)
		if err != nil {
			return 0, 0, errors.New("invalid upper bound in unsigned 32-bit range")
		}
	}
	if high < low {
		return 0, 0, errors.New("upper bound is below lower bound")
	}
	return low, high, nil
}

func validateAwgWarpDeviceOption(key, value string) error {
	switch key {
	case "jc", "jmin", "jmax":
		if _, err := strconv.ParseUint(value, 10, 32); err != nil {
			return fmt.Errorf("%s must be an unsigned 32-bit integer", strings.ToUpper(key))
		}
	case "s1", "s2", "s3", "s4":
		if _, err := strconv.ParseUint(value, 10, 16); err != nil {
			return fmt.Errorf("%s must be an unsigned 16-bit integer", strings.ToUpper(key))
		}
	case "h1", "h2", "h3", "h4",
		"content_padding_addition", "rekey_after_time", "rekey_timeout",
		"reject_after_time", "keepalive_timeout", "max_handshake_attempts":
		if _, _, err := parseAwgUint32Range(value); err != nil {
			return fmt.Errorf("%s must be an unsigned 32-bit integer or range: %w", strings.ToUpper(key), err)
		}
	case "header_protection_key":
		decoded, err := hex.DecodeString(value)
		if err != nil || len(decoded) != wireGuardKeySize {
			return errors.New("HEADER_PROTECTION_KEY must encode exactly 32 bytes")
		}
	case "random_trailers", "disable_cookies":
		if _, err := strconv.ParseBool(value); err != nil {
			return fmt.Errorf("%s must be a boolean", strings.ToUpper(key))
		}
	case "i1", "i2", "i3", "i4", "i5":
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s must not be empty", strings.ToUpper(key))
		}
	}
	return nil
}

func stripAwgWarpComment(line string) string {
	for i := 0; i < len(line); i++ {
		if (line[i] == '#' || line[i] == ';') && (i == 0 || line[i-1] == ' ' || line[i-1] == '\t') {
			return line[:i]
		}
	}
	return line
}
