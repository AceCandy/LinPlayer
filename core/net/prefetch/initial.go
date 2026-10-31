package prefetch

import (
	"context"
	"net/http"
)

type initialResponse struct {
	response *http.Response
	cancel   context.CancelFunc
}

func (r *initialResponse) close() {
	r.cancel()
	_ = r.response.Body.Close()
}

func (o *origin) takeInitial() *initialResponse {
	o.upMu.Lock()
	defer o.upMu.Unlock()
	r := o.initial
	o.initial = nil
	return r
}

func (o *origin) closeInitial() {
	if r := o.takeInitial(); r != nil {
		r.close()
	}
}
