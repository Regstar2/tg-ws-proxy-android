package tgwsroute

import (
	"net/netip"
	"strings"
)

type TelegramDCInfo struct {
	DC    int
	Media bool
}

var telegramDCByIP = map[string]TelegramDCInfo{
	// DC1
	"149.154.175.50": {DC: 1}, "149.154.175.51": {DC: 1},
	"149.154.175.53": {DC: 1}, "149.154.175.54": {DC: 1},
	"149.154.175.55": {DC: 1},
	"149.154.175.52": {DC: 1, Media: true},
	// DC2
	"149.154.167.41": {DC: 2}, "149.154.167.50": {DC: 2},
	"149.154.167.51": {DC: 2}, "149.154.167.220": {DC: 2},
	"149.154.167.35": {DC: 2}, "149.154.167.36": {DC: 2},
	"95.161.76.100":   {DC: 2},
	"149.154.167.151": {DC: 2, Media: true}, "149.154.167.222": {DC: 2, Media: true},
	"149.154.167.223": {DC: 2, Media: true}, "149.154.162.123": {DC: 2, Media: true},
	// DC3
	"149.154.175.100": {DC: 3}, "149.154.175.101": {DC: 3},
	"149.154.175.102": {DC: 3, Media: true},
	// DC4
	"149.154.167.91": {DC: 4}, "149.154.167.92": {DC: 4},
	"149.154.164.250": {DC: 4, Media: true}, "149.154.166.120": {DC: 4, Media: true},
	"149.154.166.121": {DC: 4, Media: true}, "149.154.167.118": {DC: 4, Media: true},
	"149.154.165.111": {DC: 4, Media: true},
	// Telegram Android may open MTProto sessions to IPv6 literals. Keep this
	// list exact; broad IPv6 ranges risk routing unknown traffic to the wrong DC.
	"2001:b28:f23d:f001::a": {DC: 1},
	"2001:67c:4e8:f002::a":  {DC: 2}, "2001:67c:4e8:f002::b": {DC: 2},
	"2001:67c:4e8:f004::a": {DC: 4}, "2001:67c:4e8:f004::b": {DC: 4},
	// DC5
	"91.108.56.100": {DC: 5}, "91.108.56.101": {DC: 5},
	"91.108.56.116": {DC: 5}, "91.108.56.126": {DC: 5},
	"91.108.56.198": {DC: 5},
	"149.154.171.5": {DC: 5},
	"91.108.56.102": {DC: 5, Media: true}, "91.108.56.128": {DC: 5, Media: true},
	"91.108.56.151": {DC: 5, Media: true},
	// DC203
	"91.105.192.100": {DC: 203},
}

var telegramIPv4Prefixes = mustParsePrefixes(
	"185.76.151.0/24",
	"149.154.160.0/20",
	"91.105.192.0/23",
	"91.108.0.0/16",
)

var telegramIPv6Exact = mustParseAddrSet(
	"2001:b28:f23d:f001::a",
	"2001:67c:4e8:f002::a",
	"2001:67c:4e8:f002::b",
	"2001:67c:4e8:f004::a",
	"2001:67c:4e8:f004::b",
	// Seen in Android traffic, but the exact DC mapping is not established yet.
	"2a0a:f280:203:a:5000::100",
)

func LookupTelegramDC(dst string) (TelegramDCInfo, bool) {
	addr, ok := parseAddr(dst)
	if !ok {
		return TelegramDCInfo{}, false
	}
	info, found := telegramDCByIP[addr.String()]
	return info, found
}

func IsTelegramLikeIP(dst string) bool {
	addr, ok := parseAddr(dst)
	if !ok {
		return false
	}
	if addr.Is4() {
		for _, prefix := range telegramIPv4Prefixes {
			if prefix.Contains(addr) {
				return true
			}
		}
		return false
	}
	_, found := telegramIPv6Exact[addr]
	return found
}

func IsRestrictedMode(mode ConnectionMode) bool {
	return mode == ModeWorkerOnly || mode == ModeCFOnly
}

func BlocksDirectPassthrough(mode ConnectionMode, dst string, mapped bool) bool {
	return !mapped && IsRestrictedMode(mode) && IsTelegramLikeIP(dst)
}

func parseAddr(raw string) (netip.Addr, bool) {
	addr, err := netip.ParseAddr(strings.TrimSpace(raw))
	if err != nil {
		return netip.Addr{}, false
	}
	return addr.Unmap(), true
}

func mustParsePrefixes(raw ...string) []netip.Prefix {
	out := make([]netip.Prefix, 0, len(raw))
	for _, item := range raw {
		out = append(out, netip.MustParsePrefix(item))
	}
	return out
}

func mustParseAddrSet(raw ...string) map[netip.Addr]struct{} {
	out := make(map[netip.Addr]struct{}, len(raw))
	for _, item := range raw {
		out[netip.MustParseAddr(item)] = struct{}{}
	}
	return out
}
