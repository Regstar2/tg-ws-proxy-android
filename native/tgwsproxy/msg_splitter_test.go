package main

import (
	"bytes"
	"testing"
)

type identityStream struct{}

func (identityStream) XORKeyStream(dst, src []byte) {
	copy(dst, src)
}

func TestMsgSplitterAbridgedSinglePacket(t *testing.T) {
	splitter := &MsgSplitter{stream: identityStream{}, proto: 0xEFEFEFEF}
	packet := append([]byte{2}, bytes.Repeat([]byte{0x11}, 8)...)

	parts := splitter.Split(packet)

	if len(parts) != 1 || !bytes.Equal(parts[0], packet) {
		t.Fatalf("single packet split = %d, want original packet", len(parts))
	}
}

func TestMsgSplitterAbridgedMultiplePackets(t *testing.T) {
	splitter := &MsgSplitter{stream: identityStream{}, proto: 0xEFEFEFEF}
	first := append([]byte{1}, bytes.Repeat([]byte{0x11}, 4)...)
	second := append([]byte{2}, bytes.Repeat([]byte{0x22}, 8)...)
	chunk := append(append([]byte{}, first...), second...)

	parts := splitter.Split(chunk)

	if len(parts) != 2 {
		t.Fatalf("parts = %d, want 2", len(parts))
	}
	if !bytes.Equal(parts[0], first) || !bytes.Equal(parts[1], second) {
		t.Fatal("splitter did not preserve abridged packet boundaries")
	}
}

func TestMsgSplitterAbridgedPartialPacket(t *testing.T) {
	splitter := &MsgSplitter{stream: identityStream{}, proto: 0xEFEFEFEF}
	firstHalf := append([]byte{4}, bytes.Repeat([]byte{0x11}, 8)...)

	parts := splitter.Split(firstHalf)

	if len(parts) != 0 {
		t.Fatalf("partial packet parts = %d, want 0", len(parts))
	}

	secondHalf := bytes.Repeat([]byte{0x22}, 8)
	parts = splitter.Split(secondHalf)
	packet := append(append([]byte{}, firstHalf...), secondHalf...)

	if len(parts) != 1 || !bytes.Equal(parts[0], packet) {
		t.Fatal("partial packet should be buffered until complete")
	}
}

func TestMsgSplitterUnknownProtocolDisablesSplitter(t *testing.T) {
	splitter := &MsgSplitter{stream: identityStream{}}
	chunk := []byte{0, 1, 2, 3, 4, 5}

	parts := splitter.Split(chunk)

	if len(parts) != 1 || !bytes.Equal(parts[0], chunk) {
		t.Fatal("unknown or invalid framing should keep the original chunk")
	}
}

func TestMsgSplitterFlushTail(t *testing.T) {
	splitter := &MsgSplitter{stream: identityStream{}, proto: 0xEFEFEFEF}
	first := append([]byte{1}, bytes.Repeat([]byte{0x11}, 4)...)
	tail := []byte{4, 0x33, 0x33}
	chunk := append(append([]byte{}, first...), tail...)

	parts := splitter.Split(chunk)

	if len(parts) != 1 {
		t.Fatalf("parts = %d, want complete packet only", len(parts))
	}
	if !bytes.Equal(parts[0], first) {
		t.Fatal("splitter should return complete packet")
	}
	flushed := splitter.Flush()
	if len(flushed) != 1 || !bytes.Equal(flushed[0], tail) {
		t.Fatal("splitter should flush trailing partial data")
	}
}

func TestMsgSplitterIntermediatePacket(t *testing.T) {
	splitter := &MsgSplitter{stream: identityStream{}, proto: 0xEEEEEEEE}
	payload := bytes.Repeat([]byte{0x44}, 12)
	packet := append([]byte{12, 0, 0, 0}, payload...)

	parts := splitter.Split(packet)

	if len(parts) != 1 || !bytes.Equal(parts[0], packet) {
		t.Fatal("intermediate packet split should preserve original packet")
	}
}
