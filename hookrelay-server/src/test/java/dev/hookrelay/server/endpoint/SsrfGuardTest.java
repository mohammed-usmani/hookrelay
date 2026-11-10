package dev.hookrelay.server.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hookrelay.server.config.HookRelayProperties;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    private static SsrfGuard guard(boolean allowHttp, List<String> cidrs, Map<String, String> dns) {
        HostResolver resolver = host -> dns.containsKey(host)
                ? List.of(InetAddress.getByName(dns.get(host)))
                : List.of(InetAddress.getAllByName(host));
        return new SsrfGuard(new HookRelayProperties.Ssrf(allowHttp, cidrs), resolver);
    }

    private final SsrfGuard strict = guard(false, List.of(), Map.of());

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.8.8.8", "0.0.0.0", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::a00:1"})
    void blocksInternalAddresses(String ip) {
        assertThatThrownBy(() -> strict.resolveAllowed(ip)).isInstanceOf(SsrfViolation.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:2800::1"})
    void allowsPublicAddresses(String ip) {
        assertThat(strict.resolveAllowed(ip)).isNotEmpty();
    }

    @Test
    void blocksIfAnyResolvedAddressIsInternal() {
        HostResolver twoRecords = host -> List.of(InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("10.0.0.7"));
        SsrfGuard g = new SsrfGuard(new HookRelayProperties.Ssrf(false, List.of()), twoRecords);
        assertThatThrownBy(() -> g.resolveAllowed("shop.example")).isInstanceOf(SsrfViolation.class);
    }

    @Test
    void httpNeedsTheDevFlag() {
        assertThatThrownBy(() -> strict.checkUrl(URI.create("http://example.com/hook")))
                .isInstanceOf(SsrfViolation.class);
        assertThatCode(() -> guard(true, List.of(), Map.of()).checkUrl(URI.create("http://example.com/hook")))
                .doesNotThrowAnyException();
        assertThatCode(() -> strict.checkUrl(URI.create("https://example.com/hook"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:pw@example.com/", "ftp://example.com/", "file:///etc/passwd",
            "https:///nohost", "example.com/hook"})
    void rejectsMalformedOrOddUrls(String url) {
        assertThatThrownBy(() -> strict.checkUrl(URI.create(url))).isInstanceOf(SsrfViolation.class);
    }

    @Test
    void allowlistOpensOnlyItsRange() {
        SsrfGuard dev = guard(true, List.of("172.16.0.0/12"), Map.of());
        assertThat(dev.resolveAllowed("172.18.0.5")).isNotEmpty();
        assertThatThrownBy(() -> dev.resolveAllowed("10.0.0.1")).isInstanceOf(SsrfViolation.class);
    }

    @Test
    void hostnamesAreCheckedByWhatTheyResolveTo() {
        SsrfGuard g = guard(false, List.of(), Map.of("internal.example", "192.168.0.10"));
        assertThatThrownBy(() -> g.resolveAllowed("internal.example")).isInstanceOf(SsrfViolation.class);
    }
}
