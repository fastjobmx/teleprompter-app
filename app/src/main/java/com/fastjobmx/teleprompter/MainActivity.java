package com.fastjobmx.teleprompter;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView web;
    private PermissionRequest pendingRequest;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Grabación recibida desde la página
    private File tmpFile;
    private FileOutputStream tmpOut;
    private File inputFile;
    private String inputName = "video.mp4";
    private String inputMime = "video/mp4";

    // Último archivo guardado en galería (para compartir)
    private Uri lastUri;
    private String lastMime = "video/mp4";

    // Exportación
    private Transformer transformer;
    private int step = 1, steps = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0b0c"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    pendingRequest = request;
                    askPermissions();
                });
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    // ---------- Permisos ----------

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) grantPending();
        else requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private void grantPending() {
        if (pendingRequest == null) return;
        List<String> ok = new ArrayList<>();
        for (String r : pendingRequest.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ok.add(r);
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ok.add(r);
        }
        if (ok.isEmpty()) pendingRequest.deny();
        else pendingRequest.grant(ok.toArray(new String[0]));
        pendingRequest = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) grantPending();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'yes' : 'no'", value -> {
            if (value == null || !value.contains("yes")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        if (transformer != null) transformer.cancel();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------- Utilidades ----------

    private void js(String fn, String arg) {
        main.post(() -> web.evaluateJavascript(
                "window." + fn + "&&window." + fn + "(" + JSONObject.quote(arg) + ")", null));
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private Uri saveToGallery(File src, String name, String mime) throws Exception {
        ContentResolver resolver = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, mime);
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Teleprompter");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new Exception("No se pudo crear el archivo");
            try (InputStream in = new FileInputStream(src); OutputStream out = resolver.openOutputStream(uri)) {
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Teleprompter");
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
                copy(in, out);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DATA, dest.getAbsolutePath());
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, mime);
            return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        }
    }

    private String saveTextToDownloads(String name, String mime, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("No se pudo crear el archivo");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(data); }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, name))) { out.write(data); }
        }
        return "ok";
    }

    // ---------- Exportación con Media3 (cortar silencios + subtítulos) ----------

    private static class Cue {
        final long s, e;
        final String t;
        Cue(long s, long e, String t) { this.s = s; this.e = e; this.t = t; }
    }

    private void startExport(String json) {
        try {
            if (inputFile == null || !inputFile.exists()) throw new Exception("no hay grabación");
            if (transformer != null) throw new Exception("ya hay una exportación en curso");
            JSONObject o = new JSONObject(json);
            JSONArray seg = o.optJSONArray("segments");
            JSONArray cj = o.optJSONArray("cues");
            final String name = o.optString("name", "prompter-editado.mp4");
            final List<Cue> cues = new ArrayList<>();
            if (cj != null) for (int i = 0; i < cj.length(); i++) {
                JSONObject c = cj.getJSONObject(i);
                cues.add(new Cue(c.getLong("s") * 1000L, c.getLong("e") * 1000L, c.getString("t")));
            }
            final List<Effect> fx = new ArrayList<>();
            if (!cues.isEmpty()) fx.add(captionEffect(cues, o.optString("style", "box"),
                    o.optString("pos", "bottom"), (float) o.optDouble("scale", 1.0)));

            final File out = new File(getCacheDir(), "export.mp4");
            final Runnable finish = () -> {
                try {
                    lastUri = saveToGallery(out, name, "video/mp4");
                    lastMime = "video/mp4";
                    out.delete();
                    js("onExportDone", "ok");
                } catch (Exception e) {
                    js("onExportDone", "error al guardar: " + e.getMessage());
                }
            };

            if (seg != null && seg.length() > 0) {
                // Paso 1: convertir la grabación a un MP4 normal (con índice para poder cortar).
                // Paso 2: unir solo los tramos con voz y agregar subtítulos.
                final File norm = new File(getCacheDir(), "normal.mp4");
                final JSONArray segments = seg;
                steps = 2; step = 1;
                run(single(Uri.fromFile(inputFile), new ArrayList<>()), norm, () -> {
                    try {
                        step = 2;
                        run(clipped(Uri.fromFile(norm), segments, fx), out, () -> { norm.delete(); finish.run(); });
                    } catch (Exception e) {
                        js("onExportDone", e.getMessage());
                    }
                });
            } else {
                steps = 1; step = 1;
                run(single(Uri.fromFile(inputFile), fx), out, finish);
            }
        } catch (Exception e) {
            js("onExportDone", String.valueOf(e.getMessage()));
        }
    }

    private Composition single(Uri uri, List<Effect> fx) {
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(uri)).build();
        List<EditedMediaItem> items = new ArrayList<>();
        items.add(item);
        return new Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
                .setEffects(new Effects(new ArrayList<AudioProcessor>(), fx))
                .build();
    }

    private Composition clipped(Uri uri, JSONArray seg, List<Effect> fx) throws Exception {
        List<EditedMediaItem> items = new ArrayList<>();
        for (int i = 0; i < seg.length(); i++) {
            JSONArray r = seg.getJSONArray(i);
            long a = r.getLong(0), b = r.getLong(1);
            if (b - a < 100) continue;
            MediaItem mi = new MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(a)
                            .setEndPositionMs(b)
                            .build())
                    .build();
            items.add(new EditedMediaItem.Builder(mi).build());
        }
        if (items.isEmpty()) throw new Exception("no quedó ningún tramo con voz");
        return new Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
                .setEffects(new Effects(new ArrayList<AudioProcessor>(), fx))
                .build();
    }

    private Effect captionEffect(final List<Cue> cues, String style, String pos, float scale) {
        final int color = "yellow".equals(style) ? Color.parseColor("#FFD60A") : Color.WHITE;
        final boolean boxed = "box".equals(style);
        final float size = Math.max(0.4f, scale) * ("box".equals(style) ? 0.72f : 0.95f);
        final float y = "top".equals(pos) ? 0.72f : "mid".equals(pos) ? 0f : -0.68f;
        final OverlaySettings settings = new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, y)
                .build();
        final SpannableString empty = new SpannableString(" ");
        TextOverlay overlay = new TextOverlay() {
            @Override
            public SpannableString getText(long presentationTimeUs) {
                for (Cue c : cues) {
                    if (presentationTimeUs >= c.s && presentationTimeUs < c.e) return styled(c.t);
                }
                return empty;
            }

            private SpannableString styled(String t) {
                SpannableString sp = new SpannableString(boxed ? " " + t + " " : t);
                int n = sp.length();
                sp.setSpan(new ForegroundColorSpan(color), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new StyleSpan(Typeface.BOLD), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new RelativeSizeSpan(size), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new BackgroundColorSpan(boxed ? 0xB3000000 : 0x66000000), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return sp;
            }

            @Override
            public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                return settings;
            }
        };
        List<TextureOverlay> list = new ArrayList<>();
        list.add(overlay);
        return new OverlayEffect(list);
    }

    private void run(Composition c, File out, Runnable onOk) {
        out.delete();
        transformer = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        transformer = null;
                        onOk.run();
                    }

                    @Override
                    public void onError(Composition composition, ExportResult exportResult, ExportException e) {
                        transformer = null;
                        out.delete();
                        js("onExportDone", "error de video (" + e.getErrorCodeName() + ")");
                    }
                })
                .build();
        transformer.start(c, out.getAbsolutePath());
        poll();
    }

    private void poll() {
        if (transformer == null) return;
        ProgressHolder ph = new ProgressHolder();
        if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE)
            js("onExportProgress", step + "/" + steps + ":" + ph.progress);
        main.postDelayed(this::poll, 500);
    }

    // ---------- Puente con la página ----------

    private class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public synchronized boolean begin(String name, String mime) {
            try {
                if (tmpOut != null) try { tmpOut.close(); } catch (Exception ignored) { }
                tmpFile = new File(getCacheDir(), "recibiendo.tmp");
                tmpOut = new FileOutputStream(tmpFile, false);
                inputName = name;
                inputMime = (mime == null || mime.isEmpty()) ? "video/mp4" : mime.split(";")[0];
                lastUri = null;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean append(String b64) {
            try {
                tmpOut.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized String finishRecording() {
            try {
                tmpOut.close();
                tmpOut = null;
                String ext = inputMime.contains("webm") ? ".webm" : ".mp4";
                File dest = new File(getCacheDir(), "grabacion" + ext);
                if (inputFile != null) inputFile.delete();
                dest.delete();
                if (!tmpFile.renameTo(dest)) throw new Exception("no se pudo preparar");
                inputFile = dest;
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String saveOriginal() {
            try {
                if (inputFile == null) return "no hay grabación";
                lastUri = saveToGallery(inputFile, inputName, inputMime);
                lastMime = inputMime;
                return lastUri != null ? "ok" : "error";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public boolean hasShareable() { return lastUri != null; }

        @JavascriptInterface
        public void exportVideo(String json) { main.post(() -> startExport(json)); }

        @JavascriptInterface
        public void cancelExport() { main.post(() -> { if (transformer != null) { transformer.cancel(); transformer = null; } }); }

        @JavascriptInterface
        public String saveText(String name, String mime, String text) {
            try { return saveTextToDownloads(name, mime, text); }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public void copyText(String text) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Subtítulos", text));
            });
        }

        @JavascriptInterface
        public void share() {
            main.post(() -> {
                if (lastUri == null) return;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(lastMime);
                i.putExtra(Intent.EXTRA_STREAM, lastUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Compartir video")); } catch (Exception ignored) { }
            });
        }
    }
}
