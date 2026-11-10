package dev.hookrelay.server.endpoint;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** DNS lookup, swappable in tests (e.g. to simulate DNS rebinding). */
@FunctionalInterface
public interface HostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
