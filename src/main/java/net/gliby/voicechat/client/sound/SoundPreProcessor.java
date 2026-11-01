package net.gliby.voicechat.client.sound;

import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.gliby.voicechat.client.VoiceChatClient;
import net.gliby.voicechat.client.debug.Statistics;
import net.minecraft.client.Minecraft;

import org.xiph.speex.SpeexDecoder;

public class SoundPreProcessor {

	public static List<byte[]> divideArray(byte[] source, int chunksize) {
		final List<byte[]> result = new ArrayList<byte[]>();
		if (chunksize <= 0) {
			result.add(source);
			return result;
		}
		int start = 0;
		while (start < source.length) {
			final int end = Math.min(source.length, start + chunksize);
			result.add(Arrays.copyOfRange(source, start, end));
			start += chunksize;
		}

		return result;
	}

	VoiceChatClient voiceChat;
	Statistics stats;
	private final java.util.Map<Integer, SpeexDecoder> playerDecoders = new java.util.HashMap<Integer, SpeexDecoder>();

	byte[] buffer;

	public SoundPreProcessor(VoiceChatClient voiceChat, Minecraft mc) {
		this.voiceChat = voiceChat;
		this.stats = VoiceChatClient.getStatistics();
	}

	private SpeexDecoder getDecoder(int id) {
		SpeexDecoder dec = playerDecoders.get(id);
		if (dec == null) {
			dec = new SpeexDecoder();
			dec.init(0, (int) ClientStreamManager.getUniversalAudioFormat().getSampleRate(), ClientStreamManager.getUniversalAudioFormat().getChannels(), voiceChat.getSettings().isPerceptualEnchantmentAllowed());
			playerDecoders.put(id, dec);
		}
		return dec;
	}

	public boolean process(int id, byte[] encodedSamples, int chunkSize, boolean direct, byte volume) {
		if (encodedSamples == null || encodedSamples.length == 0) {
			return false;
		}
		if (chunkSize <= 0) {
			chunkSize = encodedSamples.length;
		}
		if (chunkSize > encodedSamples.length) {
			chunkSize = encodedSamples.length;
		}

		SpeexDecoder dec = getDecoder(id);
		byte[] decodedData = null;
		if (encodedSamples.length <= chunkSize) {
			try {
				dec.processData(encodedSamples, 0, encodedSamples.length);
			} catch (final StreamCorruptedException e) {
				e.printStackTrace();
				return false;
			}
			decodedData = new byte[dec.getProcessedDataByteSize()];
			dec.getProcessedData(decodedData, 0);
		} else {
			final List<byte[]> samplesList = divideArray(encodedSamples, chunkSize);
			buffer = new byte[0];
			for (int i = 0; i < samplesList.size(); i++) {
				final byte[] sample = samplesList.get(i);
				try {
					dec.processData(sample, 0, sample.length);
				} catch (final StreamCorruptedException e) {
					e.printStackTrace();
					return false;
				}
				final byte[] sampleBuffer = new byte[dec.getProcessedDataByteSize()];
				dec.getProcessedData(sampleBuffer, 0);
				write(sampleBuffer);
			}
			decodedData = buffer;
		}
		if (decodedData != null) {
			VoiceChatClient.getSoundManager().addQueue(decodedData, direct, id, volume);
			if (stats != null) {
				stats.addEncodedSamples(encodedSamples.length);
				stats.addDecodedSamples(decodedData.length);
			}
			buffer = new byte[0];
			return true;
		}
		return false;
	}

	private void write(byte[] write) {
		final byte[] result = new byte[buffer.length + write.length];
		System.arraycopy(buffer, 0, result, 0, buffer.length);
		System.arraycopy(write, 0, result, buffer.length, write.length);
		buffer = result;
	}

}
