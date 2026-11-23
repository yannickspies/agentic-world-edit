package io.github.yannickspies.awe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reads block states straight from chunk sections. Runs on the server thread only. */
public final class Reader {
	/** Cell characters. '~' is kept out: in a grid edit it means "keep the cell". */
	static final String CHARS = ".abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@$%&+=?!^";

	private Reader() {
	}

	/** A block copy of a box: palette index per cell, x fastest, then z, then y. */
	public record Snapshot(int sx, int sy, int sz, List<BlockState> palette, int[] cells) {
		int at(int x, int y, int z) {
			return cells[(y * sz + z) * sx + x];
		}
	}

	/** Box in local coordinates, both corners inclusive. */
	public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
	}

	public static Snapshot snapshot(ServerLevel level, Workspace ws, Box box) {
		int sx = box.x1 - box.x0 + 1;
		int sy = box.y1 - box.y0 + 1;
		int sz = box.z1 - box.z0 + 1;
		int[] cells = new int[sx * sy * sz];
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new IdentityHashMap<>();
		LevelChunk chunk = null;
		int chunkX = Integer.MIN_VALUE;
		int chunkZ = Integer.MIN_VALUE;
		int i = 0;
		for (int y = 0; y < sy; y++) {
			int wy = ws.min().getY() + box.y0 + y;
			int section = level.getSectionIndex(wy);
			for (int z = 0; z < sz; z++) {
				int wz = ws.min().getZ() + box.z0 + z;
				for (int x = 0; x < sx; x++) {
					int wx = ws.min().getX() + box.x0 + x;
					if (chunk == null || (wx >> 4) != chunkX || (wz >> 4) != chunkZ) {
						chunkX = wx >> 4;
						chunkZ = wz >> 4;
						chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
						if (chunk == null) {
							throw new IllegalStateException("chunk " + chunkX + "," + chunkZ + " is not loaded. AWE never loads chunks; a player or /forceload must keep the workspace loaded.");
						}
					}
					BlockState state = chunk.getSection(section).getBlockState(wx & 15, wy & 15, wz & 15);
					Integer id = index.get(state);
					if (id == null) {
						id = palette.size();
						palette.add(state);
						index.put(state, id);
					}
					cells[i++] = id;
				}
			}
		}
		return new Snapshot(sx, sy, sz, palette, cells);
	}

	public static String summary(Snapshot snap, Box box) {
		long[] counts = new long[snap.palette().size()];
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = -1, maxY = -1, maxZ = -1;
		int[] top = new int[snap.sx() * snap.sz()];
		Arrays.fill(top, -1);
		for (int y = 0; y < snap.sy(); y++) {
			for (int z = 0; z < snap.sz(); z++) {
				for (int x = 0; x < snap.sx(); x++) {
					int id = snap.at(x, y, z);
					counts[id]++;
					if (!snap.palette().get(id).isAir()) {
						minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
						maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
						top[z * snap.sx() + x] = y;
					}
				}
			}
		}
		StringBuilder out = new StringBuilder();
		out.append("box: ").append(corner(box.x0, box.y0, box.z0)).append(" to ").append(corner(box.x1, box.y1, box.z1)).append('\n');
		if (maxX < 0) {
			out.append("all cells are air\n");
			return out.toString();
		}
		out.append("non-air bounds: ").append(corner(box.x0 + minX, box.y0 + minY, box.z0 + minZ)).append(" to ")
				.append(corner(box.x0 + maxX, box.y0 + maxY, box.z0 + maxZ)).append('\n');
		out.append("blocks (count):\n");
		Integer[] order = new Integer[counts.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Long.compare(counts[b], counts[a]));
		for (int id : order) {
			out.append("  ").append(counts[id]).append(' ').append(BlockText.format(snap.palette().get(id))).append('\n');
		}
		if (snap.sx() * snap.sz() <= 4096) {
			out.append("height map: local y of the highest non-air cell, '-' for an empty column. Rows z, columns x from ").append(box.x0).append(":\n");
			for (int z = 0; z < snap.sz(); z++) {
				out.append("  z=").append(box.z0 + z).append(' ');
				for (int x = 0; x < snap.sx(); x++) {
					int h = top[z * snap.sx() + x];
					out.append(x == 0 ? "" : " ").append(h < 0 ? "-" : Integer.toString(box.y0 + h));
				}
				out.append('\n');
			}
		}
		return out.toString();
	}

	public static String layers(Snapshot snap, Box box, int maxChars) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = -1, maxY = -1, maxZ = -1;
		for (int y = 0; y < snap.sy(); y++) {
			for (int z = 0; z < snap.sz(); z++) {
				for (int x = 0; x < snap.sx(); x++) {
					if (!snap.palette().get(snap.at(x, y, z)).isAir()) {
						minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
						maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
					}
				}
			}
		}
		if (maxX < 0) {
			return "box: " + corner(box.x0, box.y0, box.z0) + " to " + corner(box.x1, box.y1, box.z1) + "\nall cells are air\n";
		}
		// Character per palette entry, in order of first use. Air is always '.'.
		char[] symbol = new char[snap.palette().size()];
		int next = 1;
		List<Integer> used = new ArrayList<>();
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					int id = snap.at(x, y, z);
					if (symbol[id] == 0) {
						if (snap.palette().get(id).isAir()) {
							symbol[id] = '.';
						} else {
							if (next >= CHARS.length()) {
								throw new IllegalStateException("this box has more than " + (CHARS.length() - 1) + " distinct block states. Read a smaller box.");
							}
							symbol[id] = CHARS.charAt(next++);
						}
						used.add(id);
					}
				}
			}
		}
		StringBuilder out = new StringBuilder();
		out.append("box: ").append(corner(box.x0 + minX, box.y0 + minY, box.z0 + minZ)).append(" to ")
				.append(corner(box.x0 + maxX, box.y0 + maxY, box.z0 + maxZ))
				.append("  (trimmed to non-air cells; each row is x from ").append(box.x0 + minX).append(" eastward)\n");
		out.append("palette:\n");
		for (int id : used) {
			BlockState state = snap.palette().get(id);
			out.append("  ").append(symbol[id]).append(' ').append(BlockText.format(state)).append(state.hasBlockEntity() ? " *" : "").append('\n');
		}
		String previous = null;
		int runStart = minY;
		for (int y = minY; y <= maxY + 1; y++) {
			String layer = null;
			if (y <= maxY) {
				StringBuilder rows = new StringBuilder();
				for (int z = minZ; z <= maxZ; z++) {
					rows.append("  z=").append(box.z0 + z).append(' ');
					for (int x = minX; x <= maxX; x++) {
						rows.append(symbol[snap.at(x, y, z)]);
					}
					rows.append('\n');
				}
				layer = rows.toString();
			}
			if (previous != null && !previous.equals(layer)) {
				int a = box.y0 + runStart;
				int b = box.y0 + y - 1;
				out.append(a == b ? "y=" + a : "y=" + a + ".." + b + "  (" + (b - a + 1) + " equal layers)").append('\n').append(previous);
				runStart = y;
			}
			if (previous == null || !previous.equals(layer)) {
				previous = layer;
			}
			if (out.length() > maxChars) {
				throw new IllegalStateException("the read exceeds " + maxChars + " characters. Read a smaller box with 'from' and 'to', or use detail=summary.");
			}
		}
		return out.toString();
	}

	static String corner(int x, int y, int z) {
		return "[" + x + "," + y + "," + z + "]";
	}
}
