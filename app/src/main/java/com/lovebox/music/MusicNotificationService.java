package com.lovebox.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Icon;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.io.InputStream;
import java.util.Random;

public class MusicNotificationService extends Service {

    public static final String CHANNEL_ID = "lovebox_media_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_UPDATE = "com.lovebox.music.ACTION_UPDATE";
    public static final String ACTION_PLAY_PAUSE = "com.lovebox.music.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.lovebox.music.ACTION_NEXT";
    public static final String ACTION_PREV = "com.lovebox.music.ACTION_PREV";

    public interface MediaControlCallback {
        void onPlayPause();
        void onNext();
        void onPrev();
        void onSeekTo(long posMs);
    }

    public static MediaControlCallback callback;

    private MediaSessionCompat mediaSession;
    private String lastCoverKey = "";
    private Bitmap cachedArtwork = null;
    private Icon smallMusicIcon = null;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        smallMusicIcon = createCleanSmallIcon();

        mediaSession = new MediaSessionCompat(this, "MusicLoveBoxSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (callback != null) callback.onPlayPause();
            }

            @Override
            public void onPause() {
                if (callback != null) callback.onPlayPause();
            }

            @Override
            public void onSkipToNext() {
                if (callback != null) callback.onNext();
            }

            @Override
            public void onSkipToPrevious() {
                if (callback != null) callback.onPrev();
            }

            @Override
            public void onSeekTo(long pos) {
                if (callback != null) callback.onSeekTo(pos);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_PLAY_PAUSE.equals(action)) {
                if (callback != null) callback.onPlayPause();
            } else if (ACTION_NEXT.equals(action)) {
                if (callback != null) callback.onNext();
            } else if (ACTION_PREV.equals(action)) {
                if (callback != null) callback.onPrev();
            } else if (ACTION_UPDATE.equals(action)) {
                String title = intent.getStringExtra("title");
                String artist = intent.getStringExtra("artist");
                String coverUrl = intent.getStringExtra("coverUrl");
                String mp3Url = intent.getStringExtra("mp3Url");
                boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
                long position = intent.getLongExtra("position", 0L);
                long duration = intent.getLongExtra("duration", 0L);

                updateMediaNotification(title, artist, coverUrl, mp3Url, isPlaying, position, duration);
            }
        }
        return START_NOT_STICKY;
    }

    private void updateMediaNotification(String title, String artist, String coverUrl, String mp3Url,
                                         boolean isPlaying, long position, long duration) {
        if (title == null) title = "Music Love Box";
        if (artist == null) artist = "";

        String key = (coverUrl != null ? coverUrl : "") + "|" + (mp3Url != null ? mp3Url : "");
        if (!key.equals(lastCoverKey) || cachedArtwork == null) {
            lastCoverKey = key;
            Bitmap rawCover = loadTrackCover(coverUrl, mp3Url);
            cachedArtwork = createSeamlessStarryCover(rawCover, title.hashCode());
        }

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        float speed = isPlaying ? 1.0f : 0.0f;

        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, speed)
                .build();
        mediaSession.setPlaybackState(playbackState);

        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cachedArtwork)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cachedArtwork)
                .build();
        mediaSession.setMetadata(metadata);

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent prevIntent = createActionIntent(ACTION_PREV, 1);
        PendingIntent playPauseIntent = createActionIntent(ACTION_PLAY_PAUSE, 2);
        PendingIntent nextIntent = createActionIntent(ACTION_NEXT, 3);

        Notification.Action prevAction = new Notification.Action.Builder(
                createCleanTransportIcon("prev"), "Previous", prevIntent).build();
        Notification.Action playPauseAction = new Notification.Action.Builder(
                createCleanTransportIcon(isPlaying ? "pause" : "play"),
                isPlaying ? "Pause" : "Play", playPauseIntent).build();
        Notification.Action nextAction = new Notification.Action.Builder(
                createCleanTransportIcon("next"), "Next", nextIntent).build();

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
                .setMediaSession((android.media.session.MediaSession.Token) mediaSession.getSessionToken().getToken())
                .setShowActionsInCompactView(0, 1, 2);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        if (smallMusicIcon != null) {
            builder.setSmallIcon(smallMusicIcon);
        } else {
            builder.setSmallIcon(R.drawable.ic_launcher);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        Notification notification = builder
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(cachedArtwork)
                .setColor(Color.parseColor("#08080E"))
                .setColorized(true)
                .setContentIntent(contentIntent)
                .setStyle(mediaStyle)
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        }
    }

    // ساخت آیکون کوچک شفاف (نت موسیقی ظریف) برای بالای نوتیفیکیشن تا کادر سفید توپر نیفتد
    private Icon createCleanSmallIcon() {
        int size = 72;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);

        // رسم نت موسیقی استاندارد (♪)
        c.drawOval(14f, 42f, 36f, 60f, p);
        c.drawRect(30f, 14f, 36f, 52f, p);
        Path flag = new Path();
        flag.moveTo(36f, 14f);
        flag.cubicTo(52f, 16f, 58f, 26f, 54f, 38f);
        flag.cubicTo(52f, 28f, 44f, 24f, 36f, 24f);
        flag.close();
        c.drawPath(flag, p);

        return Icon.createWithBitmap(bmp);
    }

    // ساخت آیکون‌های تمیز، تیز و استاندارد برای دکمه‌های نوتیفیکیشن
    private Icon createCleanTransportIcon(String type) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);

        if ("play".equals(type)) {
            Path path = new Path();
            path.moveTo(30f, 20f);
            path.lineTo(74f, 48f);
            path.lineTo(30f, 76f);
            path.close();
            c.drawPath(path, p);
        } else if ("pause".equals(type)) {
            c.drawRoundRect(26f, 20f, 42f, 76f, 5f, 5f, p);
            c.drawRoundRect(54f, 20f, 70f, 76f, 5f, 5f, p);
        } else if ("next".equals(type)) {
            Path path = new Path();
            path.moveTo(22f, 22f);
            path.lineTo(62f, 48f);
            path.lineTo(22f, 74f);
            path.close();
            c.drawPath(path, p);
            c.drawRoundRect(64f, 22f, 74f, 74f, 4f, 4f, p);
        } else if ("prev".equals(type)) {
            c.drawRoundRect(22f, 22f, 32f, 74f, 4f, 4f, p);
            Path path = new Path();
            path.moveTo(74f, 22f);
            path.lineTo(34f, 48f);
            path.lineTo(74f, 74f);
            path.close();
            c.drawPath(path, p);
        }
        return Icon.createWithBitmap(bmp);
    }

    private PendingIntent createActionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicNotificationService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Bitmap loadTrackCover(String coverUrl, String mp3Url) {
        if (coverUrl != null && coverUrl.startsWith("file:///android_asset/")) {
            String path = coverUrl.replace("file:///android_asset/", "");
            try (InputStream is = getAssets().open(path)) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) return bmp;
            } catch (Exception ignored) {}
        }

        if (mp3Url != null && mp3Url.startsWith("file:///android_asset/")) {
            String path = mp3Url.replace("file:///android_asset/", "");
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try (AssetFileDescriptor afd = getAssets().openFd(path)) {
                retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                byte[] art = retriever.getEmbeddedPicture();
                if (art != null) {
                    return BitmapFactory.decodeByteArray(art, 0, art.length);
                }
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // ساخت تصویر مربعی استاندارد (600x600): کاور آهنگ در سمت راست که به نرمی در آسمان پرستاره سمت چپ محو می‌شود
    private Bitmap createSeamlessStarryCover(Bitmap cover, int seed) {
        int size = 600;
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // ۱. پس‌زمینه مشکی مطلق
        canvas.drawColor(Color.parseColor("#040408"));

        // ۲. اگر کاور وجود دارد، آن را در نیمه راست قرار می‌دهیم و لبه‌هایش را به نرمی در مشکی سمت چپ محو (Blend) می‌کنیم
        if (cover != null) {
            Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas layerCanvas = new Canvas(layer);

            // قرار دادن کاور در سمت راست بوم
            Rect destRect = new Rect(140, 0, size, size);
            int minDim = Math.min(cover.getWidth(), cover.getHeight());
            int srcX = (cover.getWidth() - minDim) / 2;
            int srcY = (cover.getHeight() - minDim) / 2;
            Rect srcRect = new Rect(srcX, srcY, srcX + minDim, srcY + minDim);

            Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            layerCanvas.drawBitmap(cover, srcRect, destRect, bmpPaint);

            // ماسک محوشونده افقی (از چپ کاملاً شفاف، به راست کاملاً واضح)
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maskPaint.setShader(new LinearGradient(140f, 0f, 420f, 0f,
                    new int[]{Color.TRANSPARENT, Color.argb(140, 0, 0, 0), Color.BLACK},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            layerCanvas.drawRect(0, 0, size, size, maskPaint);

            // کمی تیره‌تر کردن کلی کاور تا متن سفید نوتیفیکیشن همیشه خوانا باشد
            Paint dimPaint = new Paint();
            dimPaint.setColor(Color.argb(65, 0, 0, 0));
            layerCanvas.drawRect(0, 0, size, size, dimPaint);

            canvas.drawBitmap(layer, 0, 0, null);
        }

        // ۳. رسم آسمان پرستاره طبیعی روی نیمه چپ و میانی
        Random rnd = new Random(seed);
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 110; i++) {
            // تمرکز بیشتر ستاره‌ها در سمت چپ و وسط (زیر اسم آهنگ)
            float sx = rnd.nextFloat() * (size * 0.85f);
            float sy = rnd.nextFloat() * size;
            boolean isBright = i < 16;
            float sr = isBright ? (1.6f + rnd.nextFloat() * 1.5f) : (0.7f + rnd.nextFloat() * 1.1f);
            int alpha = isBright ? (190 + rnd.nextInt(65)) : (75 + rnd.nextInt(130));

            if (isBright) {
                RadialGradient glow = new RadialGradient(sx, sy, sr * 4.2f,
                        Color.argb(alpha / 2, 255, 250, 235),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP);
                starPaint.setShader(glow);
                canvas.drawCircle(sx, sy, sr * 4.2f, starPaint);
                starPaint.setShader(null);

                starPaint.setColor(Color.argb(alpha, 255, 255, 255));
                starPaint.setStrokeWidth(0.9f);
                canvas.drawLine(sx - sr * 3.2f, sy, sx + sr * 3.2f, sy, starPaint);
                canvas.drawLine(sx, sy - sr * 3.2f, sx, sy + sr * 3.2f, starPaint);
            }
            starPaint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(sx, sy, sr, starPaint);
        }

        return output;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Controls for Music Love Box");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }
}
