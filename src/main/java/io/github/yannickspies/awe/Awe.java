package io.github.yannickspies.awe;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Awe implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("awe");

	private static Config config;
	private static Engine engine;
	private static McpServer mcp;
	private static volatile Workspace workspace;
	private static BlockPos pos1;
	private static BlockPos pos2;
	private static ResourceKey<Level> cornerDimension;
	private static int nextWorkspaceId = 1;

	public static Workspace workspace() {
		return workspace;
	}

	public static Engine engine() {
		return engine;
	}

	/** True while an edit is queued or an MCP request came in during the last five minutes. */
	public static boolean isAgentActive() {
		return engine != null && (engine.hasWork() || System.nanoTime() - McpServer.lastRequestNanos() < 300_000_000_000L);
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer("awe").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	@Override
	public void onInitialize() {
		config = Config.load();
		engine = new Engine(config);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			mcp = new McpServer(config, server);
			try {
				mcp.start();
			} catch (IOException | RuntimeException e) {
				LOG.error("AWE could not start its MCP server on {}", config.endpoint(), e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (mcp != null) {
				mcp.stop();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> engine.tick(server, workspace));
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("awe")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("pos1")
						.executes(ctx -> corner(ctx, 1, lookedAt(ctx)))
						.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> corner(ctx, 1, BlockPosArgument.getBlockPos(ctx, "pos")))))
				.then(Commands.literal("pos2")
						.executes(ctx -> corner(ctx, 2, lookedAt(ctx)))
						.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> corner(ctx, 2, BlockPosArgument.getBlockPos(ctx, "pos")))))
				.then(Commands.literal("box")
						.then(Commands.argument("from", BlockPosArgument.blockPos())
								.then(Commands.argument("to", BlockPosArgument.blockPos()).executes(ctx -> {
									pos1 = BlockPosArgument.getBlockPos(ctx, "from");
									pos2 = BlockPosArgument.getBlockPos(ctx, "to");
									cornerDimension = ctx.getSource().getLevel().dimension();
									return update(ctx.getSource());
								}))))
				.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
				.then(Commands.literal("undo")
						.executes(ctx -> undo(ctx.getSource(), 1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 100)).executes(ctx -> undo(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
				.then(Commands.literal("pause").executes(ctx -> {
					engine.setPaused(true);
					ctx.getSource().sendSuccess(() -> Component.literal("AWE: agent writes are paused."), true);
					return 1;
				}))
				.then(Commands.literal("resume").executes(ctx -> {
					engine.setPaused(false);
					ctx.getSource().sendSuccess(() -> Component.literal("AWE: agent writes are allowed."), true);
					return 1;
				}))
				.then(Commands.literal("connect").executes(ctx -> {
					String command = "claude mcp add --transport http awe " + config.endpoint() + " --header \"Authorization: Bearer " + config.token + "\"";
					ctx.getSource().sendSuccess(() -> Component.literal("Run this where Claude Code runs:\n" + command), false);
					return 1;
				})));
	}

	private static BlockPos lookedAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		HitResult hit = player.pick(64, 0, false);
		return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : player.blockPosition();
	}

	private static int corner(CommandContext<CommandSourceStack> ctx, int which, BlockPos pos) {
		ResourceKey<Level> dimension = ctx.getSource().getLevel().dimension();
		if (cornerDimension != null && !cornerDimension.equals(dimension)) {
			pos1 = null;
			pos2 = null;
		}
		cornerDimension = dimension;
		if (which == 1) {
			pos1 = pos;
		} else {
			pos2 = pos;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("AWE: corner " + which + " set to " + pos.toShortString()), false);
		return update(ctx.getSource());
	}

	private static int update(CommandSourceStack source) {
		if (pos1 == null || pos2 == null) {
			return 1;
		}
		Workspace next = Workspace.of("w" + nextWorkspaceId, cornerDimension, pos1, pos2);
		if (next.cells() > config.maxWorkspaceCells) {
			source.sendFailure(Component.literal("AWE: the box has " + next.cells() + " cells; the limit is " + config.maxWorkspaceCells + ". The workspace did not change."));
			return 0;
		}
		nextWorkspaceId++;
		workspace = next;
		source.sendSuccess(() -> Component.literal("AWE: workspace " + next.id() + " is " + next.sizeX() + " x " + next.sizeY() + " x " + next.sizeZ()
				+ " from " + next.min().toShortString() + " to " + next.max().toShortString()), true);
		return 1;
	}

	private static int status(CommandSourceStack source) {
		Workspace ws = workspace;
		StringBuilder text = new StringBuilder("AWE ").append(version()).append(" on ").append(config.endpoint());
		text.append(ws == null ? "\nno workspace" : "\nworkspace " + ws.id() + ": " + ws.min().toShortString() + " to " + ws.max().toShortString());
		text.append("\nwrites paused: ").append(engine.isPaused());
		if (config.allowRemote) {
			text.append("\nwarning: allowRemote is on, the token travels unencrypted");
		}
		for (String line : engine.recentEdits(5)) {
			text.append("\n  ").append(line);
		}
		source.sendSuccess(() -> Component.literal(text.toString()), false);
		return 1;
	}

	private static int undo(CommandSourceStack source, int count) {
		engine.undo(count).whenComplete((report, error) -> source.getServer().execute(() -> {
			if (error != null) {
				source.sendFailure(Component.literal("AWE: undo failed: " + error.getMessage()));
			} else {
				source.sendSuccess(() -> Component.literal("AWE: " + report.text()), true);
			}
		}));
		return 1;
	}
}
