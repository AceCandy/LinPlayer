//go:build ios && tvos

package player

/*
#include <stdint.h>

// tvOS 播放由 AetherEngine 接管；这些桩只满足共享命令包的链接，不提供 mpv 播放能力。
void *mpv_create(void) { return 0; }
int mpv_initialize(void *ctx) { return -1; }
int mpv_set_option_string(void *ctx, const char *name, const char *value) { return -1; }
int mpv_set_property_string(void *ctx, const char *name, const char *value) { return -1; }
int mpv_command(void *ctx, const char **args) { return -1; }
char *mpv_get_property_string(void *ctx, const char *name) { return 0; }
void mpv_free(void *value) {}
void *mpv_wait_event(void *ctx, double timeout) { return 0; }
void mpv_terminate_destroy(void *ctx) {}
void mpv_request_log_messages(void *ctx, const char *level) {}
int mpv_render_context_create(void **out, void *ctx, void *params) { return -1; }
int mpv_render_context_render(void *ctx, void *params) { return -1; }
int mpv_render_context_get_info(void *ctx, void *params) { return -1; }
long long mpv_get_time_us(void *ctx) { return 0; }
long long mpv_get_time_ns(void *ctx) { return 0; }
uint64_t mpv_render_context_update(void *ctx) { return 0; }
void mpv_render_context_report_swap(void *ctx) {}
void mpv_render_context_free(void *ctx) {}
*/
import "C"
