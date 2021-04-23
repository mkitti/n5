/**
 * Copyright (c) 2017--2021, Stephan Saalfeld
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.saalfeldlab.n5;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

/**
 * Filesystem {@link N5Reader} implementation with JSON attributes
 * parsed with {@link Gson}.
 *
 * @author Stephan Saalfeld
 * @author Igor Pisarev
 * @author Philipp Hanslovsky
 */
public class AbstractN5FSReader implements GsonAttributesParser {

	/**
	 * A {@link FileChannel} wrapper that attempts to acquire a lock and waits
	 * for existing locks to be lifted before returning if the
	 * {@link FileSystem} supports that.  If the {@link FileSystem} does not
	 * support locking, it returns immediately.
	 */
	protected static class LockedFileChannel implements Closeable {

		private final FileChannel channel;

		public static LockedFileChannel openForReading(final Path path) throws IOException {

			return new LockedFileChannel(path, true);
		}

		public static LockedFileChannel openForWriting(final Path path) throws IOException {

			return new LockedFileChannel(path, false);
		}

		private LockedFileChannel(final Path path, final boolean readOnly) throws IOException {

			final OpenOption[] options = readOnly ? new OpenOption[]{StandardOpenOption.READ} : new OpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE};
			channel = FileChannel.open(path, options);

			for (boolean waiting = true; waiting;) {
				waiting = false;
				try {
					channel.lock(0L, Long.MAX_VALUE, readOnly);
				} catch (final OverlappingFileLockException e) {
					waiting = true;
					try {
						Thread.sleep(100);
					} catch (final InterruptedException f) {
						waiting = false;
						Thread.currentThread().interrupt();
					}
				} catch (final IOException e) {}
			}
		}

		public FileChannel getFileChannel() {

			return channel;
		}

		@Override
		public void close() throws IOException {

			channel.close();
		}
	}

	/**
	 * Data object for caching meta data.  Elements that are null are not yet
	 * cached.
	 */
	protected static class N5GroupInfo {

		public ArrayList<String> children = null;
		public HashMap<String, Object> attributesCache = null;
		public Boolean isDataset = null;
	}

	protected static final N5GroupInfo noGroup = new N5GroupInfo();

	protected final FileSystem fileSystem;

	protected final Gson gson;

	protected final HashMap<String, N5GroupInfo> metaCache = new HashMap<>();

	protected final boolean cacheMeta;

	protected static final String jsonFile = "attributes.json";

	protected final String basePath;

	/**
	 * Opens an {@link AbstractN5FSReader} at a given base path with a custom
	 * {@link GsonBuilder} to support custom attributes.
	 *
	 * @param fileSystem
	 * @param basePath N5 base path
	 * @param gsonBuilder
	 * @param cacheMeta cache attributes and meta data
	 *    Setting this to true avoids frequent reading and parsing of JSON
	 *    encoded attributes and other meta data that requires accessing the
	 *    store. This is most interesting for high latency backends. Changes
	 *    of cached attributes and meta data by an independent writer will
	 *    not be tracked.
	 *
	 * @throws IOException
	 *    if the base path cannot be read or does not exist,
	 *    if the N5 version of the container is not compatible with this
	 *    implementation.
	 */
	public AbstractN5FSReader(
			final FileSystem fileSystem,
			final String basePath,
			final GsonBuilder gsonBuilder,
			final boolean cacheMeta) throws IOException {

		this.fileSystem = fileSystem;
		this.basePath = basePath;
		gsonBuilder.registerTypeAdapter(DataType.class, new DataType.JsonAdapter());
		gsonBuilder.registerTypeHierarchyAdapter(Compression.class, CompressionAdapter.getJsonAdapter());
		gsonBuilder.disableHtmlEscaping();
		this.gson = gsonBuilder.create();
		this.cacheMeta = cacheMeta;
		if (exists("/")) {
			final Version version = getVersion();
			if (!VERSION.isCompatible(version))
				throw new IOException("Incompatible version " + version + " (this is " + VERSION + ").");
		}
	}

	@Override
	public Gson getGson() {

		return gson;
	}

	/**
	 *
	 * @return N5 base path
	 */
	public String getBasePath() {

		return this.basePath;
	}

	@Override
	public DatasetAttributes getDatasetAttributes(final String pathName) throws IOException {

		final long[] dimensions;
		final DataType dataType;
		int[] blockSize;
		Compression compression;
		final String compressionVersion0Name;
		if (cacheMeta) {
			final HashMap<String, Object> cachedMap = getCachedAttributes(pathName);
			if (cachedMap.isEmpty())
				return null;

			dimensions = getAttribute(cachedMap, DatasetAttributes.dimensionsKey, long[].class);
			if (dimensions == null)
				return null;

			dataType = getAttribute(cachedMap, DatasetAttributes.dataTypeKey, DataType.class);
			if (dataType == null)
				return null;

			blockSize = getAttribute(cachedMap, DatasetAttributes.blockSizeKey, int[].class);

			compression = getAttribute(cachedMap, DatasetAttributes.compressionKey, Compression.class);

			/* version 0 */
			compressionVersion0Name = compression
					== null
					? getAttribute(cachedMap, DatasetAttributes.compressionTypeKey, String.class)
					: null;
		} else {
			final HashMap<String, JsonElement> map = getAttributes(pathName);

			dimensions = GsonAttributesParser.parseAttribute(map, DatasetAttributes.dimensionsKey, long[].class, gson);
			if (dimensions == null)
				return null;

			dataType = GsonAttributesParser.parseAttribute(map, DatasetAttributes.dataTypeKey, DataType.class, gson);
			if (dataType == null)
				return null;

			blockSize = GsonAttributesParser.parseAttribute(map, DatasetAttributes.blockSizeKey, int[].class, gson);

			compression = GsonAttributesParser.parseAttribute(map, DatasetAttributes.compressionKey, Compression.class, gson);

			/* version 0 */
			compressionVersion0Name = compression == null
					? GsonAttributesParser.parseAttribute(map, DatasetAttributes.compressionTypeKey, String.class, gson)
					: null;
			}

		if (blockSize == null)
			blockSize = Arrays.stream(dimensions).mapToInt(a -> (int)a).toArray();

		/* version 0 */
		if (compression == null) {
			switch (compressionVersion0Name) {
			case "raw":
				compression = new RawCompression();
				break;
			case "gzip":
				compression = new GzipCompression();
				break;
			case "bzip2":
				compression = new Bzip2Compression();
				break;
			case "lz4":
				compression = new Lz4Compression();
				break;
			case "xz":
				compression = new XzCompression();
				break;
			}
		}

		return new DatasetAttributes(dimensions, blockSize, dataType, compression);
	}

	/**
	 * Get and cache attributes for a group.
	 *
	 * @param pathName
	 * @return
	 * @throws IOException
	 */
	@Override
	protected HashMap<String, Object> getCachedAttributes(final String pathName) throws IOException {

		final N5GroupInfo info = metaCache.get(pathName);
		if (info == noGroup)
			return null;
		if (info == null) {
			synchronized (metaCache)
		}
		HashMap<String, Object> cachedMap = metaCache.get(pathName);
		if (cachedMap == null) {
			final HashMap<String, ?> map = getAttributes(pathName);
			cachedMap = new HashMap<>();
			if (map != null)
				cachedMap.putAll(map);

			synchronized (attributesCache) {
				attributesCache.put(pathName, cachedMap);
			}
		}
		return cachedMap;
	}

	@SuppressWarnings("unchecked")
	protected <T> T getAttribute(
			final HashMap<String, Object> cachedMap,
			final String key,
			final Class<T> clazz) {

		final Object cachedAttribute = cachedMap.get(key);
		if (cachedAttribute == null)
			return null;
		else if (cachedAttribute instanceof JsonElement) {
			final T attribute = gson.fromJson((JsonElement)cachedAttribute, clazz);
			synchronized (cachedMap) {
				cachedMap.put(key, attribute);
			}
			return attribute;
		} else {
			return (T)cachedAttribute;
		}
	}

	@SuppressWarnings("unchecked")
	protected <T> T getAttribute(
			final HashMap<String, Object> cachedMap,
			final String key,
			final Type type) {

		final Object cachedAttribute = cachedMap.get(key);
		if (cachedAttribute == null)
			return null;
		else if (cachedAttribute instanceof JsonElement) {
			final T attribute = gson.fromJson((JsonElement)cachedAttribute, type);
			synchronized (cachedMap) {
				cachedMap.put(key, attribute);
			}
			return attribute;
		} else {
			return (T)cachedAttribute;
		}
	}

	@Override
	public <T> T getAttribute(
			final String pathName,
			final String key,
			final Class<T> clazz) throws IOException {

		if (cacheMeta) {
			final HashMap<String, Object> cachedMap = getCachedAttributes(pathName);
			if (cachedMap.isEmpty())
				return null;
			return getAttribute(cachedMap, key, clazz);
		} else {
			final HashMap<String, JsonElement> map = getAttributes(pathName);
			return GsonAttributesParser.parseAttribute(map, key, clazz, getGson());
		}
	}

	@Override
	public <T> T getAttribute(
			final String pathName,
			final String key,
			final Type type) throws IOException {

		if (cacheMeta) {
			final HashMap<String, Object> cachedMap = getCachedAttributes(pathName);
			if (cachedMap.isEmpty())
				return null;
			return getAttribute(cachedMap, key, type);
		} else {
			final HashMap<String, JsonElement> map = getAttributes(pathName);
			return GsonAttributesParser.parseAttribute(map, key, type, getGson());
		}
	}

	@Override
	public boolean exists(final String pathName) {

		final Path path = fileSystem.getPath(basePath, pathName);
		return Files.exists(path) && Files.isDirectory(path);
	}

	@Override
	public HashMap<String, JsonElement> getAttributes(final String pathName) throws IOException {

		final Path path = getAttributesPath(pathName);
		if (exists(pathName) && !Files.exists(path))
			return new HashMap<>();

		try (final LockedFileChannel lockedFileChannel = LockedFileChannel.openForReading(path)) {
			return GsonAttributesParser.readAttributes(Channels.newReader(lockedFileChannel.getFileChannel(), StandardCharsets.UTF_8.name()), getGson());
		}
	}

	@Override
	public DataBlock<?> readBlock(
			final String pathName,
			final DatasetAttributes datasetAttributes,
			final long... gridPosition) throws IOException {

		final Path path = getDataBlockPath(pathName, gridPosition);
		if (!Files.exists(path))
			return null;

		try (final LockedFileChannel lockedChannel = LockedFileChannel.openForReading(path)) {
			return DefaultBlockReader.readBlock(Channels.newInputStream(lockedChannel.getFileChannel()), datasetAttributes, gridPosition);
		}
	}

	@Override
	public String[] list(final String pathName) throws IOException {

		final Path path = fileSystem.getPath(basePath, removeLeadingSlash(pathName));
		try (final Stream<Path> pathStream = Files.list(path)) {
			return pathStream
					.filter(a -> Files.isDirectory(a))
					.map(a -> path.relativize(a).toString())
					.toArray(n -> new String[n]);
		}
	}

	/**
	 * Constructs the path for a data block in a dataset at a given grid position.
	 *
	 * The returned path is
	 * <pre>
	 * $datasetPathName/$gridPosition[0]/$gridPosition[1]/.../$gridPosition[n]
	 * </pre>
	 *
	 * This is the file into which the data block will be stored.
	 *
	 * @param datasetPathName
	 * @param gridPosition
	 * @return
	 */
	protected Path getDataBlockPath(
			final String datasetPathName,
			final long... gridPosition) {

		final String[] pathComponents = new String[gridPosition.length + 1];
		pathComponents[0] = removeLeadingSlash(datasetPathName);
		for (int i = 1; i <= pathComponents.length; ++i)
			pathComponents[i] = Long.toString(gridPosition[i - 1]);

		return fileSystem.getPath(basePath, pathComponents);
	}

	/**
	 * Constructs the path for the attributes file of a group or dataset.
	 *
	 * @param pathName
	 * @return
	 */
	protected Path getAttributesPath(final String pathName) {

		return fileSystem.getPath(basePath, removeLeadingSlash(pathName), jsonFile);
	}

	/**
	 * Removes the leading slash from a given path and returns the corrected path.
	 * It ensures correctness on both Unix and Windows, otherwise {@code pathName} is treated
	 * as UNC path on Windows, and {@code Paths.get(pathName, ...)} fails with {@code InvalidPathException}.
	 *
	 * @param pathName
	 * @return
	 */
	protected static String removeLeadingSlash(final String pathName) {

		return pathName.startsWith("/") || pathName.startsWith("\\") ? pathName.substring(1) : pathName;
	}

	@Override
	public String toString() {

		return String.format("%s[fileSystem=%s, basePath=%s]", getClass().getSimpleName(), fileSystem, basePath);
	}
}
