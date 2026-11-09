//go:build !cfproxy_poc_baseline && !cfproxy_poc_tcp_split

package main

// The shipped application always uses the unchanged production TLS profile.
var webSocketClientHelloPoCProfile = cfClientHelloPoCProfile{}
