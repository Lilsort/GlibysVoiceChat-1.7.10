package net.gliby.voicechat.client.sound;

import javax.sound.sampled.AudioFormat;

class JitterBuffer {

	private byte[] buffer;
	private final AudioFormat format;
	private int threshold;

	JitterBuffer(AudioFormat format, int jitter) {
		this.format = format;
		updateJitter(jitter);
	}

	void clearBuffer(int jitterSize) {
		buffer = new byte[0];
		updateJitter(jitterSize);
	}

	byte[] get() {
		return buffer;
	}

	private int getSizeInBytes(AudioFormat fmt, int size) {
		if (fmt == null || size <= 0) return 0;
		final int bytesPerSec = (int) (fmt.getSampleRate() * (fmt.getSampleSizeInBits() / 8) * fmt.getChannels());
		return (bytesPerSec * size) / 1000;
	}

	public boolean isReady() {
		return buffer != null && buffer.length > 0;
	}

	void push(byte[] data) {
		write(data);
	}

	void updateJitter(int size) {
		this.threshold = getSizeInBytes(format, size);
		if (buffer == null) buffer = new byte[0];
	}

	private void write(byte[] write) {
		final byte[] result = new byte[buffer.length + write.length];
		System.arraycopy(buffer, 0, result, 0, buffer.length);
		System.arraycopy(write, 0, result, buffer.length, write.length);
		buffer = result;
	}
}