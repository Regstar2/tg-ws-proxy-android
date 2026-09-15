package main

/*
#include <stdlib.h>
*/
import "C"

import "strings"

//export ConfigureAWGWarp
func ConfigureAWGWarp(
	configPath *C.char,
	enabled C.int,
	preferred C.int,
	allowFallback C.int,
) C.int {
	path := ""
	if configPath != nil {
		path = strings.TrimSpace(C.GoString(configPath))
	}
	if err := globalAWGWarpRouteRuntime.Configure(
		path,
		enabled != 0,
		preferred != 0,
		allowFallback != 0,
	); err != nil {
		if logWarn != nil {
			logWarn.Printf("AWG/WARP route configuration rejected: %v", err)
		}
		return 1
	}
	return 0
}

//export ResetAWGWarp
func ResetAWGWarp() C.int {
	globalAWGWarpRouteRuntime.Reset()
	return 0
}
