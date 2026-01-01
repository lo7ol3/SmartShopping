package com.example.smartshopping;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class YoloDetector {

    private static final int INPUT_SIZE = 640;
    private static final int NUM_BOXES = 8400;

    private Interpreter interpreter;
    private List<String> labels;

    public YoloDetector(Context context, Interpreter interpreter) throws Exception {
        this.interpreter = interpreter;
        this.labels = loadLabels(context);
    }

    private List<String> loadLabels(Context context) throws Exception {
        List<String> list = new ArrayList<>();
        BufferedReader br =
                new BufferedReader(new InputStreamReader(context.getAssets().open("labels.txt")));
        String line;
        while ((line = br.readLine()) != null) {
            list.add(line);
        }
        br.close();
        return list;
    }

    public String detect(Bitmap bitmap) {

        Bitmap resized = resize(bitmap);
        ByteBuffer input = bitmapToBuffer(resized);

        float[][][] output = new float[1][labels.size() + 4][NUM_BOXES];
        interpreter.run(input, output);

        return parse(output);
    }

    private Bitmap resize(Bitmap bitmap) {
        Matrix m = new Matrix();
        m.postScale(
                INPUT_SIZE / (float) bitmap.getWidth(),
                INPUT_SIZE / (float) bitmap.getHeight()
        );
        return Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), m, true);
    }

    private ByteBuffer bitmapToBuffer(Bitmap bitmap) {

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255f);
            buffer.putFloat((pixel & 0xFF) / 255f);
        }

        return buffer;
    }

    private String parse(float[][][] output) {

        int bestClass = -1;
        float bestScore = 0f;

        for (int i = 0; i < NUM_BOXES; i++) {
            for (int c = 0; c < labels.size(); c++) {

                float score = output[0][c + 4][i];

                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
        }

        if (bestScore > 0.25f) {
            return labels.get(bestClass) + " (" +
                    String.format("%.2f", bestScore) + ")";
        }

        return "No object";
    }
}
