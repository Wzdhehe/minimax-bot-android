package com.example.minimaxbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Local stdio ACP transport. The caller starts and owns a local agent process. */
final class AcpClient implements Closeable {
    interface Events {
        void update(JSONObject params);
        boolean approve(JSONObject toolCall);
    }
    private final Process process;
    private final Events events;
    private final BufferedWriter input;
    private final AtomicLong nextId = new AtomicLong();
    private final Map<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    AcpClient(Process process, Events events) {
        this.process = process; this.events = events;
        this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(this::readLoop, "acp-reader"); reader.setDaemon(true); reader.start();
        // Drain stderr separately; neither logs nor forwards it because it may contain private data.
        Thread errors = new Thread(() -> { try { byte[] b = new byte[4096]; while (process.getErrorStream().read(b) != -1) { } } catch (Exception ignored) { } }, "acp-stderr");
        errors.setDaemon(true); errors.start();
    }
    private synchronized void write(JSONObject value) throws Exception {
        if (closed) throw new IllegalStateException("ACP connection closed");
        input.write(value.toString()); input.newLine(); input.flush();
    }
    JSONObject request(String method, JSONObject params, long timeoutSeconds) throws Exception {
        String id = Long.toString(nextId.incrementAndGet());
        CompletableFuture<JSONObject> result = new CompletableFuture<>(); pending.put(id, result);
        try {
            write(new JSONObject().put("jsonrpc", "2.0").put("id", Long.parseLong(id)).put("method", method).put("params", params));
            JSONObject response = result.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.has("error")) throw new IllegalStateException("ACP error: " + response.getJSONObject("error").optString("message", "unknown error"));
            return response.optJSONObject("result") == null ? new JSONObject() : response.getJSONObject("result");
        } finally { pending.remove(id); }
    }
    JSONObject initialize() throws Exception {
        return request("initialize", new JSONObject().put("protocolVersion", 1).put("clientCapabilities", new JSONObject()), 30);
    }
    String createSession(String cwd) throws Exception {
        String id = request("session/new", new JSONObject().put("cwd", cwd).put("mcpServers", new JSONArray()), 60).getString("sessionId");
        requireAskMode(id);
        return id;
    }
    void loadSession(String id, String cwd) throws Exception {
        request("session/load", new JSONObject().put("sessionId", id).put("cwd", cwd).put("mcpServers", new JSONArray()), 60);
        requireAskMode(id);
    }
    private void requireAskMode(String id) throws Exception {
        JSONObject response = request("session/set_config_option", new JSONObject().put("sessionId", id).put("configId", "permissionMode").put("value", "default"), 30);
        JSONArray options = response.optJSONArray("configOptions");
        if (options != null) for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && "permissionMode".equals(option.optString("id")) && "default".equals(option.optString("currentValue"))) return;
        }
        throw new IllegalStateException("ACP did not confirm interactive permission mode");
    }
    JSONObject prompt(String id, String text) throws Exception {
        JSONArray prompt = new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
        return request("session/prompt", new JSONObject().put("sessionId", id).put("prompt", prompt), 600);
    }
    void cancel(String id) throws Exception {
        write(new JSONObject().put("jsonrpc", "2.0").put("method", "session/cancel").put("params", new JSONObject().put("sessionId", id)));
    }
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject message = new JSONObject(line);
                if (message.has("method")) {
                    String method = message.getString("method"); JSONObject params = message.optJSONObject("params");
                    if (params == null) params = new JSONObject();
                    if (message.has("id")) handleAgentRequest(message.get("id"), method, params);
                    else if ("session/update".equals(method)) { try { events.update(params); } catch (Exception ignored) { } }
                } else if (message.has("id")) {
                    CompletableFuture<JSONObject> future = pending.remove(message.get("id").toString());
                    if (future != null) future.complete(message);
                }
            }
        } catch (Exception ignored) { }
        finally {
            closed = true;
            for (CompletableFuture<JSONObject> result : pending.values()) result.completeExceptionally(new IllegalStateException("ACP process stopped"));
            pending.clear();
        }
    }
    private void handleAgentRequest(Object id, String method, JSONObject params) throws Exception {
        if (!"session/request_permission".equals(method)) {
            write(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", new JSONObject().put("code", -32601).put("message", "Unsupported client method")));
            return;
        }
        boolean allowed = false;
        try { allowed = events.approve(params.optJSONObject("toolCall")); } catch (Exception ignored) { }
        JSONArray options = params.optJSONArray("options"); String choice = null;
        if (options != null) for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && (allowed ? "allow_once" : "reject_once").equals(option.optString("kind"))) { choice = option.optString("optionId"); break; }
        }
        JSONObject outcome = choice == null ? new JSONObject().put("outcome", "cancelled") : new JSONObject().put("outcome", "selected").put("optionId", choice);
        write(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", new JSONObject().put("outcome", outcome)));
    }
    @Override public void close() {
        closed = true;
        try { input.close(); } catch (Exception ignored) { }
        process.destroy();
    }
}
