package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
)

func TestAwgWarpHeaderProtectionRuntimeFullDuplex(t *testing.T) {
	serverPrivate, serverPublic := newAwgRuntimeKeyPair(t)
	clientPrivate, clientPublic := newAwgRuntimeKeyPair(t)

	headerProtectionKey := make([]byte, 32)
	if _, err := rand.Read(headerProtectionKey); err != nil {
		t.Fatalf("generate HeaderProtectionKey: %v", err)
	}
	headerKeyHex := hex.EncodeToString(headerProtectionKey)
	serverPort := reserveAwgRuntimeUDPPort(t)

	serverIP := netip.MustParseAddr("10.77.0.1")
	clientIP := netip.MustParseAddr("10.77.0.2")
	serverTun, serverStack, err := netstack.CreateNetTUN([]netip.Addr{serverIP}, nil, 1420)
	if err != nil {
		t.Fatalf("create server netstack: %v", err)
	}
	serverDevice := device.NewDevice(serverTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer serverDevice.Close()

	clientTun, clientStack, err := netstack.CreateNetTUN([]netip.Addr{clientIP}, nil, 1420)
	if err != nil {
		t.Fatalf("create client netstack: %v", err)
	}
	clientDevice := device.NewDevice(clientTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer clientDevice.Close()

	serverConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\nheader_protection_key=%s\n"+
			"s1=12\ns2=12\ns3=12\ns4=12\nh1=1\nh2=2\nh3=3\nh4=4\n"+
			"replace_peers=true\npublic_key=%s\nreplace_allowed_ips=true\nallowed_ip=%s/32\n\n",
		serverPrivate, serverPort, headerKeyHex, clientPublic, clientIP,
	)
	if err := serverDevice.IpcSet(serverConfig); err != nil {
		t.Fatalf("configure server with HeaderProtectionKey: %v", err)
	}
	if err := serverDevice.Up(); err != nil {
		t.Fatalf("start server device: %v", err)
	}

	clientPrivateBytes, err := hex.DecodeString(clientPrivate)
	if err != nil {
		t.Fatalf("decode client private key: %v", err)
	}
	serverPublicBytes, err := hex.DecodeString(serverPublic)
	if err != nil {
		t.Fatalf("decode server public key: %v", err)
	}
	clientConfigText := fmt.Sprintf(
		"[Interface]\nPrivateKey = %s\nAddress = %s/32\n"+
			"HeaderProtectionKey = %s\nS1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\n"+
			"H1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n\n"+
			"[Peer]\nPublicKey = %s\nEndpoint = 127.0.0.1:%d\nAllowedIPs = %s/32\n",
		base64.StdEncoding.EncodeToString(clientPrivateBytes), clientIP,
		base64.StdEncoding.EncodeToString(headerProtectionKey),
		base64.StdEncoding.EncodeToString(serverPublicBytes), serverPort, serverIP,
	)
	clientConfig, err := parseAwgWarpConfig(clientConfigText)
	if err != nil {
		t.Fatalf("parse client AWG config: %v", err)
	}
	if err := clientDevice.IpcSet(clientConfig.uapiConfig()); err != nil {
		t.Fatalf("configure client from parsed AWG config: %v", err)
	}
	if err := clientDevice.Up(); err != nil {
		t.Fatalf("start client device: %v", err)
	}

	serverListener, err := serverStack.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.1:8787"))
	if err != nil {
		t.Fatalf("listen on server netstack: %v", err)
	}
	defer serverListener.Close()

	const payload = "awg31-header-protection-runtime"
	serverResult := make(chan error, 1)
	go func() {
		connection, acceptErr := serverListener.Accept()
		if acceptErr != nil {
			serverResult <- acceptErr
			return
		}
		defer connection.Close()
		_ = connection.SetDeadline(time.Now().Add(8 * time.Second))
		request := make([]byte, len(payload))
		if _, readErr := io.ReadFull(connection, request); readErr != nil {
			serverResult <- readErr
			return
		}
		_, writeErr := connection.Write(request)
		serverResult <- writeErr
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	connection, err := clientStack.DialContextTCPAddrPort(
		ctx,
		netip.MustParseAddrPort("10.77.0.1:8787"),
	)
	if err != nil {
		t.Fatalf("connect through HeaderProtectionKey-protected AWG runtime: %v", err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := io.WriteString(connection, payload); err != nil {
		t.Fatalf("write through protected AWG runtime: %v", err)
	}
	response := make([]byte, len(payload))
	if _, err := io.ReadFull(connection, response); err != nil {
		t.Fatalf("read through protected AWG runtime: %v", err)
	}
	if !bytes.Equal(response, []byte(payload)) {
		t.Fatalf("round-trip response = %q, want %q", response, payload)
	}
	if err := <-serverResult; err != nil {
		t.Fatalf("server echo failed: %v", err)
	}
}

func newAwgRuntimeKeyPair(t *testing.T) (privateHex, publicHex string) {
	t.Helper()
	privateKey := make([]byte, curve25519.ScalarSize)
	if _, err := rand.Read(privateKey); err != nil {
		t.Fatalf("generate test private key: %v", err)
	}
	publicKey, err := curve25519.X25519(privateKey, curve25519.Basepoint)
	if err != nil {
		t.Fatalf("derive test public key: %v", err)
	}
	return hex.EncodeToString(privateKey), hex.EncodeToString(publicKey)
}

func reserveAwgRuntimeUDPPort(t *testing.T) uint16 {
	t.Helper()
	listener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("reserve local UDP port: %v", err)
	}
	port := listener.LocalAddr().(*net.UDPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatalf("release reserved UDP port: %v", err)
	}
	return uint16(port)
}
