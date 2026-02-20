package com.linplayer.tvlegacy;

public final class Episode {
    public final String id;
    public final int index;
    public final String title;
    public final String mediaUrl;

    public Episode(String id, int index, String title, String mediaUrl) {
        this.id = id;
        this.index = index;
        this.title = title;
        this.mediaUrl = mediaUrl;
    }
}
