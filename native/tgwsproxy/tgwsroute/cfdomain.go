package tgwsroute

import (
	"net"
	"net/url"
	"sort"
	"strings"
)

type CFDomainSource string

const (
	CFDomainSourceManual         CFDomainSource = "manual"
	CFDomainSourceBuiltIn        CFDomainSource = "built_in"
	CFDomainSourceCachedUpstream CFDomainSource = "cached_upstream"
)

var flowsealEncodedCFDomains = []string{
	"virkgj.com",
	"vmmzovy.com",
	"mkuosckvso.com",
	"zaewayzmplad.com",
	"twdmbzcm.com",
	"awzwsldi.com",
	"clngqrflngqin.com",
	"tjacxbqtj.com",
	"bxaxtxmrw.com",
	"dmohrsgmohcrwb.com",
}

var flowsealEncodedCFDomainSet = func() map[string]struct{} {
	out := make(map[string]struct{}, len(flowsealEncodedCFDomains))
	for _, domain := range flowsealEncodedCFDomains {
		out[domain] = struct{}{}
	}
	return out
}()

func FlowsealEncodedCFDomains() []string {
	return append([]string(nil), flowsealEncodedCFDomains...)
}

func BuiltInCFDomains() []string {
	out := make([]string, 0, len(flowsealEncodedCFDomains))
	seen := make(map[string]struct{}, len(flowsealEncodedCFDomains))
	for _, encoded := range flowsealEncodedCFDomains {
		decoded, ok := NormalizeCFDomain(DecodeFlowsealCFDomain(encoded))
		if !ok {
			continue
		}
		if _, exists := seen[decoded]; exists {
			continue
		}
		seen[decoded] = struct{}{}
		out = append(out, decoded)
	}
	return out
}

func DecodeFlowsealCFDomain(s string) string {
	if !strings.HasSuffix(strings.ToLower(strings.TrimSpace(s)), ".com") {
		return strings.TrimSpace(s)
	}

	prefix := strings.TrimSpace(s)[:len(strings.TrimSpace(s))-4]
	shift := 0
	for _, c := range prefix {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
			shift++
		}
	}

	var b strings.Builder
	for _, c := range prefix {
		switch {
		case c >= 'a' && c <= 'z':
			off := int(c-'a') - shift
			for off < 0 {
				off += 26
			}
			b.WriteByte(byte('a' + off%26))
		case c >= 'A' && c <= 'Z':
			off := int(c-'A') - shift
			for off < 0 {
				off += 26
			}
			b.WriteByte(byte('A' + off%26))
		default:
			b.WriteRune(c)
		}
	}
	b.WriteString(".co.uk")
	return b.String()
}

func NormalizeCFDomain(raw string) (string, bool) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", false
	}

	lower := strings.ToLower(s)
	switch {
	case strings.HasPrefix(lower, "http://"), strings.HasPrefix(lower, "https://"):
		parsed, err := url.Parse(s)
		if err != nil || parsed.User != nil || parsed.Port() != "" {
			return "", false
		}
		host := strings.ToLower(strings.TrimSpace(parsed.Hostname()))
		if !validHostname(host) {
			return "", false
		}
		return host, true
	case strings.Contains(lower, "://"):
		return "", false
	}

	if strings.ContainsAny(s, " /?#:") {
		trimmed := strings.TrimRight(s, "/")
		if trimmed == "" || strings.ContainsAny(trimmed, " /?#:") {
			return "", false
		}
		s = trimmed
	}

	host := strings.ToLower(s)
	if !validHostname(host) {
		return "", false
	}
	return host, true
}

func NormalizeCFDomains(raw []string) []string {
	out := make([]string, 0, len(raw))
	seen := make(map[string]struct{}, len(raw))
	for _, item := range raw {
		domain, ok := NormalizeCFDomain(item)
		if !ok {
			continue
		}
		if _, exists := seen[domain]; exists {
			continue
		}
		seen[domain] = struct{}{}
		out = append(out, domain)
	}
	return out
}

func NormalizeCachedUpstreamCFDomains(raw []string) []string {
	normalized := NormalizeCFDomains(raw)
	if !looksLikeFlowsealEncodedCFList(normalized) {
		return normalized
	}

	out := make([]string, 0, len(normalized))
	seen := make(map[string]struct{}, len(normalized))
	for _, domain := range normalized {
		decoded, ok := NormalizeCFDomain(DecodeFlowsealCFDomain(domain))
		if !ok {
			continue
		}
		if _, exists := seen[decoded]; exists {
			continue
		}
		seen[decoded] = struct{}{}
		out = append(out, decoded)
	}
	return out
}

func looksLikeFlowsealEncodedCFList(domains []string) bool {
	for _, domain := range domains {
		if _, ok := flowsealEncodedCFDomainSet[domain]; ok {
			return true
		}
	}
	return false
}

func IsValidCFDomain(raw string) bool {
	_, ok := NormalizeCFDomain(raw)
	return ok
}

func validHostname(host string) bool {
	if len(host) == 0 || len(host) > 253 || strings.Contains(host, "..") || !strings.Contains(host, ".") {
		return false
	}
	if host == "localhost" || net.ParseIP(host) != nil {
		return false
	}

	labels := strings.Split(host, ".")
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 {
			return false
		}
		if label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
				return false
			}
		}
	}
	return true
}

func sortCFDomains(domains []string) []string {
	out := append([]string(nil), domains...)
	sort.Strings(out)
	return out
}
