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
import android.media.MediaCodec;
import android.media.MediaFormat;
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
import java.nio.ByteBuffer;
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
    private static final int PROTOCOL_MAGIC = 0x41524431;
    private static final int PROTOCOL_VERSION = 3;
    private static final int HANDSHAKE_STATUS_OK = 0;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int RELAY_MAGIC = 0x41525231;
    private static final int RELAY_VERSION = 1;
    private static final int RELAY_ROLE_CLIENT = 2;
    private static final int RELAY_CHANNEL_VIDEO = 1;
    private static final int RELAY_CHANNEL_AUDIO = 2;
    private static final int RELAY_STATUS_OK = 0;
    private static final int RELAY_CONNECT_TIMEOUT_MS = 10_000;
    private static final int RELAY_WAIT_TIMEOUT_MS = 60_000;
    private static final int VIDEO_PACKET_CONFIG = 1;
    private static final int VIDEO_PACKET_FRAME = 2;
    private static final int QUALITY_PROFILE_HD = 1;
    private static final int QUALITY_PROFILE_SAVE_DATA = 2;
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int CONNECTION_MODE_LAN = 1;
    private static final int CONNECTION_MODE_PUBLIC = 2;
    private static final int DEFAULT_PORT = 8888;
    private static final int DEFAULT_RELAY_PORT = 9000;
    private static final int AUDIO_PORT_OFFSET = 1;
    private static final int DISCOVERY_PORT = 8891;
    private static final int DISCOVERY_TIMEOUT_MS = 700;
    private static final int DISCOVERY_ATTEMPTS = 3;
    private static final int AUDIO_SAMPLE_RATE = 16000;
    private static final int AUDIO_CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CMD_GESTURE = 1;
    private static final int CMD_BACK = 2;
    private static final int CMD_SET_QUALITY = 3;
    private static final String DISCOVERY_REQUEST = "ANDROID_REMOTE_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE = "ANDROID_REMOTE_SERVICE_V1";
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 4.0f;

    private android.view.View rootLayout;
    private android.view.View topPanel;
    private android.view.View bottomPanel;
    private TextureView textureView;
    private EditText hostInput;
    private EditText portInput;
    private EditText tokenInput;
    private EditText relayRoomInput;
    private TextView statusView;
    private Button controlsToggleButton;
    private Button lanModeButton;
    private Button publicModeButton;
    private Button connectButton;
    private Button discoverButton;
    private Button remoteBackButton;
    private Button qualityHdButton;
    private Button qualitySaveButton;
    private Button resetViewButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final Object outputLock = new Object();
    private final Object connectionLock = new Object();
    private final Object decoderLock = new Object();
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
    private MediaCodec videoDecoder;
    private Surface decoderSurface;
    private Bitmap currentFrame;
    private byte[] frameBuffer = new byte[0];
    private byte[] decoderCsd0 = new byte[0];
    private byte[] decoderCsd1 = new byte[0];
    private int decoderVideoWidth;
    private int decoderVideoHeight;

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
    private int connectionMode = CONNECTION_MODE_LAN;
    private int selectedQualityProfile = QUALITY_PROFILE_HD;
    private boolean controlsPanelsVisible = true;
    private float controlsToggleDownRawX;
    private float controlsToggleDownRawY;
    private float controlsToggleStartX;
    private float controlsToggleStartY;
    private boolean controlsToggleDragged = false;
    private int controlsToggleTouchSlop;

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
        updateConnectionModeUi();
        updateConnectedUi(false);
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.activitymainRelativeLayout1);
        topPanel = findViewById(R.id.activity_mainTopPanel);
        bottomPanel = findViewById(R.id.activity_mainBottomPanel);
        textureView = findViewById(R.id.activity_mainTextureView);
        hostInput = findViewById(R.id.activity_mainHostEditText);
        portInput = findViewById(R.id.activity_mainPortEditText);
        tokenInput = findViewById(R.id.activity_mainTokenEditText);
        relayRoomInput = findViewById(R.id.activity_mainRelayRoomEditText);
        statusView = findViewById(R.id.activity_mainStatusTextView);
        controlsToggleButton = findViewById(R.id.activity_mainControlsToggleButton);
        lanModeButton = findViewById(R.id.activity_mainLanModeButton);
        publicModeButton = findViewById(R.id.activity_mainPublicModeButton);
        connectButton = findViewById(R.id.activity1_bt);
        discoverButton = findViewById(R.id.activity_mainDiscoverButton);
        remoteBackButton = findViewById(R.id.activity_mainBackButton);
        qualityHdButton = findViewById(R.id.activity_mainQualityHdButton);
        qualitySaveButton = findViewById(R.id.activity_mainQualitySaveButton);
        resetViewButton = findViewById(R.id.activity_mainResetButton);
        portInput.setText(String.valueOf(DEFAULT_PORT));
        controlsToggleTouchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        controlsToggleButton.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        controlsToggleDownRawX = event.getRawX();
                        controlsToggleDownRawY = event.getRawY();
                        controlsToggleStartX = view.getX();
                        controlsToggleStartY = view.getY();
                        controlsToggleDragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - controlsToggleDownRawX;
                        float deltaY = event.getRawY() - controlsToggleDownRawY;
                        if (!controlsToggleDragged
                            && (Math.abs(deltaX) > controlsToggleTouchSlop || Math.abs(deltaY) > controlsToggleTouchSlop)) {
                            controlsToggleDragged = true;
                        }
                        if (controlsToggleDragged) {
                            moveControlsToggleButton(controlsToggleStartX + deltaX, controlsToggleStartY + deltaY);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!controlsToggleDragged) {
                            view.performClick();
                            setControlPanelsVisible(!controlsPanelsVisible);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        lanModeButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                switchConnectionMode(CONNECTION_MODE_LAN);
            }
        });
        publicModeButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                switchConnectionMode(CONNECTION_MODE_PUBLIC);
            }
        });
    }

    private boolean isPublicModeEnabled() {
        return connectionMode == CONNECTION_MODE_PUBLIC;
    }

    private void switchConnectionMode(int mode) {
        if (connectionMode == mode) {
            return;
        }
        connectionMode = mode;
        if (!connected) {
            selectedQualityProfile = getRecommendedQualityProfile();
        }
        updateConnectionModeUi();
    }

    private int getRecommendedQualityProfile() {
        return isPublicModeEnabled() ? QUALITY_PROFILE_SAVE_DATA : QUALITY_PROFILE_HD;
    }

    private void updateConnectionModeUi() {
        boolean publicMode = isPublicModeEnabled();
        hostInput.setHint(publicMode ? "NAS 中继域名 / IP" : "服务端 IP / 域名 / IPv6");
        portInput.setHint(publicMode ? String.valueOf(DEFAULT_RELAY_PORT) : String.valueOf(DEFAULT_PORT));
        lanModeButton.setText(publicMode ? "局域网连接" : "局域网连接（当前）");
        publicModeButton.setText(publicMode ? "公网连接（当前）" : "公网连接");
        relayRoomInput.setVisibility(publicMode ? android.view.View.VISIBLE : android.view.View.GONE);
        discoverButton.setVisibility(publicMode ? android.view.View.GONE : android.view.View.VISIBLE);
        if (publicMode) {
            String currentPort = portInput.getText().toString().trim();
            if (TextUtils.isEmpty(currentPort) || String.valueOf(DEFAULT_PORT).equals(currentPort)) {
                portInput.setText(String.valueOf(DEFAULT_RELAY_PORT));
            }
            if (!connected) {
                setStatus("公网连接模式：请填写 NAS 地址、端口、房间号和鉴权口令，然后点击“连接”。");
            }
        } else {
            String currentPort = portInput.getText().toString().trim();
            if (TextUtils.isEmpty(currentPort) || String.valueOf(DEFAULT_RELAY_PORT).equals(currentPort)) {
                portInput.setText(String.valueOf(DEFAULT_PORT));
            }
            relayRoomInput.setError(null);
            if (!connected) {
                setStatus("局域网连接模式：可直接输入服务端地址，或点击“发现服务端”自动发现。\n如需 NAS 中继，请点上方“公网连接”。");
            }
        }
        updateConnectedUi(connected);
    }

    private void setControlPanelsVisible(boolean visible) {
        controlsPanelsVisible = visible;
        topPanel.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        bottomPanel.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (connected) {
            controlsToggleButton.setVisibility(android.view.View.VISIBLE);
            controlsToggleButton.setText(visible ? "收起控制" : "显示控制");
            controlsToggleButton.bringToFront();
        } else {
            controlsToggleButton.setVisibility(android.view.View.GONE);
        }
    }

    private void moveControlsToggleButton(float targetX, float targetY) {
        float maxX = Math.max(0, rootLayout.getWidth() - controlsToggleButton.getWidth());
        float maxY = Math.max(0, rootLayout.getHeight() - controlsToggleButton.getHeight());
        controlsToggleButton.setX(clamp(targetX, 0f, maxX));
        controlsToggleButton.setY(clamp(targetY, 0f, maxY));
    }

    private String getQualityLabel(int profile) {
        return profile == QUALITY_PROFILE_SAVE_DATA ? "省流" : "高清";
    }

    private void updateQualityButtons() {
        qualityHdButton.setEnabled(connected && selectedQualityProfile != QUALITY_PROFILE_HD);
        qualitySaveButton.setEnabled(connected && selectedQualityProfile != QUALITY_PROFILE_SAVE_DATA);
        qualityHdButton.setText(selectedQualityProfile == QUALITY_PROFILE_HD ? "高清（当前）" : "高清");
        qualitySaveButton.setText(selectedQualityProfile == QUALITY_PROFILE_SAVE_DATA ? "省流（当前）" : "省流");
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
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                screenWidth = width;
                screenHeight = height;
                configureVideoDecoderIfPossible();
                redrawFrame();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                screenWidth = width;
                screenHeight = height;
                constrainPreviewMatrix();
                configureVideoDecoderIfPossible();
                redrawFrame();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                releaseVideoDecoder();
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
        qualityHdButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                requestVideoQualityProfile(QUALITY_PROFILE_HD, true);
            }
        });
        qualitySaveButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                requestVideoQualityProfile(QUALITY_PROFILE_SAVE_DATA, true);
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
        setStatus(info.useRelay ? "正在连接公网中继..." : "正在连接局域网服务端...");
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = new Socket();
                    socket.connect(new java.net.InetSocketAddress(info.host, info.port), RELAY_CONNECT_TIMEOUT_MS);
                    socket.setTcpNoDelay(true);
                    DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    if (info.useRelay) {
                        socket.setSoTimeout(RELAY_WAIT_TIMEOUT_MS);
                        performRelayHandshake(info, input, output, RELAY_CHANNEL_VIDEO);
                    }
                    socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    String handshakeError = performHandshake(info, input, output);
                    if (handshakeError != null) {
                        closeQuietly(input);
                        closeQuietly(output);
                        closeQuietly(socket);
                        throw new IOException(handshakeError);
                    }
                    socket.setSoTimeout(0);
                    synchronized (connectionLock) {
                        clientSocket = socket;
                        clientInput = input;
                        clientOutput = output;
                        connected = true;
                    }
                    controlsPanelsVisible = false;
                    sendQualityCommand(selectedQualityProfile);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            updateConnectedUi(true);
                            setStatus(info.useRelay
                                ? "已通过公网中继连接，房间号：" + info.relayRoomId
                                : "已连接到 " + info.host + ":" + info.port);
                            toast("连接成功");
                        }
                    });
                    startAudioReceiver(info);
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

    private void performRelayHandshake(ConnectionInfo info, DataInputStream input, DataOutputStream output, int channel) throws IOException {
        output.writeInt(RELAY_MAGIC);
        output.writeInt(RELAY_VERSION);
        output.writeInt(RELAY_ROLE_CLIENT);
        output.writeInt(channel);
        writeRelayString(output, info.relayRoomId);
        writeRelayString(output, info.authToken);
        output.flush();
        int status = input.readInt();
        String message = readRelayString(input);
        if (status != RELAY_STATUS_OK) {
            throw new IOException(TextUtils.isEmpty(message) ? "NAS 中继拒绝连接" : message);
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

    private String performHandshake(ConnectionInfo info, DataInputStream input, DataOutputStream output) throws IOException {
        output.writeInt(PROTOCOL_MAGIC);
        output.writeInt(PROTOCOL_VERSION);
        output.writeUTF(info.authToken);
        output.flush();
        int status = input.readInt();
        String message = input.readUTF();
        if (status == HANDSHAKE_STATUS_OK) {
            return null;
        }
        return TextUtils.isEmpty(message) ? "服务端拒绝连接" : message;
    }

    private void readFrameLoop() {
        while (connected) {
            try {
                int packetType = clientInput.readInt();
                if (packetType == VIDEO_PACKET_CONFIG) {
                    final int orientation = clientInput.readInt();
                    decoderVideoWidth = clientInput.readInt();
                    decoderVideoHeight = clientInput.readInt();
                    serverWidth = clientInput.readInt();
                    serverHeight = clientInput.readInt();
                    int csd0Length = clientInput.readInt();
                    decoderCsd0 = new byte[csd0Length];
                    clientInput.readFully(decoderCsd0, 0, csd0Length);
                    int csd1Length = clientInput.readInt();
                    decoderCsd1 = new byte[csd1Length];
                    clientInput.readFully(decoderCsd1, 0, csd1Length);
                    serverOrientation = orientation;
                    configureVideoDecoderIfPossible();
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            redrawFrame();
                        }
                    });
                    continue;
                }
                if (packetType == VIDEO_PACKET_FRAME) {
                    int orientation = clientInput.readInt();
                    serverWidth = clientInput.readInt();
                    serverHeight = clientInput.readInt();
                    int flags = clientInput.readInt();
                    int length = clientInput.readInt();
                    if (length <= 0) {
                        continue;
                    }
                    serverOrientation = orientation;
                    ensureFrameBuffer(length);
                    clientInput.readFully(frameBuffer, 0, length);
                    queueVideoFrame(frameBuffer, length, flags);
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

    private void startAudioReceiver(final ConnectionInfo info) {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int minBufferSize = AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING);
                if (minBufferSize <= 0) {
                    return;
                }
                byte[] audioBuffer = new byte[minBufferSize * 2];
                try {
                    Socket localAudioSocket = new Socket();
                    int targetPort = info.useRelay ? info.port : info.port + AUDIO_PORT_OFFSET;
                    localAudioSocket.connect(new java.net.InetSocketAddress(info.host, targetPort), RELAY_CONNECT_TIMEOUT_MS);
                    localAudioSocket.setTcpNoDelay(true);
                    DataInputStream localInput = new DataInputStream(new BufferedInputStream(localAudioSocket.getInputStream()));
                    if (info.useRelay) {
                        localAudioSocket.setSoTimeout(RELAY_WAIT_TIMEOUT_MS);
                        performRelayHandshake(
                            info,
                            localInput,
                            new DataOutputStream(new BufferedOutputStream(localAudioSocket.getOutputStream())),
                            RELAY_CHANNEL_AUDIO
                        );
                    }
                    localAudioSocket.setSoTimeout(0);
                    audioSocket = localAudioSocket;
                    audioInput = localInput;
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
        if (isPublicModeEnabled()) {
            setStatus("公网连接模式不使用局域网发现。请填写 NAS 地址、端口和房间号后直接连接。");
            toast("公网连接模式下无需发现服务端");
            return;
        }
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

    private void requestVideoQualityProfile(final int profile, final boolean showFeedback) {
        if (selectedQualityProfile == profile && connected) {
            return;
        }
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    sendQualityCommand(profile);
                    selectedQualityProfile = profile;
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            updateConnectedUi(true);
                            setStatus("已切换到“" + getQualityLabel(profile) + "”清晰度，画面会自动刷新。");
                            if (showFeedback) {
                                toast("已切换到“" + getQualityLabel(profile) + "”");
                            }
                        }
                    });
                } catch (IOException error) {
                    disconnectFromServer(true);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("切换清晰度失败，连接已断开。");
                            toast("切换清晰度失败");
                        }
                    });
                }
            }
        });
    }

    private void sendQualityCommand(int profile) throws IOException {
        synchronized (outputLock) {
            if (clientOutput == null) {
                throw new IOException("未连接到服务端");
            }
            clientOutput.writeInt(CMD_SET_QUALITY);
            clientOutput.writeInt(profile);
            clientOutput.flush();
        }
    }

    private ConnectionInfo parseConnectionInfo() {
        String host = hostInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        String authToken = tokenInput.getText().toString().trim();
        String relayRoomId = relayRoomInput.getText().toString().trim();
        boolean useRelay = isPublicModeEnabled();

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
            hostInput.setError(useRelay ? "请输入公网中继域名或 IP" : "请输入服务端 IP、域名或 IPv6 地址");
            setStatus(useRelay
                ? "公网中继地址不能为空。示例：relay.example.com"
                : "服务端地址不能为空。示例：192.168.1.8 或 2409:xxxx::1");
            return null;
        }

        if (TextUtils.isEmpty(portText)) {
            portText = String.valueOf(useRelay ? DEFAULT_RELAY_PORT : DEFAULT_PORT);
            portInput.setText(portText);
        }

        if (!TextUtils.isDigitsOnly(portText)) {
            portInput.setError("端口必须是数字");
            setStatus("端口格式错误，请输入纯数字。\n示例：8888");
            return null;
        }

        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) {
            portInput.setError("端口范围应为 1-65535");
            setStatus(useRelay
                ? "公网中继端口超出范围，请输入 1 到 65535 之间的数字。"
                : "端口超出范围，请输入 1 到 65535 之间的数字。");
            return null;
        }

        if (useRelay && TextUtils.isEmpty(relayRoomId)) {
            relayRoomInput.setError("请输入中继房间号");
            setStatus("公网连接模式必须填写房间号，客户端和服务端要保持一致。");
            return null;
        }

        hostInput.setError(null);
        portInput.setError(null);
        relayRoomInput.setError(null);
        return new ConnectionInfo(host, port, authToken, useRelay, relayRoomId);
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

    private void configureVideoDecoderIfPossible() {
        synchronized (decoderLock) {
            if (!textureView.isAvailable() || decoderCsd0.length == 0 || decoderCsd1.length == 0) {
                return;
            }
            releaseVideoDecoderLocked();
            try {
                decoderSurface = new Surface(textureView.getSurfaceTexture());
                MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, decoderVideoWidth, decoderVideoHeight);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(decoderCsd0));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(decoderCsd1));
                videoDecoder = MediaCodec.createDecoderByType(VIDEO_MIME_TYPE);
                videoDecoder.configure(format, decoderSurface, null, 0);
                videoDecoder.start();
            } catch (IOException | RuntimeException error) {
                releaseVideoDecoderLocked();
                postToMain(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("视频解码初始化失败，请重新连接服务端。");
                        toast("视频解码初始化失败");
                    }
                });
            }
        }
    }

    private void queueVideoFrame(byte[] data, int length, int flags) throws IOException {
        synchronized (decoderLock) {
            if (videoDecoder == null) {
                return;
            }
            try {
                int inputIndex = videoDecoder.dequeueInputBuffer(10_000L);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(data, 0, length);
                        videoDecoder.queueInputBuffer(inputIndex, 0, length, System.nanoTime() / 1000L, flags);
                    }
                }
                drainVideoDecoderLocked();
            } catch (IllegalStateException error) {
                throw new IOException("视频解码器异常", error);
            }
        }
    }

    private void drainVideoDecoderLocked() {
        if (videoDecoder == null) {
            return;
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int outputIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 0L);
            if (outputIndex >= 0) {
                videoDecoder.releaseOutputBuffer(outputIndex, true);
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            break;
        }
    }

    private void releaseVideoDecoder() {
        synchronized (decoderLock) {
            releaseVideoDecoderLocked();
        }
    }

    private void releaseVideoDecoderLocked() {
        if (videoDecoder != null) {
            try {
                videoDecoder.stop();
            } catch (Exception ignored) {
            }
            try {
                videoDecoder.release();
            } catch (Exception ignored) {
            }
            videoDecoder = null;
        }
        if (decoderSurface != null) {
            try {
                decoderSurface.release();
            } catch (Exception ignored) {
            }
            decoderSurface = null;
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
        if (!textureView.isAvailable()) {
            return;
        }
        Matrix displayMatrix = new Matrix(previewMatrix);
        if (serverOrientation == Surface.ROTATION_90 || serverOrientation == Surface.ROTATION_270) {
            float scaleX = screenHeight / (float) Math.max(1, screenWidth);
            float scaleY = screenWidth / (float) Math.max(1, screenHeight);
            displayMatrix.postScale(scaleX, scaleY, screenWidth / 2.0f, screenHeight / 2.0f);
            displayMatrix.postRotate(
                serverOrientation == Surface.ROTATION_90 ? 90.0f : 270.0f,
                screenWidth / 2.0f,
                screenHeight / 2.0f
            );
        } else if (serverOrientation == Surface.ROTATION_180) {
            displayMatrix.postRotate(180.0f, screenWidth / 2.0f, screenHeight / 2.0f);
        }
        textureView.setTransform(displayMatrix);
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
        tokenInput.setEnabled(!isConnected);
        lanModeButton.setEnabled(!isConnected && connectionMode != CONNECTION_MODE_LAN);
        publicModeButton.setEnabled(!isConnected && connectionMode != CONNECTION_MODE_PUBLIC);
        relayRoomInput.setEnabled(!isConnected && isPublicModeEnabled());
        discoverButton.setEnabled(!isConnected && !isPublicModeEnabled());
        remoteBackButton.setEnabled(isConnected);
        resetViewButton.setEnabled(isConnected && currentScale > MIN_SCALE);
        updateQualityButtons();
        if (isConnected) {
            controlsToggleButton.setVisibility(android.view.View.VISIBLE);
            controlsToggleButton.setText(controlsPanelsVisible ? "收起控制" : "显示控制");
            topPanel.setVisibility(controlsPanelsVisible ? android.view.View.VISIBLE : android.view.View.GONE);
            bottomPanel.setVisibility(controlsPanelsVisible ? android.view.View.VISIBLE : android.view.View.GONE);
            controlsToggleButton.bringToFront();
        } else {
            controlsPanelsVisible = true;
            topPanel.setVisibility(android.view.View.VISIBLE);
            bottomPanel.setVisibility(android.view.View.VISIBLE);
            controlsToggleButton.setVisibility(android.view.View.GONE);
        }
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
        releaseVideoDecoder();
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
        releaseVideoDecoder();
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
        final String authToken;
        final boolean useRelay;
        final String relayRoomId;

        ConnectionInfo(String host, int port, String authToken, boolean useRelay, String relayRoomId) {
            this.host = host;
            this.port = port;
            this.authToken = authToken;
            this.useRelay = useRelay;
            this.relayRoomId = relayRoomId;
        }
    }
}