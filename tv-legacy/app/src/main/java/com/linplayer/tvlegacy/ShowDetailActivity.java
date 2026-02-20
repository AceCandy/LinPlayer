package com.linplayer.tvlegacy;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.linplayer.tvlegacy.backend.Backends;
import com.linplayer.tvlegacy.backend.Callback;
import java.util.List;

public final class ShowDetailActivity extends AppCompatActivity {
    static final String EXTRA_SHOW_ID = "show_id";

    private String showId;
    private Show show;
    private Episode firstEpisode;

    private TextView titleText;
    private TextView overviewText;
    private Button playBtn;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_show_detail);

        showId = getIntent().getStringExtra(EXTRA_SHOW_ID);

        titleText = findViewById(R.id.show_title);
        overviewText = findViewById(R.id.show_overview);
        titleText.setText("Loading...");
        overviewText.setText("");

        Button backBtn = findViewById(R.id.btn_back);
        backBtn.setOnClickListener(v -> finish());

        Button episodesBtn = findViewById(R.id.btn_open_episodes);
        episodesBtn.setOnClickListener(
                v -> {
                    Intent i = new Intent(this, EpisodeListActivity.class);
                    i.putExtra(EpisodeListActivity.EXTRA_SHOW_ID, showId);
                    startActivity(i);
                });

        playBtn = findViewById(R.id.btn_play);
        playBtn.setOnClickListener(
                v -> {
                    Episode first = firstEpisode;
                    if (first == null) {
                        Toast.makeText(this, "Loading episodes...", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_TITLE, (show != null ? show.title : "Show") + " · " + first.title);
                    i.putExtra(PlayerActivity.EXTRA_URL, first.mediaUrl);
                    startActivity(i);
                });

        Backends.media(this)
                .getShow(
                        showId,
                        new Callback<Show>() {
                            @Override
                            public void onSuccess(Show v) {
                                if (isFinishing() || isDestroyed()) return;
                                show = v;
                                if (v == null) {
                                    titleText.setText("Unknown show");
                                    overviewText.setText("");
                                    return;
                                }
                                titleText.setText(v.title);
                                overviewText.setText(v.overview);
                            }

                            @Override
                            public void onError(Throwable error) {
                                if (isFinishing() || isDestroyed()) return;
                                titleText.setText("Load failed");
                                overviewText.setText(String.valueOf(error.getMessage()));
                            }
                        });

        Backends.media(this)
                .listEpisodes(
                        showId,
                        new Callback<List<Episode>>() {
                            @Override
                            public void onSuccess(List<Episode> episodes) {
                                if (isFinishing() || isDestroyed()) return;
                                if (episodes == null || episodes.isEmpty()) {
                                    firstEpisode = null;
                                    return;
                                }
                                firstEpisode = episodes.get(0);
                            }

                            @Override
                            public void onError(Throwable error) {
                                if (isFinishing() || isDestroyed()) return;
                                firstEpisode = null;
                            }
                        });
    }
}
