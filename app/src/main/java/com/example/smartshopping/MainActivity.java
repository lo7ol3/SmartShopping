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
import android.util.Log;
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

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // ================= UI =================
    private View statusDot;
    private View qtyPanel;
    private TextView statusText, scannerText, totalText;
    private MaterialButton btnScan, btnYes, btnNo, btnTotal;
    private MaterialButton qty1, qty2, qty3, qty4, qty5, qty6, qty7, qty8,qty9, qty10;
    private PreviewView previewView;
    private RecyclerView cartRecycler;

    // ================= CORE =================
    private ExecutorService cameraExecutor;
    private YoloV8Detector detector;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ================= CART =================
    private final List<CartItem> cart = new ArrayList<>();
    private CartAdapter cartAdapter;

    // ================= STATE =================
    private boolean isScanning = false;
    private boolean awaitingConfirmation = false;
    private boolean awaitingQuantity = false;
    private boolean awaitingQtyConfirm = false;

    private boolean awaitingRemoveItem = false;
    private boolean awaitingRemoveQuantity = false;
    private boolean awaitingRemoveConfirm = false;

    private boolean isSpeaking = false;

    private String pendingItem = null;
    private float pendingPrice = 0f;
    private int pendingQty = -1;
    private int pendingRemoveQty = -1;

    // ================= STABILITY =================
    private String lastLabel = null;
    private long stableStart = 0;
    private static final long VERIFY_TIME = 5000;

    // ================= PRICES =================
    private final Map<String, Float> PRICE_DB = new HashMap<>();

    // =========================================================
    // LIFECYCLE
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        setupCart();
        setupTTS();
        setupSpeech();
        loadPricesFromJson();

        statusCalibrating();

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            detector = new YoloV8Detector(this);
        } catch (Exception e) {
            setStatus("Model failed", R.drawable.status_dot_red);
            return;
        }

        btnScan.setOnClickListener(v -> startScan());
        btnYes.setOnClickListener(v -> onYesPressed());
        btnNo.setOnClickListener(v -> onNoPressed());
        btnTotal.setOnClickListener(v -> speakTotal());

        setupQuantityButtons();
        checkCameraPermission();
    }

    // =========================================================
    // STATUS
    // =========================================================

    private void statusCalibrating() {
        runOnUiThread(() -> {
            statusText.setText("Calibrating (Wait)");
            statusDot.setBackgroundResource(R.drawable.status_dot_yellow);
        });
    }

    private void statusListening() {
        runOnUiThread(() -> {
            statusText.setText("Listening (Speak now)");
            statusDot.setBackgroundResource(R.drawable.status_dot_grey);
        });
    }

    private void statusProcessing() {
        runOnUiThread(() -> {
            statusText.setText("Processing (Busy)");
            statusDot.setBackgroundResource(R.drawable.status_dot_red);
        });
    }

    // =========================================================
    // PRICE LOADING
    // =========================================================

    private void loadPricesFromJson() {
        try {
            InputStream is = getAssets().open("prices_4.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            JSONObject root = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
            Iterator<String> keys = root.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                PRICE_DB.put(key, (float) root.getJSONObject(key).getDouble("price"));
            }
        } catch (Exception e) {
            Log.e("PRICE_DB", "Failed to load prices", e);
        }
    }

    // =========================================================
    // CAMERA + YOLO
    // =========================================================

    private void startScan() {
        if (isScanning) return;
        isScanning = true;

        runOnUiThread(() -> scannerText.setText("SCANNING..."));
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
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                analysis.setAnalyzer(cameraExecutor, image -> {

                    Bitmap bitmap = ImageUtils.rotateBitmap(
                            ImageUtils.imageToBitmap(image),
                            image.getImageInfo().getRotationDegrees());

                    if (bitmap == null || isSpeaking ||
                            awaitingConfirmation || awaitingQuantity ||
                            awaitingQtyConfirm || awaitingRemoveItem ||
                            awaitingRemoveQuantity || awaitingRemoveConfirm) {
                        image.close();
                        return;
                    }

                    List<YoloV8Detector.Detection> detections = detector.detect(bitmap);

                    if (!detections.isEmpty()) {
                        String label = detections.get(0).label;

                        if (!PRICE_DB.containsKey(label)) {
                            resetStability();
                        } else {
                            long now = System.currentTimeMillis();

                            if (label.equals(lastLabel)) {
                                if (stableStart == 0) {
                                    stableStart = now;
                                    runOnUiThread(() -> scannerText.setText("VERIFYING ITEM..."));
                                    speak("Verifying item");
                                }

                                if (now - stableStart >= VERIFY_TIME) {
                                    awaitingConfirmation = true;
                                    pendingItem = label;
                                    pendingPrice = PRICE_DB.get(label);
                                    String pendingPriceF = String.format("%.2f", PRICE_DB.get(label));
                                    stopListening();

                                    runOnUiThread(() ->
                                            scannerText.setText(
                                                    label.replace("_", " ").toUpperCase()
                                                            + "\nRM " + pendingPriceF));

                                    speak(label.replace("_", " ")
                                            + " costs "
                                            + pendingPriceF
                                            + " ringgit. Do you want to add to the cart?");
                                }
                            } else {
                                lastLabel = label;
                                stableStart = 0;
                            }
                        }
                    }
                    image.close();
                });

                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );

            } catch (Exception e) {
                setStatus("Camera error", R.drawable.status_dot_red);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void resetStability() {
        lastLabel = null;
        stableStart = 0;
    }

    // =========================================================
    // YES / NO FLOW
    // =========================================================

    private void onYesPressed() {

        if (awaitingConfirmation) {
            awaitingConfirmation = false;
            awaitingQuantity = true;
            runOnUiThread(() -> scannerText.setText("HOW MANY?"));
            showQtyPanel();
            speak("How many would you like to add?");
            return;
        }

        if (awaitingQtyConfirm) {
            addItemToCart(pendingItem, pendingPrice, pendingQty);
            hideQtyPanel();
            speak("Added " + pendingQty + " " + pendingItem.replace("_", " "));
            runOnUiThread(() -> scannerText.setText("ADDED x" + pendingQty));
            clearPending();
            return;
        }

        if (awaitingRemoveConfirm) {
            hideQtyPanel();
            performRemove();
        }
    }

    private void onNoPressed() {
        hideQtyPanel();
        clearPending();
        resetToIdle();
        runOnUiThread(() -> scannerText.setText("CANCELLED"));
        speak("Cancelled");
        startListening();
    }

    private void clearPending() {
        awaitingConfirmation = false;
        awaitingQuantity = false;
        awaitingQtyConfirm = false;
        awaitingRemoveItem = false;
        awaitingRemoveQuantity = false;
        awaitingRemoveConfirm = false;
        pendingItem = null;
        pendingQty = -1;
        pendingRemoveQty = -1;
        hideQtyPanel();
        resetToIdle();
        resetStability();
    }

    // =========================================================
    // CART OPS
    // =========================================================

    private void addItemToCart(String item, float price, int qty) {
        for (CartItem c : cart) {
            if (c.getName().equals(item)) {
                c.increaseQty(qty);
                cartAdapter.notifyDataSetChanged();
                updateTotal();
                return;
            }
        }
        cart.add(new CartItem(item, price, qty));
        cartAdapter.notifyDataSetChanged();
        updateTotal();
    }

    private void resetToIdle() {
        awaitingConfirmation = false;
        awaitingQuantity = false;
        awaitingQtyConfirm = false;
        awaitingRemoveItem = false;
        awaitingRemoveQuantity = false;
        awaitingRemoveConfirm = false;

        pendingItem = null;
        pendingQty = -1;
        pendingRemoveQty = -1;

        hideQtyPanel();
    }


    private void performRemove() {
        for (CartItem c : cart) {

            if (c.getName().equals(pendingItem)) {

                int availableQty = c.getQty();

                //  USER ASKED TOO MUCH
                if (pendingRemoveQty > availableQty) {
                    speak("You only have " + availableQty + " "
                            + c.getName().replace("_", " ")
                            + " in your cart. Please choose a smaller amount.");

                    // go back to quantity selection
                    awaitingRemoveConfirm = false;
                    awaitingRemoveQuantity = true;
                    showQtyPanel();
                    return;
                }

                // ✅ VALID REMOVAL
                if (pendingRemoveQty == availableQty) {
                    cart.remove(c);
                } else {
                    c.increaseQty(-pendingRemoveQty);
                }

                cartAdapter.notifyDataSetChanged();
                updateTotal();

                speak("Removed " + pendingRemoveQty + " "
                        + c.getName().replace("_", " "));

                resetToIdle();
                return;
            }
        }

        speak("Item not found.");
        resetToIdle();
    }



    private void speakTotal() {
        float total = 0f;
        for (CartItem c : cart) total += c.getTotal();
        final float finalTotal = total;

        speak("Your total is " +
                String.format(Locale.US, "%.2f", finalTotal) + " ringgit");

        runOnUiThread(() ->
                scannerText.setText("TOTAL\nRM " +
                        String.format(Locale.US, "%.2f", finalTotal)));
    }

    private void readCart() {
        if (cart.isEmpty()) {
            speak("Your cart is empty.");
            return;
        }

        StringBuilder sb = new StringBuilder("You have ");
        for (CartItem c : cart) {
            sb.append(c.getQty()).append(" ")
                    .append(c.getName().replace("_", " "))
                    .append(", ");
        }
        speak(sb.toString());
    }

    // =========================================================
    // SPEECH
    // =========================================================

    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                speak("System ready. Please say or click scan to start scanning." + "Hold the item steady for 5 seconds for me to verify. I will tell you the item and price after verification." + "Reply with yes or no when prompted." + "Say read to listen to cart." + "Say remove to remove item from cart." + "Say or click total to hear total.");
            }
        });
    }

    private void setupSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override public void onReadyForSpeech(Bundle params) {
                statusListening();
            }

            @Override public void onBeginningOfSpeech() {
                statusProcessing();
            }

            @Override public void onEndOfSpeech() {
                statusProcessing();
            }

            @Override
            public void onResults(Bundle results) {
                statusCalibrating();

                ArrayList<String> list =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null || list.isEmpty()) return;

                String cmd = list.get(0).toLowerCase(Locale.US);

                if (cmd.contains("read")) {
                    readCart();
                    return;
                }

                if (cmd.contains("remove") && !awaitingRemoveItem
                        && !awaitingRemoveQuantity && !awaitingRemoveConfirm) {
                    awaitingRemoveItem = true;
                    speak("Which item do you want to remove?");
                    return;
                }

                if (awaitingRemoveItem) {
                    for (CartItem c : cart) {
                        if (cmd.contains(c.getName().replace("_", " "))) {
                            pendingItem = c.getName();
                            awaitingRemoveItem = false;
                            awaitingRemoveQuantity = true;
                            showQtyPanel();
                            speak("How many do you want to remove?");
                            return;
                        }
                    }
                    speak("I couldn't find that item.");
                    return;
                }

                if (awaitingRemoveQuantity) {
                    int qty = extractQuantity(cmd);
                    if (qty > 0) {
                        pendingRemoveQty = qty;
                        awaitingRemoveQuantity = false;
                        awaitingRemoveConfirm = true;
                        hideQtyPanel();
                        speak("Remove " + qty + " "
                                + pendingItem.replace("_", " ")
                                + ". Say yes to confirm.");
                    } else {
                        speak("Please say the number of items to remove.");
                    }
                    return;
                }

                if (awaitingQuantity) {
                    int qty = extractQuantity(cmd);
                    if (qty > 0) {
                        pendingQty = qty;
                        awaitingQuantity = false;
                        awaitingQtyConfirm = true;
                        hideQtyPanel();
                        speak("You said " + qty + ". Say yes to confirm.");
                    } else {
                        speak("Please say a number.");
                    }
                    return;
                }

                if (cmd.contains("scan")) btnScan.performClick();
                else if (cmd.contains("yes")) btnYes.performClick();
                else if (cmd.contains("no")) btnNo.performClick();
                else if (cmd.contains("total")) btnTotal.performClick();
            }

            @Override public void onError(int error) {
                startListening();
            }

            public void onRmsChanged(float rmsdB) {}
            public void onBufferReceived(byte[] buffer) {}
            public void onPartialResults(Bundle partialResults) {}
            public void onEvent(int eventType, Bundle params) {}
        });

        startListening();
    }

    // =========================================================
    // LISTENING HELPERS
    // =========================================================

    private void startListening() {
        handler.postDelayed(() -> {
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                speechRecognizer.startListening(intent);
            } catch (Exception e) {
                Log.e("Speech", "startListening failed", e);
            }
        }, 600);
    }

    private void stopListening() {
        try {
            if (speechRecognizer != null) speechRecognizer.stopListening();
        } catch (Exception ignored) {}
    }

    // =========================================================
    // NUMBER EXTRACTION
    // =========================================================

    private int extractQuantity(String text) {
        Matcher m = Pattern.compile("\\b(\\d{1,2})\\b").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));

        Map<String, Integer> map = new HashMap<>();
        map.put("one",1); map.put("two",2); map.put("three",3);
        map.put("four",4); map.put("five",5); map.put("six",6);
        map.put("seven",7); map.put("eight",8); map.put("nine",9);
        map.put("ten",10);

        for (String k : map.keySet())
            if (text.contains(k)) return map.get(k);

        return -1;
    }

    // =========================================================
    // UI HELPERS
    // =========================================================

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

        qtyPanel = findViewById(R.id.qtyPanel);
        qty1 = findViewById(R.id.qty1);
        qty2 = findViewById(R.id.qty2);
        qty3 = findViewById(R.id.qty3);
        qty4 = findViewById(R.id.qty4);
        qty5 = findViewById(R.id.qty5);
        qty6 = findViewById(R.id.qty6);
        qty7 = findViewById(R.id.qty7);
        qty8 = findViewById(R.id.qty8);
        qty9 = findViewById(R.id.qty9);
        qty10 = findViewById(R.id.qty10);
    }

    private void setupQuantityButtons() {
        View.OnClickListener listener = v -> {
            if (qtyPanel.getVisibility() != View.VISIBLE) return;

            int qty = Integer.parseInt(((MaterialButton) v).getText().toString());

            if (awaitingQuantity) {
                pendingQty = qty;
                awaitingQuantity = false;
                awaitingQtyConfirm = true;
                hideQtyPanel();
                speak("You selected " + qty + ". Say yes to confirm.");
            } else if (awaitingRemoveQuantity) {
                pendingRemoveQty = qty;
                awaitingRemoveQuantity = false;
                awaitingRemoveConfirm = true;
                hideQtyPanel();
                speak("Remove " + qty + " "
                        + pendingItem.replace("_", " ")
                        + ". Say yes to confirm.");
            }
        };

        qty1.setOnClickListener(listener);
        qty2.setOnClickListener(listener);
        qty3.setOnClickListener(listener);
        qty4.setOnClickListener(listener);
        qty5.setOnClickListener(listener);
        qty6.setOnClickListener(listener);
        qty7.setOnClickListener(listener);
        qty8.setOnClickListener(listener);
        qty9.setOnClickListener(listener);
        qty10.setOnClickListener(listener);
    }

    private void setupCart() {
        cartAdapter = new CartAdapter(cart);
        cartRecycler.setLayoutManager(new LinearLayoutManager(this));
        cartRecycler.setAdapter(cartAdapter);
        updateTotal();
    }

    private void updateTotal() {
        float sum = 0f;
        for (CartItem c : cart) sum += c.getTotal();
        totalText.setText("RM " + String.format(Locale.US, "%.2f", sum));
    }

    private void setStatus(String text, int dotDrawable) {
        statusText.setText(text);
        statusDot.setBackgroundResource(dotDrawable);
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, 1001);
        }
    }

    // =========================================================
    // TTS
    // =========================================================

    private void speak(String text) {
        if (tts == null) return;

        isSpeaking = true;
        statusProcessing();

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());

        handler.postDelayed(() -> {
            isSpeaking = false;
            statusListening();
            startListening();
        }, 4500);
    }

    private void showQtyPanel() {
        runOnUiThread(() -> qtyPanel.setVisibility(View.VISIBLE));
    }

    private void hideQtyPanel() {
        runOnUiThread(() -> qtyPanel.setVisibility(View.GONE));
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (tts != null) tts.shutdown();
        cameraExecutor.shutdown();
        super.onDestroy();
    }
}
