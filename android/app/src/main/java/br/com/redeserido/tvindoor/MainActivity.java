package br.com.redeserido.tvindoor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    // Endereço virtual: o app serve o player por aqui (contexto seguro https, sem depender da internet).
    static final String HOST = "https://tvindoor.local/";
    // OPCIONAL: endereço do GitHub Pages para atualizar o player sem gerar APK novo.
    // Exemplo: "https://seuusuario.github.io/tvindoor/"   (vazio = usa só o que veio dentro do APK)
    static final String UPDATE_BASE = "";

    WebView web;
    SharedPreferences sp;
    String code;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("tv", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return handle(r);
            }
        });
        code = sp.getString("code", null);
        if (code == null) askCode(); else load();
    }

    void load() { web.loadUrl(HOST + "player.html?tv=" + Uri.encode(code)); }

    void askCode() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setHint("Ex.: padaria");
        if (code != null) e.setText(code);
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Código desta TV (igual ao cadastrado no painel)")
                .setView(e)
                .setCancelable(code != null)
                .setPositiveButton("Salvar", (dlg, w) -> {
                    String c = e.getText().toString().trim().toLowerCase();
                    if (c.isEmpty()) { askCode(); return; }
                    code = c;
                    sp.edit().putString("code", c).apply();
                    load();
                });
        if (code != null) d.setNegativeButton("Cancelar", null);
        d.show();
    }

    // ---- Servir o player, a biblioteca e a planilha pelo próprio app ----
    WebResourceResponse handle(WebResourceRequest r) {
        if (!"GET".equals(r.getMethod())) return null;
        String url = r.getUrl().toString();
        try {
            if (url.startsWith(HOST)) {
                String n = r.getUrl().getLastPathSegment();
                if (n == null || n.isEmpty()) n = "player.html";
                if (!n.equals("player.html") && !n.equals("config.js") && !n.equals("logo.png"))
                    return resp("text/plain", new byte[0], 404);
                return local(n);
            }
            if (url.startsWith("https://cdn.jsdelivr.net/npm/")) return cdn(url);
            if (url.startsWith("https://docs.google.com/spreadsheets/")) return sheet(url);
        } catch (Exception e) {
            if (url.startsWith(HOST)) return resp("text/plain", new byte[0], 500);
        }
        return null;
    }

    WebResourceResponse local(String name) throws IOException {
        byte[] d = null;
        if (!UPDATE_BASE.isEmpty()) {
            try {
                d = download(UPDATE_BASE + name + "?t=" + System.currentTimeMillis(), 2500);
                save("u_" + name, d);
            } catch (IOException e) {
                d = readFile("u_" + name);
            }
        }
        if (d == null) {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            copy(in, o);
            in.close();
            d = o.toByteArray();
        }
        return resp(mime(name), d, 200);
    }

    // Biblioteca do Supabase: guarda no aparelho para a TV abrir mesmo sem internet.
    WebResourceResponse cdn(String url) throws IOException {
        String n = "cdn_" + Integer.toHexString(url.hashCode());
        byte[] d = readFile(n);
        if (d == null) { d = download(url, 5000); save(n, d); }
        return resp("application/javascript", d, 200);
    }

    // Planilha do Google: o app busca e entrega liberada, sem bloqueio de CORS.
    WebResourceResponse sheet(String url) {
        try { return resp("text/csv", download(url, 6000), 200); }
        catch (IOException e) { return resp("text/plain", new byte[0], 504); }
    }

    WebResourceResponse resp(String mime, byte[] d, int code) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, "utf-8", code, code == 200 ? "OK" : "Erro", h, new ByteArrayInputStream(d));
    }

    String mime(String n) {
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        return "image/png";
    }

    byte[] download(String url, int ms) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(ms);
        c.setReadTimeout(ms * 2);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            copy(in, o);
            in.close();
            return o.toByteArray();
        } finally { c.disconnect(); }
    }

    void copy(InputStream in, OutputStream o) throws IOException {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }

    void save(String name, byte[] d) {
        try (FileOutputStream f = openFileOutput(name, MODE_PRIVATE)) { f.write(d); } catch (IOException e) { }
    }

    byte[] readFile(String name) {
        try (FileInputStream f = openFileInput(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            copy(f, o);
            return o.toByteArray();
        } catch (IOException e) { return null; }
    }

    // ---- Tela cheia e controle remoto ----
    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) return true; // não deixa sair sem querer
        if (k == KeyEvent.KEYCODE_MENU) { askCode(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { e.startTracking(); return true; }
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyLongPress(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { askCode(); return true; }
        return super.onKeyLongPress(k, e);
    }
}
