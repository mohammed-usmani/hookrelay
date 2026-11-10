package dev.hookrelay.server.support;

import dev.hookrelay.server.endpoint.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** DNS that answers from a test-controlled map first, then falls back to real lookups. */
public class TestDns implements HostResolver {

    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    public void map(String host, String ip) {
        overrides.put(host, ip);
    }

    public void clear() {
        overrides.clear();
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        String ip = overrides.get(host);
        return ip != null ? List.of(InetAddress.getByName(ip)) : List.of(InetAddress.getAllByName(host));
    }
}
