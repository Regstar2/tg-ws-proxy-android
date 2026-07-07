package main

import (
	"fmt"
	"sync/atomic"
)

var workerSessionIDCounter atomic.Uint64

func newWorkerSessionID() string {
	return fmt.Sprintf("%08x", workerSessionIDCounter.Add(1))
}

func workerSessionResult(upBytes, downBytes int64) string {
	if upBytes == 0 {
		return "no_payload"
	}
	if downBytes > 0 {
		return "bidirectional"
	}
	return "zero_down"
}
