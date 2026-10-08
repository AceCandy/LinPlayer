package player

import (
	"encoding/json"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
	"strings"
	"testing"
)

func TestMediaCacheIdentityAcrossLinesTokensAndAccounts(t *testing.T) {
	paths.SetRoot(t.TempDir())
	c, e := config.Load()
	if e != nil {
		t.Fatal(e)
	}
	c.AccountList = []config.Account{{Server: "https://example.invalid", UserID: "user-one", Lines: []config.ServerLine{{URL: "https://line.invalid"}}}}
	target := &emby.PlaybackTarget{ItemID: "item-one", MediaSourceID: "source-one", Bitrate: 16000000}
	session := &emby.Session{Server: "https://example.invalid", UserID: "user-one", Token: "first"}
	first := mediaCacheOptions(session, target)
	session.Server = "https://line.invalid/"
	session.Token = "renewed"
	if got := mediaCacheOptions(session, target); got.Identity != first.Identity || got.Bitrate != 16000000 {
		t.Fatal("line or token invalidated stable identity")
	}
	for _, change := range []string{"user", "server", "item", "source"} {
		s := *session
		v := *target
		switch change {
		case "user":
			s.UserID = "user-two"
		case "server":
			s.Server = "https://other.invalid"
		case "item":
			v.ItemID = "item-two"
		case "source":
			v.MediaSourceID = "source-two"
		}
		if mediaCacheOptions(&s, &v).Identity == first.Identity {
			t.Fatalf("identity did not isolate %s", change)
		}
	}
	b, _ := json.Marshal(first)
	for _, raw := range []string{session.Token, session.Server, session.UserID, target.ItemID, target.MediaSourceID} {
		if strings.Contains(string(b), raw) {
			t.Fatal("options contain raw identity")
		}
	}
}
