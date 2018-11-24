/******************************************************************************
 *  Compute the FFT and inverse FFT of a length n complex sequence
 *  using the radix 2 Cooley-Tukey algorithm.

 *  Bare bones implementation that runs in O(n log n) time. Our goal
 *  is to optimize the clarity of the code, rather than performance.
 *
 *  Limitations
 *  -----------
 *   -  assumes n is a power of 2
 *
 *   -  not the most memory efficient algorithm (because it uses
 *      an object type for representing complex numbers and because
 *      it re-allocates memory for the subarray, instead of doing
 *      in-place or reusing a single temporary array)
 *  
 *  For an in-place radix 2 Cooley-Tukey FFT, see
 *  https://introcs.cs.princeton.edu/java/97data/InplaceFFT.java.html
 *
 ******************************************************************************/

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.util.Objects;

public class FFT {

    // compute the FFT of x[], assuming its length is a power of 2
    public static Complex[] fft(Complex[] x) {
        int n = x.length;

        // base case
        if (n == 1) return new Complex[] { x[0] };

        // radix 2 Cooley-Tukey FFT
        if (n % 2 != 0) {
            throw new IllegalArgumentException("n is not a power of 2");
        }

        // fft of even terms
        Complex[] even = new Complex[n/2];
        for (int k = 0; k < n/2; k++) {
            even[k] = x[2*k];
        }
        Complex[] q = fft(even);

        // fft of odd terms
        Complex[] odd  = even;  // reuse the array
        for (int k = 0; k < n/2; k++) {
            odd[k] = x[2*k + 1];
        }
        Complex[] r = fft(odd);

        // combine
        Complex[] y = new Complex[n];
        for (int k = 0; k < n/2; k++) {
            double kth = -2 * k * Math.PI / n;
            Complex wk = new Complex(Math.cos(kth), Math.sin(kth));
            y[k]       = q[k].plus(wk.times(r[k]));
            y[k + n/2] = q[k].minus(wk.times(r[k]));
        }
        return y;
    }

    public static double[] audio_byte16_fft(byte[] buffer, double freq)
    {
        int numpoints = (int)buffer.length;
        int n = numpoints/2;

        Complex[] samples = new Complex[numpoints/2];
        // Now we need to do an fft on this
        for (int i = 0; i < numpoints/2; ++i)
        {
            double temp = (double)((buffer[2*i] & 0xFF) | (buffer[2*i+1] << 8)) / 32768.0F;
            samples[i] = new Complex(temp, 0);
        }
        
        Complex[] y = fft(samples);

        double scale = 1.0 / (freq * n);
        int n_ent = n / 2;
        double[] res = new double[n_ent + 1];

        //double freq_max = freq/2; //(n / 2.0 + 1.0) / (n / freq);
        //double freq_step = freq_max / n_ent;
        //double cur_freq = 0.0;
        for (int i = 0; i < (n_ent+1); i++)
        {
            res[i] = (y[i].conjugate().times(y[i])).re() * scale;
        }
        return res;
    }
}
                    
