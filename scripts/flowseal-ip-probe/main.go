// Android arm64 standalone diagnostic: compare IPv4/IPv6 WebSocket forwarding.
// No Telegram secrets, messages or user data; sends a new TLS ClientHello to www.google.com.
package main

import (
 "bufio"
 "bytes"
 "context"
 "crypto/rand"
 "crypto/sha1"
 "crypto/tls"
 "encoding/base64"
 "encoding/binary"
 "encoding/json"
 "errors"
 "flag"
 "fmt"
 "io"
 "net"
 "net/http"
 "os"
 "strings"
 "time"
)

type result struct {
 Network string `json:"network"`
 HelloMode string `json:"hello_mode"`
 DialAddress string `json:"dial_address,omitempty"`
 Phase string `json:"phase"`
 Remote string `json:"remote,omitempty"`
 TLSVersion string `json:"tls_version,omitempty"`
 UpgradeStatus int `json:"upgrade_status,omitempty"`
 ClientHelloBytes int `json:"client_hello_bytes,omitempty"`
 PongReceived bool `json:"pong_received"`
 ResponseBytes int `json:"response_bytes,omitempty"`
 DurationMS int64 `json:"duration_ms"`
 Error string `json:"error,omitempty"`
}

func main(){
 network:=flag.String("network","tcp4","tcp4 or tcp6")
 helloMode:=flag.String("hello","default","default or compact TLS ClientHello")
 host:=flag.String("worker","","Flowseal workers.dev hostname")
 ip:=flag.String("ip","","numeric IP from host-side DNS")
 timeout:=flag.Duration("timeout",15*time.Second,"overall timeout")
 flag.Parse()
 start:=time.Now()
 r:=result{Network:*network,HelloMode:*helloMode,Phase:"validate",DialAddress:*ip}
 if (*network!="tcp4" && *network!="tcp6") || !validDomain(*host) || !validAddress(*ip,*network) || (*helloMode!="default" && *helloMode!="compact") || *timeout<2*time.Second || *timeout>time.Minute {
  r.Error="invalid arguments: .workers.dev host, -ip of matching family, 2..60s timeout required"
 }else{ probe(&r,*network,*host,*ip,*timeout,*helloMode) }
 r.DurationMS=time.Since(start).Milliseconds()
 if err:=json.NewEncoder(os.Stdout).Encode(r);err!=nil{fmt.Fprintln(os.Stderr,err)}
}

func validDomain(host string)bool{
 if len(host)<13 || len(host)>253 || !strings.HasSuffix(host,".workers.dev"){return false}
 for _,c:=range host{if !((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='-'){return false}}
 return true
}
func validAddress(addr,network string)bool{
 ip:=net.ParseIP(addr);if ip==nil{return false}
 if network=="tcp4"{return ip.To4()!=nil}
 return ip.To4()==nil
}

func probe(r *result,network,domain,address string,timeout time.Duration,helloMode string){
 ctx,cancel:=context.WithTimeout(context.Background(),timeout);defer cancel()
 r.Phase="tcp_connect"
 raw,err:=(&net.Dialer{}).DialContext(ctx,network,net.JoinHostPort(address,"443"))
 if err!=nil{r.Error=err.Error();return}
 defer raw.Close()
 r.Remote=raw.RemoteAddr().String()
 deadline,_:=ctx.Deadline();_ =raw.SetDeadline(deadline)
 r.Phase="tls_handshake"
 conn:=tls.Client(raw,&tls.Config{ServerName:domain,NextProtos:[]string{"http/1.1"},MinVersion:tls.VersionTLS12})
 defer conn.Close()
 if err=conn.HandshakeContext(ctx);err!=nil{r.Error=err.Error();return}
 r.TLSVersion=tls.VersionName(conn.ConnectionState().Version)
 r.Phase="websocket_upgrade"
 var key [16]byte;if _,err=rand.Read(key[:]);err!=nil{r.Error=err.Error();return}
 secret:=base64.StdEncoding.EncodeToString(key[:])
 request:=fmt.Sprintf("GET /apiws?dst=www.google.com HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n",domain,secret)
 if _,err=io.WriteString(conn,request);err!=nil{r.Error=err.Error();return}
 reader:=bufio.NewReader(conn)
 resp,err:=http.ReadResponse(reader,&http.Request{Method:"GET"})
 if err!=nil{r.Error=err.Error();return}
 r.UpgradeStatus=resp.StatusCode
 if resp.StatusCode!=101{r.Error=resp.Status;return}
 accept:=sha1.Sum([]byte(secret+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
 if resp.Header.Get("Sec-WebSocket-Accept")!=base64.StdEncoding.EncodeToString(accept[:]) || !strings.EqualFold(resp.Header.Get("Upgrade"),"websocket"){
  r.Error="invalid RFC6455 upgrade response";return
 }
 r.Phase="send_ws_ping"
 pingPayload:=[]byte("tgws101")
 pingFrame,err:=maskedPing(pingPayload);if err!=nil{r.Error=err.Error();return}
 if _,err=conn.Write(pingFrame);err!=nil{r.Error=err.Error();return}
 r.Phase="send_tls_client_hello"
 hello,err:=makeTLSClientHelloMode(helloMode);if err!=nil{r.Error=err.Error();return}
 r.ClientHelloBytes=len(hello)
 frame,err:=maskedFrame(hello);if err!=nil{r.Error=err.Error();return}
 if _,err=conn.Write(frame);err!=nil{r.Error=err.Error();return}
 r.Phase="wait_worker_upstream_reply"
 for {
  opcode,payload,readErr:=readWSFrame(reader)
  if readErr!=nil{r.Error=readErr.Error();return}
  switch opcode {
  case 0x2,0x0:
   if len(payload)>0{r.ResponseBytes=len(payload);r.Phase="upstream_reply_received";return}
  case 0xa:
   if bytes.Equal(payload,pingPayload){r.PongReceived=true}
  case 0x8:r.Error="Worker closed WebSocket";return
  case 0x9:
   frame,err:=maskedPong(payload);if err!=nil{r.Error=err.Error();return}
   if _,err=conn.Write(frame);err!=nil{r.Error=err.Error();return}
  }
 }
}

type captureConn struct{b bytes.Buffer}
func (c *captureConn)Read([]byte)(int,error){return 0,io.EOF}
func (c *captureConn)Write(p []byte)(int,error){return c.b.Write(p)}
func (*captureConn)Close()error{return nil}
func (*captureConn)LocalAddr()net.Addr{return &net.TCPAddr{}}
func (*captureConn)RemoteAddr()net.Addr{return &net.TCPAddr{}}
func (*captureConn)SetDeadline(time.Time)error{return nil}
func (*captureConn)SetReadDeadline(time.Time)error{return nil}
func (*captureConn)SetWriteDeadline(time.Time)error{return nil}
func makeTLSClientHello()([]byte,error){
 return makeTLSClientHelloMode("default")
}
func makeTLSClientHelloMode(mode string)([]byte,error){
 if mode!="default" && mode!="compact" {return nil,errors.New("invalid TLS hello mode")}
 c:=&captureConn{}
 config:=&tls.Config{ServerName:"www.google.com",MinVersion:tls.VersionTLS12}
 if mode=="compact" {
  // X25519-only avoids Go's large hybrid PQ key share and isolates payload size.
  config.CurvePreferences=[]tls.CurveID{tls.X25519}
 }
 _=tls.Client(c,config).Handshake()
 data:=c.b.Bytes()
 if len(data)<100{return nil,errors.New("TLS ClientHello generation failed")}
 return append([]byte(nil),data...),nil
}

func maskedFrame(data []byte)([]byte,error){
 var buf bytes.Buffer;buf.WriteByte(0x82)
 n:=len(data)
 switch {
 case n<126:buf.WriteByte(0x80|byte(n))
 case n<65536:buf.WriteByte(0xfe);_ =binary.Write(&buf,binary.BigEndian,uint16(n))
 default:buf.WriteByte(0xff);_ =binary.Write(&buf,binary.BigEndian,uint64(n))
 }
 var key [4]byte;if _,err:=rand.Read(key[:]);err!=nil{return nil,err}
 buf.Write(key[:])
 for i,b:=range data{buf.WriteByte(b^key[i%4])}
 return buf.Bytes(),nil
}
func maskedPing(data []byte)([]byte,error){
 frame,err:=maskedPong(data)
 if err!=nil{return nil,err}
 frame[0]=0x89
 return frame,nil
}
func maskedPong(data []byte)([]byte,error){
 if len(data)>125{return nil,errors.New("invalid ping payload")}
 var key [4]byte;if _,err:=rand.Read(key[:]);err!=nil{return nil,err}
 out:=[]byte{0x8a,0x80|byte(len(data))};out=append(out,key[:]...)
 for i,b:=range data{out=append(out,b^key[i%4])}
 return out,nil
}
func readWSFrame(reader *bufio.Reader)(byte,[]byte,error){
 var h [2]byte;if _,err:=io.ReadFull(reader,h[:]);err!=nil{return 0,nil,err}
 n:=uint64(h[1]&0x7f)
 switch n{
 case 126:var l uint16;if err:=binary.Read(reader,binary.BigEndian,&l);err!=nil{return 0,nil,err};n=uint64(l)
 case 127:if err:=binary.Read(reader,binary.BigEndian,&n);err!=nil{return 0,nil,err}
 }
 if n>1024*1024{return 0,nil,errors.New("WS response exceeds 1 MiB")}
 var mask [4]byte;if h[1]&0x80!=0{if _,err:=io.ReadFull(reader,mask[:]);err!=nil{return 0,nil,err}}
 out:=make([]byte,int(n));if _,err:=io.ReadFull(reader,out);err!=nil{return 0,nil,err}
 if h[1]&0x80!=0{for i:=range out{out[i]^=mask[i%4]}}
 return h[0]&0x0f,out,nil
}
