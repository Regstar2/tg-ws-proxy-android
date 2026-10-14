package main

import (
    "bytes"
    "testing"
)

func TestClassifyFlowsealWorkerReply_PlaintextErrorOnly(t *testing.T) {
    tests := []struct {
        name string
        data []byte
        want string
    }{
        {"http response", []byte("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"), "plaintext_http"},
        {"html response", []byte("  <!DOCTYPE html><html>blocked</html>"), "plaintext_html"},
        {"html lower case", []byte("<html><body>Forbidden</body></html>"), "plaintext_html"},
        {"ordinary 309-byte encrypted candidate", bytes.Repeat([]byte{0xA7}, 309), ""},
        {"binary prefix", []byte{0x03, 0x00, 0x00, 0x01, 0x24}, ""},
        {"empty", nil, ""},
    }
    for _, tc := range tests {
        t.Run(tc.name, func(t *testing.T) {
            if got := classifyFlowsealWorkerReply(tc.data); got != tc.want {
                t.Fatalf("classified %q as %q, want %q", tc.name, got, tc.want)
            }
        })
    }
}
