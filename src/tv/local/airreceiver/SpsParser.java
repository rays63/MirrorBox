package tv.local.airreceiver;

/** Reads the picture size from an H.264 SPS NAL unit (header byte included, emulation bytes present). */
final class SpsParser {
    private final byte[] b;
    private int bit;

    private SpsParser(byte[] rbsp) { b = rbsp; }

    /** Returns {width, height} after cropping, or null if the SPS cannot be read. */
    static int[] size(byte[] nal) {
        try {
            SpsParser p = new SpsParser(unescape(nal));
            p.bits(8);                          // NAL header
            int profile = p.bits(8);
            p.bits(16);                         // constraint flags + level
            p.ue();                             // seq_parameter_set_id
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                    || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                    || profile == 139 || profile == 134 || profile == 135) {
                chroma = p.ue();
                if (chroma == 3) p.bits(1);
                p.ue(); p.ue();                 // bit depths
                p.bits(1);                      // qpprime_y_zero_transform_bypass
                if (p.bits(1) == 1) {           // seq_scaling_matrix_present
                    for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                        if (p.bits(1) == 1) p.skipScalingList(i < 6 ? 16 : 64);
                    }
                }
            }
            p.ue();                             // log2_max_frame_num
            int pocType = p.ue();
            if (pocType == 0) {
                p.ue();
            } else if (pocType == 1) {
                p.bits(1); p.se(); p.se();
                int n = p.ue();
                for (int i = 0; i < n; i++) p.se();
            }
            p.ue();                             // max_num_ref_frames
            p.bits(1);
            int wMbs = p.ue() + 1;
            int hMapUnits = p.ue() + 1;
            int frameMbsOnly = p.bits(1);
            if (frameMbsOnly == 0) p.bits(1);
            p.bits(1);                          // direct_8x8_inference
            int w = wMbs * 16;
            int h = (2 - frameMbsOnly) * hMapUnits * 16;
            if (p.bits(1) == 1) {               // frame_cropping
                int l = p.ue(), r = p.ue(), t = p.ue(), btm = p.ue();
                int cx = chroma == 0 || chroma == 3 ? 1 : 2;
                int cy = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
                w -= (l + r) * cx;
                h -= (t + btm) * cy;
            }
            return w > 0 && h > 0 ? new int[] {w, h} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] unescape(byte[] in) {
        byte[] out = new byte[in.length];
        int n = 0, zeros = 0;
        for (byte v : in) {
            if (zeros >= 2 && v == 3) { zeros = 0; continue; }
            out[n++] = v;
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private int bits(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            int byteIdx = bit >> 3;
            if (byteIdx >= b.length) throw new IllegalStateException("SPS too short");
            v = (v << 1) | ((b[byteIdx] >> (7 - (bit & 7))) & 1);
            bit++;
        }
        return v;
    }

    private int ue() {
        int zeros = 0;
        while (bits(1) == 0) {
            if (++zeros > 31) throw new IllegalStateException("bad exp-golomb");
        }
        return zeros == 0 ? 0 : ((1 << zeros) - 1 + bits(zeros));
    }

    private int se() {
        int v = ue();
        return (v & 1) == 1 ? (v + 1) / 2 : -(v / 2);
    }

    private void skipScalingList(int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }
}
