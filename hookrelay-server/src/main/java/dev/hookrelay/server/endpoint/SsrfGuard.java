package dev.hookrelay.server.endpoint;

import dev.hookrelay.server.config.HookRelayProperties;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stops HookRelay from being used to reach internal networks (server-side request forgery).
 * Checked when an endpoint is registered and again before every send, because DNS answers
 * can change after registration.
 */
public class SsrfGuard {

    private static final List<Cidr> BLOCKED = Stream.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8",
            "64:ff9b::/96" // NAT64: maps to IPv4 addresses, including private ones
    ).map(Cidr::parse).toList();

    private final boolean allowHttp;
    private final List<Cidr> allowed;
    private final HostResolver resolver;

    public SsrfGuard(HookRelayProperties.Ssrf props, HostResolver resolver) {
        this.allowHttp = props.allowHttp();
        this.allowed = props.allowedCidrs().stream().map(Cidr::parse).toList();
        this.resolver = resolver;
    }

    /** Syntax-level checks: scheme, host present, no credentials in the URL. */
    public void checkUrl(URI url) {
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equals("https") || (allowHttp && scheme.equals("http")))) {
            throw new SsrfViolation(allowHttp ? "URL must use http or https" : "URL must use https");
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new SsrfViolation("URL must have a host");
        }
        if (url.getRawUserInfo() != null) {
            throw new SsrfViolation("URL must not contain credentials");
        }
    }

    /**
     * Resolves the host and returns its addresses if every one of them is public (or
     * explicitly allow-listed). Throws {@link SsrfViolation} otherwise, and
     * {@link UncheckedIOException} if the name does not resolve.
     */
    public List<InetAddress> resolveAllowed(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(bare);
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(e);
        }
        for (InetAddress a : addresses) {
            if (isBlocked(a) && allowed.stream().noneMatch(c -> c.contains(a))) {
                throw new SsrfViolation("address " + a.getHostAddress() + " is not publicly routable");
            }
        }
        return addresses;
    }

    private static boolean isBlocked(InetAddress a) {
        return BLOCKED.stream().anyMatch(c -> c.contains(a));
    }
}
