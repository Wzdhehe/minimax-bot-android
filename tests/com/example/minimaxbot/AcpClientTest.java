package com.example.minimaxbot;

import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicInteger;

public final class AcpClientTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        for (boolean allow : new boolean[]{true, false}) {
            Process process = new ProcessBuilder("python3", "tests/acp_fake.py").start();
            AtomicInteger updates = new AtomicInteger(), approvals = new AtomicInteger();
            try (AcpClient client = new AcpClient(process, new AcpClient.Events() {
                public void update(JSONObject params) { if ("test-session".equals(params.optString("sessionId"))) updates.incrementAndGet(); }
                public boolean approve(JSONObject tool) { check("write file".equals(tool.optString("title")),"tool not forwarded"); approvals.incrementAndGet(); return allow; }
            })) {
                check(client.initialize().getInt("protocolVersion") == 1, "initialize");
                String session = client.createSession("/tmp");
                check(session.equals("test-session"),"new session");
                client.loadSession(session, "/tmp");
                JSONObject result = client.prompt(session,"hello");
                check(result.getString("selected").equals(allow ? "yes" : "no"),"incorrect permission response");
                check(updates.get() == 1 && approvals.get() == 1,"events not delivered");
            }
        }
        if (args.length > 0) {
            ProcessBuilder builder = new ProcessBuilder(args[0], "acp");
            builder.environment().put("MINIMAX_DATA_DIR", java.nio.file.Files.createTempDirectory("acp-smoke").toString());
            try (AcpClient client = new AcpClient(builder.start(), new AcpClient.Events() {
                public void update(JSONObject params) { }
                public boolean approve(JSONObject tool) { return false; }
            })) {
                check(client.initialize().getJSONObject("agentInfo").getString("name").equals("minimax-code"), "real mcode handshake");
            }
        }
        System.out.println("ACP tests passed");
    }
}
