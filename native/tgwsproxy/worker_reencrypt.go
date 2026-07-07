package main

import (
	"bytes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"fmt"
)

const (
	workerReencryptBridgeEnabled = true

	obfsHandshakeLen = 64
	obfsSkipLen      = 8
	obfsKeyLen       = 32
	obfsIVLen        = 16
	obfsProtoPos     = 56
	obfsDCPos        = 60
)

var (
	obfsReservedFirstBytes = map[byte]bool{0xef: true}
	obfsReservedStarts     = [][]byte{
		[]byte("HEAD"),
		[]byte("POST"),
		[]byte("GET "),
		{0xee, 0xee, 0xee, 0xee},
		{0xdd, 0xdd, 0xdd, 0xdd},
		{0x16, 0x03, 0x01, 0x02},
	}
	obfsReservedContinue = []byte{0x00, 0x00, 0x00, 0x00}
)

type workerReencryptContext struct {
	relayInit []byte
	clientDec cipher.Stream
	clientEnc cipher.Stream
	relayEnc  cipher.Stream
	relayDec  cipher.Stream
}

func initProtoFromObfsInit(init []byte) (uint32, bool) {
	if len(init) < obfsHandshakeLen {
		return 0, false
	}
	stream, err := newAESCTR(init[obfsSkipLen:obfsSkipLen+obfsKeyLen], init[obfsSkipLen+obfsKeyLen:obfsSkipLen+obfsKeyLen+obfsIVLen])
	if err != nil {
		return 0, false
	}
	ks := make([]byte, obfsHandshakeLen)
	stream.XORKeyStream(ks, zero64)
	plain := make([]byte, 8)
	for i := 0; i < 8; i++ {
		plain[i] = init[obfsProtoPos+i] ^ ks[obfsProtoPos+i]
	}
	proto := binary.LittleEndian.Uint32(plain[0:4])
	if !validProtos[proto] {
		return 0, false
	}
	return proto, true
}

func generateRelayObfsInit(proto uint32, signedDC int16) ([]byte, error) {
	if !validProtos[proto] {
		return nil, fmt.Errorf("unsupported proto 0x%08x", proto)
	}
	init := make([]byte, obfsHandshakeLen)
	for {
		if _, err := rand.Read(init); err != nil {
			return nil, err
		}
		if obfsReservedFirstBytes[init[0]] {
			continue
		}
		reserved := false
		for _, prefix := range obfsReservedStarts {
			if bytes.Equal(init[:4], prefix) {
				reserved = true
				break
			}
		}
		if reserved || bytes.Equal(init[4:8], obfsReservedContinue) {
			continue
		}
		break
	}

	plainTail := make([]byte, 8)
	binary.LittleEndian.PutUint32(plainTail[0:4], proto)
	binary.LittleEndian.PutUint16(plainTail[4:6], uint16(signedDC))
	if _, err := rand.Read(plainTail[6:8]); err != nil {
		return nil, err
	}

	stream, err := newAESCTR(init[obfsSkipLen:obfsSkipLen+obfsKeyLen], init[obfsSkipLen+obfsKeyLen:obfsSkipLen+obfsKeyLen+obfsIVLen])
	if err != nil {
		return nil, err
	}
	ks := make([]byte, obfsHandshakeLen)
	stream.XORKeyStream(ks, zero64)
	for i := 0; i < 8; i++ {
		init[obfsProtoPos+i] = ks[obfsProtoPos+i] ^ plainTail[i]
	}
	return init, nil
}

func reverseBytes(in []byte) []byte {
	out := make([]byte, len(in))
	for i := range in {
		out[i] = in[len(in)-1-i]
	}
	return out
}

func newWorkerReencryptContext(clientInit []byte, dc int, isMedia bool) (*workerReencryptContext, error) {
	if len(clientInit) < obfsHandshakeLen {
		return nil, fmt.Errorf("client init too short")
	}
	if dc <= 0 || dc > 32767 {
		return nil, fmt.Errorf("invalid dc %d", dc)
	}
	proto, ok := initProtoFromObfsInit(clientInit)
	if !ok {
		return nil, fmt.Errorf("client init proto unsupported")
	}

	signedDC := int16(dc)
	if isMedia {
		signedDC = -signedDC
	}
	relayInit, err := generateRelayObfsInit(proto, signedDC)
	if err != nil {
		return nil, err
	}

	clientDec, err := newAESCTR(clientInit[obfsSkipLen:obfsSkipLen+obfsKeyLen], clientInit[obfsSkipLen+obfsKeyLen:obfsSkipLen+obfsKeyLen+obfsIVLen])
	if err != nil {
		return nil, err
	}
	skip := make([]byte, obfsHandshakeLen)
	clientDec.XORKeyStream(skip, zero64)

	clientEncKeyIV := reverseBytes(clientInit[obfsSkipLen : obfsSkipLen+obfsKeyLen+obfsIVLen])
	clientEnc, err := newAESCTR(clientEncKeyIV[:obfsKeyLen], clientEncKeyIV[obfsKeyLen:])
	if err != nil {
		return nil, err
	}

	relayEnc, err := newAESCTR(relayInit[obfsSkipLen:obfsSkipLen+obfsKeyLen], relayInit[obfsSkipLen+obfsKeyLen:obfsSkipLen+obfsKeyLen+obfsIVLen])
	if err != nil {
		return nil, err
	}
	relayEnc.XORKeyStream(skip, zero64)

	relayDecKeyIV := reverseBytes(relayInit[obfsSkipLen : obfsSkipLen+obfsKeyLen+obfsIVLen])
	relayDec, err := newAESCTR(relayDecKeyIV[:obfsKeyLen], relayDecKeyIV[obfsKeyLen:])
	if err != nil {
		return nil, err
	}

	return &workerReencryptContext{
		relayInit: relayInit,
		clientDec: clientDec,
		clientEnc: clientEnc,
		relayEnc:  relayEnc,
		relayDec:  relayDec,
	}, nil
}

func (c *workerReencryptContext) clientToRelay(chunk []byte) []byte {
	plain := make([]byte, len(chunk))
	c.clientDec.XORKeyStream(plain, chunk)
	out := make([]byte, len(chunk))
	c.relayEnc.XORKeyStream(out, plain)
	return out
}

func (c *workerReencryptContext) relayToClient(chunk []byte) []byte {
	plain := make([]byte, len(chunk))
	c.relayDec.XORKeyStream(plain, chunk)
	out := make([]byte, len(chunk))
	c.clientEnc.XORKeyStream(out, plain)
	return out
}
