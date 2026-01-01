package com.example.smartshopping;

import android.content.Intent;
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

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Locale;

public class SplashActivity extends AppCompatActivity {

    private View statusDot;
    private TextView statusText;
    private MaterialButton btnStart;

    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;

    private boolean isListening = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        btnStart = findViewById(R.id.btnStart);

        btnStart.setOnClickListener(v -> startApp());

        setupSpeechRecognizer();
        setupTTS(); // 🔥 TTS FIRST, speak inside callback
    }

    // ================= TTS (FIXED) =================

    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                speakInstructions(); // ✅ SAFE NOW
            }
        });
    }

    private void speakInstructions() {
        setStatus("Calibrating...", R.drawable.status_dot_yellow);

        tts.speak(
                "Welcome to Smart Shopping Assistant. " +
                        "Say start or open to begin. " +
                        "You may also press the start button.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SPLASH_TTS"
        );

        // Start listening AFTER speech finishes
        handler.postDelayed(this::safeStartListening, 3500);
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
                    String cmd = list.get(0).toLowerCase(Locale.US);
                    if (cmd.contains("start") || cmd.contains("open")) {
                        startApp();
                        return;
                    }
                }
                safeStartListening();
            }

            @Override
            public void onError(int error) {
                isListening = false;
                safeStartListening();
            }

            // unused
            public void onRmsChanged(float rms) {}
            public void onBufferReceived(byte[] buffer) {}
            public void onEndOfSpeech() {}
            public void onPartialResults(Bundle partialResults) {}
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    // ================= SAFE LISTEN =================

    private void safeStartListening() {
        if (isListening || speechRecognizer == null) return;

        handler.postDelayed(() -> {
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

    // ================= NAVIGATION =================

    private void startApp() {
        cleanup();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void setStatus(String text, int dotDrawable) {
        statusText.setText(text);
        statusDot.setBackgroundResource(dotDrawable);
    }

    private void cleanup() {
        try {
            if (speechRecognizer != null) speechRecognizer.destroy();
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }
}
