package com.example.minimaxbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(247,246,242), INK = Color.rgb(32,31,39), MUTED = Color.rgb(111,109,120), PURPLE = Color.rgb(98,80,222);
    private SharedPreferences prefs;
    private JSONArray bots;
    private String selected;
    private volatile boolean busy;
    private LinearLayout page;
    private EditText composer;
    private SecureKey secureKey;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences("data", MODE_PRIVATE); secureKey = new SecureKey(this);
        try { bots = new JSONArray(prefs.getString("bots", "[]")); } catch (Exception e) { bots = new JSONArray(); }
        if (bots.length() == 0) {
            try { bots.put(new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "小满").put("role", "通用助手").put("prompt", "你是小满，一位可靠、主动、清晰的 AI 助手。请用用户的语言回答。").put("messages", new JSONArray())); save(); } catch (Exception ignored) { }
        }
        showHome();
    }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(null, Typeface.BOLD); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void space(LinearLayout l, int h) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1, dp(h))); }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary ? Color.WHITE : INK); b.setBackground(shape(primary ? PURPLE : Color.WHITE, 14)); b.setPadding(dp(16),0,dp(16),0); return b;
    }
    private void root() {
        page = column(); page.setBackgroundColor(BG); page.setPadding(dp(20),dp(16),dp(20),dp(12));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(20), dp(16) + top, dp(20), dp(12) + bottom);
            return insets;
        });
        setContentView(page);
    }
    private void save() { prefs.edit().putString("bots", bots.toString()).apply(); }
    private JSONObject findBot(String id) {
        for (int i=0; i<bots.length(); i++) { JSONObject b = bots.optJSONObject(i); if (b != null && b.optString("id").equals(id)) return b; } return null;
    }
    private TextView avatar(String name, int size) {
        TextView v = text(name.isEmpty() ? "✦" : name.substring(0,1), 21, PURPLE, true); v.setGravity(Gravity.CENTER); v.setBackground(shape(0xFFE8E4FF, 17)); v.setWidth(dp(size)); v.setHeight(dp(size)); return v;
    }
    private void showHome() {
        selected = null; root();
        LinearLayout top = row(); TextView brand = text("✳  MINIMAX BOT", 13, PURPLE, true); top.addView(brand, new LinearLayout.LayoutParams(0,dp(46),1));
        Button settings = button("设置", false); top.addView(settings); settings.setOnClickListener(v -> settings()); page.addView(top);
        space(page,24); page.addView(text("你的 AI 伙伴", 30, INK, true)); space(page,8); page.addView(text("让想法落地，让工作继续。", 15, MUTED, false)); space(page,24);
        LinearLayout heading = row(); heading.addView(text("我的 Bots", 17, INK, true), new LinearLayout.LayoutParams(0,dp(48),1));
        Button add = button("＋ 新建", true); heading.addView(add); add.setOnClickListener(v -> editBot(null)); page.addView(heading); space(page,8);
        ScrollView scroll = new ScrollView(this); LinearLayout list = column(); scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        for (int i=0; i<bots.length(); i++) {
            JSONObject bot = bots.optJSONObject(i); if (bot == null) continue;
            LinearLayout card = row(); card.setPadding(dp(16),dp(15),dp(16),dp(15)); card.setBackground(shape(Color.WHITE,20)); card.addView(avatar(bot.optString("name"),52));
            LinearLayout info = column(); info.setPadding(dp(14),0,0,0); info.addView(text(bot.optString("name"),17,INK,true)); space(info,5); info.addView(text(bot.optString("role"),13,MUTED,false)); card.addView(info, new LinearLayout.LayoutParams(0,-2,1)); card.addView(text("›",28,MUTED,false));
            card.setOnClickListener(v -> showChat(bot.optString("id"))); list.addView(card); space(list,10);
        }
        TextView note = text("本机工作区  ·  模型通过 MiniMax API 连接", 12, MUTED, false); note.setGravity(Gravity.CENTER); page.addView(note);
    }
    private EditText input(String hint, String value, boolean multi) {
        EditText e = new EditText(this); e.setText(value); e.setHint(hint); e.setTextSize(15); e.setSingleLine(!multi); e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(shape(0xFFF2F1F6,10)); return e;
    }
    private void settings() {
        LinearLayout form = column(); form.setPadding(dp(20),dp(10),dp(20),0);
        form.addView(text("MiniMax API Key",14,INK,true)); space(form,8);
        EditText key = input("留空以保留已保存的密钥", "", false); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(key); space(form,16);
        form.addView(text("Base URL",14,INK,true)); space(form,8);
        EditText baseUrl = input("https://api.minimax.io/v1",prefs.getString("base_url","https://api.minimax.io/v1"),false); baseUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); form.addView(baseUrl); space(form,16);
        form.addView(text("模型 ID",14,INK,true)); space(form,8); EditText model = input("模型 ID",prefs.getString("model","MiniMax-M3.1-Flash"),false); form.addView(model); space(form,12);
        form.addView(text("密钥由 Android Keystore 加密。密钥和对话内容会发送至你填写的服务地址。Base URL 请填写以 /v1 结尾的 HTTPS 地址。",13,MUTED,false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接设置").setView(form).setNegativeButton("取消",null).setNeutralButton("删除密钥",(d,w)-> { try { secureKey.save(""); toast("密钥已删除"); } catch (Exception e) { toast("删除失败"); } }).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try { String url=baseUrl.getText().toString().trim(); Agent.endpoint(url); String m=model.getText().toString().trim(); if(m.isEmpty()) throw new Exception("请填写模型 ID"); if (!key.getText().toString().trim().isEmpty()) secureKey.save(key.getText().toString().trim()); prefs.edit().putString("model",m).putString("base_url",url).apply(); dialog.dismiss(); toast("设置已保存"); } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
        })); dialog.show();
    }
    private void editBot(JSONObject existing) {
        LinearLayout form = column(); form.setPadding(dp(20),dp(10),dp(20),0);
        EditText name = input("名字", existing == null ? "" : existing.optString("name"), false); form.addView(name); space(form,10);
        EditText role = input("角色，例如：研究助手", existing == null ? "" : existing.optString("role"), false); form.addView(role); space(form,10);
        EditText prompt = input("描述这个 Bot 的职责和工作方式", existing == null ? "" : existing.optString("prompt"), true); prompt.setMinLines(4); form.addView(prompt);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "创建 Bot" : "编辑 Bot").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null);
        if (existing != null) dialog.setNeutralButton("删除",(d,w)-> new AlertDialog.Builder(this).setMessage("删除这个 Bot 和它的所有对话？本机工作区文件不会删除。 ").setNegativeButton("取消",null).setPositiveButton("删除",(d2,w2)-> { for(int i=0;i<bots.length();i++) if(bots.optJSONObject(i)==existing){ bots.remove(i); break; } save(); showHome(); }).show());
        AlertDialog shown = dialog.create(); shown.setOnShowListener(v -> shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String n = name.getText().toString().trim(), r = role.getText().toString().trim(), p = prompt.getText().toString().trim();
            if(n.isEmpty() || p.isEmpty()) { toast("请填写名字和职责"); return; }
            try { JSONObject b = existing; if(b == null) { b = new JSONObject().put("id",UUID.randomUUID().toString()).put("messages",new JSONArray()); bots.put(b); } b.put("name",n).put("role",r.isEmpty()?"AI 助手":r).put("prompt",p); save(); shown.dismiss(); showHome(); } catch (Exception e) { toast("保存失败"); }
        })); shown.show();
    }
    private void showChat(String id) {
        JSONObject bot = findBot(id); if (bot == null) { showHome(); return; } selected = id; root();
        LinearLayout header = row(); Button back = button("‹",false); header.addView(back); back.setOnClickListener(v -> showHome());
        LinearLayout title = column(); title.setPadding(dp(12),0,0,0); title.addView(text(bot.optString("name"),18,INK,true)); title.addView(text(busy ? "正在处理…" : bot.optString("role"),12,MUTED,false)); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button edit = button("管理",false); edit.setEnabled(!busy); edit.setOnClickListener(v -> editBot(bot)); header.addView(edit); page.addView(header); space(page,18);
        ScrollView scroll = new ScrollView(this); LinearLayout messages = column(); scroll.addView(messages); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        JSONArray history = bot.optJSONArray("messages");
        if (history == null || history.length() == 0) { space(messages,36); TextView welcome = text("你好，我是 " + bot.optString("name") + " ✨",24,INK,true); welcome.setGravity(Gravity.CENTER); messages.addView(welcome); space(messages,10); TextView detail = text("告诉我你想完成什么，我们一起开始。",14,MUTED,false); detail.setGravity(Gravity.CENTER); messages.addView(detail); }
        else for(int i=0;i<history.length();i++) { JSONObject m=history.optJSONObject(i); if(m==null) continue; boolean user=m.optString("role").equals("user"); LinearLayout line=row(); line.setGravity(user?Gravity.RIGHT:Gravity.LEFT); TextView bubble=text(m.optString("content"),15,user?Color.WHITE:INK,false); bubble.setTextIsSelectable(true); bubble.setPadding(dp(16),dp(13),dp(16),dp(13)); bubble.setBackground(shape(user?PURPLE:Color.WHITE,18)); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2); bp.width = dp(290); line.addView(bubble,bp); messages.addView(line); space(messages,12); }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        LinearLayout compose = row(); composer = input("给 Bot 发消息…","",true); composer.setMaxLines(4); compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1)); Button send=button(busy?"处理中":"发送",true); send.setEnabled(!busy); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,dp(50)); sp.leftMargin=dp(8); compose.addView(send,sp); page.addView(compose); send.setOnClickListener(v -> send(id));
    }
    private void send(String id) {
        if (busy) return; JSONObject bot=findBot(id); if(bot==null) return; String content=composer.getText().toString().trim(); if(content.isEmpty()) return;
        final String key; try { key=secureKey.load(); } catch(Exception e) { toast("无法读取密钥，请重新设置"); return; } if(key.isEmpty()) { toast("请先在设置中填写 MiniMax API Key"); settings(); return; }
        JSONArray history=bot.optJSONArray("messages"); if(history==null) return;
        try { history.put(new JSONObject().put("role","user").put("content",content)); save(); } catch(Exception e) { toast("无法保存消息"); return; }
        String model=prefs.getString("model","MiniMax-M3.1-Flash"), baseUrl=prefs.getString("base_url","https://api.minimax.io/v1"), prompt=bot.optString("prompt"); String snapshot=history.toString(); busy=true; showChat(id);
        new Thread(() -> {
            try {
                Agent agent=new Agent(new File(getFilesDir(),"workspace"), this::approveWrite);
                String answer=agent.run(baseUrl,key,model,prompt,new JSONArray(snapshot));
                runOnUiThread(() -> { JSONObject current=findBot(id); if(current!=null) { try { current.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",answer)); save(); } catch(Exception ignored) {} } busy=false; if(id.equals(selected)) showChat(id); });
            } catch(Exception e) { runOnUiThread(() -> { busy=false; JSONObject current=findBot(id); if(current!=null) { try { current.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","本轮未完成：" + e.getMessage() + "。如果已经执行过工具，请先检查本机结果，再决定如何继续。")); save(); } catch(Exception ignored) {} } if(id.equals(selected)) showChat(id); toast("本轮未完成，请查看对话"); }); }
        },"minimax-agent").start();
    }
    private boolean approveWrite(String action, String content) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicBoolean approved = new AtomicBoolean(false);
        runOnUiThread(() -> {
            if(isFinishing() || isDestroyed()) { latch.countDown(); return; }
            AlertDialog d=new AlertDialog.Builder(this).setTitle("允许"+action+"？").setMessage((action.equals("运行本机命令") ? "命令拥有本应用的文件和网络权限，可能启动继续运行的子进程。\n\n" : "")+content).setNegativeButton("拒绝",(x,w)->latch.countDown()).setPositiveButton("允许",(x,w)-> { approved.set(true); latch.countDown(); }).setOnCancelListener(x->latch.countDown()).create(); d.show();
        });
        return latch.await(2,TimeUnit.MINUTES) && approved.get();
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() { if(selected != null) showHome(); else super.onBackPressed(); }
}
