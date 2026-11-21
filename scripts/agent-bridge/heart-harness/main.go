// Command harness boots anytype-heart in-process through the same
// clientlibrary/service byte-level entry points gomobile exposes to Android
// (protobuf request bytes in, protobuf response bytes out), creates a fresh
// local-only account, opens the JSON API on 127.0.0.1:0 via
// Rpc.Account.ChangeJsonApiAddr, mints a JsonAPI key via LocalLink.CreateApp,
// prints BASE_URL / API_KEY / APP_HASH / CONTROL_URL lines on stdout and keeps
// serving.
//
// A control HTTP server (CONTROL_URL) exposes the RPCs for tests; every
// endpoint returns the RPC response as JSON (gogo jsonpb, original field names):
//
//	POST /changeaddr?addr=127.0.0.1:0      Rpc.Account.ChangeJsonApiAddr
//	POST /createapp?name=x&perm=rw|r&spaces=id1,id2
//	                                       Rpc.Account.LocalLink.CreateApp (scope JsonAPI); empty spaces = all spaces;
//	                                       returns {"appKey","appHash"}
//	POST /createspace?name=x               Rpc.Workspace.Create; returns {"spaceId"}
//	GET  /listapps                         Rpc.Account.LocalLink.ListApps
//	POST /revoke?hash=<appHash>            Rpc.Account.LocalLink.RevokeApp
//	GET  /events                           Account.JsonApiStatus events seen so far
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"

	"github.com/gogo/protobuf/jsonpb"
	"github.com/gogo/protobuf/proto"
	"github.com/gogo/protobuf/types"

	"github.com/anyproto/anytype-heart/clientlibrary/service"
	"github.com/anyproto/anytype-heart/pb"
	"github.com/anyproto/anytype-heart/pkg/lib/pb/model"
)

var (
	evMu   sync.Mutex
	events []string
	jm     = &jsonpb.Marshaler{OrigName: true, EmitDefaults: true}
)

func js(m proto.Message) string {
	s, err := jm.MarshalToString(m)
	if err != nil {
		return fmt.Sprintf("{\"marshal_error\":%q}", err.Error())
	}
	return s
}

// call marshals req, invokes the gomobile-style function, unmarshals into resp.
func call(fn func([]byte) []byte, req proto.Message, resp proto.Message) {
	b, err := proto.Marshal(req)
	if err != nil {
		log.Fatalf("marshal %T: %v", req, err)
	}
	if err := proto.Unmarshal(fn(b), resp); err != nil {
		log.Fatalf("unmarshal %T: %v", resp, err)
	}
}

func changeAddr(addr string) *pb.RpcAccountChangeJsonApiAddrResponse {
	r := &pb.RpcAccountChangeJsonApiAddrResponse{}
	call(service.AccountChangeJsonApiAddr, &pb.RpcAccountChangeJsonApiAddrRequest{ListenAddr: addr}, r)
	return r
}

func createApp(name string, perm model.AccountAuthAppGrantPerm, spaceIds []string) *pb.RpcAccountLocalLinkCreateAppResponse {
	r := &pb.RpcAccountLocalLinkCreateAppResponse{}
	grant := &model.AccountAuthAppGrant{AllSpaces: len(spaceIds) == 0, SpaceIds: spaceIds, Perm: perm}
	call(service.AccountLocalLinkCreateApp, &pb.RpcAccountLocalLinkCreateAppRequest{App: &model.AccountAuthAppInfo{
		AppName: name,
		Scope:   model.AccountAuth_JsonAPI,
		Grant:   grant,
	}}, r)
	return r
}

func createSpace(name string) *pb.RpcWorkspaceCreateResponse {
	r := &pb.RpcWorkspaceCreateResponse{}
	call(service.WorkspaceCreate, &pb.RpcWorkspaceCreateRequest{Details: &types.Struct{Fields: map[string]*types.Value{
		"name": {Kind: &types.Value_StringValue{StringValue: name}},
	}}}, r)
	return r
}

func listApps() *pb.RpcAccountLocalLinkListAppsResponse {
	r := &pb.RpcAccountLocalLinkListAppsResponse{}
	call(service.AccountLocalLinkListApps, &pb.RpcAccountLocalLinkListAppsRequest{}, r)
	return r
}

func revoke(hash string) *pb.RpcAccountLocalLinkRevokeAppResponse {
	r := &pb.RpcAccountLocalLinkRevokeAppResponse{}
	call(service.AccountLocalLinkRevokeApp, &pb.RpcAccountLocalLinkRevokeAppRequest{AppHash: hash}, r)
	return r
}

func main() {
	root := flag.String("root", "", "data root (default: fresh temp dir)")
	name := flag.String("app-name", "harness", "app name for the minted key")
	flag.Parse()
	log.SetOutput(os.Stderr)
	log.SetPrefix("[harness] ")

	if *root == "" {
		d, err := os.MkdirTemp("", "heart-harness-")
		if err != nil {
			log.Fatal(err)
		}
		*root = d
	}
	log.Printf("root dir: %s", *root)

	service.SetEventHandler(func(e *pb.Event) {
		for _, m := range e.Messages {
			if st := m.GetAccountJsonApiStatus(); st != nil {
				s := js(st)
				log.Printf("event Account.JsonApiStatus: %s", s)
				evMu.Lock()
				events = append(events, s)
				evMu.Unlock()
			}
		}
	})

	ir := &pb.RpcInitialSetParametersResponse{}
	call(service.InitialSetParameters, &pb.RpcInitialSetParametersRequest{
		Platform: "android", Version: "0.0.0-harness", Workdir: *root, LogLevel: os.Getenv("ANYTYPE_LOG_LEVEL"),
		DoNotSendLogs: true, DoNotSendTelemetry: true,
	}, ir)
	log.Printf("InitialSetParameters: %s", js(ir))

	wr := &pb.RpcWalletCreateResponse{}
	call(service.WalletCreate, &pb.RpcWalletCreateRequest{RootPath: *root}, wr)
	if wr.GetError().GetCode() != 0 {
		log.Fatalf("WalletCreate: %s", js(wr))
	}
	log.Printf("WalletCreate ok (mnemonic %d words)", len(strings.Fields(wr.Mnemonic)))

	ar := &pb.RpcAccountCreateResponse{}
	call(service.AccountCreate, &pb.RpcAccountCreateRequest{
		Name: "harness", StorePath: *root, NetworkMode: pb.RpcAccount_LocalOnly, DisableLocalNetworkSync: true,
		// JsonApiListenAddr deliberately empty: the API is opened by ChangeJsonApiAddr below.
	}, ar)
	if ar.GetError().GetCode() != 0 {
		log.Fatalf("AccountCreate: %s", js(ar))
	}
	log.Printf("AccountCreate ok: account id %s", ar.Account.Id)

	cr := changeAddr("127.0.0.1:0")
	fmt.Println("CHANGE_ADDR_RESPONSE " + js(cr))
	if cr.GetError().GetCode() != 0 || cr.Status == nil || !cr.Status.Success {
		log.Fatalf("ChangeJsonApiAddr failed")
	}
	app := createApp(*name, model.AccountAuthAppGrant_ReadWrite, nil)
	fmt.Println("CREATE_APP_RESPONSE " + js(app))
	if app.GetError().GetCode() != 0 {
		log.Fatalf("CreateApp failed")
	}
	la := listApps()
	fmt.Println("LIST_APPS_RESPONSE " + js(la))
	hash := ""
	for _, a := range la.App {
		if a.AppName == *name {
			hash = a.AppHash
		}
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Fatal(err)
	}
	mux := http.NewServeMux()
	write := func(w http.ResponseWriter, s string) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintln(w, s)
	}
	mux.HandleFunc("/changeaddr", func(w http.ResponseWriter, r *http.Request) {
		write(w, js(changeAddr(r.URL.Query().Get("addr"))))
	})
	mux.HandleFunc("/createapp", func(w http.ResponseWriter, r *http.Request) {
		perm := model.AccountAuthAppGrant_ReadWrite
		if r.URL.Query().Get("perm") == "r" {
			perm = model.AccountAuthAppGrant_Read
		}
		var spaceIds []string
		if sp := r.URL.Query().Get("spaces"); sp != "" {
			spaceIds = strings.Split(sp, ",")
		}
		app := createApp(r.URL.Query().Get("name"), perm, spaceIds)
		if app.GetError().GetCode() != 0 {
			write(w, js(app))
			return
		}
		hash := ""
		for _, a := range listApps().App {
			if a.AppName == r.URL.Query().Get("name") {
				hash = a.AppHash
			}
		}
		out, _ := json.Marshal(map[string]string{"appKey": app.AppKey, "appHash": hash})
		write(w, string(out))
	})
	mux.HandleFunc("/createspace", func(w http.ResponseWriter, r *http.Request) {
		sr := createSpace(r.URL.Query().Get("name"))
		if sr.GetError().GetCode() != 0 {
			write(w, js(sr))
			return
		}
		out, _ := json.Marshal(map[string]string{"spaceId": sr.SpaceId})
		write(w, string(out))
	})
	mux.HandleFunc("/listapps", func(w http.ResponseWriter, r *http.Request) { write(w, js(listApps())) })
	mux.HandleFunc("/revoke", func(w http.ResponseWriter, r *http.Request) {
		write(w, js(revoke(r.URL.Query().Get("hash"))))
	})
	mux.HandleFunc("/events", func(w http.ResponseWriter, r *http.Request) {
		evMu.Lock()
		defer evMu.Unlock()
		write(w, "["+strings.Join(events, ",")+"]")
	})
	go func() { log.Fatal(http.Serve(ln, mux)) }()

	fmt.Println("ROOT_DIR=" + *root)
	fmt.Println("BASE_URL=http://" + cr.Status.ListenAddr)
	fmt.Println("API_KEY=" + app.AppKey)
	fmt.Println("APP_HASH=" + hash)
	fmt.Println("CONTROL_URL=http://" + ln.Addr().String())
	fmt.Println("SPACE_ID=" + ar.Account.Info.AccountSpaceId)
	fmt.Println("READY")

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	log.Printf("stopping")
	sr := &pb.RpcAccountStopResponse{}
	call(service.AccountStop, &pb.RpcAccountStopRequest{}, sr)
	log.Printf("AccountStop: %s", js(sr))
}
