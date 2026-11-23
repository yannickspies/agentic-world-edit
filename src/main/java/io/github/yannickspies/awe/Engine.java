package io.github.yannickspies.awe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Applies change lists on the server thread under a time budget per tick, and keeps the undo journal.
 * Every field except the incoming queue is touched by the server thread only.
 */
public final class Engine {
	/** Flags for setBlock: send to clients, no neighbor updates, no onPlace, no drops, no block entity side effects. */
	static final int FLAGS = 2 | 816;

	public record Report(String text, int changed) {
	}

	private static final class Job {
		final String workspaceId;
		final ResourceKey<Level> dimension;
		final List<Planner.Change> changes;
		final String label;
		final boolean isUndo;
		final CompletableFuture<Report> done = new CompletableFuture<>();
		int index;
		boolean checked;
		final List<Long> positions = new ArrayList<>();
		final List<BlockState> before = new ArrayList<>();
		final List<BlockState> after = new ArrayList<>();
		final Map<String, Integer> counts = new LinkedHashMap<>();
		int unchanged;
		int skipped;
		long startNanos;

		Job(String workspaceId, ResourceKey<Level> dimension, List<Planner.Change> changes, String label, boolean isUndo) {
			this.workspaceId = workspaceId;
			this.dimension = dimension;
			this.changes = changes;
			this.label = label;
			this.isUndo = isUndo;
		}
	}

	/** One applied edit, stored with world positions so a later corner move does not change its meaning. */
	private record Entry(String id, String label, ResourceKey<Level> dimension, long[] positions, BlockState[] before, BlockState[] after) {
	}

	private final Config config;
	private final ConcurrentLinkedQueue<Job> incoming = new ConcurrentLinkedQueue<>();
	private final Deque<Job> active = new ArrayDeque<>();
	private final Deque<Entry> journal = new ArrayDeque<>();
	private long journalCells;
	private int nextEditId = 1;
	private volatile boolean isPaused;

	public Engine(Config config) {
		this.config = config;
	}

	public boolean isPaused() {
		return isPaused;
	}

	public void setPaused(boolean paused) {
		isPaused = paused;
	}

	/** Called from any thread. The future completes on the server thread. */
	public CompletableFuture<Report> submitEdit(Workspace ws, List<Planner.Change> changes, String label) {
		Job job = new Job(ws.id(), ws.dimension(), changes, label, false);
		if (isPaused) {
			job.done.completeExceptionally(new IllegalStateException("agent writes are paused. The player can run /awe resume."));
			return job.done;
		}
		incoming.add(job);
		return job.done;
	}

	/** Called on the server thread. Reverts the newest edits, newest first, as one job per edit. */
	public CompletableFuture<Report> undo(int count) {
		if (count < 1 || journal.isEmpty()) {
			return CompletableFuture.completedFuture(new Report("nothing to undo", 0));
		}
		List<CompletableFuture<Report>> parts = new ArrayList<>();
		for (int i = 0; i < count && !journal.isEmpty(); i++) {
			Entry entry = journal.pollLast();
			journalCells -= entry.positions().length;
			List<Planner.Change> changes = new ArrayList<>(entry.positions().length);
			for (int c = entry.positions().length - 1; c >= 0; c--) {
				changes.add(new Planner.Change(entry.positions()[c], entry.before()[c], new Planner.Match(entry.after()[c], null)));
			}
			Job job = new Job(null, entry.dimension(), changes, "undo of " + entry.id() + " (" + entry.label() + ")", true);
			active.add(job);
			parts.add(job.done);
		}
		return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
			StringBuilder text = new StringBuilder();
			int total = 0;
			for (CompletableFuture<Report> part : parts) {
				Report report = part.join();
				text.append(report.text()).append('\n');
				total += report.changed();
			}
			return new Report(text.toString().strip(), total);
		});
	}

	public List<String> recentEdits(int count) {
		List<String> out = new ArrayList<>();
		Iterator<Entry> it = journal.descendingIterator();
		while (it.hasNext() && out.size() < count) {
			Entry entry = it.next();
			out.add(entry.id() + " " + entry.positions().length + " cells: " + entry.label());
		}
		return out;
	}

	/** Server thread, end of each tick. With nothing queued this is two empty checks. */
	public void tick(MinecraftServer server, Workspace current) {
		if (incoming.isEmpty() && active.isEmpty()) {
			return;
		}
		Job job;
		while ((job = incoming.poll()) != null) {
			active.add(job);
		}
		long deadline = System.nanoTime() + config.tickBudgetMs * 1_000_000L;
		while (!active.isEmpty() && System.nanoTime() < deadline) {
			Job head = active.peekFirst();
			try {
				if (run(server, current, head, deadline)) {
					active.pollFirst();
					finish(server, head);
				}
			} catch (RuntimeException e) {
				active.pollFirst();
				rollback(server, head);
				head.done.completeExceptionally(e);
			}
		}
	}

	/** Returns true when the job is complete. */
	private boolean run(MinecraftServer server, Workspace current, Job job, long deadline) {
		ServerLevel level = server.getLevel(job.dimension);
		if (level == null) {
			throw new IllegalStateException("the workspace dimension is not loaded");
		}
		if (!job.checked) {
			if (!job.isUndo && (current == null || !current.id().equals(job.workspaceId))) {
				throw new IllegalStateException("the workspace changed before the edit started. Call the workspace tool and read again.");
			}
			for (Planner.Change change : job.changes) {
				BlockPos pos = BlockPos.of(change.pos());
				if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
					throw new IllegalStateException("chunk " + (pos.getX() >> 4) + "," + (pos.getZ() >> 4) + " is not loaded. No block changed.");
				}
			}
			job.checked = true;
			job.startNanos = System.nanoTime();
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (job.index < job.changes.size()) {
			Planner.Change change = job.changes.get(job.index++);
			pos.set(BlockPos.getX(change.pos()), BlockPos.getY(change.pos()), BlockPos.getZ(change.pos()));
			BlockState old = level.getBlockState(pos);
			if (change.match() != null && !change.match().test(old)) {
				job.skipped++;
			} else if (old == change.state()) {
				job.unchanged++;
			} else {
				level.setBlock(pos, change.state(), FLAGS);
				job.positions.add(change.pos());
				job.before.add(old);
				job.after.add(change.state());
				job.counts.merge(BlockText.format(change.state()), 1, Integer::sum);
			}
			if ((job.index & 255) == 0 && System.nanoTime() >= deadline) {
				return false;
			}
		}
		return true;
	}

	private void finish(MinecraftServer server, Job job) {
		int changed = job.positions.size();
		long ms = (System.nanoTime() - job.startNanos) / 1_000_000;
		StringBuilder text = new StringBuilder();
		if (job.isUndo) {
			text.append(job.label).append(": restored ").append(changed).append(" cells, skipped ").append(job.skipped)
					.append(" cells that changed after the edit.");
			job.done.complete(new Report(text.toString(), changed));
			return;
		}
		String id = "e" + nextEditId++;
		if (changed > 0) {
			long[] positions = job.positions.stream().mapToLong(Long::longValue).toArray();
			journal.addLast(new Entry(id, job.label, job.dimension, positions, job.before.toArray(BlockState[]::new), job.after.toArray(BlockState[]::new)));
			journalCells += positions.length;
			while (journalCells > config.maxJournalCells && journal.size() > 1) {
				journalCells -= journal.pollFirst().positions().length;
			}
		}
		text.append("edit ").append(changed > 0 ? id : "(none, nothing changed)").append(": ").append(changed).append(" cells changed, ")
				.append(job.unchanged).append(" already had that block");
		if (job.skipped > 0) {
			text.append(", ").append(job.skipped).append(" did not match the replace condition");
		}
		text.append(". Took ").append(ms).append(" ms.\n");
		job.counts.forEach((block, count) -> text.append("  ").append(count).append(' ').append(block).append('\n'));
		job.done.complete(new Report(text.toString().strip(), changed));
	}

	/** A failed job puts back what it already changed, so a batch is all or nothing. */
	private void rollback(MinecraftServer server, Job job) {
		ServerLevel level = server.getLevel(job.dimension);
		if (level == null) {
			return;
		}
		for (int i = job.positions.size() - 1; i >= 0; i--) {
			level.setBlock(BlockPos.of(job.positions.get(i)), job.before.get(i), FLAGS);
		}
	}
}
