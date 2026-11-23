package io.github.yannickspies.awe;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Block states as text, in the form of the /setblock command, with default properties left out. */
public final class BlockText {
	private BlockText() {
	}

	public static String format(BlockState state) {
		Block block = state.getBlock();
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		String name = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
		BlockState defaults = block.defaultBlockState();
		StringBuilder props = new StringBuilder();
		for (Property<?> property : state.getProperties()) {
			if (!state.getValue(property).equals(defaults.getValue(property))) {
				if (!props.isEmpty()) {
					props.append(',');
				}
				props.append(property.getName()).append('=').append(valueName(state, property));
			}
		}
		return props.isEmpty() ? name : name + "[" + props + "]";
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	/** Parses a block state, or throws with a message that names the closest valid block ids. */
	public static BlockState parse(String text) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text.trim(), false).blockState();
		} catch (CommandSyntaxException e) {
			String message = e.getRawMessage().getString();
			String id = text.trim();
			int bracket = id.indexOf('[');
			if (bracket >= 0) {
				id = id.substring(0, bracket);
			}
			if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id.contains(":") ? id : "minecraft:" + id))) {
				message = "unknown block '" + id + "'. Closest: " + String.join(", ", closest(id, 3));
			}
			throw new IllegalArgumentException(message);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("cannot parse block '" + text + "': " + e.getMessage());
		}
	}

	public static List<String> closest(String id, int count) {
		String target = id.replace("minecraft:", "");
		List<String> names = new ArrayList<>();
		for (Identifier key : BuiltInRegistries.BLOCK.keySet()) {
			names.add("minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString());
		}
		names.sort(Comparator.comparingInt(name -> distance(target, name)));
		return names.subList(0, Math.min(count, names.size()));
	}

	private static int distance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}
}
