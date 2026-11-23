package io.github.yannickspies.awe;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

/** MCP over HTTP: one JSON body per request, no session, no event stream. */
public final class McpServer {
	private static final Gson GSON = new Gson();
	private static final String INSTRUCTIONS = """
			AWE edits blocks inside one box (the workspace) that the player marked in Minecraft.
			Work in this order: workspace, read (summary, then layers of the part you need), edit, read again to check, undo if wrong.
			Coordinates are local to the workspace: [0,0,0] is its lowest corner, x grows east, y grows up, z grows south. North is -z.
			Send large changes as one edit batch with fill, line or grid operations, not as thousands of single cells.""";

	private final Config config;
	private final MinecraftServer server;
	private final JsonArray tools;
	private static volatile long lastRequestNanos = System.nanoTime() - 600_000_000_000L;
	private HttpServer http;
	private ExecutorService executor;

	public McpServer(Config config, MinecraftServer server) {
		this.config = config;
		this.server = server;
		try (InputStream in = McpServer.class.getResourceAsStream("/awe/tools.json")) {
			this.tools = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
		} catch (IOException | NullPointerException e) {
			throw new IllegalStateException("AWE cannot load awe/tools.json", e);
		}
	}

	public static long lastRequestNanos() {
		return lastRequestNanos;
	}

	public void start() throws IOException {
		if (!config.allowRemote && !isLoopback(config.host)) {
			throw new IllegalStateException("AWE refuses to listen on " + config.host + " without allowRemote: true in config/awe/connection.json");
		}
		http = HttpServer.create(new InetSocketAddress(config.host, config.port), 16);
		executor = Executors.newFixedThreadPool(4, runnable -> {
			Thread thread = new Thread(runnable, "awe-http");
			thread.setDaemon(true);
			return thread;
		});
		http.setExecutor(executor);
		http.createContext("/mcp", this::handle);
		http.start();
		if (config.allowRemote) {
			Awe.LOG.warn("AWE listens on {} with allowRemote. The token travels unencrypted.", config.host);
		}
		Awe.LOG.info("AWE MCP server on {}", config.endpoint());
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
			executor.shutdownNow();
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String origin = exchange.getRequestHeaders().getFirst("Origin");
			if (origin != null && !isLocalOrigin(origin)) {
				send(exchange, 403, "{\"error\":\"origin not allowed\"}");
				return;
			}
			String host = exchange.getRequestHeaders().getFirst("Host");
			if (!config.allowRemote && (host == null || !isLoopback(host.replaceFirst(":\\d+$", "")))) {
				send(exchange, 403, "{\"error\":\"host not allowed\"}");
				return;
			}
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + config.token).getBytes(StandardCharsets.UTF_8))) {
				send(exchange, 401, "{\"error\":\"missing or wrong token\"}");
				return;
			}
			lastRequestNanos = System.nanoTime();
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().add("Allow", "POST");
				send(exchange, 405, "");
				return;
			}
			JsonObject request;
			try {
				request = JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(4_000_000), StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (RuntimeException e) {
				send(exchange, 400, error(null, -32700, "parse error"));
				return;
			}
			if (!request.has("id")) {
				send(exchange, 202, "");
				return;
			}
			send(exchange, 200, dispatch(request));
		}
	}

	private String dispatch(JsonObject request) {
		JsonElement id = request.get("id");
		String method = request.has("method") ? request.get("method").getAsString() : "";
		JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
		JsonObject result = new JsonObject();
		switch (method) {
			case "initialize" -> {
				result.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18");
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject info = new JsonObject();
				info.addProperty("name", "awe");
				info.addProperty("version", Awe.version());
				result.add("serverInfo", info);
				result.addProperty("instructions", INSTRUCTIONS);
			}
			case "ping" -> {
			}
			case "tools/list" -> result.add("tools", tools);
			case "tools/call" -> {
				String name = params.has("name") ? params.get("name").getAsString() : "";
				JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
				String text;
				boolean isError = false;
				try {
					text = call(name, args);
				} catch (Exception e) {
					Throwable cause = e.getCause() != null && e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
					text = "Error: " + cause.getMessage();
					isError = true;
				}
				JsonArray content = new JsonArray();
				JsonObject item = new JsonObject();
				item.addProperty("type", "text");
				item.addProperty("text", text);
				content.add(item);
				result.add("content", content);
				result.addProperty("isError", isError);
			}
			default -> {
				return error(id, -32601, "method not found: " + method);
			}
		}
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return GSON.toJson(response);
	}

	private String call(String name, JsonObject args) throws Exception {
		if (name.equals("blocks")) {
			return blocks(args.has("search") ? args.get("search").getAsString() : "");
		}
		if (name.equals("workspace")) {
			return onServer(this::describeWorkspace);
		}
		Workspace ws = Awe.workspace();
		if (ws == null) {
			throw new IllegalStateException("no workspace is set. Ask the player to run /awe pos1 and /awe pos2.");
		}
		String given = args.has("workspace_id") ? args.get("workspace_id").getAsString() : "";
		if (!ws.id().equals(given)) {
			throw new IllegalStateException("workspace_id '" + given + "' is old or wrong; the current one is " + ws.id()
					+ ". The player moved a corner, so coordinates from earlier reads are no longer valid. Call workspace and read again.");
		}
		return switch (name) {
			case "read" -> {
				Reader.Box box = box(ws, args);
				String detail = args.has("detail") ? args.get("detail").getAsString() : "summary";
				yield onServer(() -> {
					ServerLevel level = level(ws);
					Reader.Snapshot snap = Reader.snapshot(level, ws, box);
					return detail.equals("layers") ? Reader.layers(snap, box, config.maxReadChars) : Reader.summary(snap, box);
				});
			}
			case "edit" -> {
				if (!args.has("operations") || !args.get("operations").isJsonArray()) {
					throw new IllegalArgumentException("operations: missing or not a list.");
				}
				List<Planner.Change> changes = Planner.plan(ws, args.getAsJsonArray("operations"), config.maxChangedCells);
				if (args.has("dry_run") && args.get("dry_run").getAsBoolean()) {
					yield "dry run: the batch is valid and plans " + changes.size() + " cells. No block changed.";
				}
				String label = args.has("label") ? args.get("label").getAsString() : "edit";
				Engine.Report report = Awe.engine().submitEdit(ws, changes, label).get(120, TimeUnit.SECONDS);
				server.execute(() -> notifyPlayers(ws, report));
				yield report.text();
			}
			case "undo" -> {
				int count = args.has("count") ? args.get("count").getAsInt() : 1;
				yield server.submit(() -> Awe.engine().undo(count)).get(10, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS).text();
			}
			default -> throw new IllegalArgumentException("unknown tool '" + name + "'");
		};
	}

	private String describeWorkspace() {
		Workspace ws = Awe.workspace();
		if (ws == null) {
			return "No workspace is set. Ask the player to run /awe pos1 and /awe pos2 in game, at two opposite corners of the area to edit.";
		}
		StringBuilder out = new StringBuilder();
		out.append("workspace_id: ").append(ws.id()).append('\n');
		out.append("dimension: ").append(ws.dimension().identifier()).append('\n');
		out.append("size: x ").append(ws.sizeX()).append(", y ").append(ws.sizeY()).append(", z ").append(ws.sizeZ())
				.append(" (local corners [0,0,0] to ").append(Reader.corner(ws.sizeX() - 1, ws.sizeY() - 1, ws.sizeZ() - 1)).append(")\n");
		out.append("world position of [0,0,0]: ").append(Reader.corner(ws.min().getX(), ws.min().getY(), ws.min().getZ())).append('\n');
		ServerLevel level = level(ws);
		ServerPlayer nearest = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			double d = player.blockPosition().distSqr(ws.min());
			if (d < best) {
				best = d;
				nearest = player;
			}
		}
		if (nearest != null) {
			BlockPos p = nearest.blockPosition();
			out.append("player ").append(nearest.getName().getString()).append(" stands at local ")
					.append(Reader.corner(p.getX() - ws.min().getX(), p.getY() - ws.min().getY(), p.getZ() - ws.min().getZ()))
					.append(" and faces ").append(nearest.getDirection().getName()).append('\n');
		}
		out.append("limits: ").append(config.maxChangedCells).append(" changed cells per edit, ").append(config.maxReadChars).append(" characters per read\n");
		out.append("writes paused: ").append(Awe.engine().isPaused()).append('\n');
		out.append("game version: ").append(SharedConstants.getCurrentVersion().name()).append('\n');
		out.append("conventions: x grows east, y grows up, z grows south. 'from' and 'to' are inclusive.");
		return out.toString();
	}

	private String blocks(String search) {
		String needle = search.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
		List<String> lines = new ArrayList<>();
		for (Identifier key : BuiltInRegistries.BLOCK.keySet()) {
			if (key.getPath().contains(needle)) {
				Block block = BuiltInRegistries.BLOCK.getValue(key);
				StringBuilder line = new StringBuilder("minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString());
				for (Property<?> property : block.getStateDefinition().getProperties()) {
					line.append(' ').append(property.getName()).append('=').append(values(property));
				}
				lines.add(line.toString());
			}
		}
		lines.sort(null);
		if (lines.isEmpty()) {
			return "No block id contains '" + needle + "'. Closest: " + String.join(", ", BlockText.closest(needle, 5));
		}
		int shown = Math.min(lines.size(), 40);
		return String.join("\n", lines.subList(0, shown)) + (lines.size() > shown ? "\n... " + (lines.size() - shown) + " more; search more precisely." : "");
	}

	private static <T extends Comparable<T>> String values(Property<T> property) {
		List<String> names = new ArrayList<>();
		for (T value : property.getPossibleValues()) {
			names.add(property.getName(value));
		}
		return String.join("|", names);
	}

	private Reader.Box box(Workspace ws, JsonObject args) {
		int[] a = {0, 0, 0};
		int[] b = {ws.sizeX() - 1, ws.sizeY() - 1, ws.sizeZ() - 1};
		if (args.has("from")) {
			a = vec(args.getAsJsonArray("from"));
		}
		if (args.has("to")) {
			b = vec(args.getAsJsonArray("to"));
		}
		Reader.Box box = new Reader.Box(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
		if (!ws.containsLocal(box.x0(), box.y0(), box.z0()) || !ws.containsLocal(box.x1(), box.y1(), box.z1())) {
			throw new IllegalArgumentException("the box " + Reader.corner(box.x0(), box.y0(), box.z0()) + " to " + Reader.corner(box.x1(), box.y1(), box.z1())
					+ " is not inside the workspace [0,0,0] to " + Reader.corner(ws.sizeX() - 1, ws.sizeY() - 1, ws.sizeZ() - 1));
		}
		return box;
	}

	private static int[] vec(JsonArray array) {
		return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
	}

	private ServerLevel level(Workspace ws) {
		ServerLevel level = server.getLevel(ws.dimension());
		if (level == null) {
			throw new IllegalStateException("the workspace dimension is not loaded");
		}
		return level;
	}

	private <T> T onServer(java.util.function.Supplier<T> task) throws Exception {
		return server.submit(task).get(30, TimeUnit.SECONDS);
	}

	private void notifyPlayers(Workspace ws, Engine.Report report) {
		ServerLevel level = server.getLevel(ws.dimension());
		if (level == null || report.changed() == 0) {
			return;
		}
		String line = "AWE: " + report.changed() + " blocks changed. /awe undo reverts it.";
		for (ServerPlayer player : level.players()) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line), true);
		}
	}

	private static boolean isLoopback(String host) {
		return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
	}

	private static boolean isLocalOrigin(String origin) {
		try {
			String host = URI.create(origin).getHost();
			return host != null && isLoopback(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String error(JsonElement id, int code, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		response.add("error", err);
		return GSON.toJson(response);
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		if (status == 202 || bytes.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
