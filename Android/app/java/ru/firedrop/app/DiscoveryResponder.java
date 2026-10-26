package ru.firedrop.app;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * Answers FireDrop UDP discovery probes while the phone is receiving.
 * Mirrors FireDrop.Core.DiscoveryService, so the device list on the sender
 * shows phones as well as PCs.
 */
final class DiscoveryResponder {
    private static final String TAG = "FireDrop";

    private final String deviceName;
    private final int tcpPort;
    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    DiscoveryResponder(String deviceName, int tcpPort) {
        this.deviceName = deviceName == null || deviceName.isEmpty() ? L10n.s("disc.phone.default") : deviceName;
        this.tcpPort = tcpPort;
    }

    void start() {
        if (running) return;
        try {
            DatagramSocket created = new DatagramSocket(null);
            created.setReuseAddress(true);
            created.setBroadcast(true);
            created.bind(new InetSocketAddress(Net.DISCOVERY_PORT));
            socket = created;
            running = true;

            Thread worker = new Thread(() -> loop(created), "firedrop-discovery");
            worker.setDaemon(true);
            thread = worker;
            worker.start();
        } catch (Exception error) {
            Log.w(TAG, "discovery responder unavailable", error);
        }
    }

    private void loop(DatagramSocket source) {
        byte[] buffer = new byte[512];
        while (running && !source.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                source.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8).trim();
                if (!text.startsWith(Net.PROBE)) continue;

                JSONObject reply = new JSONObject();
                reply.put("app", "FireDrop");
                reply.put("kind", "phone");
                reply.put("name", deviceName);
                reply.put("port", tcpPort);
                reply.put("version", App.VERSION);

                byte[] payload = reply.toString().getBytes(StandardCharsets.UTF_8);
                source.send(new DatagramPacket(payload, payload.length,
                        packet.getAddress(), packet.getPort()));
            } catch (SocketException closed) {
                return;
            } catch (Exception error) {
                if (running) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                }
            }
        }
    }

    void stop() {
        running = false;
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        Thread worker = thread;
        thread = null;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
