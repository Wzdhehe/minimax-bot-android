package com.example.minimaxbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentTest {
    static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    static JSONObject reply(JSONObject message) throws Exception { return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message))); }
    static JSONObject toolCall(String name, JSONObject args) throws Exception {
        return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("reasoning_content","thought")
                .put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-1").put("type","function")
                        .put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
    }
    static JSONArray history() throws Exception { return new JSONArray().put(new JSONObject().put("role","user").put("content","help")); }
    static void scenario(Path workspace, String name, JSONObject args, boolean approve, String expected) throws Exception {
        AtomicInteger turn = new AtomicInteger(); AtomicInteger approvals = new AtomicInteger();
        Agent agent = new Agent(workspace.toFile(),(action,detail)-> { approvals.incrementAndGet(); return approve; }, (base,key,body)-> {
            if (turn.getAndIncrement() == 0) return reply(toolCall(name,args));
            JSONArray messages = body.getJSONArray("messages");
            check(messages.getJSONObject(2).getString("reasoning_content").equals("thought"),"reasoning lost across tool call");
            JSONObject result = messages.getJSONObject(3);
            check(result.getString("tool_call_id").equals("call-1"),"wrong call id");
            check(result.getString("content").contains(expected),"unexpected tool result: " + result);
            return reply(new JSONObject().put("role","assistant").put("content","done"));
        });
        check(agent.run("https://api.minimax.io/v1","key","model","prompt",history()).equals("done"),"missing final answer");
        if (name.equals("write_file") && !args.getString("path").startsWith("..")) check(approvals.get()==1,"write approval missing");
    }
    public static void main(String[] args) throws Exception {
        check(Agent.endpoint("https://api.minimax.io/v1/").equals("https://api.minimax.io/v1/chat/completions"),"base URL");
        check(Agent.endpoint("https://example.com/v1/chat/completions").equals("https://example.com/v1/chat/completions"),"endpoint URL");
        try { Agent.endpoint("http://example.com/v1"); throw new AssertionError("cleartext accepted"); } catch (Exception expected) { }
        try { Agent.endpoint("https://example.com/v1?key=x"); throw new AssertionError("query accepted"); } catch (Exception expected) { }
        Path root=Files.createTempDirectory("agent-test");
        scenario(root,"write_file",new JSONObject().put("path","nested/note.txt").put("content","hello"),true,"File written");
        check(Files.readString(root.resolve("nested/note.txt")).equals("hello"),"write failed");
        scenario(root,"read_file",new JSONObject().put("path","nested/note.txt"),true,"hello");
        scenario(root,"write_file",new JSONObject().put("path","denied.txt").put("content","no"),false,"declined");
        check(!Files.exists(root.resolve("denied.txt")),"denied write happened");
        scenario(root,"read_file",new JSONObject().put("path","../outside"),true,"outside workspace");
        scenario(root,"run_command",new JSONObject().put("command","echo test"),false,"declined");
        System.out.println("Agent tests passed");
    }
}
