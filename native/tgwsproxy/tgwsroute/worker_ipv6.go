package tgwsroute

import (
	"net/netip"
	"net/url"
	"strings"
)

const (
	WorkerDstSourceParsedHost   = "parsed_host"
	WorkerDstSourceIPv6ToDCIPv4 = "ipv6_to_dc_ipv4"

	DstFamilyIPv4 = "ipv4"
	DstFamilyIPv6 = "ipv6"

	FailWorkerIPv6Unsupported   = "worker_ipv6_unsupported"
	FailWorkerIPv6RawDstBlocked = "worker_ipv6_raw_dst_blocked"
)

var workerCanonicalIPv4ByDC = map[int]string{
	1:   "149.154.175.50",
	2:   "149.154.167.51",
	3:   "149.154.175.100",
	4:   "149.154.167.91",
	5:   "149.154.171.5",
	203: "91.105.192.100",
}

type CfWorkerTransparentResolve struct {
	OK                  bool
	FailReason          string
	WorkerDst           string
	WorkerURL           string
	DstFamily           string
	IPv6WorkerDirect    bool
	WorkerDstSource     string
	PreserveOriginalDst bool
}

func WorkerCanonicalIPv4ForDC(dc int) (string, bool) {
	ip, ok := workerCanonicalIPv4ByDC[dc]
	return ip, ok && ip != ""
}

func WorkerURLContainsRawIPv6Dst(workerURL string) bool {
	u, err := url.Parse(workerURL)
	if err != nil {
		return false
	}
	dst := strings.TrimSpace(u.Query().Get("dst"))
	if dst == "" {
		return false
	}
	addr, err := netip.ParseAddr(dst)
	if err != nil {
		return strings.Contains(dst, ":")
	}
	return addr.Is6()
}

func ResolveCfWorkerTransparentWorker(workerDomain string, dcID int, isMedia, dcOk bool, parsedDstHost string) CfWorkerTransparentResolve {
	host := strings.TrimSpace(parsedDstHost)
	result := CfWorkerTransparentResolve{
		IPv6WorkerDirect: false,
	}

	addr, err := netip.ParseAddr(host)
	if err != nil {
		result.DstFamily = DstFamilyIPv4
		result.WorkerDst = host
		result.WorkerDstSource = WorkerDstSourceParsedHost
		result.PreserveOriginalDst = true
		result.WorkerURL = BuildWorkerWSURL(workerDomain, dcID, result.WorkerDst, isMedia, "")
		result.OK = !WorkerURLContainsRawIPv6Dst(result.WorkerURL)
		if !result.OK {
			result.FailReason = FailWorkerIPv6RawDstBlocked
		}
		return result
	}

	if addr.Is4() {
		result.DstFamily = DstFamilyIPv4
		result.WorkerDst = addr.String()
		result.WorkerDstSource = WorkerDstSourceParsedHost
		result.PreserveOriginalDst = true
		result.WorkerURL = BuildWorkerWSURL(workerDomain, dcID, result.WorkerDst, isMedia, "")
		result.OK = !WorkerURLContainsRawIPv6Dst(result.WorkerURL)
		if !result.OK {
			result.FailReason = FailWorkerIPv6RawDstBlocked
		}
		return result
	}

	result.DstFamily = DstFamilyIPv6
	result.PreserveOriginalDst = false
	if !dcOk || dcID <= 0 {
		result.OK = false
		result.FailReason = FailWorkerIPv6Unsupported
		return result
	}
	canonical, hasCanonical := WorkerCanonicalIPv4ForDC(dcID)
	if !hasCanonical {
		result.OK = false
		result.FailReason = FailWorkerIPv6Unsupported
		return result
	}
	result.WorkerDst = canonical
	result.WorkerDstSource = WorkerDstSourceIPv6ToDCIPv4
	result.WorkerURL = BuildWorkerWSURL(workerDomain, dcID, result.WorkerDst, isMedia, "")
	if WorkerURLContainsRawIPv6Dst(result.WorkerURL) {
		result.OK = false
		result.FailReason = FailWorkerIPv6RawDstBlocked
		return result
	}
	result.OK = true
	return result
}
