package io.github.yannickspies.awe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;

/** config/awe/connection.json. Created with a random token on first start. */
public final class Config {
	public String host = "127.0.0.1";
	public int port = 25599;
	public String token = "";
	public boolean allowRemote = false;
	public int tickBudgetMs = 8;
	public long maxWorkspaceCells = 2_000_000;
	public int maxChangedCells = 1_000_000;
	public int maxReadChars = 60_000;
	public long maxJournalCells = 4_000_000;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Config load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("awe").resolve("connection.json");
		Config config = new Config();
		try {
			if (Files.exists(file)) {
				config = GSON.fromJson(Files.readString(file), Config.class);
			}
			if (config.token == null || config.token.isBlank()) {
				byte[] bytes = new byte[24];
				new SecureRandom().nextBytes(bytes);
				config.token = HexFormat.of().formatHex(bytes);
			}
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(config));
		} catch (IOException e) {
			throw new IllegalStateException("AWE cannot read or write " + file, e);
		}
		return config;
	}

	public String endpoint() {
		return "http://" + host + ":" + port + "/mcp";
	}
}
