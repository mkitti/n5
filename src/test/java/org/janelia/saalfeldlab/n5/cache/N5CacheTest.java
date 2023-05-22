package org.janelia.saalfeldlab.n5.cache;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class N5CacheTest {


	@Test
	public void cacheBackingTest() {

		final DummyBackingStorage backingStorage = new DummyBackingStorage();

		final N5JsonCache cache = new N5JsonCache(backingStorage);

		// check existance, ensure backing storage is only called once
		assertEquals(0, backingStorage.existsCallCount);
		cache.exists("a", null);
		assertEquals(1, backingStorage.existsCallCount);
		cache.exists("a", null);
		assertEquals(1, backingStorage.existsCallCount);

		// check existance of new group, ensure backing storage is only called one more time
		cache.exists("b", null);
		assertEquals(2, backingStorage.existsCallCount);
		cache.exists("b", null);
		assertEquals(2, backingStorage.existsCallCount);

		// check isDataset, ensure backing storage is only called when expected
		// isDataset is called by exists, so should have been called twice here
		assertEquals(2, backingStorage.isDatasetCallCount);
		cache.isDataset("a", null);
		assertEquals(2, backingStorage.isDatasetCallCount);

		assertEquals(2, backingStorage.isDatasetCallCount);
		cache.isDataset("b", null);
		assertEquals(2, backingStorage.isDatasetCallCount);

		// check isGroup, ensure backing storage is only called when expected
		// isGroup is called by exists, so should have been called twice here
		assertEquals(2, backingStorage.isGroupCallCount);
		cache.isDataset("a", null);
		assertEquals(2, backingStorage.isGroupCallCount);

		assertEquals(2, backingStorage.isGroupCallCount);
		cache.isDataset("b", null);
		assertEquals(2, backingStorage.isGroupCallCount);

		// similarly check list, ensure backing storage is only called when expected
		// list is called by exists, so should have been called twice here
		assertEquals(0, backingStorage.listCallCount);
		cache.list("a");
		assertEquals(1, backingStorage.listCallCount);

		assertEquals(1, backingStorage.listCallCount);
		cache.list("b");
		assertEquals(2, backingStorage.listCallCount);

		// finally check getAttributes
		// it is not called by exists (since it needs the cache key)
		assertEquals(0, backingStorage.attrCallCount);
		cache.getAttributes("a", "foo");
		assertEquals(1, backingStorage.attrCallCount);
		cache.getAttributes("a", "foo");
		assertEquals(1, backingStorage.attrCallCount);
		cache.getAttributes("a", "bar");
		assertEquals(2, backingStorage.attrCallCount);
		cache.getAttributes("a", "bar");
		assertEquals(2, backingStorage.attrCallCount);
		cache.getAttributes("a", "face");
		assertEquals(3, backingStorage.attrCallCount);

		cache.getAttributes("b", "foo");
		assertEquals(4, backingStorage.attrCallCount);
		cache.getAttributes("b", "foo");
		assertEquals(4, backingStorage.attrCallCount);
		cache.getAttributes("b", "bar");
		assertEquals(5, backingStorage.attrCallCount);
		cache.getAttributes("b", "bar");
		assertEquals(5, backingStorage.attrCallCount);
		cache.getAttributes("b", "face");
		assertEquals(6, backingStorage.attrCallCount);

	}

	protected static class DummyBackingStorage implements N5JsonCacheableContainer {

		int attrCallCount = 0;
		int existsCallCount = 0;
		int isGroupCallCount = 0;
		int isDatasetCallCount = 0;
		int isGroupFromAttrsCallCount = 0;
		int isDatasetFromAttrsCallCount = 0;
		int listCallCount = 0;

		public DummyBackingStorage() {
		}

		public JsonElement getAttributesFromContainer(final String key, final String cacheKey) {
			attrCallCount++;
			final JsonObject obj = new JsonObject();
			obj.addProperty("key", "value");
			return obj;
		}

		public boolean existsFromContainer(final String path, final String cacheKey) {
			existsCallCount++;
			return true;
		}

		public boolean isGroupFromContainer(final String key) {
			isGroupCallCount++;
			return true;
		}

		public boolean isDatasetFromContainer(final String key) {
			isDatasetCallCount++;
			return true;
		}

		public String[] listFromContainer(final String key) {
			listCallCount++;
			return new String[] { "list" };
		}

		@Override
		public boolean isGroupFromAttributes(final String cacheKey, final JsonElement attributes) {
			isGroupFromAttrsCallCount++;
			return true;
		}

		@Override
		public boolean isDatasetFromAttributes(final String cacheKey, final JsonElement attributes) {
			isDatasetFromAttrsCallCount++;
			return true;
		}
	}

}
