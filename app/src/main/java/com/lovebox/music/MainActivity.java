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

import java.io.IOException;
import java.io.InputStream;

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

        // 1) تصویر سوال
        ImageView questionImg = new ImageView(this);
        questionImg.setImageBitmap(loadAssetBitmap("media/images/question.png"));
        questionImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        questionImg.setAdjustViewBounds(true);
        questionImg.setMaxWidth(450);
        questionImg.setMaxHeight(200);
        questionImg.setPadding(20, 20, 20, 30);
        layout.addView(questionImg);

        // 2) دکمه خوابم میاد
        ImageButton btnExit = new ImageButton(this);
        btnExit.setImageBitmap(loadAssetBitmap("media/images/sleepy.png"));
        btnExit.setBackgroundColor(Color.TRANSPARENT);
        btnExit.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btnExit.setAdjustViewBounds(true);
        btnExit.setMaxWidth(350);
        btnExit.setMaxHeight(120);
        btnExit.setPadding(0, 15, 0, 15);
        layout.addView(btnExit);

        // خط جداکننده
        View separator = new View(this);
        separator.setBackgroundColor(Color.parseColor("#30FFFFFF"));
        separator.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        layout.addView(separator);

        // 3) دکمه دستم خورد
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
