package org.famical.calendar;

import java.net.URI;
import java.net.URISyntaxException;

final class ServerAddress {
    static final String DEFAULT_SERVER = "https://famical.ymsh.synology.me/";

    private ServerAddress() {}

    static String normalize(String input) {
        String value = input.trim();
        if (!value.contains("://")) value = "https://" + value;
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (path != null && !path.isEmpty() && !path.equals("/"))
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("올바른 HTTPS 도메인 주소를 입력하세요. 경로·아이디·비밀번호는 넣지 않습니다.");
            }
            return new URI("https", null, uri.getHost().toLowerCase(java.util.Locale.ROOT),
                    uri.getPort() == 443 ? -1 : uri.getPort(), "/", null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("주소 형식을 확인해 주세요.");
        }
    }

    static boolean sameOrigin(String base, String target) {
        try {
            URI a = new URI(base), b = new URI(target);
            return "https".equalsIgnoreCase(b.getScheme()) && b.getRawUserInfo() == null
                    && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                    && port(a) == port(b);
        } catch (URISyntaxException | IllegalArgumentException e) { return false; }
    }

    private static int port(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }
}
