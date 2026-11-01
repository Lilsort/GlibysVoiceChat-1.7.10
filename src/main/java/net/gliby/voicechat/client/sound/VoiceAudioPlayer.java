package net.gliby.voicechat.client.sound;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import org.lwjgl.util.vector.Vector3f;

import net.gliby.voicechat.VoiceChat;
import net.gliby.voicechat.client.VoiceChatClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.entity.Entity;

/**
 * Modern JavaSound-based audio player for voice chat, replacing the legacy
 * PaulsCode/OpenAL rawDataStream pipeline.
 *
 * Features:
 * - Direct JavaSound SourceDataLine playback (16000 Hz, 16-bit Stereo)
 * - Multi-speaker software audio mixing
 * - High-fidelity 3D distance attenuation and constant-power stereo panning
 * - Real-time volume scaling respecting Minecraft Master volume and mod settings
 * - Jitter and lag protection (auto-drops delayed chunks > 150ms)
 * - Immune to OpenAL channel limits, Hodgepodge, and SoundPhysics occlusion bugs
 */
public class VoiceAudioPlayer {

	private static class StreamAudioQueue {
		final int streamId;
		final ConcurrentLinkedQueue<short[]> leftChunks = new ConcurrentLinkedQueue<short[]>();
		final ConcurrentLinkedQueue<short[]> rightChunks = new ConcurrentLinkedQueue<short[]>();
		volatile int queuedSamples = 0;
		volatile long lastReceivedTime = System.currentTimeMillis();
		long lastLogTime = 0;

		StreamAudioQueue(int streamId) {
			this.streamId = streamId;
		}

		void push(short[] left, short[] right) {
			// Lag protection: drop oldest chunks if queued audio exceeds 150ms (2400 samples at 16kHz)
			while (queuedSamples > 2400) {
				short[] droppedL = leftChunks.poll();
				rightChunks.poll();
				if (droppedL != null) {
					queuedSamples -= droppedL.length;
				} else {
					break;
				}
			}
			leftChunks.offer(left);
			rightChunks.offer(right);
			queuedSamples += left.length;
			lastReceivedTime = System.currentTimeMillis();
		}

		void clear() {
			leftChunks.clear();
			rightChunks.clear();
			queuedSamples = 0;
		}
	}

	private static final int FRAME_SAMPLES = 160; // 10ms at 16kHz
	private static final int BUFFER_SIZE_BYTES = 6400; // 100ms hardware buffer

	private final Minecraft mc;
	private final VoiceChatClient voiceChat;
	private final AudioFormat audioFormat;

	private final ConcurrentHashMap<Integer, StreamAudioQueue> activeStreams = new ConcurrentHashMap<Integer, StreamAudioQueue>();
	private final Object lock = new Object();

	private SourceDataLine line;
	private Thread mixerThread;
	private volatile boolean running;

	public VoiceAudioPlayer(Minecraft mc, VoiceChatClient voiceChat) {
		this.mc = mc;
		this.voiceChat = voiceChat;
		// 16kHz, 16-bit, stereo (2 channels), signed, little-endian
		this.audioFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000.0F, 16, 2, 4, 16000.0F, false);
	}

	public synchronized void start() {
		if (running) return;
		running = true;
		mixerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				mixLoop();
			}
		}, "VoiceAudioPlayer-Mixer");
		mixerThread.setDaemon(true);
		mixerThread.setPriority(Thread.MAX_PRIORITY);
		mixerThread.start();
		VoiceChat.getLogger().info("VoiceAudioPlayer started successfully.");
	}

	private synchronized boolean ensureLineOpen() {
		if (line != null && line.isOpen()) {
			if (!line.isActive()) {
				line.start();
			}
			return true;
		}
		try {
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
			if (!AudioSystem.isLineSupported(info)) {
				VoiceChat.getLogger().error("VoiceAudioPlayer: AudioFormat not supported by default audio device!");
				return false;
			}
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(audioFormat, BUFFER_SIZE_BYTES);
			line.start();
			VoiceChat.getLogger().info("VoiceAudioPlayer: SourceDataLine opened (buffer=" + line.getBufferSize() + " bytes).");
			return true;
		} catch (Throwable t) {
			VoiceChat.getLogger().error("VoiceAudioPlayer: Failed to open SourceDataLine: " + t.getMessage());
			line = null;
			return false;
		}
	}

	private synchronized void closeLine() {
		if (line != null) {
			try {
				line.stop();
				line.flush();
				line.close();
			} catch (Throwable ignored) {
			}
			line = null;
		}
	}

	private void mixLoop() {
		final byte[] outBytes = new byte[FRAME_SAMPLES * 4];
		final int[] mixL = new int[FRAME_SAMPLES];
		final int[] mixR = new int[FRAME_SAMPLES];

		while (running) {
			boolean hasData = false;
			for (StreamAudioQueue q : activeStreams.values()) {
				if (!q.leftChunks.isEmpty()) {
					hasData = true;
					break;
				}
			}

			if (!hasData) {
				try {
					synchronized (lock) {
						lock.wait(20);
					}
				} catch (InterruptedException e) {
					break;
				}
				continue;
			}

			if (!ensureLineOpen()) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException ignored) {
					break;
				}
				continue;
			}

			Arrays.fill(mixL, 0);
			Arrays.fill(mixR, 0);

			long now = System.currentTimeMillis();
			for (Map.Entry<Integer, StreamAudioQueue> entry : activeStreams.entrySet()) {
				StreamAudioQueue q = entry.getValue();
				short[] lChunk = q.leftChunks.poll();
				short[] rChunk = q.rightChunks.poll();

				if (lChunk != null && rChunk != null) {
					q.queuedSamples -= lChunk.length;
					int len = Math.min(FRAME_SAMPLES, lChunk.length);
					for (int i = 0; i < len; i++) {
						mixL[i] += lChunk[i];
						mixR[i] += rChunk[i];
					}
				} else if (now - q.lastReceivedTime > 1000) {
					activeStreams.remove(entry.getKey());
				}
			}

			for (int i = 0; i < FRAME_SAMPLES; i++) {
				int l = mixL[i];
				if (l > 32767) l = 32767;
				else if (l < -32768) l = -32768;

				int r = mixR[i];
				if (r > 32767) r = 32767;
				else if (r < -32768) r = -32768;

				int byteIdx = i * 4;
				outBytes[byteIdx] = (byte) (l & 0xFF);
				outBytes[byteIdx + 1] = (byte) ((l >> 8) & 0xFF);
				outBytes[byteIdx + 2] = (byte) (r & 0xFF);
				outBytes[byteIdx + 3] = (byte) ((r >> 8) & 0xFF);
			}

			try {
				line.write(outBytes, 0, outBytes.length);
			} catch (Throwable t) {
				VoiceChat.getLogger().warn("VoiceAudioPlayer: Write error, resetting audio line: " + t.getMessage());
				closeLine();
			}
		}

		closeLine();
	}

	public void play(ClientStream stream, byte[] monoPcm) {
		if (monoPcm == null || monoPcm.length < 2) return;

		Entity listener = mc.renderViewEntity != null ? mc.renderViewEntity : mc.thePlayer;
		if (listener == null) return;

		float leftGain = 1.0f;
		float rightGain = 1.0f;
		float distanceFactor = 1.0f;
		double dist = 0.0;
		float pan = 0.0f;

		if (stream.direct) {
			Vector3f speakerPos = stream.player != null ? stream.player.position() : null;
			if (speakerPos == null) {
				speakerPos = new Vector3f((float) listener.posX, (float) listener.posY, (float) listener.posZ);
			}

			double lx = listener.posX;
			double ly = listener.posY + listener.getEyeHeight();
			double lz = listener.posZ;

			double sx = speakerPos.x;
			double sy = speakerPos.y + 1.2; // speaker mouth height
			double sz = speakerPos.z;

			double dx = sx - lx;
			double dy = sy - ly;
			double dz = sz - lz;

			dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (Double.isNaN(dist) || Double.isInfinite(dist)) return;

			float maxDist = voiceChat.getSettings().getSoundDistance();
			if (maxDist <= 0.0f) maxDist = 64.0f;

			if (dist >= maxDist) {
				return; // out of audible range
			}

			if (dist <= 1.5) {
				distanceFactor = 1.0f;
			} else {
				distanceFactor = 1.0f - (float) ((dist - 1.5) / (maxDist - 1.5));
				if (distanceFactor < 0.0f) distanceFactor = 0.0f;
				else if (distanceFactor > 1.0f) distanceFactor = 1.0f;
			}

			double horizDist = Math.sqrt(dx * dx + dz * dz);
			if (horizDist > 0.01) {
				double nx = dx / horizDist;
				double nz = dz / horizDist;

				double yawRad = Math.toRadians(listener.rotationYaw);
				double rx = -Math.cos(yawRad);
				double rz = -Math.sin(yawRad);

				double dot = nx * rx + nz * rz;
				if (dot > 1.0) dot = 1.0;
				else if (dot < -1.0) dot = -1.0;

				pan = (float) (dot * 0.85); // 85% maximum panning
			}

			leftGain = (float) Math.sqrt(1.0f - pan);
			rightGain = (float) Math.sqrt(1.0f + pan);
		}

		float volMultiplier = voiceChat.getSettings().getWorldVolume();
		if (stream.volume >= 0) {
			volMultiplier *= (stream.volume * 0.01f);
		}
		if (mc.gameSettings != null) {
			volMultiplier *= mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
		}

		float finalLeft = distanceFactor * leftGain * volMultiplier;
		float finalRight = distanceFactor * rightGain * volMultiplier;

		if (finalLeft <= 0.0001f && finalRight <= 0.0001f) {
			return;
		}

		int totalSamples = monoPcm.length / 2;
		StreamAudioQueue queue = activeStreams.get(stream.id);
		if (queue == null) {
			StreamAudioQueue newQueue = new StreamAudioQueue(stream.id);
			StreamAudioQueue existing = activeStreams.putIfAbsent(stream.id, newQueue);
			queue = existing != null ? existing : newQueue;
		}

		if (System.currentTimeMillis() - queue.lastLogTime > 1500) {
			VoiceChat.getLogger().info("VoiceAudioPlayer: playing voice from " + stream.player.entityName()
					+ " [dist=" + String.format(Locale.US, "%.1f", dist) + "m, pan=" + String.format(Locale.US, "%.2f", pan)
					+ ", vol=" + String.format(Locale.US, "%.2f", distanceFactor * volMultiplier) + "]");
			queue.lastLogTime = System.currentTimeMillis();
		}

		for (int offset = 0; offset < totalSamples; offset += FRAME_SAMPLES) {
			int chunkSize = Math.min(FRAME_SAMPLES, totalSamples - offset);
			short[] leftChunk = new short[FRAME_SAMPLES];
			short[] rightChunk = new short[FRAME_SAMPLES];

			for (int i = 0; i < chunkSize; i++) {
				int byteIdx = (offset + i) * 2;
				short sample = (short) ((monoPcm[byteIdx] & 0xFF) | (monoPcm[byteIdx + 1] << 8));

				int l = Math.round(sample * finalLeft);
				if (l > 32767) l = 32767;
				else if (l < -32768) l = -32768;

				int r = Math.round(sample * finalRight);
				if (r > 32767) r = 32767;
				else if (r < -32768) r = -32768;

				leftChunk[i] = (short) l;
				rightChunk[i] = (short) r;
			}
			queue.push(leftChunk, rightChunk);
		}

		synchronized (lock) {
			lock.notify();
		}
	}

	public void stopStream(int streamId) {
		StreamAudioQueue q = activeStreams.remove(streamId);
		if (q != null) {
			q.clear();
		}
	}

	public void reset() {
		activeStreams.clear();
		closeLine();
	}

	public synchronized void close() {
		running = false;
		synchronized (lock) {
			lock.notifyAll();
		}
		if (mixerThread != null) {
			try {
				mixerThread.interrupt();
				mixerThread.join(250);
			} catch (InterruptedException ignored) {
			}
			mixerThread = null;
		}
		closeLine();
	}
}
