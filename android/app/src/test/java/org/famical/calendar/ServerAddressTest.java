package org.famical.calendar;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServerAddressTest {
    @Test public void acceptsHttpsOriginsAndNormalizesDefaultPort() {
        assertEquals("https://calendar.example.com/",ServerAddress.normalize(" calendar.example.com "));
        assertEquals("https://calendar.example.com/",ServerAddress.normalize("https://CALENDAR.example.com:443/"));
        assertEquals("https://calendar.example.com:8443/",ServerAddress.normalize("https://calendar.example.com:8443"));
    }
    @Test public void rejectsUnsafeOrAmbiguousServerAddresses() {
        String[] bad={"", "http://calendar.example.com", "file:///data/private", "javascript:alert(1)",
            "https://user:password@calendar.example.com", "https://calendar.example.com/calendar", "https://calendar.example.com/?token=secret", "https://calendar.example.com/#x", "https://calendar.example.com:0", "https://calendar.example.com:65536"};
        for(String value:bad) {
            try { ServerAddress.normalize(value);fail("Accepted invalid URL: "+value); }
            catch(IllegalArgumentException expected) { /* expected */ }
        }
    }
    @Test public void limitsWebViewNavigationToSameHttpsOrigin() {
        String base="https://calendar.example.com/";
        assertTrue(ServerAddress.sameOrigin(base,"https://CALENDAR.example.com:443/api/me"));
        assertFalse(ServerAddress.sameOrigin(base,"http://calendar.example.com/"));
        assertFalse(ServerAddress.sameOrigin(base,"https://calendar.example.com.evil.test/"));
        assertFalse(ServerAddress.sameOrigin(base,"https://calendar.example.com:8443/"));
        assertFalse(ServerAddress.sameOrigin(base,"https://user@calendar.example.com/"));
        assertFalse(ServerAddress.sameOrigin(base,"intent://calendar.example.com"));
    }
}
