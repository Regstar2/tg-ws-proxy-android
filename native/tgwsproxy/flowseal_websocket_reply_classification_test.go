package main

import (
    "bufio"
    "errors"
    "fmt"
    "strings"
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

func TestFlowsealRejectsPlaintextHTTPBeforeMTProto(t *testing.T) {
    tests := []struct{
        name string
        message []byte
        status int
        kind string
        fragmented bool
    }{
        {"HTTP 403", []byte("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"), 403, "plaintext_http", false},
        {"HTTP 502 fragmented", []byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"), 502, "plaintext_http", true},
        {"HTML error", []byte("<html><body>Bad Gateway</body></html>"), 0, "plaintext_html", false},
    }
    for _, tc := range tests {
        t.Run(tc.name, func(t *testing.T) {
            wire := testServerFrame(opBinary, tc.message, true)
            if tc.fragmented {
                wire=append(
                    testServerFrame(opBinary, tc.message[:9], false),
                    testServerFrame(opContinuation, tc.message[9:], true)...,
                )
            }
            conn := &frameTestConn{reader: bytes.NewReader(wire)}
            ws := &flowsealRawWebSocket{conn: conn, reader: bufio.NewReader(conn), sessionID: "test-http"}
            got, err := ws.Recv()
            if len(got) != 0 || !errors.Is(err, errFlowsealWorkerNonMTProtoReply) {
                t.Fatalf("expected rejected plaintext, bytes=%d err=%v", len(got),err)
            }
            if !ws.closed.Load() {
                t.Fatal("invalid upstream reply must close the worker WebSocket")
            }
            if ws.recvBytes.Load() != 0 {
                t.Fatal("rejected HTTP bytes must not be counted as MTProto downstream")
            }
            if !strings.Contains(err.Error(), "payload_type="+tc.kind) ||
                !strings.Contains(err.Error(), "http_status="+fmt.Sprint(tc.status)) {
                t.Fatalf("safe error metadata missing: %v",err)
            }
        })
    }
}

func TestFlowsealAllowsOpaqueMTProtoReply(t *testing.T) {
    payload := bytes.Repeat([]byte{0xA7}, 309)
    conn := &frameTestConn{reader: bytes.NewReader(testServerFrame(opBinary,payload,true))}
    ws := &flowsealRawWebSocket{conn:conn,reader:bufio.NewReader(conn)}
    actual,err := ws.Recv()
    if err!=nil || !bytes.Equal(actual,payload) || ws.recvBytes.Load()!=309 {
        t.Fatalf("valid binary reply lost: bytes=%d err=%v",len(actual),err)
    }
}
func TestFlowsealSafeHTTPStatus(t *testing.T) {
    tests:=[]struct{input string; want int}{
        {"HTTP/1.1 403 Forbidden\r\n",403},
        {" HTTP/1.0 429 Too Many Requests\r\n",429},
        {"HTTP/1.1 200 OK\r\n",200},
        {"HTTP/1.1 999 Invalid\r\n",0},
        {"HTTP/1.1 XYZ Invalid\r\n",0},
        {"HTTP/2 403\r\n",0},
        {"<html>403 Forbidden</html>",0},
    }
    for _,tc:=range tests {
        if got:=flowsealWorkerHTTPStatus([]byte(tc.input));got!=tc.want{
            t.Errorf("safe HTTP status %q: %d want %d",tc.input,got,tc.want)
        }
    }
}
