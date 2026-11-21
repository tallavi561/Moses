package com.moses.monitor.ai;

public final class Urls {

    private Urls() {
    }

    /**
     * Render's {@code fromService} "hostport" property yields a bare "host:port" with no scheme
     * (unlike the "http://host:port" local default), which URI builders would otherwise reject.
     */
    public static String normalize(String url) {
        if (url == null || url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "http://" + url;
    }
}
