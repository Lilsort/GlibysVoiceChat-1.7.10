package net.gliby.voicechat.client.sound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sound.sampled.AudioFormat;

import net.gliby.voicechat.VoiceChat;
import net.gliby.voicechat.client.VoiceChatClient;
import net.gliby.voicechat.client.sound.thread.ThreadSoundQueue;
import net.gliby.voicechat.client.sound.thread.ThreadUpdateStream;
import net.gliby.voicechat.common.PlayerProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.entity.player.EntityPlayer;

public class ClientStreamManager {

	/** Common audio format **/
	public static AudioFormat universalAudioFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000.0F, 16, 1, 2, 16000.0F, false);
	/** Details for muted players **/
	public static Map<Integer, String> playerMutedData = new HashMap<Integer, String>();

	public static AudioFormat getUniversalAudioFormat() {
		return universalAudioFormat;
	}

	public List<ClientStream> currentStreams = new ArrayList<ClientStream>();

	public List<Integer> playersMuted = new ArrayList<Integer>();

	/**
	 * Used to queue voice data, ensures order of data and easier management.
	 **/
	public ConcurrentLinkedQueue<Datalet> queue = new ConcurrentLinkedQueue<Datalet>();

	/** Currently streaming players, for API use currentStreams. **/
	public ConcurrentHashMap<Integer, ClientStream> streaming = new ConcurrentHashMap<Integer, ClientStream>();
	/** Decodes SPEEX sound data **/
	public final SoundPreProcessor soundPreProcessor;

	public ConcurrentHashMap<Integer, PlayerProxy> playerData = new ConcurrentHashMap<Integer, PlayerProxy>();
	private Thread threadUpdate;

	private ThreadSoundQueue threadQueue;
	public VoiceAudioPlayer voiceAudioPlayer;
	private final Minecraft mc;

	private final VoiceChatClient voiceChat;

	private boolean volumeControlActive;

	private final float volumeValue = 0.15F;

	private float WEATHER, RECORDS, BLOCKS, MOBS, ANIMALS;

	public ClientStreamManager(Minecraft mc, VoiceChatClient voiceChatClient) {
		this.mc = mc;
		this.voiceChat = voiceChatClient;
		soundPreProcessor = new SoundPreProcessor(voiceChatClient, mc);
	}

	public void addQueue(byte[] decoded_data, boolean global, int id, byte volume) {
		if (!playersMuted.contains(id)) {
			queue.offer(new Datalet(global, id, decoded_data, volume));
			synchronized (threadQueue) {
				threadQueue.notify();
			}
		}
	}

	private void addStreamSafe(ClientStream stream) {
		streaming.put(stream.id, stream);
		synchronized (threadUpdate) {
			threadUpdate.notify();
		}
		final String entityName = stream.player.entityName();
		for (int i = 0; i < voiceChat.getTestPlayers().length; i++) {
			final String name = voiceChat.getTestPlayers()[i];
			if (stream.player.equals(name)) stream.special = 2;
		}
		if (voiceChat.specialPlayers.containsKey(entityName)) stream.special = voiceChat.specialPlayers.get(entityName);

		if (!containsStream(stream.id)) {
			final List<ClientStream> streams = new ArrayList<ClientStream>(this.currentStreams);
			streams.add(stream);
			Collections.sort(streams, new ClientStream.PlayableStreamComparator());
			this.currentStreams.removeAll(this.currentStreams);
			this.currentStreams.addAll(streams);
		}
	}

	public void alertEnd(int id) {
		if (!playersMuted.contains(id)) {
			queue.offer(new Datalet(false, id, null, (byte) 0));
			synchronized (threadQueue) {
				threadQueue.notify();
			}
		}
	}

	public boolean containsStream(int id) {
		final ClientStream currentStream = streaming.get(id);
		for (int i = 0; i < this.currentStreams.size(); i++) {
			final ClientStream stream = this.currentStreams.get(i);
			final String currentName = currentStream.player.entityName();
			final String otherName = stream.player.entityName();
			if (stream.player.entityName() != null && currentStream.player.entityName() != null) if (currentName.equals(otherName)) return true;
			if (stream.id == id) return true;
			else continue;
		}
		return false;
	}

	public void createStream(Datalet data) {
		final PlayerProxy player = getPlayerData(data.id);
		VoiceChat.getLogger().info("Client creating audio stream for entity " + data.id + " (" + player.entityName() + ") direct=" + data.direct);
		addStreamSafe(new ClientStream(player, data.id, data.direct));
		giveStream(data);
	}

	private String generateSource(int let) {
		return "" + let;
	}

	private PlayerProxy getPlayerData(int entityId) {
		PlayerProxy proxy = playerData.get(entityId);
		final EntityPlayer entity = (EntityPlayer) mc.theWorld.getEntityByID(entityId);
		if (proxy == null) {
			if (entity != null) proxy = new PlayerProxy(entity, entity.getEntityId(), entity.getCommandSenderName(), entity.posX, entity.posY, entity.posZ);
			else {
				float px = mc.thePlayer != null ? (float) mc.thePlayer.posX : 0.0f;
				float py = mc.thePlayer != null ? (float) mc.thePlayer.posY : 0.0f;
				float pz = mc.thePlayer != null ? (float) mc.thePlayer.posZ : 0.0f;
				proxy = new PlayerProxy(null, entityId, "" + entityId, px, py, pz);
			}
			playerData.put(entityId, proxy);
		} else {
			if (entity != null) {
				proxy.setPlayer(entity);
				proxy.setName(entity.getCommandSenderName());
			}
		}
		return proxy;
	}

	public SoundPreProcessor getSoundPreProcessor() {
		return soundPreProcessor;
	}

	public void giveEnd(int id) {
		final ClientStream stream = streaming.get(id);
		if (stream != null) stream.needsEnd = true;
	}

	public void giveStream(Datalet data) {
		final ClientStream stream = streaming.get(data.id);
		if (stream != null) {
			stream.update(data, (int) (System.currentTimeMillis() - stream.lastUpdated));
			stream.buffer.push(data.data);
			stream.buffer.updateJitter(stream.getJitterRate());
			if (stream.buffer.isReady() || stream.needsEnd) {
				byte[] audioData = stream.buffer.get();
				if (audioData != null && audioData.length > 0) {
					if (voiceAudioPlayer != null) {
						voiceAudioPlayer.play(stream, audioData);
					}
				}
				stream.buffer.clearBuffer(stream.getJitterRate());
			}
			stream.lastUpdated = System.currentTimeMillis();
		}
	}

	public void init() {
		voiceAudioPlayer = new VoiceAudioPlayer(mc, voiceChat);
		voiceAudioPlayer.start();
		final Thread thread = new Thread(threadQueue = new ThreadSoundQueue(this), "Client Stream Queue");
		thread.start();
		threadUpdate = new Thread(new ThreadUpdateStream(this, voiceChat), "Client Stream Updater");
		threadUpdate.start();
	}

	public void killStream(ClientStream stream) {
		if (stream != null) {
			if (voiceAudioPlayer != null) {
				voiceAudioPlayer.stopStream(stream.id);
			}
			final List<ClientStream> streams = new ArrayList<ClientStream>(this.currentStreams);
			streams.remove(stream);
			Collections.sort(streams, new ClientStream.PlayableStreamComparator());
			this.currentStreams.removeAll(this.currentStreams);
			this.currentStreams.addAll(streams);
			this.currentStreams.remove(stream);
			Collections.sort(this.currentStreams, new ClientStream.PlayableStreamComparator());
			streaming.remove(stream.id);
		}
	}

	public boolean newDatalet(Datalet let) {
		return !streaming.containsKey(let.id);
	}

	public void reload() {
		if (!this.currentStreams.isEmpty()) {
			VoiceChatClient.getLogger().info("Reloading SoundManager, removing all active streams.");
			for (int i = 0; i < this.currentStreams.size(); i++) {
				final ClientStream stream = this.currentStreams.get(i);
				killStream(stream);
			}
		}
	}

	public void reset() {
		voiceChat.setRecorderActive(false);
		voiceChat.recorder.stop();
		volumeControlStop();
		if (voiceAudioPlayer != null) {
			voiceAudioPlayer.reset();
		}
		queue.clear();
		this.streaming.clear();
		this.currentStreams.clear();
		playerData.clear();
	}

	public void volumeControlStart() {
		if (!(mc.currentScreen instanceof GuiScreenOptionsSounds) && !volumeControlActive) {
			WEATHER = mc.gameSettings.getSoundLevel(SoundCategory.WEATHER);
			RECORDS = mc.gameSettings.getSoundLevel(SoundCategory.RECORDS);
			BLOCKS = mc.gameSettings.getSoundLevel(SoundCategory.BLOCKS);
			MOBS = mc.gameSettings.getSoundLevel(SoundCategory.MOBS);
			ANIMALS = mc.gameSettings.getSoundLevel(SoundCategory.PLAYERS);
			if (mc.gameSettings.getSoundLevel(SoundCategory.WEATHER) > volumeValue) mc.gameSettings.setSoundLevel(SoundCategory.WEATHER, volumeValue);
			if (mc.gameSettings.getSoundLevel(SoundCategory.RECORDS) > volumeValue) mc.gameSettings.setSoundLevel(SoundCategory.RECORDS, volumeValue);
			if (mc.gameSettings.getSoundLevel(SoundCategory.BLOCKS) > volumeValue) mc.gameSettings.setSoundLevel(SoundCategory.BLOCKS, volumeValue);
			if (mc.gameSettings.getSoundLevel(SoundCategory.MOBS) > volumeValue) mc.gameSettings.setSoundLevel(SoundCategory.MOBS, volumeValue);
			if (mc.gameSettings.getSoundLevel(SoundCategory.ANIMALS) > volumeValue) mc.gameSettings.setSoundLevel(SoundCategory.ANIMALS, volumeValue);
			volumeControlActive = true;
		}
	}

	public void volumeControlStop() {
		if (volumeControlActive) {
			mc.gameSettings.setSoundLevel(SoundCategory.WEATHER, WEATHER);
			mc.gameSettings.setSoundLevel(SoundCategory.RECORDS, RECORDS);
			mc.gameSettings.setSoundLevel(SoundCategory.BLOCKS, BLOCKS);
			mc.gameSettings.setSoundLevel(SoundCategory.MOBS, MOBS);
			mc.gameSettings.setSoundLevel(SoundCategory.ANIMALS, ANIMALS);
			volumeControlActive = false;
		}
	}
}