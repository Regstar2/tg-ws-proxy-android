package tgwsroute

import (
	"sort"
	"sync"
	"time"
)

type CFFailureKind string

const (
	CFFailureUnknown   CFFailureKind = "unknown"
	CFFailureDNS       CFFailureKind = "dns"
	CFFailureRateLimit CFFailureKind = "http_429"
	CFFailureForbidden CFFailureKind = "http_403"
	CFFailureServer    CFFailureKind = "http_5xx"
	CFFailureTimeout   CFFailureKind = "timeout"
	CFFailureTLS       CFFailureKind = "tls"
	CFFailureWebSocket CFFailureKind = "websocket"
)

const cachedUpstreamDNSCooldownSeconds = 6 * 60 * 60

type CFDomainHealth struct {
	Domain              string
	Source              CFDomainSource
	SuccessCount        int
	FailureCount        int
	ConsecutiveFailures int
	LastSuccessAt       float64
	LastFailureAt       float64
	LastFailureReason   CFFailureKind
	CooldownUntil       float64
	LastLatencyMs       int64
}

type CFDomainCandidate struct {
	Domain string
	Source CFDomainSource
	Score  int
	Health CFDomainHealth
}

type CFDomainSelection struct {
	Candidates      []CFDomainCandidate
	SkippedCooldown []CFDomainHealth
}

type CFDomainPool struct {
	mu             sync.Mutex
	manual         []string
	cachedUpstream []string
	builtin        []string
	health         map[string]*CFDomainHealth
	dcPreferred    map[int]string
	cachedCursor   int
	builtinCursor  int
	now            func() float64
}

func NewCFDomainPool(now func() float64) *CFDomainPool {
	if now == nil {
		now = func() float64 {
			return float64(time.Now().UnixNano()) / 1e9
		}
	}
	return &CFDomainPool{
		health:      make(map[string]*CFDomainHealth),
		dcPreferred: make(map[int]string),
		now:         now,
	}
}

func (p *CFDomainPool) SetBuiltinDomains(domains []string) {
	normalized := NormalizeCFDomains(domains)
	p.mu.Lock()
	p.builtin = normalized
	for _, domain := range normalized {
		p.ensureHealthLocked(domain, CFDomainSourceBuiltIn)
	}
	p.reclassifyRemovedDomainsLocked()
	p.mu.Unlock()
}

func (p *CFDomainPool) SetCachedUpstreamDomains(domains []string) {
	normalized := NormalizeCachedUpstreamCFDomains(domains)
	p.mu.Lock()
	p.cachedUpstream = normalized
	for _, domain := range normalized {
		p.ensureHealthLocked(domain, CFDomainSourceCachedUpstream)
	}
	p.reclassifyRemovedDomainsLocked()
	p.mu.Unlock()
}

func (p *CFDomainPool) SetManualDomain(domain string) bool {
	return len(p.SetManualDomains([]string{domain})) > 0
}

func (p *CFDomainPool) SetManualDomains(domains []string) []string {
	normalized := NormalizeCFDomains(domains)
	p.mu.Lock()
	p.manual = normalized
	for _, domain := range normalized {
		p.ensureHealthLocked(domain, CFDomainSourceManual)
	}
	p.reclassifyRemovedDomainsLocked()
	p.mu.Unlock()
	return normalized
}

func (p *CFDomainPool) ClearManualDomain() {
	p.SetManualDomains(nil)
}

func (p *CFDomainPool) MarkFailure(domain string, kind CFFailureKind, latencyMs int64) CFDomainHealth {
	normalized, ok := NormalizeCFDomain(domain)
	if !ok {
		return CFDomainHealth{}
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	source := p.sourceForLocked(normalized)
	health := p.ensureHealthLocked(normalized, source)
	now := p.now()
	health.FailureCount++
	health.ConsecutiveFailures++
	health.LastFailureAt = now
	health.LastFailureReason = kind
	if latencyMs > 0 {
		health.LastLatencyMs = latencyMs
	}
	health.CooldownUntil = now + cooldownSeconds(kind, health.ConsecutiveFailures)
	if source == CFDomainSourceCachedUpstream && IsCFDNSFailure(kind) {
		health.CooldownUntil = now + cachedUpstreamDNSCooldownSeconds
	}
	return *health
}

func (p *CFDomainPool) MarkSuccess(dc int, domain string, latencyMs int64) CFDomainHealth {
	normalized, ok := NormalizeCFDomain(domain)
	if !ok {
		return CFDomainHealth{}
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	health := p.ensureHealthLocked(normalized, p.sourceForLocked(normalized))
	health.SuccessCount++
	health.ConsecutiveFailures = 0
	health.LastSuccessAt = p.now()
	health.CooldownUntil = 0
	if latencyMs > 0 {
		health.LastLatencyMs = latencyMs
	}
	p.dcPreferred[dc] = normalized
	return *health
}

func (p *CFDomainPool) ResetCooldowns() {
	p.mu.Lock()
	for _, health := range p.health {
		health.CooldownUntil = 0
		health.ConsecutiveFailures = 0
	}
	p.mu.Unlock()
}

func (p *CFDomainPool) IsCoolingDown(domain string) bool {
	normalized, ok := NormalizeCFDomain(domain)
	if !ok {
		return false
	}

	p.mu.Lock()
	defer p.mu.Unlock()
	health, exists := p.health[normalized]
	return exists && p.now() < health.CooldownUntil
}

func (p *CFDomainPool) SelectionForDC(dc int) CFDomainSelection {
	p.mu.Lock()
	defer p.mu.Unlock()

	now := p.now()
	selection := CFDomainSelection{}
	seen := make(map[string]struct{})

	addCandidate := func(domain string, source CFDomainSource) {
		if domain == "" {
			return
		}
		if _, exists := seen[domain]; exists {
			return
		}
		seen[domain] = struct{}{}

		health := p.ensureHealthLocked(domain, source)
		if now < health.CooldownUntil {
			selection.SkippedCooldown = append(selection.SkippedCooldown, *health)
			return
		}
		selection.Candidates = append(selection.Candidates, CFDomainCandidate{
			Domain: domain,
			Source: source,
			Score:  scoreHealth(*health),
			Health: *health,
		})
	}

	for _, domain := range p.manual {
		addCandidate(domain, CFDomainSourceManual)
	}

	cached := p.rotatedCachedUpstreamLocked()
	builtins := p.rotatedBuiltinsLocked()
	if preferred, ok := p.dcPreferred[dc]; ok {
		switch p.sourceForLocked(preferred) {
		case CFDomainSourceCachedUpstream:
			cached = append([]string{preferred}, cached...)
		case CFDomainSourceBuiltIn:
			builtins = append([]string{preferred}, builtins...)
		}
	}
	for _, domain := range cached {
		addCandidate(domain, CFDomainSourceCachedUpstream)
	}
	for _, domain := range builtins {
		addCandidate(domain, CFDomainSourceBuiltIn)
	}

	sort.SliceStable(selection.Candidates, func(i, j int) bool {
		left := selection.Candidates[i]
		right := selection.Candidates[j]
		if left.Source != right.Source {
			return sourcePriority(left.Source) < sourcePriority(right.Source)
		}
		return left.Score > right.Score
	})

	return selection
}

func (p *CFDomainPool) Snapshot() []CFDomainHealth {
	p.mu.Lock()
	defer p.mu.Unlock()

	out := make([]CFDomainHealth, 0, len(p.health))
	for _, health := range p.health {
		out = append(out, *health)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Source != out[j].Source {
			return sourcePriority(out[i].Source) < sourcePriority(out[j].Source)
		}
		return out[i].Domain < out[j].Domain
	})
	return out
}

func (p *CFDomainPool) ensureHealthLocked(domain string, source CFDomainSource) *CFDomainHealth {
	if health, ok := p.health[domain]; ok {
		if sourcePriority(source) < sourcePriority(health.Source) {
			health.Source = source
		}
		return health
	}
	health := &CFDomainHealth{
		Domain: domain,
		Source: source,
	}
	p.health[domain] = health
	return health
}

func (p *CFDomainPool) sourceForLocked(domain string) CFDomainSource {
	if containsDomain(p.manual, domain) {
		return CFDomainSourceManual
	}
	if containsDomain(p.cachedUpstream, domain) {
		return CFDomainSourceCachedUpstream
	}
	return CFDomainSourceBuiltIn
}

func (p *CFDomainPool) rotatedCachedUpstreamLocked() []string {
	if len(p.cachedUpstream) == 0 {
		return nil
	}
	start := p.cachedCursor % len(p.cachedUpstream)
	p.cachedCursor = (p.cachedCursor + 1) % len(p.cachedUpstream)
	out := append([]string(nil), p.cachedUpstream[start:]...)
	out = append(out, p.cachedUpstream[:start]...)
	return out
}

func (p *CFDomainPool) rotatedBuiltinsLocked() []string {
	if len(p.builtin) == 0 {
		return nil
	}
	start := p.builtinCursor % len(p.builtin)
	p.builtinCursor = (p.builtinCursor + 1) % len(p.builtin)
	out := append([]string(nil), p.builtin[start:]...)
	out = append(out, p.builtin[:start]...)
	return out
}

func (p *CFDomainPool) reclassifyRemovedDomainsLocked() {
	for domain, health := range p.health {
		switch {
		case containsDomain(p.manual, domain):
			health.Source = CFDomainSourceManual
		case containsDomain(p.cachedUpstream, domain):
			health.Source = CFDomainSourceCachedUpstream
		case containsDomain(p.builtin, domain):
			health.Source = CFDomainSourceBuiltIn
		default:
			delete(p.health, domain)
		}
	}
}

func cooldownSeconds(kind CFFailureKind, consecutive int) float64 {
	progressive := 30.0
	switch {
	case consecutive >= 3:
		progressive = 300
	case consecutive == 2:
		progressive = 120
	}

	switch kind {
	case CFFailureDNS:
		return maxFloat(progressive, 120)
	case CFFailureRateLimit:
		return maxFloat(progressive, 300)
	case CFFailureForbidden:
		return maxFloat(progressive, 600)
	case CFFailureServer:
		return maxFloat(progressive, 120)
	default:
		return progressive
	}
}

func IsCFDNSFailure(kind CFFailureKind) bool {
	return kind == CFFailureDNS
}

func scoreHealth(health CFDomainHealth) int {
	score := 100
	score += health.SuccessCount * 6
	score -= health.FailureCount * 4
	score -= health.ConsecutiveFailures * 15
	if health.LastLatencyMs > 0 {
		score -= int(health.LastLatencyMs / 250)
	}
	if health.Source == CFDomainSourceManual {
		score += 1000
	}
	return score
}

func sourcePriority(source CFDomainSource) int {
	switch source {
	case CFDomainSourceManual:
		return 0
	case CFDomainSourceCachedUpstream:
		return 1
	default:
		return 2
	}
}

func containsDomain(domains []string, target string) bool {
	for _, domain := range domains {
		if domain == target {
			return true
		}
	}
	return false
}

func maxFloat(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}
