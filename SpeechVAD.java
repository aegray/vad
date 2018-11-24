import java.util.ArrayDeque;

public class SpeechVAD {

    private int calib_frames_got_       = 0;
    private int calib_frames_need_      = 0;

    private int chunk_size_             = 0;
    private double seconds_per_buf_             = 0.0;
    private int buffer_count_pause_             = 0;
    private int buffer_count_phrase_            = 0;
    private int buffer_count_non_speaking_      = 0;
    private int buffer_count_max_               = 0;

    private int fft_rel_freq_start_ind_         = 0;
    private int fft_rel_freq_stop_ind_          = 0;

    private double freq_                        = 0.0;

    private double power_diff_average_          = -1.0;

    private int n_phrase_cont_seen_             = 0;
    private int n_phrase_                       = 0;
    private int n_non_speech_                   = 0;

   
    private ArrayDeque<byte[]> utterances_ = new ArrayDeque<byte[]>();

    double[] baseline_powers_;
    double baseline_rms_;

    class FrameInfo
    {

        byte[] data;
        double power_diff;
        double power_diff_raw;

        FrameInfo(
            byte[] idata,
            double ipower_diff,
            double ipower_diff_raw
        )
        {
            data = idata;
            power_diff = ipower_diff;
            power_diff_raw = ipower_diff_raw;
        }
        
        FrameInfo(
            byte[] idata
        )
        {
            data = idata;
            power_diff = 0.0;
            power_diff_raw = 0.0;
        }

    }

    private ArrayDeque<FrameInfo> frames_ = new ArrayDeque<FrameInfo>();

    SpeechVAD(
                int sample_rate, 
                int chunk_size, 
                double pause_dur_thresh, 
                double phrase_dur_thresh, 
                double non_speaking_dur,
                double max_clip_dur,
                double calib_time
            )
    {
        seconds_per_buf_ = (double)(chunk_size) / (double)(sample_rate);

        buffer_count_pause_             = (int)(Math.ceil(pause_dur_thresh / seconds_per_buf_));
        buffer_count_phrase_            = (int)(Math.ceil(phrase_dur_thresh / seconds_per_buf_));
        buffer_count_non_speaking_      = (int)(Math.ceil(non_speaking_dur / seconds_per_buf_));
        buffer_count_max_               = (int)(Math.ceil(max_clip_dur / seconds_per_buf_));

        // @TODO: see if we really need calibration or can just rely on the power_diff_avg to normalize
        //calib_frames_need_              = 0;
        calib_frames_need_              = (int)(Math.ceil(calib_time / seconds_per_buf_));

        chunk_size_ = chunk_size;

        // upfront calculate freqs  and low/high we will need
        freq_ = (double)sample_rate;
        double freq_max = freq_/2.0;
        int n_ent = chunk_size/2;
        double freq_step = freq_max / n_ent;
          
        fft_rel_freq_start_ind_ = (int)(Math.ceil(300.0 / freq_step));
        fft_rel_freq_stop_ind_  = (int)(Math.floor(1200.0 / freq_step));

        baseline_powers_ = new double[fft_rel_freq_stop_ind_ - fft_rel_freq_start_ind_ + 1];
        baseline_rms_ = 0.0;
    }


    
    void reset()
    {
        frames_.clear();

        n_phrase_cont_seen_             = 0;
        n_phrase_                       = 0;
        n_non_speech_                   = 0;
    }


    byte[] pop_utterance()
    {
        assert(utterances_.size() > 0);
        return utterances_.removeFirst();
    }

    boolean next(byte[] data)
    {

        byte[] newdata = new byte[data.length]; 
        assert(data.length == chunk_size_*2);
        System.arraycopy(data, 0, newdata, 0, chunk_size_*2);
        
        
        // @TODO: maybe keep a prealloc buffer here instaed of alloc on the fly
        double[] fft = FFT.audio_byte16_fft(data, freq_);


        if (calib_frames_got_ < calib_frames_need_)
        {
            // calibration frame -> process it and update baselines
            for (int i = 0; i < baseline_powers_.length; ++i)
            {
                baseline_powers_[i] += fft[fft_rel_freq_start_ind_ + i] / ((double)(calib_frames_need_)); 
            }
            calib_frames_got_ += 1;
        }
        else
        {
            double power_diff_v_baseline = 0.0;
            for (int i = 0; i < baseline_powers_.length; ++i) 
            {
                power_diff_v_baseline += Math.max(0.0, fft[fft_rel_freq_start_ind_ + i] - baseline_powers_[i]);
            }

            double sqrt_pdiff = Math.sqrt(power_diff_v_baseline);
            if (power_diff_average_ < 0.0)
            {
                power_diff_average_ = sqrt_pdiff;
            }
            else
            {
                power_diff_average_ = 0.99 * power_diff_average_ + 0.01 * sqrt_pdiff;
            }

            double cur_pow_diff = Math.max(0.0, power_diff_v_baseline - power_diff_average_ * power_diff_average_);

            if (cur_pow_diff > 0.0)
            {
                n_phrase_ += 1;
                n_non_speech_ = 0;
                n_phrase_cont_seen_ += 1;
            }
            else
            {
                n_non_speech_ += 1;
                n_phrase_cont_seen_ = 0;
            }

            // store the raw vs baseline so we can renormalize later
            frames_.addLast(new FrameInfo(newdata, power_diff_v_baseline, power_diff_v_baseline)); 
            while (frames_.size() > buffer_count_max_)
            {
                frames_.removeFirst();
            }

            if ((n_phrase_ > buffer_count_phrase_) && (n_non_speech_ > buffer_count_pause_))
            {
                FrameInfo[] frames = frames_.toArray(new FrameInfo[0]);
                
                double cmp_powerdiff = power_diff_average_ * power_diff_average_;

                int n_speak_window = 0;
                int n_non_speak_front = 0;
                for (int i = 0; i < frames.length; ++i)
                {
                    double l_cur_pow_diff = Math.max(0.0, frames[i].power_diff_raw - cmp_powerdiff);
                    frames[i].power_diff = l_cur_pow_diff;
                    if (l_cur_pow_diff > 0.0)
                    {
                        n_speak_window += 1;
                    }
                    if (i >= 5)
                    {
                        if (frames[i-5].power_diff > 0.0)
                        {
                            n_speak_window -= 1;
                        }

                        if (n_speak_window >= 3)
                        {
                            break; 
                        }
                    }
                    n_non_speak_front += 1;
                }


                // @TODO: make this a function because this code is horribly duped
                n_speak_window = 0;
                int n_non_speak_back = 0;
                for (int i = 0; i < frames.length; ++i)
                {
                    int index = frames.length - i - 1;
                    double l_cur_pow_diff = Math.max(0.0, frames[index].power_diff_raw - cmp_powerdiff);
                    frames[index].power_diff = l_cur_pow_diff;
                    if (l_cur_pow_diff > 0.0)
                    {
                        n_speak_window += 1;
                    }
                    if (i >= 5)
                    {

                        if (frames[index + 5].power_diff > 0.0)
                        {
                            n_speak_window -= 1;
                        }

                        if (n_speak_window >= 3)
                        {
                            break; 
                        }
                    }
                    n_non_speak_back += 1;
                }
    
                   
                if (frames.length <= n_non_speak_front + n_non_speak_back)
                {
                    // there are two things that could make this happen:
                    // a) we are renormalizing based on an updated power diff,
                    // b) i sliding window filter the results and require that 3/5 frames are "speech" to keep that value, so 
                    //   it's a stricter filtering
                    //
                    // so after those, it's possible we end up with no speech frames - return and continue processing in this case
                    return false;
                }

                int clip_front = Math.max(0, n_non_speak_front - buffer_count_non_speaking_);
                int clip_back = Math.max(0, n_non_speak_back - buffer_count_non_speaking_);
                int frame_size = frames[0].data.length;
                byte[] res = new byte[frame_size * (frames.length - clip_front - clip_back)];

                for (int i = clip_front; i < frames.length - clip_back; ++i)
                {
                    System.arraycopy(frames[i].data, 0, res, (i-clip_front)*frame_size, frame_size);
                }

                utterances_.addLast(res);
                reset();
                return true;
            }
        }
        return false;
    }
}

