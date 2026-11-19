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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.io.File;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private Icon iconPlay, iconPause, iconNext, iconPrev;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        smallMusicIcon = createCleanSmallIcon();
        iconPlay = createCleanTransportIcon("play");
        iconPause = createCleanTransportIcon("pause");
        iconNext = createCleanTransportIcon("next");
        iconPrev = createCleanTransportIcon("prev");

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
                final String title = intent.getStringExtra("title");
                final String artist = intent.getStringExtra("artist");
                final String coverUrl = intent.getStringExtra("coverUrl");
                final String mp3Url = intent.getStringExtra("mp3Url");
                final boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
                final long position = intent.getLongExtra("position", 0L);
                final long duration = intent.getLongExtra("duration", 0L);

                // انتقال کل پردازش سنگین عکس و نوتیفیکیشن به ترد پس‌زمینه (حذف کامل لگ هنگام پخش)
                bgExecutor.execute(() -> {
                    String safeTitle = (title != null) ? title : "Mavi";
                    String safeArtist = (artist != null) ? artist : "";
                    String key = (coverUrl != null ? coverUrl : "") + "|" + (mp3Url != null ? mp3Url : "");

                    Bitmap artworkToUse;
                    if (!key.equals(lastCoverKey) || cachedArtwork == null) {
                        lastCoverKey = key;
                        Bitmap rawCover = loadTrackCoverSampled(coverUrl, mp3Url);
                        cachedArtwork = createSeamlessStarryCover(rawCover, safeTitle.hashCode());
                        if (rawCover != null && !rawCover.isRecycled()) {
                            rawCover.recycle();
                        }
                    }
                    artworkToUse = cachedArtwork;

                    mainHandler.post(() -> publishNotification(safeTitle, safeArtist, artworkToUse, isPlaying, position, duration));
                });
            }
        }
        return START_NOT_STICKY;
    }

    private void publishNotification(String title, String artist, Bitmap artwork,
                                     boolean isPlaying, long position, long duration) {
        if (mediaSession == null) return;

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
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
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

        Notification.Action prevAction = new Notification.Action.Builder(iconPrev, "Previous", prevIntent).build();
        Notification.Action playPauseAction = new Notification.Action.Builder(
                isPlaying ? iconPause : iconPlay,
                isPlaying ? "Pause" : "Play", playPauseIntent).build();
        Notification.Action nextAction = new Notification.Action.Builder(iconNext, "Next", nextIntent).build();

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
                .setLargeIcon(artwork)
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

        try {
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
        } catch (Exception ignored) {}
    }

    private Icon createCleanSmallIcon() {
        int size = 72;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);

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

    // لود بهینه کاور با کوچک‌سازی خودکار (جلوگیری از مصرف رم بالا برای عکس‌های سنگین)
    private Bitmap loadTrackCoverSampled(String coverUrl, String mp3Url) {
        try {
            if (coverUrl != null && coverUrl.startsWith("file:///android_asset/")) {
                String path = coverUrl.replace("file:///android_asset/", "");
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream is = getAssets().open(path)) {
                    BitmapFactory.decodeStream(is, null, bounds);
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 320);
                try (InputStream is = getAssets().open(path)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    if (bmp != null) return bmp;
                }
            } else if (coverUrl != null && coverUrl.startsWith("file://")) {
                String path = Uri.parse(coverUrl).getPath();
                if (path != null && new File(path).exists()) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, bounds);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 320);
                    Bitmap bmp = BitmapFactory.decodeFile(path, opts);
                    if (bmp != null) return bmp;
                }
            }
        } catch (Exception ignored) {}

        if (mp3Url != null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                if (mp3Url.startsWith("file:///android_asset/")) {
                    String path = mp3Url.replace("file:///android_asset/", "");
                    try (AssetFileDescriptor afd = getAssets().openFd(path)) {
                        retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    }
                } else if (mp3Url.startsWith("file://")) {
                    String path = Uri.parse(mp3Url).getPath();
                    if (path != null) retriever.setDataSource(path);
                }
                byte[] art = retriever.getEmbeddedPicture();
                if (art != null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(art, 0, art.length, bounds);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 320);
                    return BitmapFactory.decodeByteArray(art, 0, art.length, opts);
                }
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private int calculateInSampleSize(int width, int height, int reqSize) {
        int inSampleSize = 1;
        if (height > reqSize || width > reqSize) {
            int halfH = height / 2;
            int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqSize && (halfW / inSampleSize) >= reqSize) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private Bitmap createSeamlessStarryCover(Bitmap cover, int seed) {
        int size = 384;
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.parseColor("#040408"));

        if (cover != null) {
            Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas layerCanvas = new Canvas(layer);

            Rect destRect = new Rect(90, 0, size, size);
            int minDim = Math.min(cover.getWidth(), cover.getHeight());
            int srcX = (cover.getWidth() - minDim) / 2;
            int srcY = (cover.getHeight() - minDim) / 2;
            Rect srcRect = new Rect(srcX, srcY, srcX + minDim, srcY + minDim);

            Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            layerCanvas.drawBitmap(cover, srcRect, destRect, bmpPaint);

            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maskPaint.setShader(new LinearGradient(90f, 0f, 270f, 0f,
                    new int[]{Color.TRANSPARENT, Color.argb(140, 0, 0, 0), Color.BLACK},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            layerCanvas.drawRect(0, 0, size, size, maskPaint);

            Paint dimPaint = new Paint();
            dimPaint.setColor(Color.argb(65, 0, 0, 0));
            layerCanvas.drawRect(0, 0, size, size, dimPaint);

            canvas.drawBitmap(layer, 0, 0, null);
            layer.recycle();
        }

        Random rnd = new Random(seed);
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 80; i++) {
            float sx = rnd.nextFloat() * (size * 0.85f);
            float sy = rnd.nextFloat() * size;
            boolean isBright = i < 12;
            float sr = isBright ? (1.2f + rnd.nextFloat() * 1.1f) : (0.6f + rnd.nextFloat() * 0.8f);
            int alpha = isBright ? (190 + rnd.nextInt(65)) : (75 + rnd.nextInt(130));

            if (isBright) {
                RadialGradient glow = new RadialGradient(sx, sy, sr * 4.0f,
                        Color.argb(alpha / 2, 255, 250, 235),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP);
                starPaint.setShader(glow);
                canvas.drawCircle(sx, sy, sr * 4.0f, starPaint);
                starPaint.setShader(null);

                starPaint.setColor(Color.argb(alpha, 255, 255, 255));
                starPaint.setStrokeWidth(0.8f);
                canvas.drawLine(sx - sr * 3.0f, sy, sx + sr * 3.0f, sy, starPaint);
                canvas.drawLine(sx, sy - sr * 3.0f, sx, sy + sr * 3.0f, starPaint);
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
            channel.setDescription("Controls for Mavi");
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
        bgExecutor.shutdownNow();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }
}
