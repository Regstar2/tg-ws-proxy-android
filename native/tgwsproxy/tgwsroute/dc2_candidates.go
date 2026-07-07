package tgwsroute

import "strings"

const (
	DC2DefaultIPv4Candidate = "149.154.167.51"
	DC2DemotedIPv4Candidate = "149.154.167.50"
)

var DC2WorkerCandidates = []string{
	"149.154.167.41",
	DC2DemotedIPv4Candidate,
	DC2DefaultIPv4Candidate,
}

func IsDC2WorkerCandidate(ip string) bool {
	ip = strings.TrimSpace(ip)
	for _, candidate := range DC2WorkerCandidates {
		if candidate == ip {
			return true
		}
	}
	return false
}

func HasDC2ManualOverride(dcIPMap map[int]string) bool {
	if dcIPMap == nil {
		return false
	}
	ip := strings.TrimSpace(dcIPMap[2])
	return ip != ""
}
