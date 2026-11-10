package dev.hookrelay.server.endpoint;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** An IPv4 or IPv6 network, e.g. {@code 10.0.0.0/8}. */
record Cidr(byte[] network, int prefix) {

    static Cidr parse(String cidr) {
        int slash = cidr.indexOf('/');
        try {
            byte[] addr = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            return new Cidr(addr, Integer.parseInt(cidr.substring(slash + 1)));
        } catch (UnknownHostException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
        }
    }

    boolean contains(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length != network.length) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (a[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
