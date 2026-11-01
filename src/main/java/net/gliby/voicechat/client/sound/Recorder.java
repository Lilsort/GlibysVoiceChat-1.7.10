package net.gliby.voicechat.client.sound;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import net.gliby.voicechat.VoiceChat;
import net.gliby.voicechat.client.VoiceChatClient;
import net.gliby.voicechat.common.MathUtility;

import org.xiph.speex.SpeexEncoder;

public class Recorder implements Runnable {

	private boolean recording;
	private Thread thread;
	private final VoiceChatClient voiceChat;

	byte[] buffer;

	public Recorder(VoiceChatClient voiceChat) {
		this.voiceChat = voiceChat;
	}

	private byte[] boostVolume(byte[] data) {
		final int USHORT_MASK = (1 << 16) - 1;
		final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		final ByteBuffer newBuf = ByteBuffer.allocate(data.length).order(ByteOrder.LITTLE_ENDIAN);
		int sample;
		while (buf.hasRemaining()) {
			sample = buf.getShort() & USHORT_MASK;
			sample *= (int) (voiceChat.getSettings().getInputBoost() * 5) + 1;
			newBuf.putShort((short) (sample & USHORT_MASK));
		}
		return newBuf.array();
	}

	@Override
	public void run() {
		final AudioFormat format = ClientStreamManager.getUniversalAudioFormat();
		final TargetDataLine recordingLine = voiceChat.getSettings().getInputDevice().getLine();
		if (recordingLine == null) {
			VoiceChat.getLogger().fatal("Attempted to record input device, but failed! Java Sound System hasn't found any microphones, check your input devices and restart Minecraft.");
			return;
		}

		if (!startLine(recordingLine, format)) {
			voiceChat.setRecorderActive(false);
			this.stop();
			return;
		}
		final SpeexEncoder encoder = new SpeexEncoder();
		encoder.init(0, (int) MathUtility.clamp(MathUtility.clamp((int) (voiceChat.getSettings().getEncodingQuality() * 10.0f), 1, 9), voiceChat.getSettings().getMinimumQuality(), voiceChat.getSettings().getMaximumQuality()), (int) format.getSampleRate(), format.getChannels());
		final int blockSize = encoder.getFrameSize() * format.getChannels() * (16 / 8);
		final byte[] normBuffer = new byte[blockSize * 2];
		recordingLine.start();
		VoiceChat.getLogger().info("Started microphone recording on line: " + recordingLine + " device: " + voiceChat.getSettings().getInputDevice().getName());
		buffer = new byte[0];
		byte pieceSize = 0;
		int packetsSent = 0;
		while (recording && voiceChat.getClientNetwork().isConnected()) {
			final int read = recordingLine.read(normBuffer, 0, blockSize);
			if (read == -1) break;
			final byte[] boostedBuffer = boostVolume(normBuffer);
			if (!encoder.processData(boostedBuffer, 0, blockSize)) break;
			final int encoded = encoder.getProcessedData(boostedBuffer, 0);
			final byte[] encoded_data = new byte[encoded];
			System.arraycopy(boostedBuffer, 0, encoded_data, 0, encoded);
			pieceSize = (byte) encoded;
			write(encoded_data);
			if (buffer.length >= voiceChat.getSettings().getBufferSize()) {
				voiceChat.getClientNetwork().sendSamples(pieceSize, buffer, false);
				packetsSent++;
				if (packetsSent == 1) {
					VoiceChat.getLogger().info("First voice packet sent to server (" + buffer.length + " bytes)");
				}
				buffer = new byte[0];
			}
		}
		if (buffer.length > 0) {
			voiceChat.getClientNetwork().sendSamples(pieceSize, buffer, false);
		}
		voiceChat.getClientNetwork().sendSamples((byte) 0, null, true);
		try {
			recordingLine.stop();
			recordingLine.close();
		} catch (Exception e) {
		}
	}

	public void set(boolean toggle) {
		if (toggle) start();
		else stop();
	}

	public synchronized void start() {
		if (recording) return;
		if (thread != null && thread.isAlive()) {
			try {
				thread.join(200);
			} catch (InterruptedException ignored) {}
		}
		recording = true;
		thread = new Thread(this, "Input Device Recorder");
		thread.start();
	}

	private boolean startLine(TargetDataLine recordingLine, AudioFormat format) {
		try {
			if (!recordingLine.isOpen()) {
				recordingLine.open(format);
			}
		} catch (final LineUnavailableException e) {
			e.printStackTrace();
			VoiceChat.getLogger().fatal("Failed to open recording line! " + recordingLine.getFormat());
			return false;
		}
		return true;
	}

	public synchronized void stop() {
		recording = false;
		thread = null;
	}

	private void write(byte[] write) {
		final byte[] result = new byte[buffer.length + write.length];
		System.arraycopy(buffer, 0, result, 0, buffer.length);
		System.arraycopy(write, 0, result, buffer.length, write.length);
		buffer = result;
	}
}
