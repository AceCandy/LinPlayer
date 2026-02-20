package com.linplayer.tvlegacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.linplayer.tvlegacy.backend.Backends;
import com.linplayer.tvlegacy.backend.Callback;
import java.util.List;

public final class MainActivity extends AppCompatActivity {
    private TextView proxyStatusText;

    private final BroadcastReceiver statusReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!ProxyService.ACTION_STATUS.equals(intent.getAction())) return;
                    String status = intent.getStringExtra(ProxyService.EXTRA_STATUS);
                    if (status == null) status = "unknown";
                    updateProxyStatus(status);
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        proxyStatusText = findViewById(R.id.proxy_status_text);
        updateProxyStatus(AppPrefs.getLastStatus(this));

        Button settingsBtn = findViewById(R.id.btn_open_settings);
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        RecyclerView showList = findViewById(R.id.show_list);
        int spanCount = 5;
        showList.setLayoutManager(new GridLayoutManager(this, spanCount));
        int spacingPx = dpToPx(12);
        showList.addItemDecoration(new GridSpacingItemDecoration(spanCount, spacingPx, true));

        Backends.media(this)
                .listShows(
                        new Callback<List<Show>>() {
                            @Override
                            public void onSuccess(List<Show> shows) {
                                if (isFinishing() || isDestroyed()) return;
                                showList.setAdapter(
                                        new ShowAdapter(
                                                shows,
                                                show -> {
                                                    Intent i =
                                                            new Intent(
                                                                    MainActivity.this,
                                                                    ShowDetailActivity.class);
                                                    i.putExtra(
                                                            ShowDetailActivity.EXTRA_SHOW_ID,
                                                            show.id);
                                                    startActivity(i);
                                                }));
                            }

                            @Override
                            public void onError(Throwable error) {
                                if (isFinishing() || isDestroyed()) return;
                                updateProxyStatus("load shows failed: " + error.getMessage());
                            }
                        });

        if (AppPrefs.isProxyEnabled(this)) {
            ProxyService.start(this);
        } else {
            ProxyEnv.disable();
        }
    }

    private void updateProxyStatus(String status) {
        if (proxyStatusText == null) return;
        boolean enabled = AppPrefs.isProxyEnabled(this);
        String s = status != null ? status : "unknown";
        proxyStatusText.setText("Proxy: " + (enabled ? "ON" : "OFF") + " · " + s);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ProxyService.ACTION_STATUS);
        ContextCompat.registerReceiver(
                this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(statusReceiver);
    }
}
