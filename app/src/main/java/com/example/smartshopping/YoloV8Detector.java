package com.example.smartshopping;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;

import org.tensorflow.lite.Interpreter;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

public class YoloV8Detector {

    private static final int INPUT_SIZE = 640;
    private static final int NUM_BOXES = 8400;
    private static final int NUM_CLASSES = 12;
    private static final float CONF_THRESH = 0.4f;

    private final Interpreter interpreter;
    private final List<String> labels;

    public static class Detection {
        public RectF box;
        public String label;
        public float score;
    }

    public YoloV8Detector(Context context) throws Exception {
        interpreter = new Interpreter(loadModel(context));
        labels = loadLabels(context);

        if (labels.size() != NUM_CLASSES) {
            throw new RuntimeException(
                    "Label count (" + labels.size() +
                            ") does not match NUM_CLASSES (" + NUM_CLASSES + ")");
        }
    }

    private MappedByteBuffer loadModel(Context context) throws Exception {
        AssetFileDescriptor afd =
                context.getAssets().openFd("best_model_v2_float16.tflite");

        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel channel = fis.getChannel();

        return channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.getStartOffset(),
                afd.getDeclaredLength()
        );
    }

    private List<String> loadLabels(Context context) throws Exception {
        List<String> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(context.getAssets().open("labels.txt")));
        String line;
        while ((line = br.readLine()) != null) list.add(line);
        br.close();
        return list;
    }

    public List<Detection> detect(Bitmap bitmap) {

        Bitmap resized =
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        ByteBuffer input = ByteBuffer.allocateDirect(
                1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        input.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(
                pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int p : pixels) {
            input.putFloat(((p >> 16) & 0xFF) / 255f);
            input.putFloat(((p >> 8) & 0xFF) / 255f);
            input.putFloat((p & 0xFF) / 255f);
        }

        float[][][] output = new float[1][4 + NUM_CLASSES][NUM_BOXES];
        interpreter.run(input, output);

        return parse(output, bitmap.getWidth(), bitmap.getHeight());
    }

    private List<Detection> parse(float[][][] out, int imgW, int imgH) {

        List<Detection> results = new ArrayList<>();

        for (int i = 0; i < NUM_BOXES; i++) {

            float cx = out[0][0][i];
            float cy = out[0][1][i];
            float w  = out[0][2][i];
            float h  = out[0][3][i];

            int bestClass = -1;
            float bestScore = 0f;

            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = out[0][4 + c][i];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }

            if (bestScore < CONF_THRESH) continue;

            float left   = (cx - w / 2f) * imgW;
            float top    = (cy - h / 2f) * imgH;
            float right  = (cx + w / 2f) * imgW;
            float bottom = (cy + h / 2f) * imgH;

            Detection d = new Detection();
            d.box = new RectF(left, top, right, bottom);
            d.label = labels.get(bestClass);
            d.score = bestScore;

            results.add(d);
        }

        return results;
    }
}
