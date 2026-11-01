package net.gliby.voicechat.client.sound.thread;

import net.gliby.voicechat.client.VoiceChatClient;
import net.gliby.voicechat.client.sound.ClientStream;
import net.gliby.voicechat.client.sound.ClientStreamManager;
import net.minecraft.client.Minecraft;

public class ThreadUpdateStream implements Runnable {

	private final static int ARBITRARY_TIMEOUT = 325;
	private final Minecraft mc;

	private final VoiceChatClient voiceChat;

	private final ClientStreamManager manager;

	/**
	 * Handles sound streams and sound position/velocity.
	 **/
	public ThreadUpdateStream(ClientStreamManager manager, VoiceChatClient voiceChatClient) {
		this.manager = manager;
		this.mc = Minecraft.getMinecraft();
		this.voiceChat = voiceChatClient;
	}

	@Override
	public void run() {
		while (true) {
			if (!VoiceChatClient.getSoundManager().currentStreams.isEmpty()) {
				for (int i = 0; i < VoiceChatClient.getSoundManager().currentStreams.size(); i++) {
					final ClientStream stream = VoiceChatClient.getSoundManager().currentStreams.get(i);
					if (stream.needsEnd || stream.getLastTimeUpdatedMS() > ARBITRARY_TIMEOUT) {
						manager.killStream(stream);
						continue;
					}

					Minecraft.getMinecraft().func_152344_a(new Runnable() {
						@Override
						public void run() {
							stream.player.update(mc.theWorld);
						}
					});
				}
				try {
					synchronized (this) {
						wait(34);
					}
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}
			} else {
				try {
					synchronized (this) {
						this.wait(2);
					}
				} catch (final InterruptedException e1) {
					e1.printStackTrace();
				}
			}
		}
	}
}
