package mtproxyfrontend

import (
	"context"
	"crypto/hmac"
	cryptoRand "crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"math/big"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	tlsRecordHandshake = 0x16
	tlsRecordCCS       = 0x14
	tlsRecordAppData   = 0x17

	fakeTLSClientRandomOffset = 11
	fakeTLSClientRandomLen    = 32
	fakeTLSSessionIDOffset    = 44
	fakeTLSSessionIDLen       = 32
	fakeTLSTimestampTolerance = 120 * time.Second
	fakeTLSAppDataMax         = 16384

	fakeTLSServerRandomOffset = 11
	fakeTLSServerSessIDOffset = 44
	fakeTLSServerPubKeyOffset = 89
)

var (
	fakeTLSCCSFrame = []byte{0x14, 0x03, 0x03, 0x00, 0x01, 0x01}

	fakeTLSServerHelloTemplate = []byte{
		0x16, 0x03, 0x03, 0x00, 0x7a,
		0x02, 0x00, 0x00, 0x76,
		0x03, 0x03,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x20,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x13, 0x01, 0x00,
		0x00, 0x2e,
		0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x2b, 0x00, 0x02, 0x03, 0x04,
	}
)

type fakeTLSStream struct {
	conn     net.Conn
	readMu   sync.Mutex
	writeMu  sync.Mutex
	readLeft int
}

type FakeTLSPassthroughRequest struct {
	Domain      string
	InitialData []byte
}

func (e *FakeTLSPassthroughRequest) Error() string {
	return "fake tls masking passthrough requested"
}

func newFakeTLSStream(conn net.Conn) net.Conn {
	return &fakeTLSStream{conn: conn}
}

func verifyFakeTLSClientHello(data []byte, secret []byte, now time.Time) ([]byte, []byte, bool) {
	if len(data) < 43 || data[0] != tlsRecordHandshake || data[5] != 0x01 {
		return nil, nil, false
	}

	clientRandom := append([]byte(nil), data[fakeTLSClientRandomOffset:fakeTLSClientRandomOffset+fakeTLSClientRandomLen]...)
	zeroed := append([]byte(nil), data...)
	for i := 0; i < fakeTLSClientRandomLen; i++ {
		zeroed[fakeTLSClientRandomOffset+i] = 0
	}

	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write(zeroed)
	expected := mac.Sum(nil)
	if !hmac.Equal(expected[:28], clientRandom[:28]) {
		return nil, nil, false
	}

	var tsBytes [4]byte
	for i := 0; i < 4; i++ {
		tsBytes[i] = clientRandom[28+i] ^ expected[28+i]
	}
	timestamp := time.Unix(int64(binary.LittleEndian.Uint32(tsBytes[:])), 0)
	if delta := now.Sub(timestamp); delta > fakeTLSTimestampTolerance || delta < -fakeTLSTimestampTolerance {
		return nil, nil, false
	}

	sessionID := make([]byte, fakeTLSSessionIDLen)
	if len(data) >= fakeTLSSessionIDOffset+fakeTLSSessionIDLen && data[43] == 0x20 {
		copy(sessionID, data[fakeTLSSessionIDOffset:fakeTLSSessionIDOffset+fakeTLSSessionIDLen])
	}
	return clientRandom, sessionID, true
}

func buildFakeTLSServerHello(secret []byte, clientRandom []byte, sessionID []byte) []byte {
	serverHello := append([]byte(nil), fakeTLSServerHelloTemplate...)
	copy(serverHello[fakeTLSServerSessIDOffset:fakeTLSServerSessIDOffset+fakeTLSSessionIDLen], sessionID)
	_, _ = io.ReadFull(cryptoRand.Reader, serverHello[fakeTLSServerPubKeyOffset:fakeTLSServerPubKeyOffset+32])

	encryptedSize := 1900 + cryptoRandInt(201)
	encryptedData := make([]byte, encryptedSize)
	_, _ = io.ReadFull(cryptoRand.Reader, encryptedData)

	appRecord := make([]byte, 5+encryptedSize)
	appRecord[0] = tlsRecordAppData
	appRecord[1] = 0x03
	appRecord[2] = 0x03
	binary.BigEndian.PutUint16(appRecord[3:5], uint16(encryptedSize))
	copy(appRecord[5:], encryptedData)

	response := append(serverHello, fakeTLSCCSFrame...)
	response = append(response, appRecord...)

	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write(clientRandom)
	_, _ = mac.Write(response)
	serverRandom := mac.Sum(nil)
	copy(response[fakeTLSServerRandomOffset:fakeTLSServerRandomOffset+32], serverRandom)
	return response
}

func cryptoRandInt(max int64) int {
	n, err := cryptoRand.Int(cryptoRand.Reader, big.NewInt(max))
	if err != nil {
		return int(time.Now().UnixNano() % max)
	}
	return int(n.Int64())
}

func wrapFakeTLSRecords(data []byte) []byte {
	if len(data) == 0 {
		return nil
	}
	out := make([]byte, 0, len(data)+((len(data)/fakeTLSAppDataMax)+1)*5)
	for offset := 0; offset < len(data); {
		end := offset + fakeTLSAppDataMax
		if end > len(data) {
			end = len(data)
		}
		chunk := data[offset:end]
		out = append(out, tlsRecordAppData, 0x03, 0x03, 0, 0)
		binary.BigEndian.PutUint16(out[len(out)-2:], uint16(len(chunk)))
		out = append(out, chunk...)
		offset = end
	}
	return out
}

func writeFakeTLSRedirect(conn net.Conn, domain string) error {
	normalized := NormalizeFakeTLSDomain(domain)
	if normalized == "" {
		return nil
	}
	payload := fmt.Sprintf(
		"HTTP/1.1 301 Moved Permanently\r\nLocation: https://%s/\r\nConnection: close\r\nContent-Length: 0\r\n\r\n",
		normalized,
	)
	return writeFull(conn, []byte(payload))
}

func proxyFakeTLSPassthrough(ctx context.Context, client net.Conn, domain string, initial []byte) error {
	normalized := NormalizeFakeTLSDomain(domain)
	if normalized == "" {
		return ErrInvalidAddress
	}

	dialer := net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
	}
	upstream, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(normalized, "443"))
	if err != nil {
		return fmt.Errorf("masking domain dial %s: %w", normalized, err)
	}
	if len(initial) > 0 {
		if err := writeFull(upstream, initial); err != nil {
			_ = upstream.Close()
			return fmt.Errorf("masking domain initial write: %w", err)
		}
	}

	bridgeTransformedStreams(ctx, client, upstream, nil, nil)
	return nil
}

func (s *fakeTLSStream) Read(dst []byte) (int, error) {
	if len(dst) == 0 {
		return 0, nil
	}
	s.readMu.Lock()
	defer s.readMu.Unlock()

	for {
		if s.readLeft > 0 {
			limit := len(dst)
			if s.readLeft < limit {
				limit = s.readLeft
			}
			n, err := io.ReadFull(s.conn, dst[:limit])
			s.readLeft -= n
			if n > 0 {
				return n, nil
			}
			return 0, err
		}

		var header [5]byte
		if _, err := io.ReadFull(s.conn, header[:]); err != nil {
			return 0, err
		}
		recordLen := int(binary.BigEndian.Uint16(header[3:5]))
		switch header[0] {
		case tlsRecordCCS:
			if recordLen > 0 {
				if _, err := io.CopyN(io.Discard, s.conn, int64(recordLen)); err != nil {
					return 0, err
				}
			}
		case tlsRecordAppData:
			s.readLeft = recordLen
		default:
			if recordLen > 0 {
				_, _ = io.CopyN(io.Discard, s.conn, int64(recordLen))
			}
			return 0, io.EOF
		}
	}
}

func (s *fakeTLSStream) Write(data []byte) (int, error) {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if len(data) == 0 {
		return 0, nil
	}
	if err := writeFull(s.conn, wrapFakeTLSRecords(data)); err != nil {
		return 0, err
	}
	return len(data), nil
}

func (s *fakeTLSStream) Close() error {
	return s.conn.Close()
}

func (s *fakeTLSStream) LocalAddr() net.Addr {
	return s.conn.LocalAddr()
}

func (s *fakeTLSStream) RemoteAddr() net.Addr {
	return s.conn.RemoteAddr()
}

func (s *fakeTLSStream) SetDeadline(t time.Time) error {
	return s.conn.SetDeadline(t)
}

func (s *fakeTLSStream) SetReadDeadline(t time.Time) error {
	return s.conn.SetReadDeadline(t)
}

func (s *fakeTLSStream) SetWriteDeadline(t time.Time) error {
	return s.conn.SetWriteDeadline(t)
}

func NormalizeFakeTLSDomain(raw string) string {
	value := strings.ToLower(strings.TrimSpace(raw))
	value = strings.TrimPrefix(value, "https://")
	value = strings.TrimPrefix(value, "http://")
	if idx := strings.IndexAny(value, "/?#"); idx >= 0 {
		value = value[:idx]
	}
	if host, _, err := net.SplitHostPort(value); err == nil {
		value = host
	} else if idx := strings.LastIndex(value, ":"); idx >= 0 && !strings.Contains(value, "]") {
		value = value[:idx]
	}
	value = strings.Trim(value, ".")
	if !IsValidFakeTLSDomain(value) {
		return ""
	}
	return value
}

func IsValidFakeTLSDomain(domain string) bool {
	domain = strings.ToLower(strings.TrimSpace(domain))
	if domain == "" || len(domain) > 253 || strings.ContainsAny(domain, " ,;@/\\") {
		return false
	}
	if net.ParseIP(domain) != nil || !strings.Contains(domain, ".") {
		return false
	}
	for _, label := range strings.Split(domain, ".") {
		if len(label) == 0 || len(label) > 63 ||
			strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return false
		}
		for _, ch := range label {
			if ch < 'a' || ch > 'z' {
				if ch < '0' || ch > '9' {
					if ch != '-' {
						return false
					}
				}
			}
		}
	}
	return true
}
