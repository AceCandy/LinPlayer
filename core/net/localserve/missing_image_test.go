package localserve

import (
	"net/http"
	"net/url"
	"sync/atomic"
	"testing"
)

func TestImageNotFoundKeepsStatusAndCanRecover(t *testing.T) {
	var ready atomic.Bool
	var hits atomic.Int32
	s, up := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if !ready.Load() {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write(pngBytes)
	})
	s.AllowEmby(up.URL, nil)
	route := "/img?src=" + url.QueryEscape(up.URL+"/Items/missing/Images/Backdrop/0")
	if got := get(t, s, route, true).StatusCode; got != http.StatusNotFound {
		t.Fatalf("missing image status = %d, want 404 (not a retryable gateway error)", got)
	}
	if hits.Load() != 1 {
		t.Fatalf("missing image fetched %d times", hits.Load())
	}
	ready.Store(true)
	if got := get(t, s, route, true).StatusCode; got != http.StatusOK {
		t.Fatalf("recovered image status = %d", got)
	}
	if got := get(t, s, route, true).Header.Get("X-LP-Cache"); got != "hit" || hits.Load() != 2 {
		t.Fatal("recovered image should use the existing cache")
	}
}

func TestImageUpstreamFailureRemainsGatewayError(t *testing.T) {
	for _, status := range []int{http.StatusForbidden, http.StatusInternalServerError, http.StatusServiceUnavailable} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			s, up := newTestServer(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status) })
			s.AllowEmby(up.URL, nil)
			route := "/img?src=" + url.QueryEscape(up.URL+"/Items/failure/Images/Primary")
			if got := get(t, s, route, true).StatusCode; got != http.StatusBadGateway {
				t.Fatalf("upstream %d mapped to %d, want 502", status, got)
			}
		})
	}
}
