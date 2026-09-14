package main

import (
	"strings"
	"testing"
)

func TestRouteRuntimeFallbackPreservesSelectedAndTracksAttempt(t *testing.T) {
	initRouteRuntimeState()
	t.Cleanup(initRouteRuntimeState)

	noteRouteSelected(routeCFProxyWS)
	noteRouteConnectStarted(routeCFProxyWS)
	noteFallbackActivated(routeDirectWS, "cf_proxy_failed")
	noteRouteConnectStarted(routeDirectWS)

	if got := routeRuntimeString(rtSelectedRoute); got != string(routeCFProxyWS) {
		t.Fatalf("selected route=%q want=%q", got, routeCFProxyWS)
	}
	if got := routeRuntimeString(rtAttemptRoute); got != string(routeDirectWS) {
		t.Fatalf("attempt route=%q want=%q", got, routeDirectWS)
	}

	status := strings.Join(appendRouteRuntimeStatusFields(nil), ";")
	if !strings.Contains(status, "selected_route="+string(routeCFProxyWS)) {
		t.Fatalf("status lost original selection: %s", status)
	}
	if !strings.Contains(status, "attempt_route="+string(routeDirectWS)) {
		t.Fatalf("status lost current attempt: %s", status)
	}
}
