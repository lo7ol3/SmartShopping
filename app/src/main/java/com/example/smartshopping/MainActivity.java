package com.example.smartshopping;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ---------- UI ----------
    private View statusDot;
    private TextView statusText, scannerText, totalText;
    private MaterialButton btnScan, btnYes, btnNo, btnTotal;
    private PreviewView previewView;
    private RecyclerView cartRecycler;

    // ---------- CORE ----------
    private ExecutorService cameraExecutor;
    private YoloV8Detector detector;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;

    // ---------- SPEECH SAFETY ----------
    private boolean isListening = false;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());

    // ---------- STATE ----------
    private final List<CartItem> cart = new ArrayList<>();
    private CartAdapter cartAdapter;

    private boolean isScanning = false;
    private boolean awaitingConfirmation = false;

    private String pendingItem = null;
    private float pendingPrice = 0f;

    // ---------- PRICE DB ----------
    private final Map<String, Float> PRICE_DB = new HashMap<String, Float>() {{
        put("apple", 2.50f);
        put("bread", 3.20f);
        put("chips", 4.50f);
        put("egg", 4.80f);
        put("milk", 6.90f);
        put("mineral_water", 2.00f);
        put("oil", 8.90f);
        put("rice", 15.00f);
        put("sponge", 2.30f);
        put("tissue", 3.50f);
        put("toothbrush", 2.80f);
        put("toothpaste", 5.90f);
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        setupCart();
        setupSpeechRecognizer();
        setupTTS(); // 🔊 instructions spoken here

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            detector = new YoloV8Detector(this);
            setStatus("Initializing...", R.drawable.status_dot_yellow);
        } catch (Exception e) {
            setStatus("Model load failed", R.drawable.status_dot_red);
            return;
        }

        btnScan.setOnClickListener(v -> startScan());
        btnYes.setOnClickListener(v -> confirmAdd());
        btnNo.setOnClickListener(v -> cancelAdd());
        btnTotal.setOnClickListener(v -> showTotal());

        checkCameraPermission();
    }

    // ================= MAIN INSTRUCTIONS =================

    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                speakMainInstructions();
            }
        });
    }

    private void speakMainInstructions() {
        setStatus("Calibrating...", R.drawable.status_dot_yellow);

        tts.speak(
                "System ready. " +
                        "Say scan or hey shopping to start scanning. " +
                        "Say yes or add to confirm an item. " +
                        "Say no or cancel to reject. " +
                        "Say total or calculate to finish.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "MAIN_INSTRUCTIONS"
        );

        // Start listening AFTER instructions
        speechHandler.postDelayed(this::safeStartListening, 4500);
    }

    // ================= SPEECH =================

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                setStatus("Listening...", R.drawable.status_dot_grey);
            }

            @Override
            public void onBeginningOfSpeech() {
                setStatus("Processing...", R.drawable.status_dot_red);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;

                ArrayList<String> list =
                        results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);

                if (list != null && !list.isEmpty()) {
                    handleVoiceCommand(list.get(0));
                }

                safeStartListening();
            }

            @Override
            public void onError(int error) {
                isListening = false;
                safeStartListening();
            }

            public void onRmsChanged(float rms) {}
            public void onBufferReceived(byte[] buffer) {}
            public void onEndOfSpeech() {}
            public void onPartialResults(Bundle partialResults) {}
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void safeStartListening() {
        if (isListening || speechRecognizer == null) return;

        speechHandler.postDelayed(() -> {
            try {
                isListening = true;
                speechRecognizer.startListening(
                        new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                );
            } catch (Exception e) {
                isListening = false;
            }
        }, 800);
    }

    private void handleVoiceCommand(String cmd) {
        cmd = cmd.toLowerCase(Locale.US);

        if (cmd.contains("scan") || cmd.contains("hey shopping")) {
            btnScan.performClick();
        } else if (cmd.contains("yes") || cmd.contains("add")) {
            btnYes.performClick();
        } else if (cmd.contains("no") || cmd.contains("cancel")) {
            btnNo.performClick();
        } else if (cmd.contains("total") || cmd.contains("calculate")) {
            btnTotal.performClick();
        }
    }

    // ================= CAMERA =================

    private void startScan() {
        if (isScanning) return;
        isScanning = true;

        scannerText.setText("SCANNING...");
        setStatus("Camera Open", R.drawable.status_dot_green);
        speak("Scanning");

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                analysis.setAnalyzer(cameraExecutor, image -> {

                    Bitmap bitmap = ImageUtils.rotateBitmap(
                            ImageUtils.imageToBitmap(image),
                            image.getImageInfo().getRotationDegrees()
                    );

                    if (bitmap == null) {
                        image.close();
                        return;
                    }

                    if (!awaitingConfirmation) {
                        List<YoloV8Detector.Detection> detections =
                                detector.detect(bitmap);

                        if (!detections.isEmpty()) {
                            String label = detections.get(0).label;

                            if (PRICE_DB.containsKey(label)) {
                                awaitingConfirmation = true;
                                pendingItem = label;
                                pendingPrice = PRICE_DB.get(label);

                                runOnUiThread(() -> {
                                    scannerText.setText(
                                            label.replace("_", " ").toUpperCase()
                                                    + "\nRM " + pendingPrice);
                                    speak("Found " +
                                            label.replace("_", " "));
                                });
                            }
                        }
                    }

                    image.close();
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );

            } catch (Exception e) {
                runOnUiThread(() ->
                        setStatus("Camera Error",
                                R.drawable.status_dot_red));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ================= CART =================

    private void confirmAdd() {
        if (pendingItem == null) return;

        cart.add(new CartItem(pendingItem, pendingPrice, 1));
        cartAdapter.notifyDataSetChanged();
        updateTotal();

        scannerText.setText("ADDED");
        speak("Item added");

        pendingItem = null;
        awaitingConfirmation = false;
    }

    private void cancelAdd() {
        pendingItem = null;
        awaitingConfirmation = false;

        scannerText.setText("CANCELLED");
        speak("Cancelled");
    }

    private void showTotal() {
        float total = 0f;
        for (CartItem c : cart) total += c.getTotal();

        scannerText.setText("TOTAL\nRM " + total);
        speak("Total is " + total + " Ringgit");

        cart.clear();
        cartAdapter.notifyDataSetChanged();
        updateTotal();
    }

    private void updateTotal() {
        float sum = 0f;
        for (CartItem c : cart) sum += c.getTotal();
        totalText.setText("RM " + String.format("%.2f", sum));
    }

    // ================= UI & UTILS =================

    private void bindUI() {
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        scannerText = findViewById(R.id.scannerText);
        totalText = findViewById(R.id.totalText);

        btnScan = findViewById(R.id.btnScan);
        btnYes = findViewById(R.id.btnYes);
        btnNo = findViewById(R.id.btnNo);
        btnTotal = findViewById(R.id.btnTotal);

        previewView = findViewById(R.id.previewView);
        cartRecycler = findViewById(R.id.cartRecycler);
    }

    private void setupCart() {
        cartAdapter = new CartAdapter(cart);
        cartRecycler.setLayoutManager(new LinearLayoutManager(this));
        cartRecycler.setAdapter(cartAdapter);
        updateTotal();
    }

    private void setStatus(String text, int dotDrawable) {
        statusText.setText(text);
        statusDot.setBackgroundResource(dotDrawable);
    }

    private void speak(String text) {
        if (tts != null)
            tts.speak(text,
                    TextToSpeech.QUEUE_FLUSH,
                    null, null);
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    1001
            );
        }
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (tts != null) tts.shutdown();
        cameraExecutor.shutdown();
        super.onDestroy();
    }
}
