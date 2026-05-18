package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"fmt"
	"strings"
	"sync/atomic"
)

var (
	lastMetricsRoute   atomic.Value // string
	lastMetricsLatency atomic.Int64
	lastMetricsError   atomic.Value // string
)

func updateProxyMetrics(route routeKind, latencyMs int64, errReason string) {
	lastMetricsRoute.Store(string(route))
	if latencyMs > 0 {
		lastMetricsLatency.Store(latencyMs)
	}
	if errReason != "" {
		lastMetricsError.Store(errReason)
	}
}

func exportProxyStatus() string {
	settings := getRuntimeSettings()
	running := 0
	globalMu.Lock()
	if globalCancel != nil {
		running = 1
	}
	globalMu.Unlock()

	route := ""
	if v := lastMetricsRoute.Load(); v != nil {
		route = v.(string)
	}
	if route == "" {
		lastAdaptiveMu.RLock()
		if len(lastAdaptiveSel.Routes) > 0 {
			route = string(lastAdaptiveSel.Routes[0])
		}
		lastAdaptiveMu.RUnlock()
	}

	lastErr := ""
	if v := lastMetricsError.Load(); v != nil {
		lastErr = v.(string)
	}

	active := stats.connectionsTotal.Load()
	if active < 0 {
		active = 0
	}

	return fmt.Sprintf(
		"running=%d;mode=%s;route=%s;active=%d;bytes_up=%d;bytes_down=%d;latency_ms=%d;last_error=%s",
		running,
		settings.Mode,
		route,
		active,
		stats.bytesUp.Load(),
		stats.bytesDown.Load(),
		lastMetricsLatency.Load(),
		escapeStatusField(lastErr),
	)
}

func escapeStatusField(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, ";", ","), "\n", " ")
}

//export GetProxyStatus
func GetProxyStatus() *C.char {
	return C.CString(exportProxyStatus())
}

func init() {
	lastMetricsRoute.Store("")
	lastMetricsError.Store("")
}
