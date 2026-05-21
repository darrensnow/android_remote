package com.mycompany.application2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int DEFAULT_PORT = 8888;
    private static final int AUDIO_PORT_OFFSET = 1;
    private static final int DISCOVERY_PORT = 8891;
    private static final int DISCOVERY_TIMEOUT_MS = 700;
    private static final int DISCOVERY_ATTEMPTS = 3;
    private static final int AUDIO_SAMPLE_RATE = 16000;
    private static final int AUDIO_CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CMD_GESTURE = 1;
    private static final int CMD_BACK = 2;
    private static final String DISCOVERY_REQUEST = "ANDROID_REMOTE_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE = "ANDROID_REMOTE_SERVICE_V1";
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 4.0f;

    private TextureView textureView;
    private EditText hostInput;
    private EditText portInput;
    private TextView statusView;
    private Button connectButton;
    private Button discoverButton;
    private Button remoteBackButton;
    private Button resetViewButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final Object outputLock = new Object();
    private final Object connectionLock = new Object();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix previewMatrix = new Matrix();
    private final Matrix inversePreviewMatrix = new Matrix();
    private final BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();

    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private Socket clientSocket;
    private DataInputStream clientInput;
    private DataOutputStream clientOutput;
    private Socket audioSocket;
    private DataInputStream audioInput;
    private AudioTrack audioTrack;
    private Bitmap currentFrame;
    private byte[] frameBuffer = new byte[0];

    private volatile boolean connected = false;
    private volatile boolean scaleGestureActive = false;
    private int screenWidth;
    private int screenHeight;
    private int serverWidth;
    private int serverHeight;
    private int serverOrientation;
    private float touchStartX;
    private float touchStartY;
    private long touchDownTime;
    private float currentScale = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bitmapOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;

        bindViews();
        initGestures();
        initTextureView();
        initButtons();
        updateConnectedUi(false);
        setStatus("请输入服务端地址后连接，或直接点击“发现服务端”。");
    }

    private void bindViews() {
        textureView = findViewById(R.id.activity_mainTextureView);
        hostInput = findViewById(R.id.activity_mainHostEditText);
        portInput = findViewById(R.id.activity_mainPortEditText);
        statusView = findViewById(R.id.activity_mainStatusTextView);
        connectButton = findViewById(R.id.activity1_bt);
        discoverButton = findViewById(R.id.activity_mainDiscoverButton);
        remoteBackButton = findViewById(R.id.activity_mainBackButton);
        resetViewButton = findViewById(R.id.activity_mainResetButton);
        portInput.setText(String.valueOf(DEFAULT_PORT));
    }

    private void initGestures() {
        scaleGestureDetector = new ScaleGestureDetector(this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    scaleGestureActive = true;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float desiredScale = clamp(currentScale * detector.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                    float factor = desiredScale / currentScale;
                    previewMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    currentScale = desiredScale;
                    constrainPreviewMatrix();
                    updateConnectedUi(connected);
                    redrawFrame();
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    scaleGestureActive = false;
                }
            });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetPreview();
                return true;
            }
        });
    }

    private void initTextureView() {
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                redrawFrame();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                screenWidth = width;
                screenHeight = height;
                constrainPreviewMatrix();
                redrawFrame();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
            }
        });

        textureView.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View view, MotionEvent event) {
                if (!connected) {
                    return false;
                }
                gestureDetector.onTouchEvent(event);
                scaleGestureDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = event.getX();
                        touchStartY = event.getY();
                        touchDownTime = event.getEventTime();
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        scaleGestureActive = true;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        scaleGestureActive = false;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (scaleGestureDetector.isInProgress() || scaleGestureActive || event.getPointerCount() > 1) {
                            scaleGestureActive = false;
                            return true;
                        }
                        sendGestureCommand(
                            touchStartX,
                            touchStartY,
                            event.getX(),
                            event.getY(),
                            calculateGestureDuration(event)
                        );
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void initButtons() {
        connectButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                if (connected) {
                    disconnectFromServer(true);
                } else {
                    startConnection();
                }
            }
        });

        discoverButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                discoverServer();
            }
        });
        remoteBackButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                sendBackCommand();
            }
        });
        resetViewButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                resetPreview();
            }
        });
    }

    private void startConnection() {
        final ConnectionInfo info = parseConnectionInfo();
        if (info == null) {
            return;
        }
        setStatus("正在连接服务端...");
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = new Socket(info.host, info.port);
                    socket.setTcpNoDelay(true);
                    synchronized (connectionLock) {
                        clientSocket = socket;
                        clientInput = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        clientOutput = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                        connected = true;
                    }
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            updateConnectedUi(true);
                            setStatus("已连接到 " + info.host + ":" + info.port);
                            toast("连接成功");
                        }
                    });
                    startAudioReceiver(info.host, info.port + AUDIO_PORT_OFFSET);
                    readFrameLoop();
                } catch (final IOException error) {
                    disconnectFromServer(false);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            updateConnectedUi(false);
                            setStatus("连接失败，请检查地址和端口。\n" + safeMessage(error));
                            toast("连接失败");
                        }
                    });
                }
            }
        });
    }

    private void readFrameLoop() {
        while (connected) {
            try {
                int length = clientInput.readInt();
                if (length <= 0) {
                    continue;
                }
                serverOrientation = clientInput.readInt();
                serverWidth = clientInput.readInt();
                serverHeight = clientInput.readInt();
                ensureFrameBuffer(length);
                clientInput.readFully(frameBuffer, 0, length);
                Bitmap decoded = BitmapFactory.decodeByteArray(frameBuffer, 0, length, bitmapOptions);
                if (decoded != null) {
                    replaceCurrentFrame(decoded);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            redrawFrame();
                        }
                    });
                }
            } catch (IOException error) {
                if (connected) {
                    disconnectFromServer(true);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("连接已断开");
                            toast("网络异常，已断开连接");
                        }
                    });
                }
                return;
            }
        }
    }

    private void startAudioReceiver(final String host, final int port) {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int minBufferSize = AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING);
                if (minBufferSize <= 0) {
                    return;
                }
                byte[] audioBuffer = new byte[minBufferSize * 2];
                try {
                    Socket localAudioSocket = new Socket(host, port);
                    localAudioSocket.setTcpNoDelay(true);
                    audioSocket = localAudioSocket;
                    audioInput = new DataInputStream(new BufferedInputStream(localAudioSocket.getInputStream()));
                    audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        AUDIO_SAMPLE_RATE,
                        AUDIO_CHANNEL,
                        AUDIO_ENCODING,
                        minBufferSize * 4,
                        AudioTrack.MODE_STREAM
                    );
                    audioTrack.play();
                    while (connected && !localAudioSocket.isClosed()) {
                        int length = audioInput.readInt();
                        if (length <= 0) {
                            continue;
                        }
                        if (audioBuffer.length < length) {
                            audioBuffer = new byte[length];
                        }
                        audioInput.readFully(audioBuffer, 0, length);
                        if (audioTrack != null) {
                            audioTrack.write(audioBuffer, 0, length);
                        }
                    }
                } catch (IOException ignored) {
                    if (connected) {
                        postToMain(new Runnable() {
                            @Override
                            public void run() {
                                toast("音频通道未连接，仅保留画面与控制");
                            }
                        });
                    }
                } finally {
                    stopAudioPlayer();
                    closeQuietly(audioInput);
                    audioInput = null;
                    closeQuietly(audioSocket);
                    audioSocket = null;
                }
            }
        });
    }

    private void discoverServer() {
        setStatus("正在发现局域网内的服务端...");
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);
                    byte[] requestData = DISCOVERY_REQUEST.getBytes("UTF-8");
                    List<InetAddress> targets = collectBroadcastTargets();
                    if (targets.isEmpty()) {
                        targets = Collections.singletonList(InetAddress.getByName("255.255.255.255"));
                    }
                    for (int attempt = 0; attempt < DISCOVERY_ATTEMPTS; attempt++) {
                        for (InetAddress target : targets) {
                            socket.send(new DatagramPacket(requestData, requestData.length, target, DISCOVERY_PORT));
                        }
                        try {
                            DatagramPacket response = new DatagramPacket(new byte[512], 512);
                            socket.receive(response);
                            final String message = new String(response.getData(), 0, response.getLength(), "UTF-8");
                            final InetAddress discoveredHost = response.getAddress();
                            postToMain(new Runnable() {
                                @Override
                                public void run() {
                                    if (message.startsWith(DISCOVERY_RESPONSE)) {
                                        String[] parts = message.split(";");
                                        hostInput.setText(parts.length > 1 ? parts[1] : discoveredHost.getHostAddress());
                                        portInput.setText(parts.length > 2 ? parts[2] : String.valueOf(DEFAULT_PORT));
                                        setStatus("已发现服务端：" + hostInput.getText().toString());
                                        toast("发现服务端成功");
                                    } else {
                                        setStatus("发现到了未知响应：" + message);
                                    }
                                }
                            });
                            return;
                        } catch (SocketTimeoutException ignored) {
                        }
                    }
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("未发现局域网内的服务端，请确认服务端已启动且处于同一网络。");
                            toast("未发现服务端");
                        }
                    });
                } catch (final IOException error) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("发现服务端失败：" + safeMessage(error));
                            toast("发现服务端失败");
                        }
                    });
                } finally {
                    closeQuietly(socket);
                }
            }
        });
    }

    private List<InetAddress> collectBroadcastTargets() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast instanceof Inet4Address) {
                        result.add(broadcast);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private void sendGestureCommand(final float rawStartX, final float rawStartY, final float rawEndX,
                                    final float rawEndY, final int duration) {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int[] startPoint = mapToServerPoint(rawStartX, rawStartY);
                int[] endPoint = mapToServerPoint(rawEndX, rawEndY);
                try {
                    synchronized (outputLock) {
                        if (clientOutput == null) {
                            return;
                        }
                        clientOutput.writeInt(CMD_GESTURE);
                        clientOutput.writeInt(duration);
                        clientOutput.writeInt(startPoint[0]);
                        clientOutput.writeInt(startPoint[1]);
                        clientOutput.writeInt(endPoint[0]);
                        clientOutput.writeInt(endPoint[1]);
                        clientOutput.flush();
                    }
                } catch (IOException error) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            toast("命令发送失败");
                        }
                    });
                    disconnectFromServer(true);
                }
            }
        });
    }

    private void sendBackCommand() {
        if (!connected) {
            return;
        }
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (outputLock) {
                        if (clientOutput == null) {
                            return;
                        }
                        clientOutput.writeInt(CMD_BACK);
                        clientOutput.flush();
                    }
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            toast("已发送远程返回指令");
                        }
                    });
                } catch (IOException error) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            toast("远程返回指令发送失败");
                        }
                    });
                    disconnectFromServer(true);
                }
            }
        });
    }

    private ConnectionInfo parseConnectionInfo() {
        String host = hostInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();

        if (host.contains(";")) {
            String[] parts = host.split(";");
            host = parts[0].trim();
            if (parts.length > 1 && TextUtils.isEmpty(portText)) {
                portText = parts[1].trim();
            }
        }

        hostInput.setText(host);
        if (!TextUtils.isEmpty(portText)) {
            portInput.setText(portText);
        }

        if (TextUtils.isEmpty(host)) {
            hostInput.setError("请输入服务端 IP、域名或 IPv6 地址");
            setStatus("服务端地址不能为空。示例：192.168.1.8 或 2409:xxxx::1");
            return null;
        }

        if (TextUtils.isEmpty(portText)) {
            portInput.setError("请输入端口");
            setStatus("端口不能为空。默认端口是 8888。");
            return null;
        }

        if (!TextUtils.isDigitsOnly(portText)) {
            portInput.setError("端口必须是数字");
            setStatus("端口格式错误，请输入纯数字。\n示例：8888");
            return null;
        }

        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) {
            portInput.setError("端口范围应为 1-65535");
            setStatus("端口超出范围，请输入 1 到 65535 之间的数字。");
            return null;
        }

        hostInput.setError(null);
        portInput.setError(null);
        return new ConnectionInfo(host, port);
    }

    private void replaceCurrentFrame(Bitmap newFrame) {
        Bitmap oldFrame = currentFrame;
        currentFrame = newFrame;
        if (oldFrame != null && oldFrame != newFrame && !oldFrame.isRecycled()) {
            oldFrame.recycle();
        }
    }

    private void ensureFrameBuffer(int length) {
        if (frameBuffer.length < length) {
            frameBuffer = new byte[length];
        }
    }

    private int[] mapToServerPoint(float rawX, float rawY) {
        float[] point = {clamp(rawX, 0, screenWidth), clamp(rawY, 0, screenHeight)};
        previewMatrix.invert(inversePreviewMatrix);
        inversePreviewMatrix.mapPoints(point);
        float mappedX = clamp(point[0], 0, screenWidth);
        float mappedY = clamp(point[1], 0, screenHeight);

        int x;
        int y;
        switch (serverOrientation) {
            case Surface.ROTATION_90:
                x = Math.round(mappedY * serverWidth / (float) screenHeight);
                y = serverHeight - Math.round(mappedX * serverHeight / (float) screenWidth);
                break;
            case Surface.ROTATION_180:
                x = serverWidth - Math.round(mappedX * serverWidth / (float) screenWidth);
                y = serverHeight - Math.round(mappedY * serverHeight / (float) screenHeight);
                break;
            case Surface.ROTATION_270:
                x = serverWidth - Math.round(mappedY * serverWidth / (float) screenHeight);
                y = Math.round(mappedX * serverHeight / (float) screenWidth);
                break;
            case Surface.ROTATION_0:
            default:
                x = Math.round(mappedX * serverWidth / (float) screenWidth);
                y = Math.round(mappedY * serverHeight / (float) screenHeight);
                break;
        }
        return new int[]{clamp(x, 0, serverWidth), clamp(y, 0, serverHeight)};
    }

    private int calculateGestureDuration(MotionEvent event) {
        long pressDuration = event.getEventTime() - touchDownTime;
        return pressDuration >= 600 ? 1000 : 100;
    }

    private void redrawFrame() {
        if (currentFrame == null || !textureView.isAvailable()) {
            return;
        }
        Canvas canvas = textureView.lockCanvas(new Rect(0, 0, screenWidth, screenHeight));
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.save();
            canvas.concat(previewMatrix);
            drawFrameWithOrientation(canvas, currentFrame);
            canvas.restore();
        } finally {
            textureView.unlockCanvasAndPost(canvas);
        }
    }

    private void drawFrameWithOrientation(Canvas canvas, Bitmap frame) {
        Rect destination = new Rect(0, 0, screenWidth, screenHeight);
        switch (serverOrientation) {
            case Surface.ROTATION_90:
                canvas.rotate(90, screenWidth / 2.0f, screenHeight / 2.0f);
                canvas.drawBitmap(frame, null, buildRotatedRect(), framePaint);
                break;
            case Surface.ROTATION_180:
                canvas.rotate(180, screenWidth / 2.0f, screenHeight / 2.0f);
                canvas.drawBitmap(frame, null, destination, framePaint);
                break;
            case Surface.ROTATION_270:
                canvas.rotate(270, screenWidth / 2.0f, screenHeight / 2.0f);
                canvas.drawBitmap(frame, null, buildRotatedRect(), framePaint);
                break;
            case Surface.ROTATION_0:
            default:
                canvas.drawBitmap(frame, null, destination, framePaint);
                break;
        }
    }

    private Rect buildRotatedRect() {
        return new Rect(
            (screenWidth - screenHeight) / 2,
            (screenHeight - screenWidth) / 2,
            (screenWidth + screenHeight) / 2,
            (screenHeight + screenWidth) / 2
        );
    }

    private void resetPreview() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    resetPreview();
                }
            });
            return;
        }
        currentScale = 1.0f;
        previewMatrix.reset();
        updateConnectedUi(connected);
        redrawFrame();
    }

    private void constrainPreviewMatrix() {
        if (currentScale <= MIN_SCALE + 0.001f) {
            previewMatrix.reset();
            currentScale = MIN_SCALE;
            return;
        }
        float[] points = {
            0f, 0f,
            screenWidth, 0f,
            screenWidth, screenHeight,
            0f, screenHeight
        };
        previewMatrix.mapPoints(points);
        float minX = Math.min(Math.min(points[0], points[2]), Math.min(points[4], points[6]));
        float maxX = Math.max(Math.max(points[0], points[2]), Math.max(points[4], points[6]));
        float minY = Math.min(Math.min(points[1], points[3]), Math.min(points[5], points[7]));
        float maxY = Math.max(Math.max(points[1], points[3]), Math.max(points[5], points[7]));
        float dx = 0f;
        float dy = 0f;
        if (minX > 0f) {
            dx = -minX;
        } else if (maxX < screenWidth) {
            dx = screenWidth - maxX;
        }
        if (minY > 0f) {
            dy = -minY;
        } else if (maxY < screenHeight) {
            dy = screenHeight - maxY;
        }
        previewMatrix.postTranslate(dx, dy);
    }

    private void updateConnectedUi(boolean isConnected) {
        connectButton.setText(isConnected ? "断开连接" : "连接");
        hostInput.setEnabled(!isConnected);
        portInput.setEnabled(!isConnected);
        discoverButton.setEnabled(!isConnected);
        remoteBackButton.setEnabled(isConnected);
        resetViewButton.setEnabled(isConnected && currentScale > MIN_SCALE);
    }

    private void setStatus(String status) {
        statusView.setText(status);
    }

    private void disconnectFromServer(boolean updateUi) {
        synchronized (connectionLock) {
            connected = false;
            closeQuietly(clientInput);
            closeQuietly(clientOutput);
            closeQuietly(clientSocket);
            clientInput = null;
            clientOutput = null;
            clientSocket = null;
            stopAudioPlayer();
            closeQuietly(audioInput);
            closeQuietly(audioSocket);
            audioInput = null;
            audioSocket = null;
        }
        resetPreview();
        if (updateUi) {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    updateConnectedUi(false);
                }
            });
        }
    }

    private void stopAudioPlayer() {
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }

    private void postToMain(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    private String safeMessage(Throwable throwable) {
        return TextUtils.isEmpty(throwable.getMessage()) ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void onBackPressed() {
        if (connected) {
            disconnectFromServer(true);
            setStatus("已断开连接");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectFromServer(false);
        backgroundExecutor.shutdownNow();
        Bitmap frame = currentFrame;
        currentFrame = null;
        if (frame != null && !frame.isRecycled()) {
            frame.recycle();
        }
    }

    private static final class ConnectionInfo {
        final String host;
        final int port;

        ConnectionInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}