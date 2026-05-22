package com.mycompany.application2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int PROTOCOL_MAGIC = 0x41524431;
    private static final int PROTOCOL_VERSION = 3;
    private static final int HANDSHAKE_STATUS_OK = 0;
    private static final int HANDSHAKE_STATUS_AUTH_FAILED = 1;
    private static final int HANDSHAKE_STATUS_PROTOCOL_MISMATCH = 2;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int RELAY_MAGIC = 0x41525231;
    private static final int RELAY_VERSION = 1;
    private static final int RELAY_ROLE_HOST = 1;
    private static final int RELAY_CHANNEL_VIDEO = 1;
    private static final int RELAY_CHANNEL_AUDIO = 2;
    private static final int RELAY_STATUS_OK = 0;
    private static final int RELAY_CONNECT_TIMEOUT_MS = 10_000;
    private static final int RELAY_WAIT_TIMEOUT_MS = 60_000;
    private static final int VIDEO_PACKET_CONFIG = 1;
    private static final int VIDEO_PACKET_FRAME = 2;
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int VIDEO_I_FRAME_INTERVAL = 1;
    private static final int QUALITY_PROFILE_HD = 1;
    private static final int QUALITY_PROFILE_SAVE_DATA = 2;
    private static final int HD_VIDEO_FRAME_RATE = 20;
    private static final int HD_MIN_VIDEO_BITRATE = 6_000_000;
    private static final int HD_VIDEO_BITRATE_FACTOR = 8;
    private static final int SAVE_DATA_VIDEO_FRAME_RATE = 15;
    private static final int SAVE_DATA_MIN_VIDEO_BITRATE = 2_500_000;
    private static final int SAVE_DATA_VIDEO_BITRATE_FACTOR = 4;
    private static final String BUTTON_START = "启动服务";
    private static final String BUTTON_STOP = "停止服务";
    private static final int REQUEST_SCREEN_CAPTURE = 1;
    private static final int REQUEST_RECORD_AUDIO = 2;
    private static final int SERVER_PORT = 8888;
    private static final int AUDIO_PORT = SERVER_PORT + 1;
    private static final int DEFAULT_RELAY_PORT = 9000;
    private static final int DISCOVERY_PORT = 8891;
    private static final String DISCOVERY_REQUEST = "ANDROID_REMOTE_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE = "ANDROID_REMOTE_SERVICE_V1";
    private static final int CMD_GESTURE = 1;
    private static final int CMD_BACK = 2;
    private static final int CMD_SET_QUALITY = 3;
    private static final int AUDIO_SAMPLE_RATE = 16000;
    private static final int AUDIO_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int JPEG_QUALITY = 55;
    private static final int HD_MAX_FRAME_EDGE = 1440;
    private static final int SAVE_DATA_MAX_FRAME_EDGE = 960;
    private static final long MIN_FRAME_INTERVAL_MS = 100L;
    private static final long DISCOVERY_READY_TIMEOUT_MS = 1200L;

    private TextView addressTextView;
    private TextView statusTextView;
    private Button toggleButton;
    private CheckBox relayModeCheckBox;
    private EditText relayHostInput;
    private EditText relayPortInput;
    private EditText relayRoomInput;
    private EditText authTokenInput;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final Object videoConnectionLock = new Object();
    private final Object audioConnectionLock = new Object();
    private final Object frameLock = new Object();
    private final Object videoCodecLock = new Object();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final DisplayMetrics realMetrics = new DisplayMetrics();
    private final FrameSlot[] frameSlots = {new FrameSlot(), new FrameSlot()};

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaCodec videoEncoder;
    private Surface videoEncoderInputSurface;
    private WindowManager windowManager;
    private Display display;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private ServerSocket videoServerSocket;
    private ServerSocket audioServerSocket;
    private DatagramSocket discoverySocket;
    private Socket clientSocket;
    private DataOutputStream videoOutput;
    private DataInputStream commandInput;
    private Socket audioClientSocket;
    private DataOutputStream audioOutput;

    private Bitmap captureBitmap;
    private Bitmap encodeBitmap;
    private Canvas encodeCanvas;
    private android.graphics.Rect captureRect = new android.graphics.Rect();
    private android.graphics.Rect encodeRect = new android.graphics.Rect();

    private volatile boolean serviceRunning = false;
    private volatile boolean clientConnected = false;
    private volatile FrameSlot latestFrame;
    private volatile long frameSequence = 0L;
    private volatile String activeAuthToken = "";
    private volatile String publicEndpointText = "查询中";
    private volatile RelayConfig activeRelayConfig;
    private volatile int activeVideoQualityProfile = QUALITY_PROFILE_HD;
    private volatile boolean videoEncoderLoopRunning = false;
    private volatile byte[] videoCsd0 = new byte[0];
    private volatile byte[] videoCsd1 = new byte[0];
    private volatile int encodedVideoWidth = 0;
    private volatile int encodedVideoHeight = 0;
    private int nextFrameSlotIndex = 0;
    private long lastFrameTimestamp = 0L;
    private int lastFrameSignature = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        addressTextView = findViewById(R.id.activitymainTextView2);
        statusTextView = findViewById(R.id.activitymainTextView1);
        toggleButton = findViewById(R.id.activitymainButton1);
        relayModeCheckBox = findViewById(R.id.activitymainRelayModeCheckBox);
        relayHostInput = findViewById(R.id.activitymainRelayHostEditText);
        relayPortInput = findViewById(R.id.activitymainRelayPortEditText);
        relayRoomInput = findViewById(R.id.activitymainRelayRoomEditText);
        authTokenInput = findViewById(R.id.activitymainAuthTokenEditText);
        relayPortInput.setText(String.valueOf(DEFAULT_RELAY_PORT));
        toggleButton.setOnClickListener(this);
        relayModeCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updateAddressInfo();
            }
        });

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        display = windowManager.getDefaultDisplay();
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        captureThread = new HandlerThread("remote-capture-thread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        requestAudioPermissionIfNeeded();
        requestScreenShot();
        updateConfigInputs(true);
        updateAddressInfo();
        refreshPublicAddressInfo();
        updatePrerequisiteStatus();
    }

    private void updateConfigInputs(boolean enabled) {
        relayModeCheckBox.setEnabled(enabled);
        relayHostInput.setEnabled(enabled);
        relayPortInput.setEnabled(enabled);
        relayRoomInput.setEnabled(enabled);
        authTokenInput.setEnabled(enabled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPublicAddressInfo();
        updatePrerequisiteStatus();
    }

    private void requestAudioPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestScreenShot() {
        if (projectionManager != null) {
            startKeepAliveService();
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                createVirtualDisplay();
                updateAddressInfo();
                updatePrerequisiteStatus();
            } else {
                toast("必须同意屏幕录制授权才能启动服务");
                finish();
            }
        }
    }

    private void createVirtualDisplay() {
        if (mediaProjection == null) {
            return;
        }
        synchronized (videoCodecLock) {
            releaseVirtualDisplay();
            display.getRealMetrics(realMetrics);
            int[] encodedSize = buildEncodedVideoSize(realMetrics.widthPixels, realMetrics.heightPixels);
            encodedVideoWidth = encodedSize[0];
            encodedVideoHeight = encodedSize[1];
            try {
                MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, encodedVideoWidth, encodedVideoHeight);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    Math.max(getActiveMinVideoBitrate(), encodedVideoWidth * encodedVideoHeight * getActiveVideoBitrateFactor())
                );
                format.setInteger(MediaFormat.KEY_FRAME_RATE, getActiveVideoFrameRate());
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL);
                videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoderInputSurface = videoEncoder.createInputSurface();
                videoEncoder.start();
                startVideoEncoderLoop(videoEncoder);
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    encodedVideoWidth,
                    encodedVideoHeight,
                    realMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    videoEncoderInputSurface,
                    null,
                    captureHandler
                );
            } catch (IOException | RuntimeException error) {
                releaseVirtualDisplay();
                setStatus("H.264 编码器初始化失败：" + safeMessage(error));
                toast("视频编码初始化失败");
            }
        }
    }

    private int[] buildEncodedVideoSize(int sourceWidth, int sourceHeight) {
        float scale = Math.min(1.0f, getActiveMaxFrameEdge() / (float) Math.max(sourceWidth, sourceHeight));
        int targetWidth = Math.max(2, Math.round(sourceWidth * scale));
        int targetHeight = Math.max(2, Math.round(sourceHeight * scale));
        if ((targetWidth & 1) != 0) {
            targetWidth -= 1;
        }
        if ((targetHeight & 1) != 0) {
            targetHeight -= 1;
        }
        return new int[]{Math.max(2, targetWidth), Math.max(2, targetHeight)};
    }

    private int getActiveMaxFrameEdge() {
        return activeVideoQualityProfile == QUALITY_PROFILE_SAVE_DATA ? SAVE_DATA_MAX_FRAME_EDGE : HD_MAX_FRAME_EDGE;
    }

    private int getActiveMinVideoBitrate() {
        return activeVideoQualityProfile == QUALITY_PROFILE_SAVE_DATA ? SAVE_DATA_MIN_VIDEO_BITRATE : HD_MIN_VIDEO_BITRATE;
    }

    private int getActiveVideoBitrateFactor() {
        return activeVideoQualityProfile == QUALITY_PROFILE_SAVE_DATA ? SAVE_DATA_VIDEO_BITRATE_FACTOR : HD_VIDEO_BITRATE_FACTOR;
    }

    private int getActiveVideoFrameRate() {
        return activeVideoQualityProfile == QUALITY_PROFILE_SAVE_DATA ? SAVE_DATA_VIDEO_FRAME_RATE : HD_VIDEO_FRAME_RATE;
    }

    private String getQualityLabel(int profile) {
        return profile == QUALITY_PROFILE_SAVE_DATA ? "省流" : "高清";
    }

    private void applyVideoQualityProfile(int profile, boolean notifyStatus) {
        if (profile != QUALITY_PROFILE_HD && profile != QUALITY_PROFILE_SAVE_DATA) {
            return;
        }
        if (activeVideoQualityProfile == profile && videoEncoder != null && virtualDisplay != null) {
            if (notifyStatus) {
                postStatusOnMain("当前已经是“" + getQualityLabel(profile) + "”模式。");
            }
            return;
        }
        activeVideoQualityProfile = profile;
        if (notifyStatus) {
            postStatusOnMain("正在切换到“" + getQualityLabel(profile) + "”模式，画面会自动刷新...");
        }
        if (mediaProjection != null) {
            createVirtualDisplay();
        }
        postToMain(new Runnable() {
            @Override
            public void run() {
                updateAddressInfo();
            }
        });
    }

    private void startVideoEncoderLoop(final MediaCodec encoder) {
        videoEncoderLoopRunning = true;
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (videoEncoderLoopRunning && videoEncoder == encoder) {
                    int index;
                    try {
                        index = encoder.dequeueOutputBuffer(bufferInfo, 10_000L);
                    } catch (IllegalStateException error) {
                        return;
                    }
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    }
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        cacheVideoCodecConfig(encoder.getOutputFormat());
                        continue;
                    }
                    if (index < 0) {
                        continue;
                    }
                    try {
                        java.nio.ByteBuffer buffer = encoder.getOutputBuffer(index);
                        if (buffer != null && bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            byte[] encoded = new byte[bufferInfo.size];
                            buffer.position(bufferInfo.offset);
                            buffer.limit(bufferInfo.offset + bufferInfo.size);
                            buffer.get(encoded);
                            sendEncodedVideoFrame(encoded, bufferInfo.flags);
                        }
                    } finally {
                        encoder.releaseOutputBuffer(index, false);
                    }
                }
            }
        });
    }

    private void cacheVideoCodecConfig(MediaFormat format) {
        java.nio.ByteBuffer csd0Buffer = format.getByteBuffer("csd-0");
        java.nio.ByteBuffer csd1Buffer = format.getByteBuffer("csd-1");
        if (csd0Buffer != null) {
            videoCsd0 = copyByteBuffer(csd0Buffer);
        }
        if (csd1Buffer != null) {
            videoCsd1 = copyByteBuffer(csd1Buffer);
        }
        sendVideoConfigIfAvailable();
    }

    private byte[] copyByteBuffer(java.nio.ByteBuffer buffer) {
        java.nio.ByteBuffer duplicate = buffer.duplicate();
        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        return data;
    }

    private void handleImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            if (!serviceRunning || !clientConnected) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFrameTimestamp < MIN_FRAME_INTERVAL_MS) {
                return;
            }
            final Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int paddedWidth = width + (rowStride - pixelStride * width) / pixelStride;
            ensureCaptureBitmap(paddedWidth, height);
            ensureEncodeBitmap(width, height);
            buffer.rewind();
            captureBitmap.copyPixelsFromBuffer(buffer);
            captureRect.set(0, 0, width, height);

            int signature = buildFrameSignature(captureBitmap, width, height);
            if (signature == lastFrameSignature && now - lastFrameTimestamp < 500L) {
                return;
            }
            lastFrameSignature = signature;
            lastFrameTimestamp = now;

            encodeCanvas.drawColor(Color.BLACK);
            encodeCanvas.drawBitmap(captureBitmap, captureRect, encodeRect, framePaint);
            FrameSlot slot = obtainWritableFrameSlot();
            if (slot == null) {
                return;
            }
            slot.stream.reset();
            if (!encodeBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, slot.stream)) {
                return;
            }
            display.getRealMetrics(realMetrics);
            slot.rotation = display.getRotation();
            slot.serverWidth = realMetrics.widthPixels;
            slot.serverHeight = realMetrics.heightPixels;
            slot.sequence = ++frameSequence;
            synchronized (frameLock) {
                latestFrame = slot;
                frameLock.notifyAll();
            }
        } finally {
            image.close();
        }
    }

    private void ensureCaptureBitmap(int width, int height) {
        if (captureBitmap == null || captureBitmap.getWidth() != width || captureBitmap.getHeight() != height) {
            if (captureBitmap != null && !captureBitmap.isRecycled()) {
                captureBitmap.recycle();
            }
            captureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private void ensureEncodeBitmap(int width, int height) {
        float scale = Math.min(1.0f, getActiveMaxFrameEdge() / (float) Math.max(width, height));
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        if (encodeBitmap == null || encodeBitmap.getWidth() != targetWidth || encodeBitmap.getHeight() != targetHeight) {
            if (encodeBitmap != null && !encodeBitmap.isRecycled()) {
                encodeBitmap.recycle();
            }
            encodeBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            encodeCanvas = new Canvas(encodeBitmap);
            encodeRect.set(0, 0, targetWidth, targetHeight);
        }
    }

    private int buildFrameSignature(Bitmap bitmap, int width, int height) {
        int[] xs = {0, width / 4, width / 2, width * 3 / 4, width - 1};
        int[] ys = {0, height / 3, height * 2 / 3, height - 1};
        int signature = 17;
        for (int y : ys) {
            for (int x : xs) {
                signature = signature * 31 + bitmap.getPixel(Math.max(0, x), Math.max(0, y));
            }
        }
        return signature;
    }

    @Override
    public void onClick(View view) {
        if (serviceRunning) {
            stopRemoteService();
        } else {
            startRemoteService();
        }
    }

    private void startRemoteService() {
        if (!ensureAccessibilityReady()) {
            return;
        }
        if (mediaProjection == null) {
            toast("请先完成屏幕录制授权");
            requestScreenShot();
            return;
        }
        if (videoEncoder == null || virtualDisplay == null) {
            createVirtualDisplay();
        }
        if (videoEncoder == null || virtualDisplay == null) {
            return;
        }
        RelayConfig relayConfig = parseRelayConfig();
        if (isRelayModeEnabled() && relayConfig == null) {
            return;
        }
        serviceRunning = true;
        clientConnected = false;
        activeAuthToken = authTokenInput.getText().toString().trim();
        activeRelayConfig = relayConfig;
        toggleButton.setText(BUTTON_STOP);
        updateConfigInputs(false);
        requestBatteryOptimizationWhitelistIfNeeded();
        startKeepAliveService();
        if (relayConfig != null) {
            startRelayVideoHostLoop(relayConfig);
            startRelayAudioHostLoop(relayConfig);
            setStatus("中继模式已启动，正在连接 NAS 中继并等待客户端进入房间...");
        } else {
            boolean discoveryReady = startDiscoveryResponder();
            startVideoServerLoop();
            startAudioServerLoop();
            if (discoveryReady) {
                setStatus("服务已启动，等待客户端连接...");
            } else {
                setStatus("服务已启动，局域网发现正在初始化，客户端点击“发现服务端”时会自动重试。");
            }
        }
        updateAddressInfo();
    }

    private void stopRemoteService() {
        serviceRunning = false;
        clientConnected = false;
        activeRelayConfig = null;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        closeCurrentVideoConnection();
        closeCurrentAudioConnection();
        closeQuietly(videoServerSocket);
        videoServerSocket = null;
        closeQuietly(audioServerSocket);
        audioServerSocket = null;
        closeQuietly(discoverySocket);
        discoverySocket = null;
        toggleButton.setText(BUTTON_START);
        updateConfigInputs(true);
        setStatus("服务已停止");
        stopKeepAliveService();
    }

    private boolean isRelayModeEnabled() {
        return relayModeCheckBox != null && relayModeCheckBox.isChecked();
    }

    private RelayConfig parseRelayConfig() {
        if (!isRelayModeEnabled()) {
            return null;
        }
        String host = relayHostInput.getText().toString().trim();
        String portText = relayPortInput.getText().toString().trim();
        String roomId = relayRoomInput.getText().toString().trim();
        String authToken = authTokenInput.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            relayHostInput.setError("请输入 NAS 中继域名或 IP");
            setStatus("已开启 NAS 中继模式，请先填写中继地址。");
            return null;
        }
        if (TextUtils.isEmpty(portText)) {
            portText = String.valueOf(DEFAULT_RELAY_PORT);
            relayPortInput.setText(portText);
        }
        if (!TextUtils.isDigitsOnly(portText)) {
            relayPortInput.setError("端口必须是数字");
            setStatus("NAS 中继端口格式错误，请输入纯数字。");
            return null;
        }
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) {
            relayPortInput.setError("端口范围应为 1-65535");
            setStatus("NAS 中继端口超出范围，请输入 1 到 65535。\n推荐端口：9000");
            return null;
        }
        if (TextUtils.isEmpty(roomId)) {
            relayRoomInput.setError("请输入中继房间号");
            setStatus("NAS 中继模式需要房间号，客户端和服务端必须填写相同的房间号。");
            return null;
        }
        relayHostInput.setError(null);
        relayPortInput.setError(null);
        relayRoomInput.setError(null);
        return new RelayConfig(host, port, roomId, authToken);
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        intent.setAction(KeepAliveService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopKeepAliveService() {
        stopService(new Intent(this, KeepAliveService.class));
    }

    private boolean startDiscoveryResponder() {
        final CountDownLatch readyLatch = new CountDownLatch(1);
        final boolean[] ready = {false};
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                DatagramSocket localSocket = null;
                boolean signaled = false;
                try {
                    localSocket = new DatagramSocket(null);
                    localSocket.setReuseAddress(true);
                    localSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                    discoverySocket = localSocket;
                    ready[0] = true;
                    readyLatch.countDown();
                    signaled = true;
                    byte[] buffer = new byte[256];
                    while (serviceRunning && !localSocket.isClosed()) {
                        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                        localSocket.receive(request);
                        String message = new String(request.getData(), 0, request.getLength(), "UTF-8");
                        if (!DISCOVERY_REQUEST.equals(message)) {
                            continue;
                        }
                        String responseMessage = DISCOVERY_RESPONSE + ";"
                            + ipUtl.getDiscoveryHost(getApplicationContext()) + ";" + SERVER_PORT;
                        byte[] responseBytes = responseMessage.getBytes("UTF-8");
                        DatagramPacket response = new DatagramPacket(
                            responseBytes,
                            responseBytes.length,
                            request.getAddress(),
                            request.getPort()
                        );
                        localSocket.send(response);
                    }
                } catch (IOException ignored) {
                } finally {
                    if (!signaled) {
                        readyLatch.countDown();
                    }
                    closeQuietly(localSocket);
                    if (discoverySocket == localSocket) {
                        discoverySocket = null;
                    }
                }
            }
        });
        try {
            return readyLatch.await(DISCOVERY_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ready[0];
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startVideoServerLoop() {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    videoServerSocket = new ServerSocket();
                    videoServerSocket.setReuseAddress(true);
                    videoServerSocket.bind(new InetSocketAddress(SERVER_PORT));
                    while (serviceRunning) {
                        Socket accepted = videoServerSocket.accept();
                        handleVideoClient(accepted);
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(videoServerSocket);
                    videoServerSocket = null;
                }
            }
        });
    }

    private void startRelayVideoHostLoop(final RelayConfig config) {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                while (serviceRunning && activeRelayConfig == config) {
                    RelaySocketConnection connection = null;
                    try {
                        postStatusOnMain("正在连接 NAS 中继视频通道，房间号：" + config.roomId);
                        connection = connectRelayChannel(config, RELAY_CHANNEL_VIDEO);
                        handleVideoClient(connection.socket, connection.input, connection.output);
                    } catch (IOException error) {
                        if (serviceRunning) {
                            postStatusOnMain("NAS 中继视频通道异常：" + safeMessage(error));
                        }
                    } finally {
                        if (connection != null) {
                            closeQuietly(connection.input);
                            closeQuietly(connection.output);
                            closeQuietly(connection.socket);
                        }
                    }
                    if (serviceRunning && activeRelayConfig == config) {
                        sleepBeforeRetry();
                    }
                }
            }
        });
    }

    private void handleVideoClient(Socket accepted) {
        try {
            accepted.setTcpNoDelay(true);
            accepted.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            handleVideoClient(
                accepted,
                new DataInputStream(new BufferedInputStream(accepted.getInputStream())),
                new DataOutputStream(new BufferedOutputStream(accepted.getOutputStream()))
            );
        } catch (IOException error) {
            closeCurrentVideoConnection();
            closeQuietly(accepted);
        }
    }

    private void handleVideoClient(Socket accepted, DataInputStream localInput, DataOutputStream localOutput) {
        closeCurrentVideoConnection();
        try {
            String handshakeError = authenticateClient(localInput, localOutput);
            if (handshakeError != null) {
                postStatusOnMain("客户端连接被拒绝：" + handshakeError);
                closeQuietly(localInput);
                closeQuietly(localOutput);
                closeQuietly(accepted);
                return;
            }
            accepted.setSoTimeout(0);
            synchronized (videoConnectionLock) {
                clientSocket = accepted;
                videoOutput = localOutput;
                commandInput = localInput;
                clientConnected = true;
            }
            postStatusOnMain("客户端已连接，正在传输画面...");
            sendVideoConfigIfAvailable();
            backgroundExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    readCommandLoop();
                }
            });
        } catch (IOException error) {
            closeCurrentVideoConnection();
            closeQuietly(localInput);
            closeQuietly(localOutput);
            closeQuietly(accepted);
        }
    }

    private String authenticateClient(DataInputStream input, DataOutputStream output) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        String clientToken = input.readUTF();
        if (magic != PROTOCOL_MAGIC || version != PROTOCOL_VERSION) {
            writeHandshakeResult(output, HANDSHAKE_STATUS_PROTOCOL_MISMATCH, "客户端协议版本不匹配，请更新应用");
            return "协议版本不匹配";
        }
        String expectedToken = activeAuthToken;
        if (!TextUtils.isEmpty(expectedToken) && !expectedToken.equals(clientToken)) {
            writeHandshakeResult(output, HANDSHAKE_STATUS_AUTH_FAILED, "鉴权口令错误");
            return "鉴权口令错误";
        }
        writeHandshakeResult(output, HANDSHAKE_STATUS_OK, "OK");
        return null;
    }

    private void writeHandshakeResult(DataOutputStream output, int status, String message) throws IOException {
        output.writeInt(status);
        output.writeUTF(message);
        output.flush();
    }

    private void sendVideoConfigIfAvailable() {
        byte[] csd0 = videoCsd0;
        byte[] csd1 = videoCsd1;
        if (csd0.length == 0 || csd1.length == 0) {
            return;
        }
        try {
            synchronized (videoConnectionLock) {
                if (videoOutput == null) {
                    return;
                }
                display.getRealMetrics(realMetrics);
                videoOutput.writeInt(VIDEO_PACKET_CONFIG);
                videoOutput.writeInt(display.getRotation());
                videoOutput.writeInt(encodedVideoWidth);
                videoOutput.writeInt(encodedVideoHeight);
                videoOutput.writeInt(realMetrics.widthPixels);
                videoOutput.writeInt(realMetrics.heightPixels);
                videoOutput.writeInt(csd0.length);
                videoOutput.write(csd0);
                videoOutput.writeInt(csd1.length);
                videoOutput.write(csd1);
                videoOutput.flush();
            }
        } catch (IOException error) {
            closeCurrentVideoConnection();
            postStatusOnMain("客户端已断开，等待新的连接...");
        }
    }

    private void sendEncodedVideoFrame(byte[] encoded, int flags) {
        try {
            synchronized (videoConnectionLock) {
                if (videoOutput == null) {
                    return;
                }
                display.getRealMetrics(realMetrics);
                videoOutput.writeInt(VIDEO_PACKET_FRAME);
                videoOutput.writeInt(display.getRotation());
                videoOutput.writeInt(realMetrics.widthPixels);
                videoOutput.writeInt(realMetrics.heightPixels);
                videoOutput.writeInt(flags);
                videoOutput.writeInt(encoded.length);
                videoOutput.write(encoded);
                videoOutput.flush();
            }
        } catch (IOException error) {
            closeCurrentVideoConnection();
            postStatusOnMain("客户端已断开，等待新的连接...");
        }
    }

    private void sendFrameLoop() {
        long lastSentSequence = -1L;
        while (serviceRunning && clientConnected) {
            FrameSlot frame = null;
            synchronized (frameLock) {
                while (serviceRunning && clientConnected && (latestFrame == null || latestFrame.sequence == lastSentSequence)) {
                    try {
                        frameLock.wait(500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frame = latestFrame;
                if (frame != null) {
                    frame.inUse = true;
                }
            }
            if (frame == null) {
                continue;
            }
            try {
                synchronized (videoConnectionLock) {
                    if (videoOutput == null) {
                        return;
                    }
                    videoOutput.writeInt(frame.stream.size());
                    videoOutput.writeInt(frame.rotation);
                    videoOutput.writeInt(frame.serverWidth);
                    videoOutput.writeInt(frame.serverHeight);
                    videoOutput.write(frame.stream.buffer(), 0, frame.stream.size());
                    videoOutput.flush();
                }
                lastSentSequence = frame.sequence;
            } catch (IOException error) {
                closeCurrentVideoConnection();
                postStatusOnMain("客户端已断开，等待新的连接...");
                return;
            } finally {
                frame.inUse = false;
            }
        }
    }

    private FrameSlot obtainWritableFrameSlot() {
        FrameSlot preferred = frameSlots[nextFrameSlotIndex];
        if (preferred.inUse) {
            int alternateIndex = (nextFrameSlotIndex + 1) % frameSlots.length;
            FrameSlot alternate = frameSlots[alternateIndex];
            if (alternate.inUse) {
                return null;
            }
            nextFrameSlotIndex = (alternateIndex + 1) % frameSlots.length;
            return alternate;
        }
        nextFrameSlotIndex = (nextFrameSlotIndex + 1) % frameSlots.length;
        return preferred;
    }

    private void readCommandLoop() {
        try {
            while (serviceRunning && clientConnected) {
                int command = commandInput.readInt();
                if (command == CMD_GESTURE) {
                    final int duration = commandInput.readInt();
                    final int x1 = commandInput.readInt();
                    final int y1 = commandInput.readInt();
                    final int x2 = commandInput.readInt();
                    final int y2 = commandInput.readInt();
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            if (!myAccessibity.performRemoteGesture(duration, x1, y1, x2, y2)) {
                                setStatus("无障碍服务未启用，远程手势不可用。请在服务端重新开启无障碍后重试。");
                                toast("无障碍服务未启用，无法执行远程手势");
                            }
                        }
                    });
                } else if (command == CMD_BACK) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            if (!myAccessibity.performRemoteBack()) {
                                setStatus("无障碍服务未启用，远程返回不可用。请在服务端重新开启无障碍后重试。");
                                toast("无障碍服务未启用，无法执行远程返回");
                            }
                        }
                    });
                } else if (command == CMD_SET_QUALITY) {
                    int profile = commandInput.readInt();
                    applyVideoQualityProfile(profile, true);
                }
            }
        } catch (IOException ignored) {
            if (clientConnected) {
                closeCurrentVideoConnection();
                postStatusOnMain("客户端已断开，等待新的连接...");
            }
        }
    }

    private void startAudioServerLoop() {
        if (!hasAudioPermission()) {
            postStatusOnMain("录音权限未授予，将只提供画面和控制。\n可在系统设置中开启音频权限后重启服务。");
            return;
        }
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    audioServerSocket = new ServerSocket();
                    audioServerSocket.setReuseAddress(true);
                    audioServerSocket.bind(new InetSocketAddress(AUDIO_PORT));
                    while (serviceRunning) {
                        Socket accepted = audioServerSocket.accept();
                        streamAudio(accepted);
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(audioServerSocket);
                    audioServerSocket = null;
                }
            }
        });
    }

    private void startRelayAudioHostLoop(final RelayConfig config) {
        if (!hasAudioPermission()) {
            postStatusOnMain("录音权限未授予，将只提供画面和控制。\n可在系统设置中开启音频权限后重启服务。");
            return;
        }
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                while (serviceRunning && activeRelayConfig == config) {
                    RelaySocketConnection connection = null;
                    try {
                        connection = connectRelayChannel(config, RELAY_CHANNEL_AUDIO);
                        streamAudio(connection.socket, connection.output);
                    } catch (IOException error) {
                        if (serviceRunning) {
                            postStatusOnMain("NAS 中继音频通道异常：" + safeMessage(error));
                        }
                    } finally {
                        if (connection != null) {
                            closeQuietly(connection.input);
                            closeQuietly(connection.output);
                            closeQuietly(connection.socket);
                        }
                    }
                    if (serviceRunning && activeRelayConfig == config) {
                        sleepBeforeRetry();
                    }
                }
            }
        });
    }

    private void streamAudio(Socket accepted) {
        DataOutputStream localOutput = null;
        try {
            localOutput = new DataOutputStream(new BufferedOutputStream(accepted.getOutputStream()));
            streamAudio(accepted, localOutput);
        } catch (IOException error) {
            closeQuietly(localOutput);
            closeQuietly(accepted);
        }
    }

    private void streamAudio(Socket accepted, DataOutputStream output) {
        closeCurrentAudioConnection();
        int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING);
        if (minBufferSize <= 0) {
            return;
        }
        AudioRecord audioRecord = null;
        final String captureMode;
        try {
            accepted.setTcpNoDelay(true);
            synchronized (audioConnectionLock) {
                audioClientSocket = accepted;
                audioOutput = output;
            }
            AudioRecordWithMode recordWithMode = createAudioRecord(minBufferSize * 4);
            audioRecord = recordWithMode.record;
            captureMode = recordWithMode.modeLabel;
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                toastOnMain("录音初始化失败，音频通道不可用");
                return;
            }
            audioRecord.startRecording();
            postStatusOnMain("客户端已连接，正在传输画面与音频（" + captureMode + "）...");
            byte[] buffer = new byte[minBufferSize * 2];
            while (serviceRunning && audioClientSocket != null && !audioClientSocket.isClosed()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                synchronized (audioConnectionLock) {
                    if (audioOutput == null) {
                        return;
                    }
                    audioOutput.writeInt(read);
                    audioOutput.write(buffer, 0, read);
                    audioOutput.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException ignored) {
                }
                audioRecord.release();
            }
            closeCurrentAudioConnection();
        }
    }

    private AudioRecordWithMode createAudioRecord(int bufferSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                AudioPlaybackCaptureConfiguration playbackConfig =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                AudioFormat captureFormat = new AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AUDIO_CHANNEL)
                    .build();
                AudioRecord playbackRecord = new AudioRecord.Builder()
                    .setAudioFormat(captureFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build();
                if (playbackRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    return new AudioRecordWithMode(playbackRecord, "系统音频捕获");
                }
                playbackRecord.release();
            } catch (RuntimeException ignored) {
            }
        }
        AudioRecord microphoneRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL,
            AUDIO_ENCODING,
            bufferSize
        );
        return new AudioRecordWithMode(microphoneRecord, "麦克风采集");
    }

    private RelaySocketConnection connectRelayChannel(RelayConfig config, int channel) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(config.host, config.port), RELAY_CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(RELAY_WAIT_TIMEOUT_MS);
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        try {
            output.writeInt(RELAY_MAGIC);
            output.writeInt(RELAY_VERSION);
            output.writeInt(RELAY_ROLE_HOST);
            output.writeInt(channel);
            writeRelayString(output, config.roomId);
            writeRelayString(output, config.authToken);
            output.flush();
            int status = input.readInt();
            String message = readRelayString(input);
            if (status != RELAY_STATUS_OK) {
                throw new IOException(TextUtils.isEmpty(message) ? "NAS 中继拒绝连接" : message);
            }
            socket.setSoTimeout(0);
            return new RelaySocketConnection(socket, input, output);
        } catch (IOException error) {
            closeQuietly(input);
            closeQuietly(output);
            closeQuietly(socket);
            throw error;
        }
    }

    private void writeRelayString(DataOutputStream output, String value) throws IOException {
        byte[] data = value.getBytes("UTF-8");
        output.writeInt(data.length);
        output.write(data);
    }

    private String readRelayString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 1024) {
            throw new IOException("中继返回了非法字符串长度：" + length);
        }
        byte[] data = new byte[length];
        input.readFully(data, 0, length);
        return new String(data, "UTF-8");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(1_500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateAddressInfo() {
        StringBuilder builder = new StringBuilder();
        String ipInfo = ipUtl.getIp(getApplicationContext());
        String currentToken = authTokenInput != null ? authTokenInput.getText().toString().trim() : activeAuthToken;
        boolean relayMode = isRelayModeEnabled() || activeRelayConfig != null;
        if (!TextUtils.isEmpty(ipInfo)) {
            builder.append(ipInfo);
        }
        builder.append("\n视频端口:").append(SERVER_PORT);
        builder.append("\n音频端口:").append(AUDIO_PORT);
        builder.append("\n局域网发现端口:").append(DISCOVERY_PORT);
        builder.append("\n当前清晰度:").append(getQualityLabel(activeVideoQualityProfile));
        builder.append("\n鉴权口令:").append(TextUtils.isEmpty(currentToken) ? "未设置（局域网测试可留空）" : "已设置，请客户端填写相同口令");
        builder.append("\n公网参考地址:").append(publicEndpointText);
        if (relayMode) {
            String relayHost = relayHostInput != null ? relayHostInput.getText().toString().trim() : "";
            String relayPort = relayPortInput != null ? relayPortInput.getText().toString().trim() : String.valueOf(DEFAULT_RELAY_PORT);
            String roomId = relayRoomInput != null ? relayRoomInput.getText().toString().trim() : "";
            builder.append("\nNAS 中继模式: 已启用");
            builder.append("\n中继地址:").append(TextUtils.isEmpty(relayHost) ? "未填写" : relayHost);
            builder.append(":").append(TextUtils.isEmpty(relayPort) ? DEFAULT_RELAY_PORT : relayPort);
            builder.append("\n中继房间号:").append(TextUtils.isEmpty(roomId) ? "未填写" : roomId);
            builder.append("\n公网使用提示: 服务端与客户端都连接 NAS 中继即可，无需额外映射 8888/8889 端口。");
        } else {
            builder.append("\n公网使用提示: 需要在路由器或云主机开放/映射视频端口 8888 和音频端口 8889。客户端可直接填写公网 IP 或域名。");
        }
        builder.append("\n双端在同一局域网时，客户端可直接点击“发现服务端”。");
        addressTextView.setText(builder.toString());
    }

    private void refreshPublicAddressInfo() {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String publicIp = ipUtl.fetchPublicIp();
                publicEndpointText = TextUtils.isEmpty(publicIp) ? "未获取到，可手动填写域名或公网 IP" : publicIp;
                postToMain(new Runnable() {
                    @Override
                    public void run() {
                        updateAddressInfo();
                    }
                });
            }
        });
    }

    private void updatePrerequisiteStatus() {
        if (serviceRunning) {
            return;
        }
        if (!myAccessibity.isReady()) {
            setStatus("请先在系统设置中开启无障碍服务，再启动远程控制。\n路径：设置 > 无障碍 > 远程协助服务");
            return;
        }
        if (mediaProjection == null || videoEncoder == null || virtualDisplay == null) {
            setStatus("无障碍已就绪，等待屏幕录制授权...");
            return;
        }
        setStatus("屏幕录制和无障碍均已就绪，可启动服务。");
    }

    private boolean ensureAccessibilityReady() {
        if (myAccessibity.isReady()) {
            return true;
        }
        setStatus("未检测到无障碍服务，无法执行远程返回和手势。\n请先开启后再启动服务。");
        toast("请先开启无障碍服务");
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return false;
    }

    private void requestBatteryOptimizationWhitelistIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("建议允许忽略电池优化，以提高远程服务保活能力");
        } catch (Exception ignored) {
        }
    }

    private void closeCurrentVideoConnection() {
        synchronized (videoConnectionLock) {
            clientConnected = false;
            closeQuietly(commandInput);
            closeQuietly(videoOutput);
            closeQuietly(clientSocket);
            commandInput = null;
            videoOutput = null;
            clientSocket = null;
        }
    }

    private void closeCurrentAudioConnection() {
        synchronized (audioConnectionLock) {
            closeQuietly(audioOutput);
            closeQuietly(audioClientSocket);
            audioOutput = null;
            audioClientSocket = null;
        }
    }

    private void postStatusOnMain(final String status) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                setStatus(status);
            }
        });
    }

    private void toastOnMain(final String text) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                toast(text);
            }
        });
    }

    private void setStatus(String status) {
        statusTextView.setText(status);
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    private String safeMessage(Throwable throwable) {
        return TextUtils.isEmpty(throwable.getMessage()) ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private void postToMain(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private void releaseVirtualDisplay() {
        videoEncoderLoopRunning = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (videoEncoderInputSurface != null) {
            videoEncoderInputSurface.release();
            videoEncoderInputSurface = null;
        }
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                videoEncoder.release();
            } catch (Exception ignored) {
            }
            videoEncoder = null;
        }
        videoCsd0 = new byte[0];
        videoCsd1 = new byte[0];
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(ServerSocket serverSocket) {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("录音权限已授予，客户端连接后可同步麦克风音频");
            } else {
                setStatus("录音权限被拒绝，将只提供画面和控制。可在设置中开启后重启服务。");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRemoteService();
        releaseVirtualDisplay();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        backgroundExecutor.shutdownNow();
        if (captureBitmap != null && !captureBitmap.isRecycled()) {
            captureBitmap.recycle();
            captureBitmap = null;
        }
        if (encodeBitmap != null && !encodeBitmap.isRecycled()) {
            encodeBitmap.recycle();
            encodeBitmap = null;
        }
    }

    private static final class FrameSlot {
        final ReusableByteArrayOutputStream stream = new ReusableByteArrayOutputStream();
        long sequence;
        int rotation;
        int serverWidth;
        int serverHeight;
        volatile boolean inUse;
    }

    private static final class AudioRecordWithMode {
        final AudioRecord record;
        final String modeLabel;

        AudioRecordWithMode(AudioRecord record, String modeLabel) {
            this.record = record;
            this.modeLabel = modeLabel;
        }
    }

    private static final class RelayConfig {
        final String host;
        final int port;
        final String roomId;
        final String authToken;

        RelayConfig(String host, int port, String roomId, String authToken) {
            this.host = host;
            this.port = port;
            this.roomId = roomId;
            this.authToken = authToken;
        }
    }

    private static final class RelaySocketConnection {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;

        RelaySocketConnection(Socket socket, DataInputStream input, DataOutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
        }
    }

    private static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        byte[] buffer() {
            return buf;
        }
    }
}