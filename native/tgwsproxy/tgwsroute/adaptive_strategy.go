package tgwsroute

import "strings"

// AutoStrategy presets affect scoring for Auto / DirectWithFallback only.
type AutoStrategy string

const (
	StrategyBalanced           AutoStrategy = "balanced"
	StrategyDirectPreferred    AutoStrategy = "direct_preferred"
	StrategyWorkerPreferred    AutoStrategy = "worker_preferred"
	StrategyCFPreferred        AutoStrategy = "cf_preferred"
	StrategyStrictFastFailover AutoStrategy = "strict_fast_failover"
)

func ParseAutoStrategy(raw string) AutoStrategy {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "direct_preferred", "direct":
		return StrategyDirectPreferred
	case "worker_preferred", "worker":
		return StrategyWorkerPreferred
	case "cf_preferred", "cf":
		return StrategyCFPreferred
	case "strict_fast_failover", "fast_failover", "strict":
		return StrategyStrictFastFailover
	default:
		return StrategyBalanced
	}
}

// RouteScoreWeights holds explicit scoring parameters.
type RouteScoreWeights struct {
	BaseScore            float64
	BaseDirect           float64
	BaseWorker           float64
	BaseCFProxy          float64
	BaseTCPFallback      float64
	SuccessBonus         float64
	LastGoodBonus        float64
	FailurePenalty       float64
	ConsecutivePenalty   float64
	LatencyPenaltyFactor float64
	TCPFallbackPenalty   float64
}

type StrategyRuntime struct {
	Weights          RouteScoreWeights
	CooldownAfter    int
	CooldownPeriodMs int64
}

func StrategyRuntimeFor(strategy AutoStrategy, netType NetworkProfileType) StrategyRuntime {
	w := RouteScoreWeights{
		BaseScore:            100,
		SuccessBonus:         5,
		LastGoodBonus:        40,
		FailurePenalty:       8,
		ConsecutivePenalty:   15,
		LatencyPenaltyFactor: 25,
		TCPFallbackPenalty:   20,
	}
	cooldownAfter := AdaptiveCooldownAfter
	cooldownMs := AdaptiveCooldownPeriod.Milliseconds()

	switch strategy {
	case StrategyDirectPreferred:
		w.BaseDirect = w.BaseScore + 25
	case StrategyWorkerPreferred:
		w.BaseWorker = w.BaseScore + 25
	case StrategyCFPreferred:
		w.BaseCFProxy = w.BaseScore + 25
	case StrategyStrictFastFailover:
		w.ConsecutivePenalty = 22
		w.FailurePenalty = 12
		cooldownAfter = 2
		cooldownMs = 2 * 60 * 1000
	default:
		w.BaseDirect = w.BaseScore
		w.BaseWorker = w.BaseScore
		w.BaseCFProxy = w.BaseScore
	}

	switch netType {
	case NetworkWiFi:
		if strategy == StrategyBalanced || strategy == StrategyDirectPreferred {
			w.BaseDirect += 30
		}
	case NetworkMobile:
		if strategy == StrategyBalanced || strategy == StrategyWorkerPreferred {
			w.BaseWorker += 25
		}
		if strategy == StrategyBalanced || strategy == StrategyCFPreferred {
			w.BaseCFProxy += 15
		}
	}

	if w.BaseDirect == 0 {
		w.BaseDirect = w.BaseScore
	}
	if w.BaseWorker == 0 {
		w.BaseWorker = w.BaseScore
	}
	if w.BaseCFProxy == 0 {
		w.BaseCFProxy = w.BaseScore
	}
	w.BaseTCPFallback = w.BaseScore - w.TCPFallbackPenalty

	return StrategyRuntime{
		Weights:          w,
		CooldownAfter:    cooldownAfter,
		CooldownPeriodMs: cooldownMs,
	}
}

func baseScoreForRoute(route RouteKind, w RouteScoreWeights) float64 {
	switch route {
	case RouteDirectWS:
		return w.BaseDirect
	case RouteCFWorkerWS:
		return w.BaseWorker
	case RouteCFProxyWS:
		return w.BaseCFProxy
	case RouteTCPFallback:
		return w.BaseTCPFallback
	default:
		return w.BaseScore
	}
}
