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
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbMatrix;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView web;
    private PermissionRequest pendingRequest;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Tomas recibidas desde la página (se guardan en el almacenamiento interno de la app)
    private File tmpFile;
    private FileOutputStream tmpOut;
    private String tmpName = "toma.mp4";
    private File takesDir, normDir;

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

        takesDir = new File(getFilesDir(), "takes");
        normDir = new File(getFilesDir(), "norm");
        takesDir.mkdirs();
        normDir.mkdirs();
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/takes/", new WebViewAssetLoader.InternalStoragePathHandler(this, takesDir))
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
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
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
        try { if (Build.VERSION.SDK_INT >= 31) ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).clearCommunicationDevice(); } catch (Exception ignored) { }
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

    // ---------- Exportación con Media3: unir partes, cortes, zoom, color, fundidos y subtítulos ----------

    private static class Key {
        final long t;
        final float z;
        final char m;
        Key(long t, float z, char m) { this.t = t; this.z = z; this.m = m; }
    }

    private File takeFile(String id) {
        File[] fs = takesDir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith(id + ".")) return f;
        return null;
    }

    private static String mimeOf(File f) {
        return f.getName().endsWith(".webm") ? "video/webm" : "video/mp4";
    }

    private void startExport(String json) {
        try {
            if (transformer != null) throw new Exception("ya hay una exportación en curso");
            JSONObject o = new JSONObject(json);
            final JSONArray items = o.getJSONArray("items");
            final int w = o.optInt("w", 1080), h = o.optInt("h", 1920);
            final String name = o.optString("name", "prompter-editado.mp4");

            // Efectos que se aplican sobre el video ya unido (tiempo de salida)
            final List<Effect> fx = new ArrayList<>();
            JSONArray zj = o.optJSONArray("zooms");
            if (zj != null && zj.length() > 0) {
                List<Key> keys = new ArrayList<>();
                for (int i = 0; i < zj.length(); i++) {
                    JSONObject k = zj.getJSONObject(i);
                    String m = k.optString("m", "c");
                    keys.add(new Key(k.getLong("t") * 1000L, (float) k.getDouble("z"), m.isEmpty() ? 'c' : m.charAt(0)));
                }
                boolean any = false;
                for (Key k : keys) if (Math.abs(k.z - 1f) > 0.001f) any = true;
                if (any) fx.add(zoomEffect(keys));
            }
            String filter = o.optString("filter", "none");
            JSONArray fj = o.optJSONArray("fades");
            long[] fades = new long[fj == null ? 0 : fj.length()];
            for (int i = 0; i < fades.length; i++) fades[i] = fj.getLong(i) * 1000L;
            if (fades.length > 0 || !"none".equals(filter)) fx.add(colorEffect(filter, fades));
            JSONArray cj = o.optJSONArray("cues");
            if (cj != null && cj.length() > 0) fx.add(captionEffect(o, cj, w, h));

            // Tomas que hay que preparar (convertir a MP4 con índice para poder cortarlas)
            final LinkedHashMap<String, File> src = new LinkedHashMap<>();
            for (int i = 0; i < items.length(); i++) {
                String id = items.getJSONObject(i).getString("take");
                File f = takeFile(id);
                if (f == null) throw new Exception("falta la toma de la parte " + (i + 1) + ", vuelve a grabarla");
                src.put(id, f);
            }
            final List<String> todo = new ArrayList<>();
            for (Map.Entry<String, File> e : src.entrySet()) {
                File n = new File(normDir, e.getKey() + ".mp4");
                if (!n.exists() || n.length() == 0) todo.add(e.getKey());
            }
            steps = todo.size() + 1;
            final File out = new File(getCacheDir(), "export.mp4");
            normalizeNext(todo, 0, src, () -> {
                try {
                    step = steps;
                    run(compose(items, w, h, fx), out, () -> {
                        try {
                            lastUri = saveToGallery(out, name, "video/mp4");
                            lastMime = "video/mp4";
                            out.delete();
                            js("onExportDone", "ok");
                        } catch (Exception e) {
                            js("onExportDone", "error al guardar: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    js("onExportDone", String.valueOf(e.getMessage()));
                }
            });
        } catch (Exception e) {
            js("onExportDone", String.valueOf(e.getMessage()));
        }
    }

    private void normalizeNext(List<String> ids, int i, Map<String, File> src, Runnable done) {
        if (i >= ids.size()) { done.run(); return; }
        step = i + 1;
        String id = ids.get(i);
        File dst = new File(normDir, id + ".mp4");
        File part = new File(normDir, id + ".tmp.mp4");
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src.get(id)))).build();
        List<EditedMediaItem> list = new ArrayList<>();
        list.add(item);
        Composition c = new Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(list)).build();
        run(c, part, () -> {
            dst.delete();
            if (!part.renameTo(dst)) { js("onExportDone", "no se pudo preparar la toma"); return; }
            normalizeNext(ids, i + 1, src, done);
        });
    }

    private Composition compose(JSONArray items, int w, int h, List<Effect> fx) throws Exception {
        List<EditedMediaItem> list = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            Uri uri = Uri.fromFile(new File(normDir, it.getString("take") + ".mp4"));
            JSONArray segs = it.getJSONArray("segs");
            for (int j = 0; j < segs.length(); j++) {
                JSONArray r = segs.getJSONArray(j);
                long a = r.getLong(0), b = r.getLong(1);
                if (b - a < 100) continue;
                MediaItem mi = new MediaItem.Builder()
                        .setUri(uri)
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(a)
                                .setEndPositionMs(b)
                                .build())
                        .build();
                List<Effect> itemFx = new ArrayList<>();
                // Todas las partes al mismo tamaño y formato (recorta para llenar)
                itemFx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
                list.add(new EditedMediaItem.Builder(mi)
                        .setEffects(new Effects(new ArrayList<AudioProcessor>(), itemFx))
                        .build());
            }
        }
        if (list.isEmpty()) throw new Exception("no quedó ningún tramo con voz");
        return new Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(list))
                .setEffects(new Effects(new ArrayList<AudioProcessor>(), fx))
                .build();
    }

    /** Zoom animado: 'c' = cambio inmediato (corte), 'e' = suave en 0,3 s, 'l' = lineal desde la clave anterior. */
    private static float zoomAt(List<Key> keys, long t) {
        float pz = 1f;
        long pt = 0;
        for (Key k : keys) {
            if (t < k.t) {
                if (k.m == 'l') {
                    long d = k.t - pt;
                    return d > 0 ? pz + (k.z - pz) * Math.max(0f, (float) (t - pt) / d) : k.z;
                }
                if (k.m == 'e' && t > k.t - 300_000L) {
                    float f = (float) (t - (k.t - 300_000L)) / 300_000f;
                    return pz + (k.z - pz) * (1 - (1 - f) * (1 - f));
                }
                return pz;
            }
            pz = k.z;
            pt = k.t;
        }
        return pz;
    }

    private Effect zoomEffect(final List<Key> keys) {
        return (MatrixTransformation) presentationTimeUs -> {
            float z = zoomAt(keys, presentationTimeUs);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(z, z);
            return m;
        };
    }

    /** Filtro de color + fundido a negro entre partes. Matriz 4x4 en orden por columnas. */
    private Effect colorEffect(String filter, final long[] fades) {
        final float[] base = new float[16];
        android.opengl.Matrix.setIdentityM(base, 0);
        switch (filter) {
            case "bw": {
                float r = 0.2126f, g = 0.7152f, b = 0.0722f;
                for (int row = 0; row < 3; row++) { base[row] = r; base[4 + row] = g; base[8 + row] = b; }
                break;
            }
            case "vivid": {
                float s = 1.3f, r = 0.2126f * (1 - s), g = 0.7152f * (1 - s), b = 0.0722f * (1 - s);
                for (int row = 0; row < 3; row++) { base[row] = r; base[4 + row] = g; base[8 + row] = b; }
                base[0] += s; base[5] += s; base[10] += s;
                break;
            }
            case "warm": base[0] = 1.08f; base[5] = 1.0f; base[10] = 0.88f; break;
            case "cool": base[0] = 0.9f; base[5] = 1.0f; base[10] = 1.08f; break;
            default: break;
        }
        return (RgbMatrix) (presentationTimeUs, useHdr) -> {
            float f = 1f;
            for (long b : fades) {
                long d = Math.abs(presentationTimeUs - b);
                if (d < 180_000L) f = Math.min(f, d / 180_000f);
            }
            float[] m = base.clone();
            if (f < 1f) for (int col = 0; col < 3; col++) for (int row = 0; row < 3; row++) m[col * 4 + row] *= f;
            return m;
        };
    }

    // ---------- Subtítulos estilo redes (palabra por palabra) ----------

    private static class CapWord {
        final String t;
        final long s, e;
        CapWord(String t, long s, long e) { this.t = t; this.s = s; this.e = e; }
    }

    private static class CapCue {
        final long s, e;
        final CapWord[] w;
        CapCue(long s, long e, CapWord[] w) { this.s = s; this.e = e; this.w = w; }
    }

    private Typeface capFont(String style) {
        try {
            if ("pop".equals(style)) return Typeface.createFromAsset(getAssets(), "www/fonts/Anton-Regular.ttf");
            if ("comic".equals(style)) return Typeface.createFromAsset(getAssets(), "www/fonts/Bangers-Regular.ttf");
            int wt = "minimal".equals(style) ? 600 : "clean".equals(style) ? 700 : "neon".equals(style) ? 800 : 900;
            if (Build.VERSION.SDK_INT >= 26) {
                return new Typeface.Builder(getAssets(), "www/fonts/Montserrat-VF.ttf")
                        .setFontVariationSettings("'wght' " + wt).build();
            }
        } catch (Exception ignored) { }
        return Typeface.DEFAULT_BOLD;
    }

    private class CaptionOverlay extends androidx.media3.effect.BitmapOverlay {
        private final List<CapCue> cues;
        private final String style;
        private final int accent;
        private final boolean upper;
        private final float size;
        private final int vw;
        private final OverlaySettings settings;
        private final Typeface tf;
        private final android.graphics.Bitmap empty = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
        private android.graphics.Bitmap cache = empty;
        private String key = "";

        CaptionOverlay(List<CapCue> cues, String style, int accent, boolean upper, float fontFactor, float userSize, int w, int h, String pos) {
            this.cues = cues;
            this.style = style;
            this.accent = accent;
            this.upper = upper;
            this.vw = w;
            this.size = Math.min(w, h) * fontFactor * userSize;
            this.tf = capFont(style);
            float y = "top".equals(pos) ? 0.7f : "mid".equals(pos) ? 0f : -0.6f;
            this.settings = new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build();
        }

        @Override
        public android.graphics.Bitmap getBitmap(long t) {
            int ci = -1;
            for (int i = 0; i < cues.size(); i++) {
                CapCue c = cues.get(i);
                if (t >= c.s && t < c.e) { ci = i; break; }
            }
            if (ci < 0) { key = ""; return empty; }
            CapCue c = cues.get(ci);
            int a = -1;
            if (!"clean".equals(style)) for (int i = 0; i < c.w.length; i++) if (t >= c.w[i].s) a = i;
            String k = ci + ":" + a;
            if (!k.equals(key)) { cache = draw(c, a); key = k; }
            return cache;
        }

        @Override
        public OverlaySettings getOverlaySettings(long presentationTimeUs) { return settings; }

        private android.graphics.Bitmap draw(CapCue c, int a) {
            boolean grow = "pop".equals(style) || "comic".equals(style);
            boolean stroke = "karaoke".equals(style) || "box".equals(style) || grow;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(tf);
            p.setTextSize(size);
            float space = p.measureText(" ") * ("clean".equals(style) ? 1f : 1.2f);
            String[] words = new String[c.w.length];
            float[] wid = new float[c.w.length];
            for (int i = 0; i < words.length; i++) {
                words[i] = upper ? c.w[i].t.toUpperCase() : c.w[i].t;
                p.setTextSize(size * (grow && i == a ? 1.12f : 1f));
                wid[i] = p.measureText(words[i]);
            }
            // Repartir en líneas (máximo 3)
            float maxW = vw * 0.86f;
            List<int[]> lines = new ArrayList<>();
            List<Float> lw = new ArrayList<>();
            int start = 0;
            float cur = 0;
            for (int i = 0; i < words.length; i++) {
                float add = (i == start ? 0 : space) + wid[i];
                if (i > start && cur + add > maxW && lines.size() < 2) {
                    lines.add(new int[]{start, i});
                    lw.add(cur);
                    start = i;
                    cur = wid[i];
                } else cur += add;
            }
            lines.add(new int[]{start, words.length});
            lw.add(cur);
            float maxLine = 0;
            for (float x : lw) maxLine = Math.max(maxLine, x);
            float sw = stroke ? size * 0.16f : 0;
            float pad = size * 0.45f + sw + ("neon".equals(style) ? size * 0.5f : 0);
            float lineH = size * 1.22f;
            int bw = (int) Math.ceil(Math.min(maxLine, vw) + pad * 2);
            int bh = (int) Math.ceil(lines.size() * lineH + pad * 2);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(Math.max(2, bw), Math.max(2, bh), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            if ("clean".equals(style)) {
                android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bg.setColor(0xAD000000);
                cv.drawRoundRect(new android.graphics.RectF(pad * 0.4f, pad * 0.4f, bw - pad * 0.4f, bh - pad * 0.4f), size * 0.3f, size * 0.3f, bg);
            }
            float base = pad + size * 0.92f;
            for (int li = 0; li < lines.size(); li++) {
                int[] r = lines.get(li);
                float x = (bw - lw.get(li)) / 2f;
                for (int i = r[0]; i < r[1]; i++) {
                    boolean act = i == a, past = i < a;
                    float ts = size * (grow && act ? 1.12f : 1f);
                    p.setTextSize(ts);
                    p.clearShadowLayer();
                    if ("box".equals(style) && act) {
                        android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        bp.setColor(accent);
                        cv.drawRoundRect(new android.graphics.RectF(x - size * 0.14f, base - size * 0.86f, x + wid[i] + size * 0.14f, base + size * 0.2f), size * 0.18f, size * 0.18f, bp);
                    }
                    int fill;
                    switch (style) {
                        case "karaoke": case "pop": case "neon": fill = act ? accent : Color.WHITE; break;
                        case "comic": fill = act ? Color.WHITE : accent; break;
                        case "minimal": fill = act || past ? Color.WHITE : 0xB3FFFFFF; break;
                        default: fill = Color.WHITE;
                    }
                    float yy = base + (grow && act ? size * 0.04f : 0);
                    if (sw > 0) {
                        p.setStyle(android.graphics.Paint.Style.STROKE);
                        p.setStrokeWidth(sw);
                        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                        p.setColor(Color.BLACK);
                        cv.drawText(words[i], x, yy, p);
                    }
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    if ("neon".equals(style)) p.setShadowLayer(size * 0.35f, 0, 0, accent);
                    else if ("minimal".equals(style)) p.setShadowLayer(size * 0.15f, 0, size * 0.05f, 0xB3000000);
                    p.setColor(fill);
                    cv.drawText(words[i], x, yy, p);
                    x += wid[i] + space;
                }
                base += lineH;
            }
            return bmp;
        }
    }

    private Effect captionEffect(JSONObject o, JSONArray cj, int w, int h) throws Exception {
        List<CapCue> cues = new ArrayList<>();
        for (int i = 0; i < cj.length(); i++) {
            JSONObject c = cj.getJSONObject(i);
            JSONArray ws = c.getJSONArray("w");
            CapWord[] arr = new CapWord[ws.length()];
            for (int j = 0; j < ws.length(); j++) {
                JSONArray x = ws.getJSONArray(j);
                arr[j] = new CapWord(x.getString(0), x.getLong(1) * 1000L, x.getLong(2) * 1000L);
            }
            if (arr.length > 0) cues.add(new CapCue(c.getLong("s") * 1000L, c.getLong("e") * 1000L, arr));
        }
        int accent;
        try { accent = Color.parseColor(o.optString("color", "#FFD60A")); } catch (Exception e) { accent = Color.parseColor("#FFD60A"); }
        List<TextureOverlay> list = new ArrayList<>();
        list.add(new CaptionOverlay(cues, o.optString("style", "karaoke"), accent, o.optBoolean("upper", true),
                (float) o.optDouble("font", 0.075), (float) o.optDouble("size", 1.0), w, h, o.optString("pos", "bottom")));
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

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        f.delete();
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
                tmpName = name;
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
        public synchronized String finishTake(String id) {
            try {
                tmpOut.close();
                tmpOut = null;
                String ext = tmpName.endsWith(".webm") ? ".webm" : ".mp4";
                File dest = new File(takesDir, id + ext);
                dest.delete();
                if (!tmpFile.renameTo(dest)) throw new Exception("no se pudo guardar la toma");
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized void deleteTake(String id) {
            File f = takeFile(id);
            if (f != null) f.delete();
            new File(normDir, id + ".mp4").delete();
        }

        @JavascriptInterface
        public synchronized void clearTakes() {
            deleteRecursive(takesDir);
            deleteRecursive(normDir);
            takesDir.mkdirs();
            normDir.mkdirs();
            lastUri = null;
        }

        @JavascriptInterface
        public synchronized String saveTake(String id) {
            try {
                File f = takeFile(id);
                if (f == null) return "no encontré la toma";
                lastUri = saveToGallery(f, "prompter-" + id + (f.getName().endsWith(".webm") ? ".webm" : ".mp4"), mimeOf(f));
                lastMime = mimeOf(f);
                return lastUri != null ? "ok" : "error";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public boolean hasShareable() { return lastUri != null; }

        /** Micrófonos que Android reconoce: [{name, ext}] (ext = externo: USB, Bluetooth o cable). */
        @JavascriptInterface
        public String micInfo() {
            JSONArray a = new JSONArray();
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    String t;
                    boolean ext = true;
                    switch (d.getType()) {
                        case AudioDeviceInfo.TYPE_BUILTIN_MIC: t = "Micrófono del celular"; ext = false; break;
                        case AudioDeviceInfo.TYPE_USB_DEVICE:
                        case AudioDeviceInfo.TYPE_USB_HEADSET:
                        case AudioDeviceInfo.TYPE_USB_ACCESSORY: t = "USB"; break;
                        case AudioDeviceInfo.TYPE_WIRED_HEADSET: t = "Micrófono de cable"; break;
                        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: t = "Bluetooth"; break;
                        case 26: t = "Bluetooth LE"; break;
                        default: continue;
                    }
                    CharSequence n = d.getProductName();
                    String name = ext && n != null && n.length() > 0 && !n.toString().equals(Build.MODEL) ? t + " (" + n + ")" : t;
                    boolean dup = false;
                    for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("name").equals(name)) dup = true;
                    if (!dup) a.put(new JSONObject().put("name", name).put("ext", ext));
                }
            } catch (Exception ignored) { }
            return a.toString();
        }

        /** Pide a Android usar el micrófono externo (Android 12+). on=false vuelve al del celular. */
        @JavascriptInterface
        public String routeExternal(boolean on) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (Build.VERSION.SDK_INT >= 31) {
                    if (!on) { am.clearCommunicationDevice(); return "ok"; }
                    int[] pref = {AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            26, AudioDeviceInfo.TYPE_BLUETOOTH_SCO};
                    for (int type : pref)
                        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices())
                            if (d.getType() == type) return am.setCommunicationDevice(d) ? "ok" : "rechazado";
                    return "sin dispositivo";
                }
                return "no disponible";
            } catch (Exception e) {
                return "error";
            }
        }

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
