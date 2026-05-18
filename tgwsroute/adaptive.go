package tgwsroute

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

const (
	LastGoodRouteTTL       = 12 * time.Hour
	AdaptiveCooldownAfter  = 3
	AdaptiveCooldownPeriod = 5 * time.Minute
	MaxNetworkProfiles     = 20
)

type NetworkProfileType string

const (
	NetworkWiFi     NetworkProfileType = "wifi"
	NetworkMobile   NetworkProfileType = "mobile"
	NetworkUnknown  NetworkProfileType = "unknown"
)

type NetworkProfile struct {
	ID        string
	Type      NetworkProfileType
	Label     string
	CreatedAt int64
	LastSeen  int64
}

type RouteStatKey struct {
	ProfileID string
	Route     RouteKind
	DC        int
	Media     bool
}

type RouteStat struct {
	Key                 RouteStatKey
	SuccessCount        int
	FailureCount        int
	LastSuccessAt       int64
	LastFailureAt       int64
	LastFailureReason   string
	LastLatencyMs       int64
	AverageLatencyMs    int64
	CooldownUntil       int64
	ConsecutiveFailures int
	LastUsedAt          int64
}

type LastGoodRoute struct {
	ProfileID string
	DC        int
	Media     bool
	Route     RouteKind
	LastGoodAt int64
}

type AdaptiveSelection struct {
	Routes  []RouteKind
	Reasons []string
	Scores  map[RouteKind]float64
}

type AdaptiveStore struct {
	Profile   NetworkProfile
	Stats     map[string]*RouteStat // keyed by statKeyString
	LastGoods map[string]*LastGoodRoute
	nowFn     func() int64
}

func NewAdaptiveStore(nowFn func() int64) *AdaptiveStore {
	if nowFn == nil {
		nowFn = func() int64 { return time.Now().UnixMilli() }
	}
	return &AdaptiveStore{
		Profile: NetworkProfile{
			ID:   "unknown",
			Type: NetworkUnknown,
		},
		Stats:     make(map[string]*RouteStat),
		LastGoods: make(map[string]*LastGoodRoute),
		nowFn:     nowFn,
	}
}

func (s *AdaptiveStore) SetNowFn(nowFn func() int64) {
	if nowFn != nil {
		s.nowFn = nowFn
	}
}

func (s *AdaptiveStore) Now() int64 {
	return s.nowFn()
}

func (s *AdaptiveStore) SetProfile(p NetworkProfile) {
	if p.ID == "" {
		p.ID = "unknown"
	}
	if p.Type == "" {
		p.Type = NetworkUnknown
	}
	now := s.Now()
	if p.CreatedAt == 0 {
		p.CreatedAt = now
	}
	p.LastSeen = now
	s.Profile = p
}

func statKeyString(k RouteStatKey) string {
	media := 0
	if k.Media {
		media = 1
	}
	return fmt.Sprintf("%s|%s|%d|%d", k.ProfileID, k.Route, k.DC, media)
}

func lastGoodKey(profileID string, dc int, media bool) string {
	m := 0
	if media {
		m = 1
	}
	return fmt.Sprintf("%s|%d|%d", profileID, dc, m)
}

func (s *AdaptiveStore) statFor(route RouteKind, dc int, media bool) *RouteStat {
	key := RouteStatKey{
		ProfileID: s.Profile.ID,
		Route:     route,
		DC:        dc,
		Media:     media,
	}
	sk := statKeyString(key)
	if st, ok := s.Stats[sk]; ok {
		return st
	}
	st := &RouteStat{Key: key}
	s.Stats[sk] = st
	return st
}

func (s *AdaptiveStore) IsInCooldown(route RouteKind, dc int, media bool, now int64) bool {
	st := s.statFor(route, dc, media)
	return st.CooldownUntil > now
}

func (s *AdaptiveStore) RecordSuccess(route RouteKind, dc int, media bool, latencyMs int64) {
	now := s.Now()
	st := s.statFor(route, dc, media)
	st.SuccessCount++
	st.ConsecutiveFailures = 0
	st.LastSuccessAt = now
	st.LastUsedAt = now
	st.CooldownUntil = 0
	if latencyMs > 0 {
		st.LastLatencyMs = latencyMs
		if st.AverageLatencyMs == 0 {
			st.AverageLatencyMs = latencyMs
		} else {
			st.AverageLatencyMs = (st.AverageLatencyMs*3 + latencyMs) / 4
		}
	}
	lgk := lastGoodKey(s.Profile.ID, dc, media)
	s.LastGoods[lgk] = &LastGoodRoute{
		ProfileID:  s.Profile.ID,
		DC:         dc,
		Media:      media,
		Route:      route,
		LastGoodAt: now,
	}
}

func (s *AdaptiveStore) RecordFailure(route RouteKind, dc int, media bool, reason string, latencyMs int64) {
	now := s.Now()
	st := s.statFor(route, dc, media)
	st.FailureCount++
	st.ConsecutiveFailures++
	st.LastFailureAt = now
	st.LastUsedAt = now
	st.LastFailureReason = reason
	if latencyMs > 0 {
		st.LastLatencyMs = latencyMs
	}
	if st.ConsecutiveFailures >= AdaptiveCooldownAfter {
		st.CooldownUntil = now + AdaptiveCooldownPeriod.Milliseconds()
	}
}

func (s *AdaptiveStore) LastGood(dc int, media bool, now int64) (*LastGoodRoute, bool) {
	lgk := lastGoodKey(s.Profile.ID, dc, media)
	lg, ok := s.LastGoods[lgk]
	if !ok || lg == nil {
		return nil, false
	}
	if now-lg.LastGoodAt > LastGoodRouteTTL.Milliseconds() {
		return nil, false
	}
	return lg, true
}

func routeAvailable(route RouteKind, settings RouteSettings) bool {
	workerOK := settings.Worker.Enabled && settings.Worker.Domain != ""
	cfOK := settings.CF.Enabled
	switch route {
	case RouteDirectWS:
		return true
	case RouteCFWorkerWS:
		return workerOK
	case RouteCFProxyWS:
		return cfOK
	case RouteTCPFallback:
		return true
	default:
		return false
	}
}

func scoreRoute(
	route RouteKind,
	st *RouteStat,
	netType NetworkProfileType,
	lastGood *LastGoodRoute,
	now int64,
) (float64, []string) {
	if st.CooldownUntil > now {
		return -1, []string{"cooldown"}
	}
	var reasons []string
	score := 100.0

	switch netType {
	case NetworkWiFi:
		if route == RouteDirectWS {
			score += 30
			reasons = append(reasons, "wifi_direct_bonus")
		}
	case NetworkMobile:
		switch route {
		case RouteCFWorkerWS:
			score += 25
			reasons = append(reasons, "mobile_worker_bonus")
		case RouteCFProxyWS:
			score += 15
			reasons = append(reasons, "mobile_cf_bonus")
		}
	}

	score += float64(st.SuccessCount) * 5
	score -= float64(st.FailureCount) * 8
	score -= float64(st.ConsecutiveFailures) * 15
	if st.AverageLatencyMs > 0 {
		score -= float64(st.AverageLatencyMs) / 25
	}
	if lastGood != nil && lastGood.Route == route {
		score += 40
		reasons = append(reasons, "last_good_route")
	}
	return score, reasons
}

// AdaptiveOrderRoutes reorders fallback routes for Auto / DirectWithFallback.
// skipDirect means direct WS is handled outside the chain (fallback only).
func AdaptiveOrderRoutes(
	base []RouteKind,
	store *AdaptiveStore,
	settings RouteSettings,
	dc int,
	media bool,
	skipDirect bool,
) AdaptiveSelection {
	now := store.Now()
	candidates := make([]RouteKind, 0, len(base))
	for _, r := range base {
		if skipDirect && r == RouteDirectWS {
			continue
		}
		if !routeAvailable(r, settings) {
			continue
		}
		if store.IsInCooldown(r, dc, media, now) {
			continue
		}
		candidates = append(candidates, r)
	}

	lastGood, _ := store.LastGood(dc, media, now)
	var lgRoute *LastGoodRoute
	if lastGood != nil && routeAvailable(lastGood.Route, settings) && !store.IsInCooldown(lastGood.Route, dc, media, now) {
		lgRoute = lastGood
	}

	type scored struct {
		route   RouteKind
		score   float64
		reasons []string
	}
	scoredList := make([]scored, 0, len(candidates))
	scores := make(map[RouteKind]float64)
	for _, r := range candidates {
		st := store.statFor(r, dc, media)
		sc, reasons := scoreRoute(r, st, store.Profile.Type, lgRoute, now)
		if sc < 0 {
			continue
		}
		scoredList = append(scoredList, scored{route: r, score: sc, reasons: reasons})
		scores[r] = sc
	}

	if len(scoredList) == 0 {
		return AdaptiveSelection{Routes: base, Reasons: []string{"no_scored_candidates_use_default"}, Scores: scores}
	}

	sort.Slice(scoredList, func(i, j int) bool {
		if scoredList[i].score == scoredList[j].score {
			return routePriority(scoredList[i].route) < routePriority(scoredList[j].route)
		}
		return scoredList[i].score > scoredList[j].score
	})

	out := make([]RouteKind, 0, len(scoredList))
	var reasons []string
	if len(scoredList) > 0 {
		reasons = append(reasons, fmt.Sprintf("selected=%s", scoredList[0].route))
		reasons = append(reasons, scoredList[0].reasons...)
	}
	for _, item := range scoredList {
		out = append(out, item.route)
	}
	return AdaptiveSelection{Routes: out, Reasons: reasons, Scores: scores}
}

func routePriority(r RouteKind) int {
	switch r {
	case RouteDirectWS:
		return 0
	case RouteCFWorkerWS:
		return 1
	case RouteCFProxyWS:
		return 2
	case RouteTCPFallback:
		return 3
	default:
		return 9
	}
}

func FormatAdaptiveSelectionReason(sel AdaptiveSelection, dc int, media bool, netLabel string) string {
	parts := []string{
		fmt.Sprintf("network=%s dc=%d media=%t", netLabel, dc, media),
	}
	parts = append(parts, sel.Reasons...)
	return strings.Join(parts, "; ")
}

func ParseNetworkProfileType(raw string) NetworkProfileType {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "wifi":
		return NetworkWiFi
	case "mobile":
		return NetworkMobile
	default:
		return NetworkUnknown
	}
}

func (s *AdaptiveStore) ResetAll() {
	s.Stats = make(map[string]*RouteStat)
	s.LastGoods = make(map[string]*LastGoodRoute)
}

func (s *AdaptiveStore) ResetCurrentProfile() {
	pid := s.Profile.ID
	for k, st := range s.Stats {
		if st.Key.ProfileID == pid {
			delete(s.Stats, k)
		}
	}
	for k, lg := range s.LastGoods {
		if lg.ProfileID == pid {
			delete(s.LastGoods, k)
		}
	}
}

func (s *AdaptiveStore) Snapshot() ([]RouteStat, []LastGoodRoute) {
	stats := make([]RouteStat, 0, len(s.Stats))
	for _, st := range s.Stats {
		if st != nil {
			stats = append(stats, *st)
		}
	}
	lastGoods := make([]LastGoodRoute, 0, len(s.LastGoods))
	for _, lg := range s.LastGoods {
		if lg != nil {
			lastGoods = append(lastGoods, *lg)
		}
	}
	return stats, lastGoods
}
