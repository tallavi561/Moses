package com.moses.monitor.ai;

final class Urls {

    private Urls() {
    }

    /**
     * Render's {@code fromService} "hostport" property yields a bare "host:port" with no scheme
     * (unlike the "http://host:port" local default), which URI builders would otherwise reject.
     */
    static String normalize(String url) {
        if (url == null || url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "http://" + url;
    }
}
