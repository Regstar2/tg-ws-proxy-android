package tgwsroute

import "strings"

type RouteFailureKind string

const (
	FailureWS302              RouteFailureKind = "WS_302"
	FailureWS429              RouteFailureKind = "WS_429"
	FailureWS403              RouteFailureKind = "WS_403"
	FailureWS5XX              RouteFailureKind = "WS_5XX"
	FailureWSTimeout          RouteFailureKind = "WS_TIMEOUT"
	FailureTCPTimeout         RouteFailureKind = "TCP_TIMEOUT"
	FailureDNSFailure         RouteFailureKind = "DNS_FAILURE"
	FailureTLSFailure         RouteFailureKind = "TLS_FAILURE"
	FailureWorkerEmptyDomain  RouteFailureKind = "WORKER_EMPTY_DOMAIN"
	FailureWorkerWSFailure    RouteFailureKind = "WORKER_WS_FAILURE"
	FailureCFPoolExhausted    RouteFailureKind = "CF_POOL_EXHAUSTED"
	FailureCFDomainCooldown   RouteFailureKind = "CF_DOMAIN_COOLDOWN"
	FailureTelegramDCUnknown  RouteFailureKind = "TELEGRAM_DC_UNKNOWN"
	FailureTelegramIPv6Blocked RouteFailureKind = "TELEGRAM_IPV6_BLOCKED"
	FailureClientEOF          RouteFailureKind = "CLIENT_EOF"
	FailureContextCancelled   RouteFailureKind = "CONTEXT_CANCELLED"
	FailureUnknown            RouteFailureKind = "UNKNOWN"
)

func ClassifyFailureReason(raw string) RouteFailureKind {
	s := strings.ToLower(strings.TrimSpace(raw))
	switch {
	case s == "":
		return FailureUnknown
	case strings.Contains(s, "client_eof"), strings.Contains(s, "eof"):
		return FailureClientEOF
	case strings.Contains(s, "context_cancel"), strings.Contains(s, "canceled"), strings.Contains(s, "cancelled"):
		return FailureContextCancelled
	case strings.Contains(s, "ws_302"), strings.Contains(s, "302"):
		return FailureWS302
	case strings.Contains(s, "429"), strings.Contains(s, "rate_limit"):
		return FailureWS429
	case strings.Contains(s, "403"):
		return FailureWS403
	case strings.Contains(s, "5xx"), strings.Contains(s, "server_error"):
		return FailureWS5XX
	case strings.Contains(s, "tcp_timeout"), strings.Contains(s, "tcp_failed"):
		return FailureTCPTimeout
	case strings.Contains(s, "dns"):
		return FailureDNSFailure
	case strings.Contains(s, "tls"):
		return FailureTLSFailure
	case strings.Contains(s, "worker_disabled"), strings.Contains(s, "worker_empty"):
		return FailureWorkerEmptyDomain
	case strings.Contains(s, "worker"):
		return FailureWorkerWSFailure
	case strings.Contains(s, "cfproxy_unavailable"), strings.Contains(s, "cf_pool"):
		return FailureCFPoolExhausted
	case strings.Contains(s, "cooldown"), strings.Contains(s, "429"):
		return FailureCFDomainCooldown
	case strings.Contains(s, "timeout"), strings.Contains(s, "ws_connect"):
		return FailureWSTimeout
	default:
		return FailureUnknown
	}
}

func IsNeutralFailure(kind RouteFailureKind) bool {
	switch kind {
	case FailureClientEOF, FailureContextCancelled:
		return true
	default:
		return false
	}
}

func FailureAppliesToRoute(kind RouteFailureKind, route RouteKind) bool {
	switch kind {
	case FailureWS302:
		return route == RouteDirectWS
	case FailureWS429, FailureWS403, FailureWS5XX, FailureCFPoolExhausted, FailureCFDomainCooldown:
		return route == RouteCFProxyWS
	case FailureWorkerEmptyDomain, FailureWorkerWSFailure:
		return route == RouteCFWorkerWS
	case FailureTCPTimeout:
		return route == RouteTCPFallback
	case FailureWSTimeout, FailureDNSFailure, FailureTLSFailure:
		return true
	default:
		return !IsNeutralFailure(kind)
	}
}

func ExtraConsecutivePenalty(kind RouteFailureKind, strategy AutoStrategy) int {
	if strategy != StrategyStrictFastFailover {
		return 0
	}
	switch kind {
	case FailureWS302, FailureWSTimeout, FailureTCPTimeout:
		return 1
	default:
		return 0
	}
}
