package io.github.yannickspies.awe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns an edit request into a change list. Reads no world data, so it runs on the HTTP thread.
 * The whole batch is resolved first; one bad item fails the whole batch.
 */
public final class Planner {
	private Planner() {
	}

	/** A planned cell change. If match is set, the cell changes only if its current state matches. */
	public record Change(long pos, BlockState state, Match match) {
	}

	/** Replace condition: an exact state, or any state of one block. */
	public record Match(BlockState exact, Block block) {
		boolean test(BlockState current) {
			return exact != null ? current == exact : current.getBlock() == block;
		}
	}

	public static final class PlanError extends RuntimeException {
		public PlanError(String message) {
			super(message);
		}
	}

	public static List<Change> plan(Workspace ws, JsonArray operations, int maxCells) {
		Map<Long, Change> changes = new LinkedHashMap<>();
		List<String> errors = new ArrayList<>();
		Map<String, BlockState> parsed = new HashMap<>();
		for (int i = 0; i < operations.size(); i++) {
			String where = "operations[" + i + "]";
			try {
				JsonObject op = operations.get(i).getAsJsonObject();
				String kind = string(op, "op", where);
				switch (kind) {
					case "set" -> {
						BlockState state = block(op, "block", where, parsed);
						for (JsonElement at : array(op, "at", where)) {
							put(ws, changes, vec(at.getAsJsonArray(), where + ".at"), state, null, where, maxCells);
						}
					}
					case "fill" -> fill(ws, changes, op, where, parsed, maxCells);
					case "line" -> {
						int[] a = vec(array(op, "from", where), where + ".from");
						int[] b = vec(array(op, "to", where), where + ".to");
						BlockState state = block(op, "block", where, parsed);
						int steps = Math.max(Math.abs(b[0] - a[0]), Math.max(Math.abs(b[1] - a[1]), Math.abs(b[2] - a[2])));
						for (int s = 0; s <= steps; s++) {
							double t = steps == 0 ? 0 : (double) s / steps;
							int[] p = {
									(int) Math.round(a[0] + (b[0] - a[0]) * t),
									(int) Math.round(a[1] + (b[1] - a[1]) * t),
									(int) Math.round(a[2] + (b[2] - a[2]) * t)};
							put(ws, changes, p, state, null, where, maxCells);
						}
					}
					case "replace" -> {
						int[][] box = box(op, where);
						BlockState state = block(op, "block", where, parsed);
						String matchText = string(op, "match", where);
						BlockState matchState = parse(matchText, where + ".match", parsed);
						Match match = matchText.contains("[") ? new Match(matchState, null) : new Match(null, matchState.getBlock());
						for (int y = box[0][1]; y <= box[1][1]; y++) {
							for (int z = box[0][2]; z <= box[1][2]; z++) {
								for (int x = box[0][0]; x <= box[1][0]; x++) {
									put(ws, changes, new int[] {x, y, z}, state, match, where, maxCells);
								}
							}
						}
					}
					case "grid" -> grid(ws, changes, op, where, parsed, maxCells);
					default -> throw new PlanError(where + ".op: unknown operation '" + kind + "'. Valid: set, fill, line, replace, grid.");
				}
			} catch (PlanError | IllegalStateException | UnsupportedOperationException | ClassCastException e) {
				errors.add(e.getMessage());
				if (errors.size() >= 20) {
					break;
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new PlanError("the batch was refused and no block changed. Fix these items and send the whole batch again:\n- " + String.join("\n- ", errors));
		}
		return new ArrayList<>(changes.values());
	}

	private static void fill(Workspace ws, Map<Long, Change> changes, JsonObject op, String where, Map<String, BlockState> parsed, int maxCells) {
		int[][] box = box(op, where);
		BlockState state = block(op, "block", where, parsed);
		String mode = op.has("mode") ? op.get("mode").getAsString() : "solid";
		int[] a = box[0];
		int[] b = box[1];
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = a[1]; y <= b[1]; y++) {
			for (int z = a[2]; z <= b[2]; z++) {
				for (int x = a[0]; x <= b[0]; x++) {
					boolean edgeX = x == a[0] || x == b[0];
					boolean edgeY = y == a[1] || y == b[1];
					boolean edgeZ = z == a[2] || z == b[2];
					BlockState target = switch (mode) {
						case "solid" -> state;
						case "hollow" -> edgeX || edgeY || edgeZ ? state : air;
						case "walls" -> edgeX || edgeZ ? state : null;
						case "frame" -> (edgeX ? 1 : 0) + (edgeY ? 1 : 0) + (edgeZ ? 1 : 0) >= 2 ? state : null;
						default -> throw new PlanError(where + ".mode: unknown mode '" + mode + "'. Valid: solid, hollow, walls, frame.");
					};
					if (target != null) {
						put(ws, changes, new int[] {x, y, z}, target, null, where, maxCells);
					}
				}
			}
		}
	}

	private static void grid(Workspace ws, Map<Long, Change> changes, JsonObject op, String where, Map<String, BlockState> parsed, int maxCells) {
		int[] origin = vec(array(op, "origin", where), where + ".origin");
		JsonObject paletteJson = op.getAsJsonObject("palette");
		if (paletteJson == null) {
			throw new PlanError(where + ".palette: missing. Give an object that maps one character to one block.");
		}
		Map<Character, BlockState> palette = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : paletteJson.entrySet()) {
			if (entry.getKey().length() != 1 || entry.getKey().equals("~")) {
				throw new PlanError(where + ".palette: key '" + entry.getKey() + "' must be one character and not '~'.");
			}
			palette.put(entry.getKey().charAt(0), parse(entry.getValue().getAsString(), where + ".palette." + entry.getKey(), parsed));
		}
		palette.putIfAbsent('.', Blocks.AIR.defaultBlockState());
		JsonArray layers = array(op, "layers", where);
		for (int ly = 0; ly < layers.size(); ly++) {
			JsonArray rows = layers.get(ly).getAsJsonArray();
			for (int lz = 0; lz < rows.size(); lz++) {
				String row = rows.get(lz).getAsString().replaceFirst("^\\s*z=-?\\d+\\s", "").strip();
				for (int lx = 0; lx < row.length(); lx++) {
					char c = row.charAt(lx);
					if (c == '~') {
						continue;
					}
					BlockState state = palette.get(c);
					if (state == null) {
						throw new PlanError(where + ".layers[" + ly + "][" + lz + "]: character '" + c + "' is not in the palette.");
					}
					put(ws, changes, new int[] {origin[0] + lx, origin[1] + ly, origin[2] + lz}, state, null, where, maxCells);
				}
			}
		}
	}

	private static void put(Workspace ws, Map<Long, Change> changes, int[] p, BlockState state, Match match, String where, int maxCells) {
		if (!ws.containsLocal(p[0], p[1], p[2])) {
			throw new PlanError(where + ": cell " + Reader.corner(p[0], p[1], p[2]) + " is outside the workspace, which runs from [0,0,0] to "
					+ Reader.corner(ws.sizeX() - 1, ws.sizeY() - 1, ws.sizeZ() - 1) + ".");
		}
		long pos = ws.toWorld(p[0], p[1], p[2]).asLong();
		changes.put(pos, new Change(pos, state, match));
		if (changes.size() > maxCells) {
			throw new PlanError("the batch changes more than " + maxCells + " cells. Split it into smaller edits.");
		}
	}

	private static int[][] box(JsonObject op, String where) {
		int[] a = vec(array(op, "from", where), where + ".from");
		int[] b = vec(array(op, "to", where), where + ".to");
		return new int[][] {
				{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])},
				{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])}};
	}

	private static int[] vec(JsonArray array, String where) {
		if (array.size() != 3) {
			throw new PlanError(where + ": a position is [x,y,z], three integers.");
		}
		return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
	}

	private static JsonArray array(JsonObject op, String key, String where) {
		if (!op.has(key) || !op.get(key).isJsonArray()) {
			throw new PlanError(where + "." + key + ": missing or not a list.");
		}
		return op.getAsJsonArray(key);
	}

	private static String string(JsonObject op, String key, String where) {
		if (!op.has(key)) {
			throw new PlanError(where + "." + key + ": missing.");
		}
		return op.get(key).getAsString();
	}

	private static BlockState block(JsonObject op, String key, String where, Map<String, BlockState> parsed) {
		return parse(string(op, key, where), where + "." + key, parsed);
	}

	private static BlockState parse(String text, String where, Map<String, BlockState> parsed) {
		BlockState cached = parsed.get(text);
		if (cached != null) {
			return cached;
		}
		try {
			BlockState state = BlockText.parse(text);
			parsed.put(text, state);
			return state;
		} catch (IllegalArgumentException e) {
			throw new PlanError(where + ": " + e.getMessage());
		}
	}

	static BlockPos pos(long packed) {
		return BlockPos.of(packed);
	}
}
