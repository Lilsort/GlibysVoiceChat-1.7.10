package net.gliby.voicechat.client.sound;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import paulscode.sound.SoundSystem;

import java.lang.reflect.Field;

public class GlibySoundAccess {
	private static Field sndManagerField;
	private static Field sndSystemField;

	static {
		try {
			sndManagerField = ReflectionHelper.findField(SoundHandler.class, "sndManager", "field_147694_f");
			sndManagerField.setAccessible(true);
		} catch (Throwable t) {
			t.printStackTrace();
		}
		try {
			sndSystemField = ReflectionHelper.findField(SoundManager.class, "sndSystem", "field_148620_e");
			sndSystemField.setAccessible(true);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	public static SoundManager getSoundManager(SoundHandler handler) {
		if (handler == null || sndManagerField == null) return null;
		try {
			return (SoundManager) sndManagerField.get(handler);
		} catch (Throwable t) {
			return null;
		}
	}

	public static SoundSystem getSoundSystem(SoundManager manager) {
		if (manager == null || sndSystemField == null) return null;
		try {
			return (SoundSystem) sndSystemField.get(manager);
		} catch (Throwable t) {
			return null;
		}
	}

	public static SoundSystem getSoundSystem() {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null) return null;
		SoundHandler handler = mc.getSoundHandler();
		if (handler == null) return null;
		SoundManager manager = getSoundManager(handler);
		if (manager == null) return null;
		return getSoundSystem(manager);
	}
}
