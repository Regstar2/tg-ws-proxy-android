package main

import (
    "fmt"
    "strings"
)

// Reject policies with no usable outbound candidate before replacing the runtime
// snapshot. An explicitly disabled Direct/TCP route must never be enabled implicitly.
func validateMtProtoRequestedRoutes(settings runtimeSettings) error {
    if settings.OrderedPolicy {
        for _, route := range settings.OrderedRoutes {
            switch route {
            case routeAWGWarp:
                if strings.TrimSpace(settings.AWGConfigPath) != "" {
                    return nil
                }
            case routeCFWorkerWS:
                if settings.Worker.Domain != "" || len(settings.Worker.Failover.Candidates) > 0 {
                    return nil
                }
            default:
                // Direct WS, CF Proxy and TCP need network I/O to test, but do
                // not depend on a missing local Worker/AWG configuration.
                return nil
            }
        }
        return fmt.Errorf("ordered MTProto policy has no configured outbound route")
    }
    if len(mtProtoRoutesForCapability(settings)) == 0 {
        return fmt.Errorf("MTProto policy has no outbound route")
    }
    return nil
}
