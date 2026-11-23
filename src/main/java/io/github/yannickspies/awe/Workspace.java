package io.github.yannickspies.awe;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** One box in one dimension. Immutable: every corner move makes a new instance with a new id. */
public record Workspace(String id, ResourceKey<Level> dimension, BlockPos min, BlockPos max) {

	public int sizeX() {
		return max.getX() - min.getX() + 1;
	}

	public int sizeY() {
		return max.getY() - min.getY() + 1;
	}

	public int sizeZ() {
		return max.getZ() - min.getZ() + 1;
	}

	public long cells() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public boolean containsLocal(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < sizeX() && y < sizeY() && z < sizeZ();
	}

	public BlockPos toWorld(int x, int y, int z) {
		return new BlockPos(min.getX() + x, min.getY() + y, min.getZ() + z);
	}

	public static Workspace of(String id, ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		return new Workspace(id, dimension, min, max);
	}
}
