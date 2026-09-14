package main

import "tg-ws-proxy/mtproxyfrontend"

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
	request mtproxyfrontend.OutboundRequest,
	truth mtProtoAttemptTruth,
) {
	if logInfo == nil {
		return
	}
	logInfo.Printf(
		"MTProto route truth frontend=MTProto selected_backend=%s attempt_backend=%s actual_backend=none fallback_used=%t reason=connecting dc=%d media=%t transport=%s",
		truth.SelectedBackend,
		truth.AttemptBackend,
		truth.FallbackUsed,
		request.DCID,
		request.IsMedia,
		request.Transport,
	)
}
