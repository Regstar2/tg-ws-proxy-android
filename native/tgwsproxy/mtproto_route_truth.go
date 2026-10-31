package main

import (
	"context"
	"fmt"
	"strings"

	"tg-ws-proxy/mtproxyfrontend"
)

type mtProtoRouteDecisionKey struct{}

type mtProtoRouteDecision struct {
	Routes []routeKind
	Generation uint64
}

type mtProtoAttemptTruth struct {
	SelectedBackend string
	AttemptBackend  string
	FallbackUsed    bool
}

func mtProtoAttemptTruthForRoutes(routes []routeKind, attempt routeKind) mtProtoAttemptTruth {
	attemptBackend := mtProtoBackendForRoute(attempt)
	selectedBackend := attemptBackend
	fallbackUsed := false
	if len(routes) > 0 {
		selectedBackend = mtProtoBackendForRoute(routes[0])
		fallbackUsed = attempt != routes[0]
	}
	return mtProtoAttemptTruth{
		SelectedBackend: selectedBackend,
		AttemptBackend:  attemptBackend,
		FallbackUsed:    fallbackUsed,
	}
}

func logMtProtoRouteAttempt(
	ctx context.Context,
	request mtproxyfrontend.OutboundRequest,
	attempt routeKind,
	detailsFormat string,
	detailsArgs ...any,
) {
	if logInfo == nil {
		return
	}
	decision, ok := ctx.Value(mtProtoRouteDecisionKey{}).(mtProtoRouteDecision)
	if !ok {
		settings := getRuntimeSettings()
		decision = mtProtoRouteDecision{
			Routes: mtProtoRoutesForRequest(settings, request),
			Generation: settings.PolicyGen,
		}
	}
	truth := mtProtoAttemptTruthForRoutes(decision.Routes, attempt)
	details := strings.TrimSpace(fmt.Sprintf(detailsFormat, detailsArgs...))
	if details != "" {
		details = " " + details
	}
	logInfo.Printf(
		"MTProto route truth frontend=MTProto session_id=%s route_generation=%d selected_backend=%s attempt_backend=%s actual_backend=none fallback_used=%t reason=connecting dc=%d media=%t transport=%s%s",
		mtproxyfrontend.SessionIDForRequest(request),
		decision.Generation,
		truth.SelectedBackend,
		truth.AttemptBackend,
		truth.FallbackUsed,
		request.DCID,
		request.IsMedia,
		request.Transport,
		details,
	)
}
