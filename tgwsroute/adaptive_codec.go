package tgwsroute

import (
	"fmt"
	"strconv"
	"strings"
)

// EncodeRouteStats serializes stats and last-good routes for runtime tokens.
// Format: stat entries separated by ';', fields by ':'
// route:dc:media:success:failure:lastSucc:lastFail:reason:lat:avgLat:cooldown:consec:lastUsed:profileId
// last-good entries prefixed with "lg:"
func EncodeRouteStats(stats []RouteStat, lastGoods []LastGoodRoute) string {
	var parts []string
	for _, st := range stats {
		media := 0
		if st.Key.Media {
			media = 1
		}
		parts = append(parts, fmt.Sprintf("%s:%d:%d:%d:%d:%d:%d:%s:%d:%d:%d:%d:%d:%s",
			st.Key.Route,
			st.Key.DC,
			media,
			st.SuccessCount,
			st.FailureCount,
			st.LastSuccessAt,
			st.LastFailureAt,
			escapeField(st.LastFailureReason),
			st.LastLatencyMs,
			st.AverageLatencyMs,
			st.CooldownUntil,
			st.ConsecutiveFailures,
			st.LastUsedAt,
			escapeField(st.Key.ProfileID),
		))
	}
	for _, lg := range lastGoods {
		media := 0
		if lg.Media {
			media = 1
		}
		parts = append(parts, fmt.Sprintf("lg:%s:%d:%d:%s:%d",
			escapeField(lg.ProfileID),
			lg.DC,
			media,
			lg.Route,
			lg.LastGoodAt,
		))
	}
	return strings.Join(parts, ";")
}

func DecodeRouteStats(raw string) ([]RouteStat, []LastGoodRoute, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil, nil
	}
	var stats []RouteStat
	var lastGoods []LastGoodRoute
	for _, entry := range strings.Split(raw, ";") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if strings.HasPrefix(entry, "lg:") {
			lg, err := decodeLastGood(entry[3:])
			if err != nil {
				continue
			}
			lastGoods = append(lastGoods, lg)
			continue
		}
		st, err := decodeStat(entry)
		if err != nil {
			continue
		}
		stats = append(stats, st)
	}
	return stats, lastGoods, nil
}

func decodeStat(entry string) (RouteStat, error) {
	fields := strings.Split(entry, ":")
	if len(fields) < 14 {
		return RouteStat{}, fmt.Errorf("short stat entry")
	}
	media := fields[2] == "1"
	dc, _ := strconv.Atoi(fields[1])
	succ, _ := strconv.Atoi(fields[3])
	fail, _ := strconv.Atoi(fields[4])
	lastSucc, _ := strconv.ParseInt(fields[5], 10, 64)
	lastFail, _ := strconv.ParseInt(fields[6], 10, 64)
	lat, _ := strconv.ParseInt(fields[8], 10, 64)
	avgLat, _ := strconv.ParseInt(fields[9], 10, 64)
	cooldown, _ := strconv.ParseInt(fields[10], 10, 64)
	consec, _ := strconv.Atoi(fields[11])
	lastUsed, _ := strconv.ParseInt(fields[12], 10, 64)
	return RouteStat{
		Key: RouteStatKey{
			ProfileID: unescapeField(fields[13]),
			Route:     RouteKind(fields[0]),
			DC:        dc,
			Media:     media,
		},
		SuccessCount:        succ,
		FailureCount:        fail,
		LastSuccessAt:       lastSucc,
		LastFailureAt:       lastFail,
		LastFailureReason:   unescapeField(fields[7]),
		LastLatencyMs:       lat,
		AverageLatencyMs:    avgLat,
		CooldownUntil:       cooldown,
		ConsecutiveFailures: consec,
		LastUsedAt:          lastUsed,
	}, nil
}

func decodeLastGood(entry string) (LastGoodRoute, error) {
	fields := strings.Split(entry, ":")
	if len(fields) < 5 {
		return LastGoodRoute{}, fmt.Errorf("short last-good entry")
	}
	media := fields[2] == "1"
	dc, _ := strconv.Atoi(fields[1])
	lastGoodAt, _ := strconv.ParseInt(fields[4], 10, 64)
	return LastGoodRoute{
		ProfileID:  unescapeField(fields[0]),
		DC:         dc,
		Media:      media,
		Route:      RouteKind(fields[3]),
		LastGoodAt: lastGoodAt,
	}, nil
}

func (s *AdaptiveStore) LoadEncodedStats(blob string) {
	stats, lastGoods, _ := DecodeRouteStats(blob)
	for i := range stats {
		st := stats[i]
		sk := statKeyString(st.Key)
		s.Stats[sk] = &stats[i]
	}
	for i := range lastGoods {
		lg := lastGoods[i]
		s.LastGoods[lastGoodKey(lg.ProfileID, lg.DC, lg.Media)] = &lastGoods[i]
	}
}

func escapeField(s string) string {
	s = strings.ReplaceAll(s, ":", "_")
	s = strings.ReplaceAll(s, ";", "_")
	return s
}

func unescapeField(s string) string {
	return s
}

func MergeRouteStats(existing []RouteStat, incoming []RouteStat, maxProfiles int) []RouteStat {
	byKey := make(map[string]RouteStat)
	for _, st := range existing {
		byKey[statKeyString(st.Key)] = st
	}
	for _, st := range incoming {
		sk := statKeyString(st.Key)
		if prev, ok := byKey[sk]; ok {
			prev.SuccessCount += st.SuccessCount
			prev.FailureCount += st.FailureCount
			if st.LastSuccessAt > prev.LastSuccessAt {
				prev.LastSuccessAt = st.LastSuccessAt
				prev.LastLatencyMs = st.LastLatencyMs
				prev.AverageLatencyMs = st.AverageLatencyMs
			}
			if st.LastFailureAt > prev.LastFailureAt {
				prev.LastFailureAt = st.LastFailureAt
				prev.LastFailureReason = st.LastFailureReason
			}
			if st.CooldownUntil > prev.CooldownUntil {
				prev.CooldownUntil = st.CooldownUntil
			}
			if st.ConsecutiveFailures > prev.ConsecutiveFailures {
				prev.ConsecutiveFailures = st.ConsecutiveFailures
			}
			if st.LastUsedAt > prev.LastUsedAt {
				prev.LastUsedAt = st.LastUsedAt
			}
			byKey[sk] = prev
		} else {
			byKey[sk] = st
		}
	}
	out := make([]RouteStat, 0, len(byKey))
	for _, st := range byKey {
		out = append(out, st)
	}
	if len(out) <= maxProfiles*32 {
		return out
	}
	return out[:maxProfiles*32]
}
