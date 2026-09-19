package main

/*
#include <stdlib.h>
*/
import "C"

import (
    "bytes"
    "context"
    "crypto/tls"
    "encoding/json"
    "errors"
    "fmt"
    "io"
    "net"
    "net/http"
    "net/http/httptrace"
    "net/netip"
    "strconv"
    "strings"
    "sync/atomic"
    "time"
)

const (
    consumerWarpAPIHost            = "api.cloudflareclient.com"
    consumerWarpAPIPort            = uint16(443)
    consumerWarpRegisterPath       = "/v0a4005/reg"
    consumerWarpMaxResponseBytes   = 256 * 1024
    consumerWarpDefaultHTTPTimeout = 20 * time.Second
    consumerWarpMaximumHTTPTimeout = 60 * time.Second
    consumerWarpUserAgent          = "okhttp/3.12.1"
    consumerWarpClientVersion      = "a-6.11-2223"
)

type consumerWarpBootstrapHTTPResult struct {
    OK          bool   `json:"ok"`
    Code        string `json:"code"`
    Status      int    `json:"status,omitempty"`
    Body        string `json:"body,omitempty"`
    RequestSent bool   `json:"request_sent,omitempty"`
}

func consumerWarpHTTPTimeout(timeoutMillis int64) time.Duration {
    if timeoutMillis <= 0 {
        return consumerWarpDefaultHTTPTimeout
    }
    timeout := time.Duration(timeoutMillis) * time.Millisecond
    if timeout > consumerWarpMaximumHTTPTimeout {
        return consumerWarpMaximumHTTPTimeout
    }
    return timeout
}

func consumerWarpRegistrationIDValid(registrationID string) bool {
    registrationID = strings.TrimSpace(registrationID)
    if len(registrationID) == 0 || len(registrationID) > 128 {
        return false
    }
    for _, char := range registrationID {
        if (char >= 'a' && char <= 'z') ||
            (char >= 'A' && char <= 'Z') ||
            (char >= '0' && char <= '9') || char == '-' || char == '_' {
            continue
        }
        return false
    }
    return true
}

func validateConsumerWarpBootstrapRequest(method, path string) error {
    if strings.ContainsAny(path, "?#") {
        return errors.New("Consumer WARP bootstrap query and fragment are not allowed")
    }
    switch method {
    case http.MethodGet:
        if path == "/" {
            return nil
        }
    case http.MethodPost:
        if path == consumerWarpRegisterPath {
            return nil
        }
    case http.MethodPatch:
        prefix := consumerWarpRegisterPath + "/"
        if strings.HasPrefix(path, prefix) && consumerWarpRegistrationIDValid(strings.TrimPrefix(path, prefix)) {
            return nil
        }
    }
    return errors.New("Consumer WARP bootstrap operation is not allowlisted")
}

func consumerWarpDialContext(
    ctx context.Context,
    network string,
    address string,
    resolver awgWarpEndpointResolver,
    awgDial awgWarpProbeDialContext,
) (net.Conn, error) {
    if ctx == nil {
        return nil, errors.New("Consumer WARP bootstrap requires a non-nil context")
    }
    if err := ctx.Err(); err != nil {
        return nil, err
    }
    if resolver == nil || awgDial == nil {
        return nil, errors.New("Consumer WARP bootstrap transport is unavailable")
    }
    if network != "tcp" && network != "tcp4" && network != "tcp6" {
        return nil, fmt.Errorf("Consumer WARP bootstrap rejects network %q", network)
    }

    host, portText, err := net.SplitHostPort(strings.TrimSpace(address))
    if err != nil || !strings.EqualFold(host, consumerWarpAPIHost) || portText != strconv.Itoa(int(consumerWarpAPIPort)) {
        return nil, errors.New("Consumer WARP bootstrap destination is not allowlisted")
    }

    addresses, err := resolver.LookupNetIP(ctx, "ip", consumerWarpAPIHost)
    if err != nil {
        return nil, fmt.Errorf("resolve Consumer WARP API: %w", err)
    }

    var lastErr error
    for _, wantIPv4 := range []bool{true, false} {
        for _, resolved := range addresses {
            resolved = resolved.Unmap()
            if !resolved.IsValid() || resolved.Is4() != wantIPv4 {
                continue
            }
            if network == "tcp4" && !resolved.Is4() {
                continue
            }
            if network == "tcp6" && !resolved.Is6() {
                continue
            }
            target := netip.AddrPortFrom(resolved, consumerWarpAPIPort).String()
            conn, dialErr := awgDial(ctx, "tcp", target)
            if dialErr == nil {
                return conn, nil
            }
            lastErr = dialErr
        }
    }
    if lastErr != nil {
        return nil, fmt.Errorf("Consumer WARP API connect through AWG/WARP failed: %w", lastErr)
    }
    return nil, errors.New("Consumer WARP API resolution returned no usable addresses")
}

func executeConsumerWarpBootstrapRequest(
    configPath string,
    method string,
    path string,
    body []byte,
    bearerToken string,
    timeout time.Duration,
    includeResponseBody bool,
) consumerWarpBootstrapHTTPResult {
    if strings.TrimSpace(configPath) == "" {
        return consumerWarpBootstrapHTTPResult{Code: "config_path_empty"}
    }
    if err := validateConsumerWarpBootstrapRequest(method, path); err != nil {
        return consumerWarpBootstrapHTTPResult{Code: "bootstrap_request_not_allowed"}
    }
    if timeout <= 0 {
        timeout = consumerWarpDefaultHTTPTimeout
    }

    dialer, err := newAwgWarpDialerFromFile(configPath)
    if err != nil {
        return consumerWarpBootstrapHTTPResult{Code: "bootstrap_transport_init_failed"}
    }
    defer dialer.Close()

    transport := &http.Transport{
        Proxy: nil,
        DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
            return consumerWarpDialContext(ctx, network, address, net.DefaultResolver, dialer.DialContext)
        },
        TLSClientConfig: &tls.Config{
            MinVersion: tls.VersionTLS12,
            ServerName: consumerWarpAPIHost,
        },
        DisableKeepAlives: true,
        ForceAttemptHTTP2: false,
    }
    client := &http.Client{
        Transport: transport,
        Timeout:   timeout,
        CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
            return errors.New("Consumer WARP bootstrap redirects are not allowed")
        },
    }
    defer client.CloseIdleConnections()

    requestURL := "https://" + consumerWarpAPIHost + path
    req, err := http.NewRequest(method, requestURL, bytes.NewReader(body))
    if err != nil {
        return consumerWarpBootstrapHTTPResult{Code: "bootstrap_request_invalid"}
    }
    req.Header.Set("User-Agent", consumerWarpUserAgent)
    req.Header.Set("CF-Client-Version", consumerWarpClientVersion)
    req.Header.Set("Accept", "application/json")
    if len(body) > 0 {
        req.Header.Set("Content-Type", "application/json; charset=UTF-8")
    }
    if bearerToken != "" {
        req.Header.Set("Authorization", "Bearer "+bearerToken)
    }

    var requestSent atomic.Bool
    trace := &httptrace.ClientTrace{
        WroteHeaders: func() {
            requestSent.Store(true)
        },
    }
    req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))

    response, err := client.Do(req)
    if err != nil {
        return consumerWarpBootstrapHTTPResult{
            Code:        "registration_network_error",
            RequestSent: requestSent.Load(),
        }
    }
    defer response.Body.Close()

    if method == http.MethodGet {
        return consumerWarpBootstrapHTTPResult{
            OK:          true,
            Code:        "ok",
            Status:      response.StatusCode,
            RequestSent: requestSent.Load(),
        }
    }

    result := consumerWarpBootstrapHTTPResult{
        Status:      response.StatusCode,
        RequestSent: requestSent.Load(),
    }
    switch {
    case response.StatusCode == http.StatusTooManyRequests:
        result.Code = "registration_rate_limited"
        return result
    case response.StatusCode >= 500 && response.StatusCode <= 599:
        result.Code = "registration_server_error"
        return result
    case response.StatusCode < 200 || response.StatusCode > 299:
        result.Code = fmt.Sprintf("registration_http_%d", response.StatusCode)
        return result
    }

    if includeResponseBody {
        limited := io.LimitReader(response.Body, consumerWarpMaxResponseBytes+1)
        data, readErr := io.ReadAll(limited)
        if readErr != nil {
            result.Code = "registration_response_read_failed"
            return result
        }
        if len(data) > consumerWarpMaxResponseBytes {
            result.Code = "registration_response_too_large"
            return result
        }
        if len(data) == 0 {
            result.Code = "registration_empty_response"
            return result
        }
        if !json.Valid(data) {
            result.Code = "registration_response_invalid"
            return result
        }
        result.Body = string(data)
    }

    result.OK = true
    result.Code = "ok"
    return result
}

//export ProbeConsumerWARPAPIWithAWG
func ProbeConsumerWARPAPIWithAWG(configPath *C.char, timeoutMillis C.longlong) (result *C.char) {
    defer func() {
        if recover() != nil {
            result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
        }
    }()
    path := ""
    if configPath != nil {
        path = strings.TrimSpace(C.GoString(configPath))
    }
    response := executeConsumerWarpBootstrapRequest(
        path,
        http.MethodGet,
        "/",
        nil,
        "",
        consumerWarpHTTPTimeout(int64(timeoutMillis)),
        false,
    )
    return cJSON(response)
}

//export RegisterConsumerWARPWithAWG
func RegisterConsumerWARPWithAWG(configPath *C.char, publicKey *C.char, timeoutMillis C.longlong) (result *C.char) {
    defer func() {
        if recover() != nil {
            result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
        }
    }()
    path := ""
    if configPath != nil {
        path = strings.TrimSpace(C.GoString(configPath))
    }
    key := ""
    if publicKey != nil {
        key = strings.TrimSpace(C.GoString(publicKey))
    }
    if _, err := decodeWireGuardKey(key); err != nil {
        return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_public_key_invalid"})
    }
    body, err := json.Marshal(map[string]string{"key": key})
    if err != nil {
        return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_request_invalid"})
    }
    response := executeConsumerWarpBootstrapRequest(
        path,
        http.MethodPost,
        consumerWarpRegisterPath,
        body,
        "",
        consumerWarpHTTPTimeout(int64(timeoutMillis)),
        true,
    )
    return cJSON(response)
}

//export ActivateConsumerWARPWithAWG
func ActivateConsumerWARPWithAWG(
    configPath *C.char,
    registrationID *C.char,
    token *C.char,
    timeoutMillis C.longlong,
) (result *C.char) {
    defer func() {
        if recover() != nil {
            result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
        }
    }()
    path := ""
    if configPath != nil {
        path = strings.TrimSpace(C.GoString(configPath))
    }
    id := ""
    if registrationID != nil {
        id = strings.TrimSpace(C.GoString(registrationID))
    }
    bearerToken := ""
    if token != nil {
        bearerToken = strings.TrimSpace(C.GoString(token))
    }
    if !consumerWarpRegistrationIDValid(id) {
        return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_missing_id"})
    }
    if bearerToken == "" {
        return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_missing_token"})
    }
    response := executeConsumerWarpBootstrapRequest(
        path,
        http.MethodPatch,
        consumerWarpRegisterPath+"/"+id,
        []byte(`{"warp_enabled":true}`),
        bearerToken,
        consumerWarpHTTPTimeout(int64(timeoutMillis)),
        false,
    )
    return cJSON(response)
}
