package tgwsroute

import (
	"fmt"
	"net/url"
	"strings"
)

type ConnectionMode string

const (
	ModeAuto               ConnectionMode = "auto"
	ModeDirectWithFallback ConnectionMode = "direct_with_fallback"
	ModeWorkerFirst        ConnectionMode = "worker_first"
	ModeCFFirst            ConnectionMode = "cf_first"
	ModeWorkerOnly         ConnectionMode = "worker_only"
	ModeCFOnly             ConnectionMode = "cf_only"
	ModeDirectOnly         ConnectionMode = "direct_only"
)

type RouteKind string

const (
	RouteDirectWS    RouteKind = "direct_ws"
	RouteCFWorkerWS  RouteKind = "cf_worker_ws"
	RouteCFProxyWS   RouteKind = "cf_proxy_ws"
	RouteTCPFallback RouteKind = "tcp_fallback"
)

type CFProxyFlags struct {
	Enabled  bool
	Priority bool
	Only     bool
}

type WorkerSettings struct {
	Enabled bool
	Domain  string
}

type RouteSettings struct {
	Mode   ConnectionMode
	CF     CFProxyFlags
	Worker WorkerSettings
}

func ParseConnectionMode(raw string) (ConnectionMode, bool) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case string(ModeAuto):
		return ModeAuto, true
	case string(ModeDirectWithFallback), "direct_fallback", "legacy_direct_cf":
		return ModeDirectWithFallback, true
	case string(ModeWorkerFirst):
		return ModeWorkerFirst, true
	case string(ModeCFFirst):
		return ModeCFFirst, true
	case string(ModeWorkerOnly):
		return ModeWorkerOnly, true
	case string(ModeCFOnly):
		return ModeCFOnly, true
	case string(ModeDirectOnly):
		return ModeDirectOnly, true
	default:
		return "", false
	}
}

func LegacyModeFromCF(cfg CFProxyFlags) ConnectionMode {
	if cfg.Only {
		return ModeCFOnly
	}
	if cfg.Priority && cfg.Enabled {
		return ModeCFFirst
	}
	if cfg.Enabled {
		return ModeDirectWithFallback
	}
	return ModeDirectOnly
}

func NormalizeWorkerDomain(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	lower := strings.ToLower(s)
	if strings.HasPrefix(lower, "https://") {
		s = s[len("https://"):]
	} else if strings.HasPrefix(lower, "http://") {
		s = s[len("http://"):]
	}
	s = strings.TrimSpace(s)
	if idx := strings.IndexAny(s, "/?#"); idx >= 0 {
		s = s[:idx]
	}
	s = strings.TrimSuffix(s, "/")
	s = strings.TrimSpace(s)
	if host, err := url.Parse("https://" + s); err == nil && host.Hostname() != "" {
		return host.Hostname()
	}
	return s
}

func BuildWorkerWSPath(dcID int, dcIP string, media bool) string {
	mediaVal := 0
	if media {
		mediaVal = 1
	}
	return fmt.Sprintf("/apiws?dst=%s&dc=%d&media=%d", dcIP, dcID, mediaVal)
}

func BuildWorkerWSURL(workerDomain string, dcID int, dcIP string, media bool) string {
	return fmt.Sprintf("wss://%s%s", workerDomain, BuildWorkerWSPath(dcID, dcIP, media))
}

func RoutesForMode(mode ConnectionMode, settings RouteSettings, skipDirect bool) []RouteKind {
	workerOK := settings.Worker.Enabled && settings.Worker.Domain != ""
	cfOK := settings.CF.Enabled

	var routes []RouteKind

	switch mode {
	case ModeAuto:
		if !skipDirect {
			routes = append(routes, RouteDirectWS)
		}
		if workerOK {
			routes = append(routes, RouteCFWorkerWS)
		}
		if cfOK {
			routes = append(routes, RouteCFProxyWS)
		}
		routes = append(routes, RouteTCPFallback)
	case ModeDirectWithFallback:
		if !skipDirect {
			routes = append(routes, RouteDirectWS)
		}
		if workerOK {
			routes = append(routes, RouteCFWorkerWS)
		}
		if cfOK {
			routes = append(routes, RouteCFProxyWS)
		}
		routes = append(routes, RouteTCPFallback)
	case ModeWorkerFirst:
		if workerOK {
			routes = append(routes, RouteCFWorkerWS)
		}
		if cfOK {
			routes = append(routes, RouteCFProxyWS)
		}
		if !skipDirect {
			routes = append(routes, RouteDirectWS)
		}
		routes = append(routes, RouteTCPFallback)
	case ModeCFFirst:
		if cfOK {
			routes = append(routes, RouteCFProxyWS)
		}
		if workerOK {
			routes = append(routes, RouteCFWorkerWS)
		}
		if !skipDirect {
			routes = append(routes, RouteDirectWS)
		}
		routes = append(routes, RouteTCPFallback)
	case ModeWorkerOnly:
		if workerOK {
			routes = append(routes, RouteCFWorkerWS)
		}
	case ModeCFOnly:
		if cfOK {
			routes = append(routes, RouteCFProxyWS)
		}
	case ModeDirectOnly:
		if !skipDirect {
			routes = append(routes, RouteDirectWS)
		}
	}
	return routes
}

func PrimaryRoutesBeforeDirectWS(mode ConnectionMode, settings RouteSettings) []RouteKind {
	switch mode {
	case ModeCFFirst, ModeCFOnly, ModeWorkerFirst, ModeWorkerOnly:
		return RoutesForMode(mode, settings, true)
	default:
		return nil
	}
}
