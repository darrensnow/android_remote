package com.mycompany.application2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
    private static final String BUTTON_START = "启动服务";
    private static final String BUTTON_STOP = "停止服务";
    private static final int REQUEST_SCREEN_CAPTURE = 1;
    private static final int REQUEST_RECORD_AUDIO = 2;
    private static final int SERVER_PORT = 8888;
    private static final int AUDIO_PORT = SERVER_PORT + 1;
    private static final int DISCOVERY_PORT = 8891;
    private static final String DISCOVERY_REQUEST = "ANDROID_REMOTE_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE = "ANDROID_REMOTE_SERVICE_V1";
    private static final int CMD_GESTURE = 1;
    private static final int CMD_BACK = 2;
    private static final int AUDIO_SAMPLE_RATE = 16000;
    private static final int AUDIO_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int JPEG_QUALITY = 55;
    private static final int MAX_FRAME_EDGE = 960;
    private static final long MIN_FRAME_INTERVAL_MS = 100L;
    private static final long DISCOVERY_READY_TIMEOUT_MS = 1200L;

    private TextView addressTextView;
    private TextView statusTextView;
    private Button toggleButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final Object videoConnectionLock = new Object();
    private final Object audioConnectionLock = new Object();
    private final Object frameLock = new Object();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final DisplayMetrics realMetrics = new DisplayMetrics();
    private final FrameSlot[] frameSlots = {new FrameSlot(), new FrameSlot()};

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
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
        toggleButton.setOnClickListener(this);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        display = windowManager.getDefaultDisplay();
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        captureThread = new HandlerThread("remote-capture-thread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        requestAudioPermissionIfNeeded();
        requestScreenShot();
        updateAddressInfo();
        updatePrerequisiteStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        releaseVirtualDisplay();
        display.getRealMetrics(realMetrics);
        imageReader = ImageReader.newInstance(
            realMetrics.widthPixels,
            realMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        );
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            realMetrics.widthPixels,
            realMetrics.heightPixels,
            realMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            captureHandler
        );
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                handleImageAvailable(reader);
            }
        }, captureHandler);
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
        float scale = Math.min(1.0f, MAX_FRAME_EDGE / (float) Math.max(width, height));
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
        if (mediaProjection == null || imageReader == null) {
            toast("请先完成屏幕录制授权");
            requestScreenShot();
            return;
        }
        serviceRunning = true;
        clientConnected = false;
        toggleButton.setText(BUTTON_STOP);
        startKeepAliveService();
        boolean discoveryReady = startDiscoveryResponder();
        startVideoServerLoop();
        startAudioServerLoop();
        updateAddressInfo();
        if (discoveryReady) {
            setStatus("服务已启动，等待客户端连接...");
        } else {
            setStatus("服务已启动，局域网发现正在初始化，客户端点击“发现服务端”时会自动重试。");
        }
    }

    private void stopRemoteService() {
        serviceRunning = false;
        clientConnected = false;
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
        setStatus("服务已停止");
        stopKeepAliveService();
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

    private void handleVideoClient(Socket accepted) {
        closeCurrentVideoConnection();
        try {
            accepted.setTcpNoDelay(true);
            synchronized (videoConnectionLock) {
                clientSocket = accepted;
                videoOutput = new DataOutputStream(new BufferedOutputStream(accepted.getOutputStream()));
                commandInput = new DataInputStream(new BufferedInputStream(accepted.getInputStream()));
                clientConnected = true;
            }
            postStatusOnMain("客户端已连接，正在传输画面...");
            backgroundExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    readCommandLoop();
                }
            });
            sendFrameLoop();
        } catch (IOException error) {
            closeCurrentVideoConnection();
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
            postStatusOnMain("录音权限未授予，将只提供画面和控制。\n可在系统设置中开启麦克风权限后重启服务。");
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

    private void streamAudio(Socket accepted) {
        closeCurrentAudioConnection();
        int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING);
        if (minBufferSize <= 0) {
            return;
        }
        AudioRecord audioRecord = null;
        try {
            accepted.setTcpNoDelay(true);
            synchronized (audioConnectionLock) {
                audioClientSocket = accepted;
                audioOutput = new DataOutputStream(new BufferedOutputStream(accepted.getOutputStream()));
            }
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL,
                AUDIO_ENCODING,
                minBufferSize * 4
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                toastOnMain("录音初始化失败，音频通道不可用");
                return;
            }
            audioRecord.startRecording();
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

    private void updateAddressInfo() {
        StringBuilder builder = new StringBuilder();
        String ipInfo = ipUtl.getIp(getApplicationContext());
        if (!TextUtils.isEmpty(ipInfo)) {
            builder.append(ipInfo);
        }
        builder.append("\n视频端口:").append(SERVER_PORT);
        builder.append("\n音频端口:").append(AUDIO_PORT);
        builder.append("\n局域网发现端口:").append(DISCOVERY_PORT);
        builder.append("\n双端在同一局域网时，客户端可直接点击“发现服务端”。");
        addressTextView.setText(builder.toString());
    }

    private void updatePrerequisiteStatus() {
        if (serviceRunning) {
            return;
        }
        if (!myAccessibity.isReady()) {
            setStatus("请先在系统设置中开启无障碍服务，再启动远程控制。\n路径：设置 > 无障碍 > 远程协助服务");
            return;
        }
        if (mediaProjection == null || imageReader == null) {
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

    private void postToMain(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
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

    private static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        byte[] buffer() {
            return buf;
        }
    }
}