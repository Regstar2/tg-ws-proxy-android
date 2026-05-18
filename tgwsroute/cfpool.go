package tgwsroute

import (
	"sort"
	"sync"
	"time"
)

type CFFailureKind string

const (
	CFFailureUnknown   CFFailureKind = "unknown"
	CFFailureRateLimit CFFailureKind = "http_429"
	CFFailureForbidden CFFailureKind = "http_403"
	CFFailureServer    CFFailureKind = "http_5xx"
	CFFailureTimeout   CFFailureKind = "timeout"
	CFFailureTLS       CFFailureKind = "tls"
	CFFailureWebSocket CFFailureKind = "websocket"
)

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
	mu          sync.Mutex
	manual      string
	builtin     []string
	health      map[string]*CFDomainHealth
	dcPreferred map[int]string
	roundRobin  int
	now         func() float64
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
	p.mu.Unlock()
}

func (p *CFDomainPool) SetManualDomain(domain string) bool {
	normalized, ok := NormalizeCFDomain(domain)
	if !ok {
		return false
	}
	p.mu.Lock()
	oldManual := p.manual
	p.manual = normalized
	p.dropOrReclassifyManualLocked(oldManual)
	p.ensureHealthLocked(normalized, CFDomainSourceManual)
	p.mu.Unlock()
	return true
}

func (p *CFDomainPool) ClearManualDomain() {
	p.mu.Lock()
	oldManual := p.manual
	p.manual = ""
	p.dropOrReclassifyManualLocked(oldManual)
	p.mu.Unlock()
}

func (p *CFDomainPool) MarkFailure(domain string, kind CFFailureKind, latencyMs int64) CFDomainHealth {
	normalized, ok := NormalizeCFDomain(domain)
	if !ok {
		return CFDomainHealth{}
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	health := p.ensureHealthLocked(normalized, p.sourceForLocked(normalized))
	now := p.now()
	health.FailureCount++
	health.ConsecutiveFailures++
	health.LastFailureAt = now
	health.LastFailureReason = kind
	if latencyMs > 0 {
		health.LastLatencyMs = latencyMs
	}
	health.CooldownUntil = now + cooldownSeconds(kind, health.ConsecutiveFailures)
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

	addCandidate(p.manual, CFDomainSourceManual)

	builtins := p.rotatedBuiltinsLocked()
	if preferred, ok := p.dcPreferred[dc]; ok {
		builtins = append([]string{preferred}, builtins...)
	}
	for _, domain := range builtins {
		addCandidate(domain, CFDomainSourceBuiltIn)
	}

	sort.SliceStable(selection.Candidates, func(i, j int) bool {
		left := selection.Candidates[i]
		right := selection.Candidates[j]
		if left.Source != right.Source {
			return left.Source == CFDomainSourceManual
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
			return out[i].Source < out[j].Source
		}
		return out[i].Domain < out[j].Domain
	})
	return out
}

func (p *CFDomainPool) ensureHealthLocked(domain string, source CFDomainSource) *CFDomainHealth {
	if health, ok := p.health[domain]; ok {
		if health.Source != CFDomainSourceManual && source == CFDomainSourceManual {
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
	if domain == p.manual {
		return CFDomainSourceManual
	}
	return CFDomainSourceBuiltIn
}

func (p *CFDomainPool) rotatedBuiltinsLocked() []string {
	if len(p.builtin) == 0 {
		return nil
	}
	start := p.roundRobin % len(p.builtin)
	p.roundRobin = (p.roundRobin + 1) % len(p.builtin)
	out := append([]string(nil), p.builtin[start:]...)
	out = append(out, p.builtin[:start]...)
	return out
}

func (p *CFDomainPool) dropOrReclassifyManualLocked(domain string) {
	if domain == "" || domain == p.manual {
		return
	}
	health, ok := p.health[domain]
	if !ok {
		return
	}
	for _, builtIn := range p.builtin {
		if builtIn == domain {
			health.Source = CFDomainSourceBuiltIn
			return
		}
	}
	delete(p.health, domain)
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

func maxFloat(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}
