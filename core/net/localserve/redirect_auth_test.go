package localserve

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestImageRedirectKeepsSameOriginButStripsCrossOriginCredentials(t *testing.T) {
	var origin *httptest.Server
	foreign := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "" || r.Header.Get("X-Emby-Authorization") != "" {
			t.Error("credentials leaked to external image origin")
		}
		http.Redirect(w, r, origin.URL+"/returned", 302)
	}))
	defer foreign.Close()
	origin = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/same":
			http.Redirect(w, r, "/image", 301)
		case "/cross":
			http.Redirect(w, r, foreign.URL, 302)
		case "/image":
			if r.Header.Get("X-Emby-Token") != "fixture" {
				t.Error("same-origin authentication lost")
			}
			_, _ = w.Write([]byte("image"))
		case "/returned":
			if r.Header.Get("X-Emby-Token") != "" {
				t.Error("credentials restored after external redirect")
			}
			_, _ = w.Write([]byte("image"))
		}
	}))
	defer origin.Close()
	s := &Server{Client: origin.Client()}
	for _, path := range []string{"/same", "/cross"} {
		_, err := s.fetch(context.Background(), origin.URL+path, http.Header{"X-Emby-Token": {"fixture"}, "X-Emby-Authorization": {"fixture"}})
		if err != nil {
			t.Fatal(err)
		}
	}
}
