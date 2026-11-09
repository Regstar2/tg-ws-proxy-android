//go:build cfproxy_poc_tcp_split && !cfproxy_poc_baseline

package main

// Experimental A/B arm: same TLS policy as the PoC control, split TCP writes.
var cfProxyPoCProfile = cfClientHelloPoCProfile{tcpSplit: true, verifyCertificates: true}
