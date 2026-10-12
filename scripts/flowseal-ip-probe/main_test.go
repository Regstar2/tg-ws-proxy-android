package main

import (
 "bufio"
 "bytes"
 "testing"
)

func TestWorkerProbeValidation(t *testing.T){
 if !validDomain("black-mountain-bb46.vovasazuz.workers.dev") || validDomain("localhost") || validDomain("bad_.workers.dev"){t.Fatal("invalid Worker domain validation")}
 if !validAddress("104.21.9.19","tcp4") || !validAddress("2606:4700:3030::6815:913","tcp6"){t.Fatal("valid IP rejected")}
 if validAddress("104.21.9.19","tcp6") || validAddress("2606:4700:3030::6815:913","tcp4"){t.Fatal("IP family mismatch accepted")}
}
func TestHelloAndWebSocketFraming(t *testing.T){
 hello,err:=makeTLSClientHello()
 if err!=nil || len(hello)<100 || hello[0]!=0x16{t.Fatalf("bad TLS ClientHello: length=%d err=%v",len(hello),err)}
 wire,err:=maskedFrame(hello);if err!=nil{t.Fatal(err)}
 if wire[0]!=0x82 || wire[1]&0x80==0{t.Fatalf("unmasked or nonbinary frame: %x",wire[:2])}
 opcode,data,err:=readWSFrame(bufio.NewReader(bytes.NewReader(wire)))
 if err!=nil || opcode!=2 || !bytes.Equal(hello,data){t.Fatal("WebSocket masked binary data corrupted",err)}
}
func TestWebSocketFrameSizeLimit(t *testing.T){
 wire:=[]byte{0x82,0x7f,0,0,0,0,0,0,0x20,0}
 _,_,err:=readWSFrame(bufio.NewReader(bytes.NewReader(wire)))
 if err==nil{t.Fatal("oversized WS frame should be rejected")}
}
