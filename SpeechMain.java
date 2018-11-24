
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import java.io.*;

public class SpeechMain {
    public static void main(String[] args) { 
        AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);

        double pause_thresh = 0.4;
        int chunk_size = 1024;
        byte[] buffer = new byte[chunk_size*2];

        int max_frames = 40;
        byte[] fullbuff = new byte[chunk_size*2*max_frames];

        int got_frames = 0;
        int onind = 0;

        SpeechVAD vad = new SpeechVAD(16000, chunk_size, 0.5, 0.1, 0.5, 4.0, 1.0);
        try {
            TargetDataLine microphone;
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info))
            {
                System.out.println("Failed");
                return;
            }

            microphone = (TargetDataLine)AudioSystem.getLine(info);
            microphone.open(format, chunk_size);
            microphone.start();

            int cnt = 0;
            while ((cnt = microphone.read(buffer, 0, buffer.length)) > 0)
            {
                boolean res = vad.next(buffer);

                if (res)
                {
                    System.out.println("Finished speech!");
                    WavUtils.write_wav("out.wav", vad.pop_utterance());
                    return;
                }
            }

            System.out.println("Stream ended unexpectedly...");
        } catch (Exception e)
        {
            System.out.println("failed: " + e.toString());
        }
    }
}
