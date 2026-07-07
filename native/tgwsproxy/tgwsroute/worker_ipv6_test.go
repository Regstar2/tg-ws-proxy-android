package tgwsroute

import (
	"strings"
	"testing"
)

func TestWorkerCanonicalIPv4ForDC(t *testing.T) {
	cases := map[int]string{
		1:   "149.154.175.50",
		2:   "149.154.167.51",
		4:   "149.154.167.91",
		5:   "149.154.171.5",
		203: "91.105.192.100",
	}
	for dc, want := range cases {
		got, ok := WorkerCanonicalIPv4ForDC(dc)
		if !ok || got != want {
			t.Fatalf("WorkerCanonicalIPv4ForDC(%d) = (%q, %t), want (%q, true)", dc, got, ok, want)
		}
	}
}

func TestResolveCfWorkerTransparentWorker_IPv4PreservesParsedDst(t *testing.T) {
	resolved := ResolveCfWorkerTransparentWorker("example.workers.dev", 2, false, true, "149.154.167.41")
	if !resolved.OK {
		t.Fatalf("expected ok, got reason=%s", resolved.FailReason)
	}
	if resolved.DstFamily != DstFamilyIPv4 {
		t.Fatalf("dst_family=%q want ipv4", resolved.DstFamily)
	}
	if resolved.WorkerDst != "149.154.167.41" {
		t.Fatalf("workerDst=%q", resolved.WorkerDst)
	}
	if resolved.WorkerDstSource != WorkerDstSourceParsedHost {
		t.Fatalf("worker_dst_source=%q", resolved.WorkerDstSource)
	}
	if !resolved.PreserveOriginalDst {
		t.Fatal("preserve_original_dst should be true for ipv4")
	}
	if !strings.Contains(resolved.WorkerURL, "dst=149.154.167.41") {
		t.Fatalf("workerURL=%s", resolved.WorkerURL)
	}
	if strings.Contains(resolved.WorkerURL, "dst=149.154.167.51") {
		t.Fatalf("workerURL must not rewrite ipv4 dst: %s", resolved.WorkerURL)
	}
}

func TestResolveCfWorkerTransparentWorker_IPv6MapsToCanonicalDC2(t *testing.T) {
	info, ok := LookupTelegramDC("2001:67c:4e8:f002::a")
	if !ok || info.DC != 2 {
		t.Fatalf("expected mapped_dc=2, got %+v ok=%t", info, ok)
	}

	resolved := ResolveCfWorkerTransparentWorker("example.workers.dev", 2, false, true, "2001:67c:4e8:f002::a")
	if !resolved.OK {
		t.Fatalf("expected ok, got reason=%s", resolved.FailReason)
	}
	if resolved.DstFamily != DstFamilyIPv6 {
		t.Fatalf("dst_family=%q want ipv6", resolved.DstFamily)
	}
	if resolved.IPv6WorkerDirect {
		t.Fatal("ipv6_worker_direct must be false")
	}
	if resolved.WorkerDst != "149.154.167.51" {
		t.Fatalf("workerDst=%q want canonical DC2 ipv4", resolved.WorkerDst)
	}
	if resolved.WorkerDstSource != WorkerDstSourceIPv6ToDCIPv4 {
		t.Fatalf("worker_dst_source=%q", resolved.WorkerDstSource)
	}
	if strings.Contains(resolved.WorkerURL, "dst=2001") {
		t.Fatalf("workerURL must not contain raw ipv6 dst: %s", resolved.WorkerURL)
	}
	if !strings.Contains(resolved.WorkerURL, "dst=149.154.167.51") {
		t.Fatalf("workerURL=%s", resolved.WorkerURL)
	}
}

func TestResolveCfWorkerTransparentWorker_IPv6UnknownDCUnsupported(t *testing.T) {
	resolved := ResolveCfWorkerTransparentWorker("example.workers.dev", 0, false, false, "2a0a:f280:203:a:5000::100")
	if resolved.OK {
		t.Fatal("expected failure for ipv6 without mapped dc")
	}
	if resolved.FailReason != FailWorkerIPv6Unsupported {
		t.Fatalf("failReason=%q want %q", resolved.FailReason, FailWorkerIPv6Unsupported)
	}
}

func TestWorkerURLContainsRawIPv6Dst(t *testing.T) {
	if !WorkerURLContainsRawIPv6Dst("wss://example.workers.dev/apiws?dst=2001%3A67c%3A4e8%3Af002%3A%3Aa&dc=2&media=0") {
		t.Fatal("expected encoded ipv6 dst to be detected")
	}
	if WorkerURLContainsRawIPv6Dst("wss://example.workers.dev/apiws?dst=149.154.167.51&dc=2&media=0") {
		t.Fatal("ipv4 dst should not trigger ipv6 leak detection")
	}
}

func TestBuildWorkerWSPath_UsesURLEncoding(t *testing.T) {
	got := BuildWorkerWSPath(2, "149.154.167.51", false, "deadbeef")
	if !strings.Contains(got, "dst=149.154.167.51") {
		t.Fatalf("path=%q", got)
	}
	if strings.Contains(got, " ") {
		t.Fatalf("path must be url-encoded: %q", got)
	}
	if !strings.Contains(got, "sid=deadbeef") {
		t.Fatalf("path=%q missing sid", got)
	}
}
