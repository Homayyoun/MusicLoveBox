package com.lovebox.music;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String GITHUB_OWNER = "Homayyoun";
    private static final String GITHUB_REPO = "MusicLoveBox";
    private static final String GITHUB_BRANCH = "main";

    private WebView webView;
    private boolean isPlayerPageOpen = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Vibrator vibrator;
    private boolean isSyncing = false;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (webView != null) {
                webView.evaluateJavascript("if(typeof audioPlayer !== 'undefined' && !audioPlayer.paused) audioPlayer.pause();", null);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        MusicNotificationService.callback = new MusicNotificationService.MediaControlCallback() {
            @Override
            public void onPlayPause() {
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("togglePlayPause();", null);
                });
            }

            @Override
            public void onNext() {
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("playNext();", null);
                });
            }

            @Override
            public void onPrev() {
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("playPrev();", null);
                });
            }

            @Override
            public void onSeekTo(long posMs) {
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("seekFromNotification(" + (posMs / 1000.0) + ");", null);
                });
            }
        };

        setupAudioFocus();
        setupWebView();
        setupBackNavigation();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(Color.BLACK);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String urlStr = uri.toString();

                // ۱. لود عکس‌های آپدیت‌شده رابط کاربری از گیت‌هاب (مثل header.png یا quote.png)
                if (urlStr.startsWith("file:///android_asset/media/images/")) {
                    String imgName = urlStr.replace("file:///android_asset/media/images/", "");
                    File cloudImg = new File(new File(getFilesDir(), "cloud_images"), imgName);
                    if (cloudImg.exists() && cloudImg.length() > 0) {
                        try {
                            String mime = imgName.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                            return new WebResourceResponse(mime, "UTF-8", new FileInputStream(cloudImg));
                        } catch (Exception ignored) {}
                    }
                }

                // ۲. استخراج خودکار، کم‌حجم‌سازی و کش کردن کاور از داخل خود فایل MP3
                if ("https".equals(uri.getScheme()) && "lovebox.cover".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path != null && path.length() > 1) {
                        String fileName = Uri.decode(path.substring(1));
                        byte[] coverBytes = extractEmbeddedCoverOptimized(fileName);
                        if (coverBytes != null) {
                            return new WebResourceResponse("image/jpeg", "UTF-8", new ByteArrayInputStream(coverBytes));
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        File cloudHtml = new File(getFilesDir(), "cloud_m.html");
        if (cloudHtml.exists() && cloudHtml.length() > 500) {
            try (InputStream is = new FileInputStream(cloudHtml)) {
                String html = readStreamText(is);
                webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                return;
            } catch (Exception ignored) {}
        }
        webView.loadUrl("file:///android_asset/m.html");
    }

    // استخراج کاور از MP3 با کوچک‌سازی خودکار به ۳۶۰ پیکسل و ذخیره در کش (بدون لگ اسکرول)
    private byte[] extractEmbeddedCoverOptimized(String fileName) {
        File cacheDir = new File(getCacheDir(), "cover_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File cachedFile = new File(cacheDir, fileName + ".jpg");

        if (cachedFile.exists() && cachedFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(cachedFile)) {
                return readStreamBytes(fis);
            } catch (Exception ignored) {}
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            File cloudFile = new File(new File(getFilesDir(), "cloud_audios"), fileName);
            if (cloudFile.exists()) {
                retriever.setDataSource(cloudFile.getAbsolutePath());
            } else {
                try (AssetFileDescriptor afd = getAssets().openFd("media/audios/" + fileName)) {
                    retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                }
            }
            byte[] rawArt = retriever.getEmbeddedPicture();
            if (rawArt != null && rawArt.length > 0) {
                byte[] optimized = optimizeCoverBytes(rawArt, 360);
                try (FileOutputStream fos = new FileOutputStream(cachedFile)) {
                    fos.write(optimized);
                } catch (Exception ignored) {}
                return optimized;
            }
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    private byte[] optimizeCoverBytes(byte[] raw, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);

            int inSampleSize = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while ((w / inSampleSize) > maxDim * 2 || (h / inSampleSize) > maxDim * 2) {
                inSampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = inSampleSize;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (bmp == null) return raw;

            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw > maxDim || bh > maxDim) {
                float scale = Math.min((float) maxDim / bw, (float) maxDim / bh);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bw * scale), Math.round(bh * scale), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            return raw;
        }
    }

    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isPlayerPageOpen) {
                    webView.evaluateJavascript("closePlayerPage();", null);
                } else {
                    showExitDialog();
                }
            }
        });
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void setPlayerOpen(boolean isOpen) {
            isPlayerPageOpen = isOpen;
        }

        @JavascriptInterface
        public void triggerHeartbeatVibration() {
            try {
                if (vibrator != null && vibrator.hasVibrator()) {
                    long[] pattern = {0, 45, 95, 85};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build();
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes);
                    } else {
                        vibrator.vibrate(pattern, -1);
                    }
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void stopHeartbeatVibration() {
            try {
                if (vibrator != null) {
                    vibrator.cancel();
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void updateMediaNotification(String title, String artist, String coverUrl, String mp3Url,
                                            boolean isPlaying, double currentSec, double durationSec) {
            try {
                Intent intent = new Intent(MainActivity.this, MusicNotificationService.class);
                intent.setAction(MusicNotificationService.ACTION_UPDATE);
                intent.putExtra("title", title);
                intent.putExtra("artist", artist);
                intent.putExtra("coverUrl", coverUrl);
                intent.putExtra("mp3Url", mp3Url);
                intent.putExtra("isPlaying", isPlaying);
                intent.putExtra("position", (long) (currentSec * 1000));
                intent.putExtra("duration", (long) (durationSec * 1000));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String getCloudNotesJson() {
            SharedPreferences prefs = getSharedPreferences("lovebox_cloud", Context.MODE_PRIVATE);
            return prefs.getString("notes_json", "{}");
        }

        @JavascriptInterface
        public void syncWithCloud() {
            if (isSyncing) return;
            isSyncing = true;
            new Thread(() -> {
                boolean hasTrackOrNoteChanges = false;
                SharedPreferences prefs = getSharedPreferences("lovebox_cloud", Context.MODE_PRIVATE);

                try {
                    String remoteHtml = fetchUrlText("https://raw.githubusercontent.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/" + GITHUB_BRANCH + "/app/src/main/assets/m.html?t=" + System.currentTimeMillis());
                    if (remoteHtml == null) {
                        remoteHtml = fetchUrlText("https://cdn.jsdelivr.net/gh/" + GITHUB_OWNER + "/" + GITHUB_REPO + "@" + GITHUB_BRANCH + "/app/src/main/assets/m.html");
                    }
                    if (remoteHtml != null && remoteHtml.contains("</html>")) {
                        File cloudHtmlFile = new File(getFilesDir(), "cloud_m.html");
                        try (FileOutputStream fos = new FileOutputStream(cloudHtmlFile)) {
                            fos.write(remoteHtml.getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    String notesContent = fetchUrlText("https://raw.githubusercontent.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/" + GITHUB_BRANCH + "/notes.json?t=" + System.currentTimeMillis());
                    if (notesContent == null) {
                        notesContent = fetchUrlText("https://cdn.jsdelivr.net/gh/" + GITHUB_OWNER + "/" + GITHUB_REPO + "@" + GITHUB_BRANCH + "/notes.json");
                    }
                    if (notesContent != null && notesContent.trim().startsWith("{")) {
                        String oldNotes = prefs.getString("notes_json", "{}");
                        if (!notesContent.equals(oldNotes)) {
                            prefs.edit().putString("notes_json", notesContent).apply();
                            hasTrackOrNoteChanges = true;
                        }
                    }

                    String[] builtInAudios = getAssets().list("media/audios");
                    Set<String> builtInAudioSet = new HashSet<>();
                    if (builtInAudios != null) Collections.addAll(builtInAudioSet, builtInAudios);

                    File cloudAudioDir = new File(getFilesDir(), "cloud_audios");
                    if (!cloudAudioDir.exists()) cloudAudioDir.mkdirs();

                    String apiAudiosUrl = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/contents/app/src/main/assets/media/audios?ref=" + GITHUB_BRANCH;
                    String apiResponse = fetchUrlText(apiAudiosUrl);
                    if (apiResponse != null && apiResponse.trim().startsWith("[")) {
                        JSONArray filesArray = new JSONArray(apiResponse);
                        for (int i = 0; i < filesArray.length(); i++) {
                            JSONObject fileObj = filesArray.getJSONObject(i);
                            String name = fileObj.optString("name", "");
                            String sha = fileObj.optString("sha", "");
                            if (name.toLowerCase().endsWith(".mp3")) {
                                String savedSha = prefs.getString("audio_sha_" + name, "");
                                File targetFile = new File(cloudAudioDir, name);

                                if (!builtInAudioSet.contains(name) && (!targetFile.exists() || !sha.equals(savedSha))) {
                                    String cdnUrl = "https://cdn.jsdelivr.net/gh/" + GITHUB_OWNER + "/" + GITHUB_REPO + "@" + GITHUB_BRANCH + "/app/src/main/assets/media/audios/" + Uri.encode(name);
                                    boolean downloaded = downloadFile(cdnUrl, targetFile);
                                    if (!downloaded) {
                                        String rawUrl = fileObj.optString("download_url", "");
                                        if (!rawUrl.isEmpty()) downloaded = downloadFile(rawUrl, targetFile);
                                    }
                                    if (downloaded) {
                                        prefs.edit().putString("audio_sha_" + name, sha).apply();
                                        new File(new File(getCacheDir(), "cover_cache"), name + ".jpg").delete();
                                        hasTrackOrNoteChanges = true;
                                    }
                                }
                            }
                        }
                    }

                    String[] builtInImages = getAssets().list("media/images");
                    Set<String> builtInImageSet = new HashSet<>();
                    if (builtInImages != null) Collections.addAll(builtInImageSet, builtInImages);

                    File cloudImageDir = new File(getFilesDir(), "cloud_images");
                    if (!cloudImageDir.exists()) cloudImageDir.mkdirs();

                    String apiImagesUrl = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/contents/app/src/main/assets/media/images?ref=" + GITHUB_BRANCH;
                    String imgApiResponse = fetchUrlText(apiImagesUrl);
                    if (imgApiResponse != null && imgApiResponse.trim().startsWith("[")) {
                        JSONArray imgArray = new JSONArray(imgApiResponse);
                        for (int i = 0; i < imgArray.length(); i++) {
                            JSONObject imgObj = imgArray.getJSONObject(i);
                            String name = imgObj.optString("name", "");
                            String sha = imgObj.optString("sha", "");
                            if (name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png")) {
                                String savedSha = prefs.getString("img_sha_" + name, "");
                                File targetImg = new File(cloudImageDir, name);

                                if (savedSha.isEmpty() && builtInImageSet.contains(name)) {
                                    prefs.edit().putString("img_sha_" + name, sha).apply();
                                } else if (!sha.equals(savedSha) || (!builtInImageSet.contains(name) && !targetImg.exists())) {
                                    String cdnUrl = "https://cdn.jsdelivr.net/gh/" + GITHUB_OWNER + "/" + GITHUB_REPO + "@" + GITHUB_BRANCH + "/app/src/main/assets/media/images/" + Uri.encode(name);
                                    boolean downloaded = downloadFile(cdnUrl, targetImg);
                                    if (!downloaded) {
                                        String rawUrl = imgObj.optString("download_url", "");
                                        if (!rawUrl.isEmpty()) downloaded = downloadFile(rawUrl, targetImg);
                                    }
                                    if (downloaded) {
                                        prefs.edit().putString("img_sha_" + name, sha).apply();
                                        hasTrackOrNoteChanges = true;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    isSyncing = false;
                }

                if (hasTrackOrNoteChanges) {
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if(typeof onCloudSyncUpdated === 'function') onCloudSyncUpdated();", null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public String getTracksJson() {
            JSONArray jsonArray = new JSONArray();
            try {
                String[] assetAudios = getAssets().list("media/audios");
                File cloudAudioDir = new File(getFilesDir(), "cloud_audios");

                Map<String, Boolean> isCloudMap = new HashMap<>();
                List<String> allMp3s = new ArrayList<>();

                if (assetAudios != null) {
                    for (String f : assetAudios) {
                        if (f.toLowerCase().endsWith(".mp3")) {
                            allMp3s.add(f);
                            isCloudMap.put(f, false);
                        }
                    }
                }

                if (cloudAudioDir.exists() && cloudAudioDir.listFiles() != null) {
                    for (File cf : cloudAudioDir.listFiles()) {
                        String name = cf.getName();
                        if (name.toLowerCase().endsWith(".mp3") && cf.length() > 1024 && !isCloudMap.containsKey(name)) {
                            allMp3s.add(name);
                            isCloudMap.put(name, true);
                        }
                    }
                }

                Collections.sort(allMp3s, (a, b) -> {
                    int numA = extractNumber(a);
                    int numB = extractNumber(b);
                    if (numA != -1 && numB != -1 && numA != numB) {
                        return Integer.compare(numA, numB);
                    }
                    return a.compareToIgnoreCase(b);
                });

                int idCounter = 1;
                for (String fileName : allMp3s) {
                    boolean isCloud = Boolean.TRUE.equals(isCloudMap.get(fileName));
                    String title = "";
                    String artist = "";
                    String mp3Url;

                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        if (isCloud) {
                            File cf = new File(cloudAudioDir, fileName);
                            retriever.setDataSource(cf.getAbsolutePath());
                            mp3Url = Uri.fromFile(cf).toString();
                        } else {
                            String assetPath = "media/audios/" + fileName;
                            try (AssetFileDescriptor afd = getAssets().openFd(assetPath)) {
                                retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                            }
                            mp3Url = "file:///android_asset/" + assetPath;
                        }
                        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    } catch (Exception ignored) {
                        mp3Url = isCloud
                                ? Uri.fromFile(new File(cloudAudioDir, fileName)).toString()
                                : "file:///android_asset/media/audios/" + fileName;
                    } finally {
                        try { retriever.release(); } catch (Exception ignored) {}
                    }

                    if (title == null || title.trim().isEmpty()) {
                        title = fileName.replaceFirst("[.][^.]+$", "");
                    }
                    if (artist == null || artist.trim().isEmpty()) {
                        artist = "My Love's Playlist";
                    }

                    // استخراج خودکار کاور از داخل فایل MP3 برای همه آهنگ‌ها
                    String coverUrl = "https://lovebox.cover/" + Uri.encode(fileName);

                    JSONObject obj = new JSONObject();
                    obj.put("id", idCounter++);
                    obj.put("fileName", fileName);
                    obj.put("title", title.trim());
                    obj.put("artist", artist.trim());
                    obj.put("cover", coverUrl);
                    obj.put("mp3", mp3Url);
                    jsonArray.put(obj);
                }
            } catch (Exception ignored) {}
            return jsonArray.toString();
        }

        @JavascriptInterface
        public String getLyrics(String mp3Url) {
            if (mp3Url == null) return "";

            if (mp3Url.startsWith("file:///android_asset/")) {
                String assetPath = mp3Url.replace("file:///android_asset/", "");
                String lrcPath = assetPath.replaceAll("\\.mp3$", ".lrc");
                try (InputStream lrcStream = getAssets().open(lrcPath)) {
                    return readStreamText(lrcStream);
                } catch (IOException ignored) {}

                try (InputStream is = getAssets().open(assetPath)) {
                    return extractId3LyricsFromStream(is);
                } catch (IOException ignored) {}
            } else if (mp3Url.startsWith("file://")) {
                String filePath = Uri.parse(mp3Url).getPath();
                if (filePath != null) {
                    File mp3File = new File(filePath);
                    if (mp3File.exists()) {
                        try (InputStream is = new FileInputStream(mp3File)) {
                            return extractId3LyricsFromStream(is);
                        } catch (IOException ignored) {}
                    }
                }
            }
            return "";
        }
    }

    private byte[] readStreamBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private String readStreamText(InputStream is) throws IOException {
        return new String(readStreamBytes(is), StandardCharsets.UTF_8);
    }

    private String fetchUrlText(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MusicLoveBox-App");
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private boolean downloadFile(String urlStr, File destFile) {
        HttpURLConnection conn = null;
        File tempFile = new File(destFile.getParentFile(), destFile.getName() + ".tmp");
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("User-Agent", "MusicLoveBox-App");
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                }
                if (tempFile.length() > 100) {
                    if (destFile.exists()) destFile.delete();
                    return tempFile.renameTo(destFile);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (tempFile.exists()) tempFile.delete();
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private int extractNumber(String name) {
        String digits = name.replaceAll("\\D+", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String extractId3LyricsFromStream(InputStream is) {
        try {
            byte[] header = new byte[10];
            if (is.read(header) != 10) return "";
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return "";

            int version = header[3] & 0xFF;
            int flags = header[5] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21) |
                          ((header[7] & 0x7F) << 14) |
                          ((header[8] & 0x7F) << 7)  |
                          (header[9] & 0x7F);

            if ((flags & 0x40) != 0) {
                byte[] extSizeBytes = new byte[4];
                if (is.read(extSizeBytes) != 4) return "";
                int extSize = (version == 4)
                        ? (((extSizeBytes[0] & 0x7F) << 21) | ((extSizeBytes[1] & 0x7F) << 14) | ((extSizeBytes[2] & 0x7F) << 7) | (extSizeBytes[3] & 0x7F))
                        : (((extSizeBytes[0] & 0xFF) << 24) | ((extSizeBytes[1] & 0xFF) << 16) | ((extSizeBytes[2] & 0xFF) << 8) | (extSizeBytes[3] & 0xFF));
                is.skip(extSize - 4);
            }

            int bytesRead = 0;
            byte[] frameHeader = new byte[10];

            while (bytesRead + 10 < tagSize) {
                if (is.read(frameHeader) != 10) break;
                bytesRead += 10;

                if (frameHeader[0] == 0) break;

                String frameId = new String(frameHeader, 0, 4, StandardCharsets.ISO_8859_1);
                int frameSize;
                if (version == 4) {
                    frameSize = ((frameHeader[4] & 0x7F) << 21) |
                                ((frameHeader[5] & 0x7F) << 14) |
                                ((frameHeader[6] & 0x7F) << 7)  |
                                (frameHeader[7] & 0x7F);
                } else {
                    frameSize = ((frameHeader[4] & 0xFF) << 24) |
                                ((frameHeader[5] & 0xFF) << 16) |
                                ((frameHeader[6] & 0xFF) << 8)  |
                                (frameHeader[7] & 0xFF);
                }

                if (frameSize <= 0 || bytesRead + frameSize > tagSize) break;

                if ("USLT".equals(frameId)) {
                    byte[] data = new byte[frameSize];
                    int read = 0;
                    while (read < frameSize) {
                        int r = is.read(data, read, frameSize - read);
                        if (r == -1) break;
                        read += r;
                    }
                    return decodeUsltFrame(data);
                } else {
                    long skipped = 0;
                    while (skipped < frameSize) {
                        long s = is.skip(frameSize - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                    bytesRead += frameSize;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String decodeUsltFrame(byte[] data) {
        if (data.length <= 4) return "";
        int encoding = data[0] & 0xFF;
        int pos = 4;

        if (encoding == 0 || encoding == 3) {
            while (pos < data.length && data[pos] != 0) {
                pos++;
            }
            pos++;
            if (pos >= data.length) return "";
            Charset charset = (encoding == 3) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            return new String(data, pos, data.length - pos, charset).replace("\u0000", "").trim();
        } else if (encoding == 1 || encoding == 2) {
            while (pos + 1 < data.length && !(data[pos] == 0 && data[pos + 1] == 0)) {
                pos += 2;
            }
            pos += 2;
            if (pos >= data.length) return "";
            Charset charset = (encoding == 1) ? StandardCharsets.UTF_16 : StandardCharsets.UTF_16BE;
            return new String(data, pos, data.length - pos, charset).replace("\u0000", "").trim();
        }
        return "";
    }

    private void showExitDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);

        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(Color.parseColor("#CC000000"));
        dialogBg.setCornerRadius(40f);
        dialogBg.setStroke(1, Color.parseColor("#40FFFFFF"));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 40);
        layout.setBackground(dialogBg);
        layout.setGravity(Gravity.CENTER);

        ImageView questionImg = new ImageView(this);
        questionImg.setImageBitmap(loadAssetBitmap("media/images/question.png"));
        questionImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        questionImg.setAdjustViewBounds(true);
        questionImg.setMaxWidth(450);
        questionImg.setMaxHeight(200);
        questionImg.setPadding(20, 20, 20, 30);
        layout.addView(questionImg);

        ImageButton btnExit = new ImageButton(this);
        btnExit.setImageBitmap(loadAssetBitmap("media/images/sleepy.png"));
        btnExit.setBackgroundColor(Color.TRANSPARENT);
        btnExit.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btnExit.setAdjustViewBounds(true);
        btnExit.setMaxWidth(350);
        btnExit.setMaxHeight(120);
        btnExit.setPadding(0, 15, 0, 15);
        layout.addView(btnExit);

        View separator = new View(this);
        separator.setBackgroundColor(Color.parseColor("#30FFFFFF"));
        separator.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        layout.addView(separator);

        ImageButton btnCancel = new ImageButton(this);
        btnCancel.setImageBitmap(loadAssetBitmap("media/images/finger.png"));
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btnCancel.setAdjustViewBounds(true);
        btnCancel.setMaxWidth(350);
        btnCancel.setMaxHeight(120);
        btnCancel.setPadding(0, 15, 0, 15);
        layout.addView(btnCancel);

        builder.setView(layout);
        final AlertDialog dialog = builder.create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().setDimAmount(0.6f);
        }

        btnExit.setOnClickListener(v -> {
            dialog.dismiss();
            finish();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private Bitmap loadAssetBitmap(String path) {
        try (InputStream is = getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusChangeListener);
            }
        }
        try {
            stopService(new Intent(this, MusicNotificationService.class));
        } catch (Exception ignored) {}
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
