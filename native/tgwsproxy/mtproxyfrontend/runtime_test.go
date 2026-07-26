package mtproxyfrontend

import (
	"net"
	"strings"
	"testing"
)

const testSecret = "0123456789abcdef0123456789abcdef"

func TestIsRawSecret(t *testing.T) {
	if !IsRawSecret(testSecret) {
		t.Fatalf("expected valid raw secret")
	}
	if IsRawSecret("bad") {
		t.Fatalf("expected invalid raw secret")
	}
}

func TestSecretFingerprintDoesNotContainSecret(t *testing.T) {
	fingerprint := SecretFingerprint(testSecret)
	if fingerprint == "" {
		t.Fatalf("expected non-empty fingerprint")
	}
	if strings.Contains(testSecret, fingerprint) || strings.Contains(fingerprint, testSecret) {
		t.Fatalf("fingerprint should not expose raw secret")
	}
}

func TestRuntimeStartStopValidSecret(t *testing.T) {
	port := reservePort(t)
	runtime := NewRuntime(nil)

	result := runtime.Start(Config{
		Host:   "127.0.0.1",
		Port:   port,
		Secret: testSecret,
	})
	if result.Err != nil {
		t.Fatalf("start failed: %v", result.Err)
	}
	if result.Status != StatusListeningLocalOnly {
		t.Fatalf("status = %s, want %s", result.Status, StatusListeningLocalOnly)
	}
	status := runtime.Status()
	if status.OutboundStatus != OutboundUnsupported {
		t.Fatalf("outbound = %s, want %s", status.OutboundStatus, OutboundUnsupported)
	}
	if status.SecretFingerprint == "" || status.SecretFingerprint == testSecret {
		t.Fatalf("secret fingerprint should be masked")
	}

	if err := runtime.Stop(); err != nil {
		t.Fatalf("stop failed: %v", err)
	}
	if runtime.Status().Status != StatusStopped {
		t.Fatalf("status = %s, want %s", runtime.Status().Status, StatusStopped)
	}
}

func TestRuntimeStartInvalidSecret(t *testing.T) {
	runtime := NewRuntime(nil)

	result := runtime.Start(Config{
		Host:   "127.0.0.1",
		Port:   1443,
		Secret: "bad",
	})
	if result.Err == nil {
		t.Fatalf("expected invalid secret error")
	}
	if result.Status != StatusFailedInvalidSecret {
		t.Fatalf("status = %s, want %s", result.Status, StatusFailedInvalidSecret)
	}
}

func TestRuntimeStartPortBusy(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve busy listener: %v", err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port

	runtime := NewRuntime(nil)
	result := runtime.Start(Config{
		Host:   "127.0.0.1",
		Port:   port,
		Secret: testSecret,
	})
	if result.Err == nil {
		defer runtime.Stop()
		t.Fatalf("expected port busy error")
	}
	if result.Status != StatusFailedPortInUse {
		t.Fatalf("status = %s, want %s", result.Status, StatusFailedPortInUse)
	}
}

func reservePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve port: %v", err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}
