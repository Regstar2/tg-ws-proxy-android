package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"sync/atomic"
	"time"
)

func isCertificateVerificationFailure(err error) bool {
	var tlsVerification *tls.CertificateVerificationError
	var unknownAuthority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	return errors.As(err, &tlsVerification) || errors.As(err, &unknownAuthority) ||
		errors.As(err, &hostname) || errors.As(err, &invalid)
}

// workerTLSConnect retries only a failed handshake, before any HTTP bytes are sent.
// A successful default profile is reused by later requests in the same relay session.
func workerTLSConnect(ctx context.Context, config *tls.Config, profileState *atomic.Int32, attempt func(*tls.Config) (net.Conn, error)) (net.Conn, error) {
	if profileState.Load() == 2 {
		fallback := config.Clone()
		fallback.CurvePreferences = nil
		return attempt(fallback)
	}
	primary := config.Clone()
	primary.CurvePreferences = classicalTLSCurves()
	conn, err := attempt(primary)
	var stage *wsStageError
	if err == nil || ctx.Err() != nil || errors.Is(err, context.Canceled) || isCertificateVerificationFailure(err) ||
		!errors.As(err, &stage) || stage.Stage != "tls_handshake" || !profileState.CompareAndSwap(0, 1) {
		return conn, err
	}
	if logDebug != nil {
		logDebug.Printf("Worker TLS fallback profile=go_default reason=classical_handshake_failed")
	}
	fallback := config.Clone()
	fallback.CurvePreferences = nil
	conn, fallbackErr := attempt(fallback)
	if fallbackErr == nil {
		profileState.Store(2)
		return conn, nil
	}
	return nil, err
}

func dialWorkerTLS(ctx context.Context, network, address string, config *tls.Config, timeout time.Duration, profileState *atomic.Int32) (net.Conn, error) {
	return workerTLSConnect(ctx, config, profileState, func(profile *tls.Config) (net.Conn, error) {
		dialTimeout := timeout
		if dialTimeout > 10*time.Second {
			dialTimeout = 10 * time.Second
		}
		dialer := &net.Dialer{Timeout: dialTimeout}
		raw, err := dialer.DialContext(ctx, network, address)
		if err != nil {
			return nil, &wsStageError{Stage: "tcp_dial", Err: err}
		}
		setSockOpts(raw)
		conn := tls.Client(raw, profile)
		handshakeCtx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		if err := conn.HandshakeContext(handshakeCtx); err != nil {
			_ = conn.Close()
			return nil, &wsStageError{Stage: "tls_handshake", Err: err}
		}
		return conn, nil
	})
}
