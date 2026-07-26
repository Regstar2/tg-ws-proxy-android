package main

import (
	"testing"

	"tg-ws-proxy/tgwsroute"
)

func TestParseRuntimeConfigRejectsShortCachedCFDomainList(t *testing.T) {
	_, settings, err := parseRuntimeConfig(
		"@cf_cached_domains=one.example|two.example,@cf_manual_domains=manual.example",
	)
	if err != nil {
		t.Fatalf("parseRuntimeConfig: %v", err)
	}
	if len(settings.CFCachedUpstream) != 0 {
		t.Fatalf("cached upstream=%v, want rejected", settings.CFCachedUpstream)
	}
	if len(settings.CFManualDomains) != 1 || settings.CFManualDomains[0] != "manual.example" {
		t.Fatalf("manual domains=%v, want manual domain preserved", settings.CFManualDomains)
	}
}

func TestParseRuntimeConfigAcceptsCachedCFDomainQualityGate(t *testing.T) {
	_, settings, err := parseRuntimeConfig(
		"@cf_cached_domains=one.example|two.example|three.example",
	)
	if err != nil {
		t.Fatalf("parseRuntimeConfig: %v", err)
	}
	if len(settings.CFCachedUpstream) != 3 {
		t.Fatalf("cached upstream=%v, want 3 domains", settings.CFCachedUpstream)
	}
}

func TestParseRuntimeConfigDecodesFlowsealCachedCFDomains(t *testing.T) {
	_, settings, err := parseRuntimeConfig(
		"@cf_cached_domains=virkgj.com|vmmzovy.com|mkuosckvso.com",
	)
	if err != nil {
		t.Fatalf("parseRuntimeConfig: %v", err)
	}
	wantFirst := tgwsroute.DecodeFlowsealCFDomain("virkgj.com")
	if len(settings.CFCachedUpstream) == 0 || settings.CFCachedUpstream[0] != wantFirst {
		t.Fatalf("cached upstream=%v, want first %s", settings.CFCachedUpstream, wantFirst)
	}
}
