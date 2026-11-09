//go:build cfproxy_poc_baseline && !cfproxy_poc_tcp_split

package main

// A/B control: strict certificate verification, unmodified TLS ClientHello.
var webSocketClientHelloPoCProfile = cfClientHelloPoCProfile{verifyCertificates: true}
