package com.example.minimaxbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** The model is remote; the bounded tool loop and workspace are on this Android device. */
final class Agent {
    interface Approval { boolean approve(String action, String detail) throws Exception; }
    interface Transport { JSONObject post(String baseUrl, String key, JSONObject body) throws Exception; }
    private final File root;
    private final Approval approval;
    private final Transport transport;
    Agent(File root, Approval approval) { this(root, approval, Agent::post); }
    Agent(File root, Approval approval, Transport transport) { this.root = root; this.approval = approval; this.transport = transport; }
    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }
    private static JSONObject tool(String name, String description, JSONObject properties, JSONArray required) throws Exception {
        JSONObject parameters = new JSONObject().put("type", "object").put("properties", properties).put("required", required);
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", description).put("parameters", parameters));
    }
    private static JSONObject field(String description) throws Exception { return new JSONObject().put("type", "string").put("description", description); }
    private static JSONArray tools() throws Exception {
        JSONObject path = new JSONObject().put("path", field("Relative path within the local workspace. Use . for its root."));
        JSONObject write = new JSONObject().put("path", field("Relative file path inside workspace")).put("content", field("Complete UTF-8 file contents"));
        return new JSONArray()
                .put(tool("list_files", "List files in a local workspace directory", path, new JSONArray().put("path")))
                .put(tool("read_file", "Read a UTF-8 file in the local workspace", path, new JSONArray().put("path")))
                .put(tool("write_file", "Create or replace a UTF-8 file after user approval", write, new JSONArray().put("path").put("content")))
                .put(tool("run_command", "Run a command on this Android device after user approval, in the app sandbox, starting in the workspace", new JSONObject().put("command", field("Command to execute with Android sh")), new JSONArray().put("command")));
    }
    private File safePath(String path) throws Exception {
        File base = root.getCanonicalFile();
        File target = new File(base, path).getCanonicalFile();
        if (!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator)) throw new Exception("Path outside workspace");
        return target;
    }
    private static byte[] read(InputStream in, int limit) throws Exception {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n; int total = 0;
            while ((n = stream.read(buf)) != -1) { total += n; if (total > limit) throw new Exception("Data exceeds size limit"); out.write(buf, 0, n); }
            return out.toByteArray();
        }
    }
    private String execute(String name, JSONObject args) throws Exception {
        if (name.equals("run_command")) {
            String command = args.getString("command");
            if (command.trim().isEmpty() || command.length() > 10000) throw new Exception("Invalid command");
            if (!approval.approve("运行本机命令", command)) return "User declined the command.";
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command).directory(root).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        synchronized (output) { int keep = Math.min(count, Math.max(0, 50000 - output.size())); output.write(buffer, 0, keep); }
                    }
                } catch (Exception ignored) { }
            }, "command-output");
            reader.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            reader.join(2000);
            synchronized (output) {
                return "Exit: " + (finished ? Integer.toString(process.exitValue()) : "timed out after 30 seconds") + "\n" + new String(output.toByteArray(), StandardCharsets.UTF_8) + (output.size() >= 50000 ? "\n[output truncated]" : "");
            }
        }
        File file = safePath(args.getString("path"));
        if (name.equals("list_files")) {
            File[] children = file.listFiles();
            if (children == null) throw new Exception("Directory does not exist");
            JSONArray names = new JSONArray();
            for (File child : children) names.put(child.getName() + (child.isDirectory() ? "/" : ""));
            return names.toString();
        }
        if (name.equals("read_file")) return new String(read(new FileInputStream(file), 100_000), StandardCharsets.UTF_8);
        if (name.equals("write_file")) {
            String content = args.getString("content");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 100_000) throw new Exception("File exceeds size limit");
            if (file.equals(root.getCanonicalFile())) throw new Exception("Cannot write workspace root");
            if (!approval.approve("写入文件：" + args.getString("path"), content)) return "User declined the write.";
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) throw new Exception("Cannot create directory");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
            return "File written.";
        }
        throw new Exception("Unknown tool");
    }
    String run(String baseUrl, String key, String model, String prompt, JSONArray history) throws Exception {
        if (!root.exists() && !root.mkdirs()) throw new Exception("Cannot create workspace");
        JSONArray messages = new JSONArray();
        messages.put(message("system", prompt + "\n你可以使用本机工作区工具。只能访问这个应用的私有工作区；写入文件和执行命令需要用户批准。命令在 Android 应用权限内运行，工作目录是私有工作区。不要声称你有未提供的工具。"));
        for (int i = 0; i < history.length(); i++) messages.put(history.getJSONObject(i));
        for (int turn = 0; turn < 8; turn++) {
            JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("tools", tools()).put("stream", false);
            JSONObject result = transport.post(baseUrl, key, body);
                JSONObject status = result.optJSONObject("base_resp");
                if (status != null && status.optInt("status_code") != 0) throw new Exception("MiniMax: " + status.optString("status_msg", "请求失败"));
                JSONObject assistant = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                JSONArray calls = assistant.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    String answer = assistant.optString("content", "");
                    if (answer.isEmpty() || answer.equals("null")) throw new Exception("模型没有返回文本");
                    return answer;
                }
                messages.put(assistant);
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i); JSONObject function = call.getJSONObject("function"); String resultText;
                    try { resultText = execute(function.getString("name"), new JSONObject(function.getString("arguments"))); }
                    catch (Exception e) { resultText = "Tool error: " + e.getMessage(); }
                    messages.put(new JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", resultText));
                }
        }
        throw new Exception("工具调用达到本轮上限，请继续发送指令。");
    }
    static String endpoint(String baseUrl) throws Exception {
        URI uri = new URI(baseUrl.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new Exception("Base URL 必须是没有账号、查询参数或片段的 HTTPS 地址");
        String base = baseUrl.trim().replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) return base;
        return base + "/chat/completions";
    }
    private static JSONObject post(String baseUrl, String key, JSONObject body) throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) new URL(endpoint(baseUrl)).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST"); conn.setConnectTimeout(20000); conn.setReadTimeout(180000); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + key); conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            try (java.io.OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            InputStream input = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = input == null ? "" : new String(read(input, 2_000_000), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) throw new Exception("MiniMax API HTTP " + code + ": " + response.substring(0, Math.min(500, response.length())));
            return new JSONObject(response);
        } finally { conn.disconnect(); }
    }
}
