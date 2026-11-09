package main

import (
	"encoding/binary"
	"io"
	"net"
)

// cfClientHelloPoCProfile is intentionally selected at build time. The zero
// value preserves the existing production transport and TLS configuration.
type cfClientHelloPoCProfile struct {
	tcpSplit           bool
	verifyCertificates bool
}

// cfClientHelloTCPWriteSplitter changes only the first outgoing TLS write.
// It preserves the ClientHello bytes; two net.Conn.Write calls do not prove
// that the kernel emitted two distinct TCP segments.
type cfClientHelloTCPWriteSplitter struct {
	net.Conn
	firstWrite bool
}

func newCFClientHelloTCPWriteSplitter(conn net.Conn) *cfClientHelloTCPWriteSplitter {
	return &cfClientHelloTCPWriteSplitter{Conn: conn, firstWrite: true}
}

func (conn *cfClientHelloTCPWriteSplitter) Write(data []byte) (int, error) {
	if !conn.firstWrite {
		return conn.Conn.Write(data)
	}
	conn.firstWrite = false
	split, ok := cfClientHelloSNISplitOffset(data)
	if !ok {
		return conn.Conn.Write(data)
	}
	first, err := conn.Conn.Write(data[:split])
	if err != nil {
		return first, err
	}
	if first != split {
		return first, io.ErrShortWrite
	}
	second, err := conn.Conn.Write(data[split:])
	if err != nil {
		return first + second, err
	}
	if second != len(data)-split {
		return first + second, io.ErrShortWrite
	}
	if logDebug != nil {
		logDebug.Printf("CF Proxy PoC: ClientHello tcp_split performed two writes (TCP segments unverified)")
	}
	return first + second, nil
}

// cfClientHelloSNISplitOffset returns one byte into the first DNS hostname
// inside a complete TLS ClientHello record. Unknown or fragmented formats
// are passed through unchanged. No hostname or TLS payload is logged.
func cfClientHelloSNISplitOffset(record []byte) (int, bool) {
	if len(record) < 9 || record[0] != 22 || record[1] != 3 {
		return 0, false
	}
	recordSize := int(binary.BigEndian.Uint16(record[3:5]))
	if recordSize+5 != len(record) || record[5] != 1 {
		return 0, false
	}
	helloSize := int(record[6])<<16 | int(record[7])<<8 | int(record[8])
	if helloSize+4 != recordSize {
		return 0, false
	}
	end := len(record)
	pos := 9 + 2 + 32 // legacy_version and random
	if pos >= end {
		return 0, false
	}
	// Session ID, cipher suites and compression methods are length-prefixed.
	sessionSize := int(record[pos])
	pos++
	if sessionSize > end-pos {
		return 0, false
	}
	pos += sessionSize
	ciphers, ok := cfTLSReadUint16(record, &pos, end)
	if !ok || ciphers > end-pos {
		return 0, false
	}
	pos += ciphers
	if pos >= end {
		return 0, false
	}
	compressionSize := int(record[pos])
	pos++
	if compressionSize > end-pos {
		return 0, false
	}
	pos += compressionSize
	extensionsSize, ok := cfTLSReadUint16(record, &pos, end)
	if !ok || extensionsSize != end-pos {
		return 0, false
	}
	for pos < end {
		extensionType, valid := cfTLSReadUint16(record, &pos, end)
		if !valid {
			return 0, false
		}
		extensionSize, valid := cfTLSReadUint16(record, &pos, end)
		if !valid || extensionSize > end-pos {
			return 0, false
		}
		extensionEnd := pos + extensionSize
		if extensionType == 0 {
			namesSize, valid := cfTLSReadUint16(record, &pos, extensionEnd)
			if !valid || namesSize != extensionEnd-pos {
				return 0, false
			}
			for pos < extensionEnd {
				if pos >= extensionEnd {
					return 0, false
				}
				nameType := record[pos]
				pos++
				nameSize, valid := cfTLSReadUint16(record, &pos, extensionEnd)
				if !valid || nameSize > extensionEnd-pos {
					return 0, false
				}
				if nameType == 0 && nameSize >= 2 {
					return pos + 1, true
				}
				pos += nameSize
			}
			return 0, false
		}
		pos = extensionEnd
	}
	return 0, false
}

func cfTLSReadUint16(data []byte, offset *int, limit int) (int, bool) {
	if *offset > limit || limit-*offset < 2 {
		return 0, false
	}
	value := int(binary.BigEndian.Uint16(data[*offset : *offset+2]))
	*offset += 2
	return value, true
}
