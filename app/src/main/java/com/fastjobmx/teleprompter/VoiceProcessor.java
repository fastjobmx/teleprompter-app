package com.fastjobmx.teleprompter;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mejora de voz en tiempo real, sin latencia y sin cambiar la duración:
 * filtro de graves (ruido de golpes/zumbido), reducción de ruido de fondo entre frases
 * (expansor con piso de ruido adaptativo), ecualizador de claridad, compresor y limitador.
 */
@OptIn(markerClass = UnstableApi.class)
public final class VoiceProcessor extends BaseAudioProcessor {

    /** strength: 0 = suave, 1 = fuerte. */
    private final float strength;

    private int channels, sampleRate;
    private Biquad[] hp, lowShelf, presence, air;
    private float env, gainSmooth = 1f, compEnv, peakEnv;
    private float floor = -1f, blockSum;
    private int blockCount, blockLen;
    private float aEnvA, aEnvR, aGainOpen, aGainClose, aCompA, aCompR, aPeakR, floorRise;

    public VoiceProcessor(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT && in.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(in);
        }
        return in;
    }

    @Override
    protected void onFlush() {
        AudioFormat f = inputAudioFormat;
        if (f == null || f.sampleRate <= 0) return;
        channels = f.channelCount;
        sampleRate = f.sampleRate;
        hp = new Biquad[channels];
        lowShelf = new Biquad[channels];
        presence = new Biquad[channels];
        air = new Biquad[channels];
        float hpFreq = 80f + 40f * strength;
        for (int c = 0; c < channels; c++) {
            hp[c] = Biquad.highPass(sampleRate, hpFreq, 0.707f);
            lowShelf[c] = Biquad.lowShelf(sampleRate, 220f, -2.5f);
            presence[c] = Biquad.peaking(sampleRate, 3200f, 1.0f, 3.5f);
            air[c] = Biquad.highShelf(sampleRate, 9500f, 2.0f);
        }
        aEnvA = coef(0.004f);
        aEnvR = coef(0.08f);
        aGainOpen = coef(0.003f);
        aGainClose = coef(0.09f);
        aCompA = coef(0.01f);
        aCompR = coef(0.18f);
        aPeakR = coef(0.06f);
        blockLen = Math.max(1, sampleRate / 50); // bloques de 20 ms
        floorRise = (float) Math.pow(10, 6.0 / 20.0 / 150.0); // +6 dB cada 3 s
        env = 0;
        gainSmooth = 1f;
        compEnv = 0;
        peakEnv = 0;
        floor = -1f;
        blockSum = 0;
        blockCount = 0;
    }

    @Override
    protected void onReset() {
        hp = null;
    }

    private float coef(float seconds) {
        return (float) (1.0 - Math.exp(-1.0 / (seconds * sampleRate)));
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        if (hp == null) onFlush();
        boolean isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
        int bps = isFloat ? 4 : 2;
        ByteBuffer out = replaceOutputBuffer(remaining);
        input.order(ByteOrder.nativeOrder());
        out.order(ByteOrder.nativeOrder());

        float minGain = (float) Math.pow(10, -(10 + 14 * strength) / 20.0);
        float ratio = 2.2f + 1.8f * strength;
        float compThr = 0.1f;             // -20 dBFS
        float compRatio = 3f;
        float makeup = 1.9f;              // +5.6 dB
        float[] x = new float[channels];

        while (input.remaining() >= bps * channels) {
            float m = 0;
            for (int c = 0; c < channels; c++) {
                float s = isFloat ? input.getFloat() : input.getShort() / 32768f;
                s = hp[c].process(s);
                x[c] = s;
                m = Math.max(m, Math.abs(s));
            }

            // Piso de ruido adaptativo (por bloques de 20 ms)
            blockSum += m * m;
            if (++blockCount >= blockLen) {
                float rms = (float) Math.sqrt(blockSum / blockCount);
                if (floor < 0) floor = Math.max(rms, 1e-5f);
                else if (rms < floor) floor = Math.max(rms, 1e-5f);
                else floor = Math.min(floor * floorRise, 0.05f);
                blockSum = 0;
                blockCount = 0;
            }

            // Expansor: baja el ruido cuando no hablas
            env += (m > env ? aEnvA : aEnvR) * (m - env);
            float thr = Math.max(floor, 1e-5f) * 3.6f;
            float g = 1f;
            if (env < thr) g = (float) Math.max(minGain, Math.pow(env / thr, ratio - 1f));
            gainSmooth += (g > gainSmooth ? aGainOpen : aGainClose) * (g - gainSmooth);

            // Ecualizador de claridad
            float lvl = 0;
            for (int c = 0; c < channels; c++) {
                float s = x[c] * gainSmooth;
                s = lowShelf[c].process(s);
                s = presence[c].process(s);
                s = air[c].process(s);
                x[c] = s;
                lvl = Math.max(lvl, Math.abs(s));
            }

            // Compresor
            compEnv += (lvl > compEnv ? aCompA : aCompR) * (lvl - compEnv);
            float cg = 1f;
            if (compEnv > compThr) {
                double over = 20 * Math.log10(compEnv / compThr);
                cg = (float) Math.pow(10, -(over - over / compRatio) / 20.0);
            }
            float tot = cg * makeup;

            // Limitador (sin distorsión)
            float pk = 0;
            for (int c = 0; c < channels; c++) pk = Math.max(pk, Math.abs(x[c] * tot));
            peakEnv = pk > peakEnv ? pk : peakEnv + aPeakR * (pk - peakEnv);
            float lim = peakEnv > 0.94f ? 0.94f / peakEnv : 1f;

            for (int c = 0; c < channels; c++) {
                float s = x[c] * tot * lim;
                if (s > 0.99f) s = 0.99f;
                else if (s < -0.99f) s = -0.99f;
                if (isFloat) out.putFloat(s);
                else out.putShort((short) Math.round(s * 32767f));
            }
        }
        out.flip();
    }

    /** Filtro biquad (fórmulas RBJ). */
    static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float z1, z2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        static Biquad highPass(int sr, float f, float q) {
            double w = 2 * Math.PI * f / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al);
        }

        static Biquad peaking(int sr, float f, float q, float gainDb) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad(1 + al * A, -2 * cs, 1 - al * A, 1 + al / A, -2 * cs, 1 - al / A);
        }

        static Biquad lowShelf(int sr, float f, float gainDb) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / sr, cs = Math.cos(w), sn = Math.sin(w);
            double al = sn / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
            return new Biquad(A * ((A + 1) - (A - 1) * cs + sa), 2 * A * ((A - 1) - (A + 1) * cs), A * ((A + 1) - (A - 1) * cs - sa),
                    (A + 1) + (A - 1) * cs + sa, -2 * ((A - 1) + (A + 1) * cs), (A + 1) + (A - 1) * cs - sa);
        }

        static Biquad highShelf(int sr, float f, float gainDb) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / sr, cs = Math.cos(w), sn = Math.sin(w);
            double al = sn / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
            return new Biquad(A * ((A + 1) + (A - 1) * cs + sa), -2 * A * ((A - 1) + (A + 1) * cs), A * ((A + 1) + (A - 1) * cs - sa),
                    (A + 1) - (A - 1) * cs + sa, 2 * ((A - 1) - (A + 1) * cs), (A + 1) - (A - 1) * cs - sa);
        }
    }
}
