package com.lovebox.music;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private boolean isPlayerPageOpen = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

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

        setupAudioFocus();
        setupWebView();
        setupBackNavigation();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

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
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/m.html");
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
        public String getLyrics(String mp3Url) {
            if (mp3Url == null) return "";
            String assetPath = mp3Url.replace("file:///android_asset/", "");

            // ۱. اگر فایل .lrc همنام کنار آهنگ بود اولویت با آن است
            String lrcPath = assetPath.replaceAll("\\.mp3$", ".lrc");
            try (InputStream lrcStream = getAssets().open(lrcPath)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = lrcStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toString("UTF-8");
            } catch (IOException ignored) {}

            // ۲. استخراج خودکار لیریک (USLT) از داخل تگ ID3v2 خود فایل MP3
            return extractId3Lyrics(assetPath);
        }
    }

    private String extractId3Lyrics(String assetPath) {
        try (InputStream is = getAssets().open(assetPath)) {
            byte[] header = new byte[10];
            if (is.read(header) != 10) return "";
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return "";

            int version = header[3] & 0xFF;
            int flags = header[5] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21) |
                          ((header[7] & 0x7F) << 14) |
                          ((header[8] & 0x7F) << 7)  |
                          (header[9] & 0x7F);

            // رد کردن Extended Header در صورت وجود
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

                if (frameHeader[0] == 0) break; // Padding

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
        int pos = 4; // 1 byte encoding + 3 bytes language (e.g. "eng")

        if (encoding == 0 || encoding == 3) {
            while (pos < data.length && data[pos] != 0) {
                pos++;
            }
            pos++; // رد کردن بایت صفرِ پایانِ Descriptor
            if (pos >= data.length) return "";
            Charset charset = (encoding == 3) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            return new String(data, pos, data.length - pos, charset).trim();
        } else if (encoding == 1 || encoding == 2) {
            while (pos + 1 < data.length && !(data[pos] == 0 && data[pos + 1] == 0)) {
                pos += 2;
            }
            pos += 2; // رد کردن دو بایت صفرِ پایانِ Descriptor در UTF-16
            if (pos >= data.length) return "";
            Charset charset = (encoding == 1) ? StandardCharsets.UTF_16 : StandardCharsets.UTF_16BE;
            return new String(data, pos, data.length - pos, charset).trim();
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
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
