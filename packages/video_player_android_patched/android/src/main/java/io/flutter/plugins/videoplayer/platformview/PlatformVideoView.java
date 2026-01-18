// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.platformview;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import io.flutter.plugin.platform.PlatformView;

/**
 * A class used to create a native video view that can be embedded in a Flutter app. It wraps an
 * {@link ExoPlayer} instance and displays its video content.
 */
public final class PlatformVideoView implements PlatformView {
  @NonNull private final ExoPlayer exoPlayer;
  @NonNull private final FrameLayout containerView;
  @NonNull private final SurfaceView surfaceView;
  @NonNull private final TextView subtitleView;
  @NonNull private final androidx.media3.common.Player.Listener subtitleListener;

  /**
   * Constructs a new PlatformVideoView.
   *
   * @param context The context in which the view is running.
   * @param exoPlayer The ExoPlayer instance used to play the video.
   */
  @OptIn(markerClass = UnstableApi.class)
  public PlatformVideoView(@NonNull Context context, @NonNull ExoPlayer exoPlayer) {
    this.exoPlayer = exoPlayer;
    containerView = new FrameLayout(context);
    surfaceView = new SurfaceView(context);
    containerView.addView(
        surfaceView,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    subtitleView = new TextView(context);
    subtitleView.setTextColor(Color.WHITE);
    subtitleView.setShadowLayer(6f, 2f, 2f, Color.BLACK);
    subtitleView.setTextSize(18);
    subtitleView.setGravity(android.view.Gravity.CENTER);
    subtitleView.setPadding(24, 8, 24, 24);
    subtitleView.setVisibility(View.GONE);
    containerView.addView(
        subtitleView,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL));

    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
      // Workaround for rendering issues on Android 9 (API 28).
      // On Android 9, using setVideoSurfaceView seems to lead to issues where the first frame is
      // not displayed if the video is paused initially.
      // To ensure the first frame is visible, the surface is directly set using holder.getSurface()
      // when the surface is created, and ExoPlayer seeks to a position to force rendering of the
      // first frame.
      setupSurfaceWithCallback(exoPlayer);
    } else {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
        // Avoid blank space instead of a video on Android versions below 8 by adjusting video's
        // z-layer within the Android view hierarchy:
        surfaceView.setZOrderMediaOverlay(true);
      }
      exoPlayer.setVideoSurfaceView(surfaceView);
    }

    subtitleListener =
        new androidx.media3.common.Player.Listener() {
          @Override
          public void onCues(@NonNull androidx.media3.common.text.CueGroup cueGroup) {
            final String text = cueGroupToText(cueGroup);
            subtitleView.setText(text);
            subtitleView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
          }
        };
    exoPlayer.addListener(subtitleListener);
  }

  private void setupSurfaceWithCallback(@NonNull ExoPlayer exoPlayer) {
    surfaceView
        .getHolder()
        .addCallback(
            new SurfaceHolder.Callback() {
              @Override
              public void surfaceCreated(@NonNull SurfaceHolder holder) {
                exoPlayer.setVideoSurface(holder.getSurface());
                // Force first frame rendering:
                exoPlayer.seekTo(1);
              }

              @Override
              public void surfaceChanged(
                  @NonNull SurfaceHolder holder, int format, int width, int height) {
                // No implementation needed.
              }

              @Override
              public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                exoPlayer.setVideoSurface(null);
              }
            });
  }

  /**
   * Returns the view associated with this PlatformView.
   *
   * @return The SurfaceView used to display the video.
   */
  @NonNull
  @Override
  public View getView() {
    return containerView;
  }

  /** Disposes of the resources used by this PlatformView. */
  @Override
  public void dispose() {
    exoPlayer.removeListener(subtitleListener);
    surfaceView.getHolder().getSurface().release();
  }

  private static String cueGroupToText(@NonNull androidx.media3.common.text.CueGroup cueGroup) {
    final StringBuilder sb = new StringBuilder();
    for (final androidx.media3.common.text.Cue cue : cueGroup.cues) {
      if (cue.text == null) {
        continue;
      }
      final String line = cue.text.toString().trim();
      if (line.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(line);
    }
    return sb.toString();
  }
}
