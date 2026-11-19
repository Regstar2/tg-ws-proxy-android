package main

import (
	"crypto/x509"
	"os"
	"runtime"
	"sync"
)

// Android 14+ delivers updated system CAs in Conscrypt APEX. Go's x509 pool
// may read only the older /system location. Combine both system sources and
// never substitute an insecure TLS mode if either source is unavailable.
const androidConscryptCACerts = "/apex/com.android.conscrypt/cacerts"

var androidTLSRoots = sync.OnceValue(func() *x509.CertPool {
	roots, err := x509.SystemCertPool()
	if err != nil || roots == nil {
		roots = x509.NewCertPool()
	}
	appendAndroidCACerts(roots, androidConscryptCACerts)
	return roots
})

func nativeTLSRootCAs() *x509.CertPool {
	if runtime.GOOS != "android" {
		return nil // crypto/tls uses the operating system's trusted roots.
	}
	return androidTLSRoots()
}

func appendAndroidCACerts(roots *x509.CertPool, directory string) int {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return 0
	}
	added := 0
	for _, entry := range entries {
		if !entry.Type().IsRegular() {
			continue
		}
		pemBytes, err := os.ReadFile(directory + "/" + entry.Name())
		if err == nil && roots.AppendCertsFromPEM(pemBytes) {
			added++
		}
	}
	return added
}
