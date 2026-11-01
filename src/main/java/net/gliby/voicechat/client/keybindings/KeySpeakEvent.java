package net.gliby.voicechat.client.keybindings;

import net.gliby.voicechat.client.VoiceChatClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiCrafting;
import net.minecraft.client.gui.inventory.GuiFurnace;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;

class KeySpeakEvent extends KeyEvent {

	private final VoiceChatClient voiceChat;
	KeySpeakEvent(VoiceChatClient voiceChat, EnumBinding keyBind, int keyID, boolean repeating) {
		super(keyBind, keyID, repeating);
		this.voiceChat = voiceChat;
	}

	@Override
	public void keyDown(KeyBinding kb, boolean tickEnd, boolean isRepeat) {
		final GuiScreen screen = Minecraft.getMinecraft().currentScreen;
		final boolean canSpeak = voiceChat.getSettings().getInputDevice() != null;
		if (tickEnd && canSpeak) {
			if (screen == null || screen instanceof GuiInventory || screen instanceof GuiCrafting || screen instanceof GuiChest || screen instanceof GuiFurnace || screen.getClass().getSimpleName().startsWith("GuiDriveableController")) {
				if (voiceChat.getSettings().getSpeakMode() == 1) {
					final boolean nextState = !voiceChat.isRecorderActive();
					voiceChat.recorder.set(nextState);
					voiceChat.setRecorderActive(nextState);
					VoiceChatClient.getLogger().info("Voice Chat Toggle key pressed: recording=" + nextState);
				} else {
					voiceChat.recorder.set(true);
					voiceChat.setRecorderActive(true);
					VoiceChatClient.getLogger().info("Voice Chat Push-To-Talk key pressed: recording=true");
				}
			}
		}
	}

	@Override
	public void keyUp(KeyBinding kb, boolean tickEnd) {
		if (tickEnd) {
			if (voiceChat.getSettings().getSpeakMode() == 0) {
				voiceChat.setRecorderActive(false);
				voiceChat.recorder.stop();
				VoiceChatClient.getLogger().info("Voice Chat Push-To-Talk key released: recording=false");
			}
		}
	}

}
