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
import android.graphics.RectF;
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

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

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
            cachedArtwork = createStarryCompositeArtwork(rawCover, title.hashCode());
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

        Notification.Action prevAction = createNotificationAction("media/images/prev.png", android.R.drawable.ic_media_previous, "Previous", prevIntent);
        Notification.Action playPauseAction = createNotificationAction(
                isPlaying ? "media/images/pause.png" : "media/images/play.png",
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play",
                playPauseIntent
        );
        Notification.Action nextAction = createNotificationAction("media/images/next.png", android.R.drawable.ic_media_next, "Next", nextIntent);

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
                .setMediaSession((android.media.session.MediaSession.Token) mediaSession.getSessionToken().getToken())
                .setShowActionsInCompactView(0, 1, 2);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(cachedArtwork)
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

    private Notification.Action createNotificationAction(String assetPath, int fallbackRes, String title, PendingIntent intent) {
        Icon icon = null;
        try (InputStream is = getAssets().open(assetPath)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, 72, 72, true);
                icon = Icon.createWithBitmap(scaled);
            }
        } catch (Exception ignored) {}

        if (icon == null) {
            icon = Icon.createWithResource(this, fallbackRes);
        }
        return new Notification.Action.Builder(icon, title, intent).build();
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

    // ساخت تصویر اختصاصی نوتیفیکیشن: پس‌زمینه آسمان پرستاره + کاور آهنگ در سمت راست
    private Bitmap createStarryCompositeArtwork(Bitmap cover, int seed) {
        int w = 800;
        int h = 420;
        Bitmap output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // ۱. پس‌زمینه مشکی شب
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(0, 0, w, h,
                Color.parseColor("#020205"),
                Color.parseColor("#0A0A12"),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bgPaint);

        // ۲. رسم ستاره‌های درخشان در آسمان
        Random rnd = new Random(seed);
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 95; i++) {
            float sx = rnd.nextFloat() * w;
            float sy = rnd.nextFloat() * h;
            boolean isBright = i < 14;
            float sr = isBright ? (1.8f + rnd.nextFloat() * 1.6f) : (0.8f + rnd.nextFloat() * 1.2f);
            int alpha = isBright ? (180 + rnd.nextInt(75)) : (70 + rnd.nextInt(130));

            if (isBright) {
                starPaint.setColor(Color.argb(alpha / 3, 255, 252, 240));
                canvas.drawCircle(sx, sy, sr * 3.5f, starPaint);
                starPaint.setColor(Color.argb(alpha, 255, 255, 255));
                canvas.drawLine(sx - sr * 3.2f, sy, sx + sr * 3.2f, sy, starPaint);
                canvas.drawLine(sx, sy - sr * 3.2f, sx, sy + sr * 3.2f, starPaint);
            }
            starPaint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(sx, sy, sr, starPaint);
        }

        // ۳. قرار دادن کاور آهنگ در سمت راست کارت با حاشیه ظریف و گوشه گرد
        if (cover != null) {
            float coverSize = 230f;
            float rightMargin = 48f;
            float left = w - coverSize - rightMargin;
            float top = (h - coverSize) / 2f;
            RectF rect = new RectF(left, top, left + coverSize, top + coverSize);

            // سایه نرم پشت کاور
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(Color.argb(190, 0, 0, 0));
            shadowPaint.setShadowLayer(22f, 0f, 8f, Color.BLACK);
            canvas.drawRoundRect(rect, 34f, 34f, shadowPaint);

            // برش گوشه گرد کاور
            canvas.save();
            Path clipPath = new Path();
            clipPath.addRoundRect(rect, 34f, 34f, Path.Direction.CW);
            canvas.clipPath(clipPath);
            canvas.drawBitmap(cover, null, rect, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();

            // حاشیه ظریف دور کاور
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2.5f);
            borderPaint.setColor(Color.argb(65, 255, 255, 255));
            canvas.drawRoundRect(rect, 34f, 34f, borderPaint);
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
