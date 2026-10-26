package ru.firedrop.app;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** LAN discovery and address helpers. Mirrors FireDrop.Core.DiscoveryService on the PC side. */
public final class Net {
    public static final int DISCOVERY_PORT = 47822;
    public static final String PROBE = "FIREDROP_DISCOVER_V1";

    private Net() {
    }

    public static List<String> localIpv4() {
        List<String> result = new ArrayList<>();
        for (String[] entry : addresses()) {
            if (!result.contains(entry[1])) result.add(entry[1]);
        }
        return result;
    }

    /** Returns {interfaceName, address} for every usable LAN IPv4 address. */
    private static List<String[]> addresses() {
        List<String[]> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;

                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                // Skip VPN tunnels and cellular interfaces: their addresses are not on the LAN.
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp")
                        || name.startsWith("wg") || name.startsWith("rmnet")
                        || name.startsWith("ccmni") || name.startsWith("dummy")
                        || name.startsWith("clat")) {
                    continue;
                }

                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (!address.isSiteLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.startsWith("169.254.")) continue;

                    boolean duplicate = false;
                    for (String[] existing : result) {
                        if (existing[1].equals(host)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) result.add(new String[]{name, host});
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static String primaryIp() {
        List<String[]> all = addresses();
        for (String[] entry : all) {
            String name = entry[0];
            if (name.startsWith("wlan") || name.startsWith("wifi")
                    || name.equals("ap0") || name.startsWith("swlan")) {
                return entry[1];
            }
        }
        for (String[] entry : all) {
            if (entry[1].startsWith("192.168.")) return entry[1];
        }
        return all.isEmpty() ? "" : all.get(0)[1];
    }

    /**
     * True when a VPN tunnel interface is up. Even when the Wi-Fi address is correct,
     * a VPN can route the traffic into its own tunnel, so the other side never hears us.
     */
    public static boolean isVpnUp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp")
                        || name.startsWith("wg") || name.startsWith("vpn")
                        || name.startsWith("ipsec")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Home routers hand out 192.168.x.x (or 172.16–31.x.x). An address like 10.x means the
     * phone is in a guest Wi-Fi, a repeater subnet, on mobile data or under VPN — the PC on
     * the home network will not be able to reach it, although other phones in the same
     * guest subnet will.
     */
    public static boolean isHomeLike(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    public static final class Device {
        public final String name;
        public final String host;
        public final int port;
        public final String kind;
        public final String version;

        Device(String name, String host, int port, String kind, String version) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.kind = kind;
            this.version = version;
        }

        public String title() {
            return name == null || name.isEmpty() ? host : name;
        }

        public String subtitle() {
            String type = "phone".equals(kind) ? "телефон" : "компьютер";
            return host + ":" + port + " · " + type;
        }
    }

    public static List<Device> discover(int timeoutMs) {
        List<Device> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(220);

            Set<InetAddress> targets = new LinkedHashSet<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface nif = interfaces.nextElement();
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                        InetAddress broadcast = address.getBroadcast();
                        if (broadcast != null) targets.add(broadcast);
                    }
                }
            } catch (Exception ignored) {
            }

            byte[] probe = PROBE.getBytes("UTF-8");
            for (InetAddress target : targets) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, target, DISCOVERY_PORT));
                } catch (IOException ignored) {
                }
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                } catch (IOException broken) {
                    break;
                }

                try {
                    String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    JSONObject json = new JSONObject(text);
                    if (!"FireDrop".equals(json.optString("app"))) continue;
                    String host = packet.getAddress().getHostAddress();
                    int port = json.optInt("port", 47820);
                    String key = host + ":" + port;
                    if (!seen.add(key)) continue;
                    found.add(new Device(
                            json.optString("name", host),
                            host,
                            port,
                            json.optString("kind", "pc"),
                            json.optString("version", "")));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
        }

        Collections.sort(found, (a, b) -> a.title().compareToIgnoreCase(b.title()));
        return found;
    }
}
